package com.nuvio.tv.ui.screens.player

import android.os.SystemClock
import android.util.Log
import com.nuvio.tv.R
import com.nuvio.tv.domain.model.Subtitle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import androidx.media3.exoplayer.ExoPlayer
import com.nuvio.tv.core.network.IPv4FirstDns
import com.nuvio.tv.core.player.SubtitleCharsetDetector
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor
import okhttp3.Request
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLException
import kotlin.math.roundToInt

private fun OkHttpClient.Builder.subtitleDownloadDefaults(): OkHttpClient.Builder = this
    // Sidecar and auto-sync downloads use these clients; keep timeouts generous for flaky hosts.
    .connectTimeout(12_000, TimeUnit.MILLISECONDS)
    .readTimeout(15_000, TimeUnit.MILLISECONDS)
    .callTimeout(25_000, TimeUnit.MILLISECONDS)
    .retryOnConnectionFailure(true)
    .followRedirects(true)
    .followSslRedirects(true)
    .addNetworkInterceptor(ForwardedStreamHeaderGuard)

// Validating client. Interceptors are cleared so playbackHttpClient's trust-all SSL fallback can't
// resend stream credentials; executeSubtitleRequest handles the fallback instead.
internal val subtitleHttpClient: OkHttpClient by lazy {
    PlayerPlaybackNetworking.playbackHttpClient.newBuilder()
        .apply { interceptors().clear() }
        .subtitleDownloadDefaults()
        .build()
}

internal val subtitleUnvalidatedTlsHttpClient: OkHttpClient by lazy {
    PlayerPlaybackNetworking.trustAllPlaybackHttpClient.newBuilder()
        .subtitleDownloadDefaults()
        .build()
}

private const val SUBTITLE_DOWNLOAD_MAX_ATTEMPTS = 3
private const val SUBTITLE_DOWNLOAD_RETRY_DELAY_MS = 350L

private const val AUTO_SYNC_REACTION_COMPENSATION_MS = 300L
private const val AUTO_SYNC_MAX_FAST_PROBES = 6
private const val AUTO_SYNC_VALIDATION_MAX_WALL_CLOCK_MS = 40_000L
private const val AUTO_SYNC_MAX_VALIDATIONS = 4

private data class AutoSyncPlaybackSuspension(
    val exoPlayer: ExoPlayer? = null,
    val exoPositionMs: Long = 0L,
    val exoWasStopped: Boolean = false,
    val shouldResumePlayback: Boolean = false,
    val mpvWasPlaying: Boolean = false
)

private data class AutoSyncExecutedProbe(
    val result: SubtitleFastAudioProbeResult,
    val plan: SubtitleFastAudioProbePlan,
    val nextPlan: SubtitleFastAudioProbePlan
)

internal fun PlayerRuntimeController.showSubtitleTimingDialog() {
    openSubtitleTimingDialog(runAutomaticSync = false)
}

internal fun PlayerRuntimeController.showSubtitleAutoSyncDialog() {
    openSubtitleTimingDialog(runAutomaticSync = true)
}

private fun PlayerRuntimeController.openSubtitleTimingDialog(runAutomaticSync: Boolean) {
    _uiState.update {
        it.copy(
            showSubtitleTimingDialog = true,
            showSubtitleOverlay = false,
            showSubtitleStylePanel = false,
            showSubtitleDelayOverlay = false,
            showMoreDialog = false,
            showSpeedDialog = false,
            showAudioOverlay = false,
            showControls = false,
            subtitleAutoSyncCapturedVideoMs = null,
            subtitleAutoSyncLoading = runAutomaticSync,
            subtitleAutoSyncStatus = if (runAutomaticSync) {
                context.getString(R.string.subtitle_auto_sync_checking)
            } else {
                null
            },
            subtitleAutoSyncError = null,
            subtitleAutoSyncAlternatives = emptyList()
        )
    }
    if (runAutomaticSync) {
        startAutomaticSubtitleSync()
    } else {
        maybeLoadSubtitleAutoSyncCues(force = false)
    }
}

internal fun PlayerRuntimeController.dismissSubtitleTimingDialog() {
    subtitleAutoSyncLoadJob?.cancel()
    subtitleAutoSyncLoadJob = null
    subtitleAutoSyncAttemptId++
    _uiState.update {
        it.copy(
            showSubtitleTimingDialog = false,
            subtitleAutoSyncStatus = null,
            subtitleAutoSyncAlternatives = emptyList()
        )
    }
    scheduleHideControls()
}

internal fun PlayerRuntimeController.captureSubtitleAutoSyncTime() {
    val capturePositionMs = currentPlaybackPositionMs()?.coerceAtLeast(0L) ?: 0L
    _uiState.update {
        it.copy(
            subtitleAutoSyncCapturedVideoMs = capturePositionMs,
            subtitleAutoSyncStatus = null,
            subtitleAutoSyncError = null
        )
    }
}

internal fun PlayerRuntimeController.applySubtitleAutoSyncCue(cueStartTimeMs: Long) {
    val capturePositionMs =
        _uiState.value.subtitleAutoSyncCapturedVideoMs ?: currentPlaybackPositionMs() ?: return
    val newDelayMs = (capturePositionMs - cueStartTimeMs - AUTO_SYNC_REACTION_COMPENSATION_MS)
        .toInt()
        .coerceIn(SUBTITLE_DELAY_MIN_MS, SUBTITLE_DELAY_MAX_MS)

    subtitleDelayUs.set(newDelayMs.toLong() * 1000L)
    _uiState.update {
        it.copy(
            subtitleDelayMs = newDelayMs,
            showSubtitleTimingDialog = false,
            showSubtitleDelayOverlay = true,
            showControls = false,
            subtitleAutoSyncStatus = context.getString(
                R.string.subtitle_auto_sync_applied,
                formatAutoSyncDelay(newDelayMs)
            ),
            subtitleAutoSyncError = null
        )
    }
    // Remember the delay so it survives to the next session (issue #1063).
    persistTrackPreference()
    refreshActiveSubtitleTrackAfterTimingChange()
    scheduleHideSubtitleDelayOverlay()
}

internal fun PlayerRuntimeController.reloadSubtitleAutoSyncCues() {
    maybeLoadSubtitleAutoSyncCues(force = true)
}

internal fun PlayerRuntimeController.resetSubtitleAutoSyncState(clearLoadedTrack: Boolean = true) {
    subtitleAutoSyncLoadJob?.cancel()
    subtitleAutoSyncLoadJob = null
    subtitleAutoSyncAttemptId++
    _uiState.update {
        it.copy(
            subtitleAutoSyncCues = emptyList(),
            subtitleAutoSyncCapturedVideoMs = null,
            subtitleAutoSyncStatus = null,
            subtitleAutoSyncError = null,
            subtitleAutoSyncLoading = false,
            subtitleAutoSyncLoadedTrackKey = if (clearLoadedTrack) null else it.subtitleAutoSyncLoadedTrackKey,
            subtitleAutoSyncAlternatives = emptyList()
        )
    }
}

private fun PlayerRuntimeController.maybeLoadSubtitleAutoSyncCues(force: Boolean) {
    val selectedSubtitle = _uiState.value.selectedAddonSubtitle
    if (selectedSubtitle == null) {
        _uiState.update {
            it.copy(
                subtitleAutoSyncCues = emptyList(),
                subtitleAutoSyncCapturedVideoMs = null,
                subtitleAutoSyncLoading = false,
                subtitleAutoSyncStatus = null,
                subtitleAutoSyncError = context.getString(R.string.subtitle_auto_sync_select_addon_track),
                subtitleAutoSyncLoadedTrackKey = null
            )
        }
        return
    }

    val selectedTrackKey = selectedSubtitle.autoSyncTrackKey()
    val state = _uiState.value
    if (!force &&
        state.subtitleAutoSyncLoadedTrackKey == selectedTrackKey &&
        state.subtitleAutoSyncCues.isNotEmpty()
    ) {
        _uiState.update {
            it.copy(
                subtitleAutoSyncLoading = false,
                subtitleAutoSyncStatus = null,
                subtitleAutoSyncError = null
            )
        }
        return
    }

    subtitleAutoSyncLoadJob?.cancel()
    subtitleAutoSyncLoadJob = scope.launch {
        _uiState.update {
            it.copy(
                subtitleAutoSyncLoading = true,
                subtitleAutoSyncError = null,
                subtitleAutoSyncStatus = null,
                subtitleAutoSyncCues = if (force) emptyList() else it.subtitleAutoSyncCues,
                subtitleAutoSyncCapturedVideoMs = if (force) null else it.subtitleAutoSyncCapturedVideoMs,
                subtitleAutoSyncLoadedTrackKey = selectedTrackKey
            )
        }

        try {
            val rawSubtitleBody = downloadSubtitleBody(
                selectedSubtitle.url,
                selectedSubtitle.lang,
                selectedSubtitle.headers
            )
            val parsedCues = PlayerSubtitleCueParser.parseFromText(
                rawText = rawSubtitleBody,
                sourceUrl = selectedSubtitle.url
            )
                .filter { cue -> cue.text.isNotBlank() }

            if (_uiState.value.selectedAddonSubtitle?.autoSyncTrackKey() != selectedTrackKey) {
                return@launch
            }

            _uiState.update {
                it.copy(
                    subtitleAutoSyncLoading = false,
                    subtitleAutoSyncCues = parsedCues,
                    subtitleAutoSyncStatus = null,
                    subtitleAutoSyncError = if (parsedCues.isEmpty()) {
                        context.getString(com.nuvio.tv.R.string.subtitle_timing_file_no_lines)
                    } else {
                        null
                    },
                    subtitleAutoSyncLoadedTrackKey = selectedTrackKey
                )
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (_uiState.value.selectedAddonSubtitle?.autoSyncTrackKey() != selectedTrackKey) {
                return@launch
            }
            _uiState.update {
                it.copy(
                    subtitleAutoSyncLoading = false,
                    subtitleAutoSyncCues = emptyList(),
                    subtitleAutoSyncStatus = null,
                    subtitleAutoSyncError = e.message ?: context.getString(com.nuvio.tv.R.string.subtitle_timing_load_lines_failed),
                    subtitleAutoSyncLoadedTrackKey = selectedTrackKey
                )
            }
        }
    }
}

private fun PlayerRuntimeController.startAutomaticSubtitleSync() {
    val initialState = _uiState.value
    val selectedSubtitle = initialState.selectedAddonSubtitle
    if (selectedSubtitle == null) {
        _uiState.update {
            it.copy(subtitleAutoSyncLoading = false, subtitleAutoSyncStatus = null,
                subtitleAutoSyncError = context.getString(R.string.subtitle_auto_sync_select_addon_track),
                subtitleAutoSyncAlternatives = emptyList())
        }
        return
    }
    subtitleAutoSyncLoadJob?.cancel()
    val attemptId = ++subtitleAutoSyncAttemptId
    val selectedTrackKey = selectedSubtitle.autoSyncTrackKey()
    val streamUrl = currentStreamUrl
    val streamHeaders = currentHeaders
    val selectedAudioTrack = initialState.audioTracks.firstOrNull {
        it.index == initialState.selectedAudioTrackIndex
    } ?: initialState.audioTracks.firstOrNull { it.isSelected }
    val positionMs = currentPlaybackPositionMs() ?: _playbackTimeline.value.currentPosition
    val durationMs = _playbackTimeline.value.duration
    val startedAtMs = SystemClock.elapsedRealtime()

    subtitleAutoSyncLoadJob = scope.launch {
        val suspension = suspendMainPlaybackForAutoSync()
        val fastProbe = SubtitleFastAudioProbe(context)
        val analyzer = SubtitleAutoSyncAnalysisSession()
        val downloads = mutableMapOf<String, Deferred<Result<List<SubtitleSyncCue>>>>()
        val cueCache = mutableMapOf<String, List<SubtitleSyncCue>>()
        val failedKeys = mutableSetOf<String>()
        val downloadSlots = Semaphore(2)
        val snapshots = mutableListOf<SubtitleSpeechSnapshot>()
        val validationAttempts = mutableListOf<Pair<String, Int>>()
        var firstProbe: Deferred<SubtitleFastAudioProbeResult>? = null
        var selectedDownload: Deferred<List<SubtitleSyncCue>>? = null
        var selectedCues = initialState.subtitleAutoSyncCues.takeIf {
            initialState.subtitleAutoSyncLoadedTrackKey == selectedTrackKey
        }.orEmpty()
        var currentResult = SubtitleAutoSyncResult(0, 0.0, 0.0, 0.0, 0.0, 0,
            SubtitleAutoSyncRejection.NOT_ENOUGH_AUDIO)
        var alternativeResults = emptyList<SubtitleAutoSyncCandidateResult>()
        var alternatives = emptyList<Subtitle>()
        var lastScoredSnapshot: SubtitleSpeechSnapshot? = null
        var lastScoredKeys = emptySet<String>()
        var lastFailure: String? = null
        var validationLimitForProbe = 2

        fun checkAttempt() {
            if (!isCurrentAutoSyncAttempt(attemptId, selectedTrackKey, streamUrl)) {
                throw CancellationException("Auto Sync selection changed")
            }
        }
        fun refreshDownloads() {
            alternatives = SubtitleAutoSyncCandidateMatcher.alternatives(
                selectedSubtitle, _uiState.value.addonSubtitles)
            for (subtitle in alternatives) {
                val key = subtitle.autoSyncTrackKey()
                if (key in downloads || key in cueCache || key in failedKeys) continue
                downloads[key] = async(Dispatchers.IO) {
                    try {
                        downloadSlots.withPermit { Result.success(loadSubtitleAutoSyncCues(subtitle)) }
                    } catch (error: CancellationException) {
                        throw error
                    } catch (error: Exception) {
                        Result.failure(error)
                    }
                }
            }
        }
        suspend fun harvestDownloads() {
            // A slow provider must not block an already downloaded track from being analysed.
            for ((key, task) in downloads.toMap()) {
                if (!task.isCompleted) continue
                task.await().fold(
                    onSuccess = { cueCache[key] = it },
                    onFailure = { failedKeys += key }
                )
                downloads.remove(key)
            }
        }
        fun nomination(isFinal: Boolean = false): SubtitleAutoSyncCandidateResult? {
            fun untried(subtitle: Subtitle, result: SubtitleAutoSyncResult) =
                !SubtitleAutoSyncProgressivePolicy.wasTried(
                    subtitle.autoSyncTrackKey(), result.offsetMs, validationAttempts)
            val selected = currentResult.takeIf {
                untried(selectedSubtitle, it) &&
                    (SubtitleAutoSyncProgressivePolicy.canNominate(it) ||
                        SubtitleAutoSyncTargetedValidation.shouldStart(it, isFinal))
            }?.let { SubtitleAutoSyncCandidateResult(selectedSubtitle, it) }
            val external = SubtitleAutoSyncCandidateMatcher.rank(alternativeResults).firstOrNull {
                untried(it.subtitle, it.result) && it.result.confidence >= 0.62 &&
                    SubtitleAutoSyncProgressivePolicy.canNominate(it.result)
            }
            return when {
                selected?.result?.shouldApply == true -> selected
                external != null && (selected == null ||
                    external.result.confidence >= selected.result.confidence + 0.08) -> external
                else -> selected ?: external
            }
        }
        suspend fun score(snapshot: SubtitleSpeechSnapshot) {
            checkAttempt()
            refreshDownloads()
            harvestDownloads()
            val availableKeys = cueCache.keys.toSet()
            if (lastScoredSnapshot == snapshot && lastScoredKeys == availableKeys) return
            if (selectedCues.isEmpty()) return
            val scoringStartedMs = SystemClock.elapsedRealtime()
            currentResult = analyzer.analyze(selectedCues, snapshot, allowShortHypothesis = true)
            logAutoSyncResult("selected-progressive", selectedSubtitle, currentResult)
            alternativeResults = buildList {
                // Reuse this exact audio profile for every available subtitle.
                for (subtitle in alternatives) {
                    currentCoroutineContext().ensureActive()
                    val cues = cueCache[subtitle.autoSyncTrackKey()] ?: continue
                    val result = analyzer.analyze(cues, snapshot, allowShortHypothesis = true)
                    add(SubtitleAutoSyncCandidateResult(subtitle, result))
                    logAutoSyncResult("alternative-progressive", subtitle, result)
                }
            }
            lastScoredSnapshot = snapshot
            lastScoredKeys = availableKeys
            Log.i(PlayerRuntimeController.TAG, "Subtitle Auto Sync scoring: tracks=" +
                (1 + alternativeResults.size) + " wall=" +
                (SystemClock.elapsedRealtime() - scoringStartedMs) + "ms")
        }
        suspend fun discoveryCheckpoint(partial: SubtitleSpeechSnapshot): Boolean {
            checkAttempt()
            val quality = SubtitleAutoSyncSpeechScout.quality(
                SubtitleFastAudioProbeResult(partial, null, null))
            // Give a quiet opening a second block before abandoning this location.
            if (quality.observedMs < 30_000L && !quality.dialogueRich) return false
            if (selectedCues.isNotEmpty()) {
                score(mergeAutoSyncSnapshots(snapshots + partial, null))
                if (validationAttempts.size < validationLimitForProbe && nomination() != null) return true
            }
            return quality.observedMs >= 30_000L && !quality.dialogueRich
        }

        try {
            _uiState.update {
                it.copy(subtitleAutoSyncLoading = true,
                    subtitleAutoSyncStatus = context.getString(R.string.subtitle_auto_sync_probing_audio),
                    subtitleAutoSyncError = null, subtitleAutoSyncAlternatives = emptyList(),
                    subtitleAutoSyncLoadedTrackKey = selectedTrackKey)
            }
            val positions = planSubtitleAutoSyncProbePositions(positionMs, durationMs)
            validationLimitForProbe = if (positions.size <= 1) AUTO_SYNC_MAX_VALIDATIONS else 2
            val plan = SubtitleFastAudioProbePolicy.plan(currentVideoSize, durationMs)
            selectedDownload = async(Dispatchers.IO) {
                selectedCues.ifEmpty { loadSubtitleAutoSyncCues(selectedSubtitle) }
            }
            refreshDownloads()
            fun request(position: Long) = SubtitleFastAudioProbeRequest(
                streamUrl = streamUrl, headers = streamHeaders, preferredStartMs = position,
                mediaDurationMs = durationMs, selectedAudioTrack = selectedAudioTrack,
                playbackSpeed = plan.playbackSpeed, maxWallClockMs = plan.activeDecodeTimeoutMs,
                onCheckpoint = ::discoveryCheckpoint
            )
            if (streamUrl.isNotBlank() && positions.isNotEmpty()) {
                firstProbe = async { fastProbe.probe(request(positions.first())) }
            }
            selectedCues = selectedDownload.await()
            checkAttempt()
            _uiState.update { it.copy(subtitleAutoSyncCues = selectedCues) }
            Log.i(PlayerRuntimeController.TAG, "Subtitle Auto Sync cues ready: count=" +
                selectedCues.size + " elapsed=" + (SystemClock.elapsedRealtime() - startedAtMs) + "ms")

            suspend fun tryCandidates(isFinal: Boolean): Boolean {
                // Discovery fits offsets to data; it NEVER applies one, even with a high score.
                // Failed holdouts are retained for future discovery, but never counted again as
                // independent confirmation. The planner excludes ALL previously read PCM.
                val attemptLimit = if (isFinal) AUTO_SYNC_MAX_VALIDATIONS else 2
                while (validationAttempts.size < attemptLimit) {
                    val candidate = nomination(isFinal) ?: break
                    val key = candidate.subtitle.autoSyncTrackKey()
                    validationAttempts += key to candidate.result.offsetMs
                    val cues = if (key == selectedTrackKey) selectedCues else cueCache[key] ?: break
                    val confirmed = validateAutoSyncCandidate(
                        candidate.result, cues, snapshots, fastProbe, streamUrl, streamHeaders,
                        selectedAudioTrack, plan, durationMs, attemptId, selectedTrackKey,
                        candidate.subtitle, analyzer)
                    checkAttempt()
                    if (confirmed != null) {
                        if (key == selectedTrackKey) {
                            finishAutoSyncForCurrentTrack(attemptId, confirmed)
                        } else {
                            subtitleAutoSyncLoadJob = null
                            applyMatchedSubtitle(candidate.subtitle, confirmed.offsetMs)
                        }
                        return true
                    }
                    score(mergeAutoSyncSnapshots(snapshots, lastFailure))
                }
                return false
            }

            for ((index, position) in positions.withIndex()) {
                validationLimitForProbe = if (index == positions.lastIndex) AUTO_SYNC_MAX_VALIDATIONS else 2
                currentCoroutineContext().ensureActive()
                checkAttempt()
                if (streamUrl.isBlank()) break
                _uiState.update {
                    it.copy(subtitleAutoSyncStatus = context.getString(
                        R.string.subtitle_auto_sync_probe_attempt, index + 1, positions.size))
                }
                val probe = if (index == 0) firstProbe?.await() else fastProbe.probe(request(position))
                if (probe == null) continue
                probe.snapshot?.let(snapshots::add)
                lastFailure = probe.failureReason ?: lastFailure
                Log.i(PlayerRuntimeController.TAG, "Subtitle Auto Sync progressive probe " +
                    "${index + 1}/${positions.size}: range=${probe.decodedStartMs}..${probe.decodedEndMs} " +
                    "observed=${probe.observedDurationMs}ms wall=${probe.wallClockMs}ms " +
                    "termination=${probe.termination} elapsed=${SystemClock.elapsedRealtime() - startedAtMs}ms")
                score(mergeAutoSyncSnapshots(snapshots, lastFailure))

                if (tryCandidates(isFinal = index == positions.lastIndex)) return@launch
            }
            // On failure only, allow a bounded final chance for pending alternatives. Successful
            // sync never waits for unused downloads; neither does cleanup after this grace period.
            if (downloads.isNotEmpty()) {
                withTimeoutOrNull(5_000L) { downloads.values.toList().joinAll() }
            }
            score(mergeAutoSyncSnapshots(snapshots, lastFailure))
            if (tryCandidates(isFinal = true)) return@launch
            val suggestions = SubtitleAutoSyncCandidateMatcher.rank(alternativeResults)
                .filter { it.result.shouldApply }.take(3).map {
                    SubtitleAutoSyncAlternative(it.subtitle.autoSyncTrackKey(), it.subtitle,
                        it.result.offsetMs, it.result.confidence)
                }
            showAutoSyncFallback(currentResult, suggestions)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            Log.e(PlayerRuntimeController.TAG, "Subtitle Auto Sync failed", error)
            if (isCurrentAutoSyncAttempt(attemptId, selectedTrackKey, streamUrl)) {
                _uiState.update {
                    it.copy(subtitleAutoSyncLoading = false, subtitleAutoSyncStatus = null,
                        subtitleAutoSyncError = error.message ?: context.getString(R.string.subtitle_auto_sync_failed),
                        subtitleAutoSyncAlternatives = emptyList())
                }
            }
        } finally {
            // Cancel all HTTP calls together, then restore playback before joining their children.
            downloads.values.forEach { it.cancel() }
            selectedDownload?.cancel()
            withContext(NonCancellable) {
                firstProbe?.cancelAndJoin()
                fastProbe.release()
                restoreMainPlaybackAfterAutoSync(suspension, streamUrl)
                downloads.values.forEach { it.join() }
                selectedDownload?.join()
            }
            Log.i(PlayerRuntimeController.TAG, "Subtitle Auto Sync attempt finished: attempt=$attemptId " +
                "elapsed=${SystemClock.elapsedRealtime() - startedAtMs}ms")
        }
    }
}

private fun PlayerRuntimeController.suspendMainPlaybackForAutoSync(): AutoSyncPlaybackSuspension {
    if (isUsingMpvEngine()) {
        val wasPlaying = mpvView?.isPlayingNow() == true
        mpvView?.setPaused(true)
        Log.i(PlayerRuntimeController.TAG, "Subtitle Auto Sync suspended main MPV playback")
        return AutoSyncPlaybackSuspension(
            shouldResumePlayback = wasPlaying,
            mpvWasPlaying = wasPlaying
        )
    }

    val player = _exoPlayer ?: return AutoSyncPlaybackSuspension()
    val shouldResume = player.playWhenReady && !userPausedManually
    val positionMs = player.currentPosition.coerceAtLeast(0L)
    val canStopLoading = player.currentMediaItem != null
    subtitleAutoSyncPlaybackSuspended = true
    player.playWhenReady = false
    player.pause()
    if (canStopLoading) {
        // pause() alone keeps filling ExoPlayer's buffer. stop() retains the media item and track
        // selection but cancels the loader, so the probe is the only large HTTP reader.
        player.stop()
    }
    Log.i(
        PlayerRuntimeController.TAG,
        "Subtitle Auto Sync suspended main Exo playback: position=$positionMs " +
            "stopped=$canStopLoading resume=$shouldResume"
    )
    return AutoSyncPlaybackSuspension(
        exoPlayer = player,
        exoPositionMs = positionMs,
        exoWasStopped = canStopLoading,
        shouldResumePlayback = shouldResume
    )
}

private fun PlayerRuntimeController.restoreMainPlaybackAfterAutoSync(
    suspension: AutoSyncPlaybackSuspension,
    expectedStreamUrl: String
) {
    if (currentStreamUrl != expectedStreamUrl) {
        subtitleAutoSyncPlaybackSuspended = false
        return
    }

    val suspendedExoPlayer = suspension.exoPlayer
    if (suspendedExoPlayer != null) {
        if (_exoPlayer !== suspendedExoPlayer) {
            subtitleAutoSyncPlaybackSuspended = false
            return
        }
        val resume = suspension.shouldResumePlayback && !userPausedManually
        if (!resume) {
            // PlayerStartupPlaybackPolicy resumes every post-first-frame READY unless the pause is
            // explicit. Preserve the pre-Auto-Sync paused state across stop()/prepare().
            userPausedManually = true
        }
        if (suspension.exoWasStopped) {
            suspendedExoPlayer.seekTo(suspension.exoPositionMs)
            suspendedExoPlayer.prepare()
        }
        suspendedExoPlayer.playWhenReady = resume
        if (resume) suspendedExoPlayer.play() else suspendedExoPlayer.pause()
        subtitleAutoSyncPlaybackSuspended = false
        Log.i(
            PlayerRuntimeController.TAG,
            "Subtitle Auto Sync restored main Exo playback: position=${suspension.exoPositionMs} " +
                "resume=$resume"
        )
        return
    }

    subtitleAutoSyncPlaybackSuspended = false
    if (suspension.mpvWasPlaying && !userPausedManually) {
        mpvView?.setPaused(false)
        Log.i(PlayerRuntimeController.TAG, "Subtitle Auto Sync restored main MPV playback")
    }
}

private suspend fun SubtitleFastAudioProbe.probeWithAdaptiveRetry(
    request: SubtitleFastAudioProbeRequest,
    plan: SubtitleFastAudioProbePlan
): AutoSyncExecutedProbe {
    val firstBudgetMs = maxOf(request.maxWallClockMs, plan.activeDecodeTimeoutMs)
    val firstResult = probe(
        request.copy(
            playbackSpeed = plan.playbackSpeed,
            maxWallClockMs = firstBudgetMs
        )
    )
    val nextPlan = SubtitleFastAudioProbePolicy.afterProbe(plan, firstResult)
    val trialNeedsFallback = plan.isUpshiftTrial &&
        firstResult.termination == SubtitleFastAudioProbeTermination.WALL_TIMEOUT &&
        nextPlan.playbackSpeed < plan.playbackSpeed
    if (!trialNeedsFallback) return AutoSyncExecutedProbe(firstResult, plan, nextPlan)

    Log.i(
        PlayerRuntimeController.TAG,
        "Subtitle Auto Sync speed trial retry: ${plan.playbackSpeed}x -> " +
            "${nextPlan.playbackSpeed}x observed=${firstResult.observedDurationMs}ms"
    )
    val fallbackResult = probe(
        request.copy(
            playbackSpeed = nextPlan.playbackSpeed,
            maxWallClockMs = maxOf(request.maxWallClockMs, nextPlan.activeDecodeTimeoutMs),
            // PCM decoded during the failed speed trial is still valid evidence. Continue from
            // its endpoint at the fallback speed instead of downloading that range again.
            seedSnapshot = firstResult.snapshot ?: request.seedSnapshot
        )
    )
    val afterFallback = SubtitleFastAudioProbePolicy.afterProbe(nextPlan, fallbackResult)
    val stableNextPlan = if (afterFallback.isUpshiftTrial) {
        // The same source has just failed at the faster tier. Keep the proven fallback speed for
        // the next sample instead of immediately paying for another identical trial.
        nextPlan
    } else {
        afterFallback
    }
    return AutoSyncExecutedProbe(fallbackResult, nextPlan, stableNextPlan)
}

private suspend fun PlayerRuntimeController.validateAutoSyncCandidate(
    candidate: SubtitleAutoSyncResult,
    cues: List<SubtitleSyncCue>,
    existingSnapshots: MutableList<SubtitleSpeechSnapshot>,
    fastProbe: SubtitleFastAudioProbe,
    streamUrl: String,
    streamHeaders: Map<String, String>,
    selectedAudioTrack: TrackInfo?,
    probePlan: SubtitleFastAudioProbePlan,
    mediaDurationMs: Long,
    attemptId: Long,
    selectedTrackKey: String,
    selectedSubtitle: Subtitle,
    analyzer: SubtitleAutoSyncAnalysisSession
): SubtitleAutoSyncResult? {
    var validationProbePlan = probePlan
    val positions = planSubtitleAutoSyncValidationPositions(
        cues = cues,
        candidateOffsetMs = candidate.offsetMs,
        durationMs = mediaDurationMs,
        excludedObservedSpans = existingSnapshots.flatMap { it.observedSpans }
    )
    if (positions.size < SubtitleAutoSyncTargetedValidation.MAX_PROBES) {
        Log.i(
            PlayerRuntimeController.TAG,
            "Subtitle Auto Sync targeted validation skipped: candidate=${candidate.offsetMs} " +
                "independentWindows=${positions.size}"
        )
        return null
    }

    val validationResults = mutableListOf<SubtitleAutoSyncResult>()
    for ((index, positionMs) in positions.withIndex()) {
        currentCoroutineContext().ensureActive()
        if (!isCurrentAutoSyncAttempt(attemptId, selectedTrackKey, streamUrl)) {
            throw CancellationException("Auto Sync selection changed")
        }
        _uiState.update {
            it.copy(
                subtitleAutoSyncStatus = context.getString(
                    R.string.subtitle_auto_sync_confirming_timing,
                    index + 1,
                    positions.size
                )
            )
        }

        val executedProbe = fastProbe.probeWithAdaptiveRetry(
            request = SubtitleFastAudioProbeRequest(
                streamUrl = streamUrl,
                headers = streamHeaders,
                preferredStartMs = positionMs,
                mediaDurationMs = mediaDurationMs,
                selectedAudioTrack = selectedAudioTrack,
                playbackSpeed = validationProbePlan.playbackSpeed,
                maxWallClockMs = maxOf(
                    AUTO_SYNC_VALIDATION_MAX_WALL_CLOCK_MS,
                    validationProbePlan.activeDecodeTimeoutMs
                ),
                onCheckpoint = { partial ->
                    if (SubtitleAutoSyncEngine.measureAudioEvidence(partial).ready) {
                        val result = analyzer.analyze(cues, partial,
                            candidate.offsetMs - SubtitleAutoSyncProgressivePolicy.VALIDATION_COMPETITOR_RADIUS_MS,
                            candidate.offsetMs + SubtitleAutoSyncProgressivePolicy.VALIDATION_COMPETITOR_RADIUS_MS)
                        SubtitleAutoSyncProgressivePolicy.confirms(candidate, result)
                    } else false
                }
            ),
            plan = validationProbePlan
        )
        val probeResult = executedProbe.result
        val usedValidationPlan = executedProbe.plan
        validationProbePlan = executedProbe.nextPlan
        Log.i(
            PlayerRuntimeController.TAG,
            "Subtitle Auto Sync validation ${index + 1}/${positions.size}: " +
                "candidate=${candidate.offsetMs} requested=$positionMs " +
                "range=${probeResult.decodedStartMs}..${probeResult.decodedEndMs} " +
                "decoded=${probeResult.decodedDurationMs}ms " +
                "observed=${probeResult.observedDurationMs}ms " +
                "termination=${probeResult.termination} speed=${usedValidationPlan.playbackSpeed}x " +
                "wall=${probeResult.wallClockMs}ms startup=${probeResult.startupDurationMs}ms " +
                "active=${probeResult.activeDecodeDurationMs}ms"
        )
        val validationSnapshot = probeResult.snapshot ?: return null
        val overlaps = existingSnapshots.any { previous ->
            previous.observedSpans.any { old ->
                validationSnapshot.observedSpans.any { fresh ->
                    old.startMs < fresh.endMs && fresh.startMs < old.endMs
                }
            }
        }
        existingSnapshots += validationSnapshot
        if (overlaps) {
            Log.w(PlayerRuntimeController.TAG, "Subtitle Auto Sync holdout overlaps previous PCM; rejecting")
            return null
        }
        val validationResult = analyzer.analyze(
            cues = cues,
            snapshot = validationSnapshot,
            minimumOffsetMs = candidate.offsetMs - SubtitleAutoSyncProgressivePolicy.VALIDATION_COMPETITOR_RADIUS_MS,
            maximumOffsetMs = candidate.offsetMs + SubtitleAutoSyncProgressivePolicy.VALIDATION_COMPETITOR_RADIUS_MS
        )
        logAutoSyncResult(
            source = "selected-validation-${index + 1}",
            subtitle = selectedSubtitle,
            result = validationResult
        )
        validationResults += validationResult
        if (!SubtitleAutoSyncProgressivePolicy.confirms(candidate, validationResult)) {
            return null
        }
    }

    if (candidate.evidenceWindows < 2 &&
        validationResults.maxOf { it.offsetMs } - validationResults.minOf { it.offsetMs } > 1_000
    ) return null
    return SubtitleAutoSyncTargetedValidation.confirmedResult(candidate, validationResults)
        ?.also { confirmed ->
            logAutoSyncResult("selected-validation-confirmed", selectedSubtitle, confirmed)
        }
}

internal fun PlayerRuntimeController.applySubtitleAutoSyncAlternative(trackKey: String) {
    val alternative = _uiState.value.subtitleAutoSyncAlternatives
        .firstOrNull { it.trackKey == trackKey }
        ?: return
    subtitleAutoSyncLoadJob?.cancel()
    subtitleAutoSyncLoadJob = null
    subtitleAutoSyncAttemptId++
    applyMatchedSubtitle(alternative.subtitle, alternative.offsetMs)
}

private fun PlayerRuntimeController.applyMatchedSubtitle(subtitle: Subtitle, offsetMs: Int) {
    autoSubtitleSelected = true
    rememberAddonSubtitleSelection(subtitle)
    selectAddonSubtitle(subtitle)
    setSubtitleDelayMs(targetMs = offsetMs, showOverlay = true)
    _uiState.update {
        it.copy(
            showSubtitleTimingDialog = false,
            subtitleAutoSyncLoading = false,
            subtitleAutoSyncStatus = context.getString(
                R.string.subtitle_auto_sync_applied,
                formatAutoSyncDelay(offsetMs)
            ),
            subtitleAutoSyncError = null,
            subtitleAutoSyncAlternatives = emptyList()
        )
    }
}

private fun PlayerRuntimeController.finishAutoSyncForCurrentTrack(
    attemptId: Long,
    result: SubtitleAutoSyncResult
) {
    if (subtitleAutoSyncAttemptId != attemptId) return
    setSubtitleDelayMs(targetMs = result.offsetMs, showOverlay = true)
    _uiState.update {
        it.copy(
            showSubtitleTimingDialog = false,
            subtitleAutoSyncLoading = false,
            subtitleAutoSyncStatus = context.getString(
                R.string.subtitle_auto_sync_applied,
                formatAutoSyncDelay(result.offsetMs)
            ),
            subtitleAutoSyncError = null,
            subtitleAutoSyncAlternatives = emptyList()
        )
    }
}

private fun PlayerRuntimeController.isCurrentAutoSyncAttempt(
    attemptId: Long,
    selectedTrackKey: String,
    streamUrl: String
): Boolean = subtitleAutoSyncAttemptId == attemptId &&
    currentStreamUrl == streamUrl &&
    _uiState.value.selectedAddonSubtitle?.autoSyncTrackKey() == selectedTrackKey

private suspend fun PlayerRuntimeController.loadSubtitleAutoSyncCues(
    subtitle: Subtitle
): List<SubtitleSyncCue> {
    val rawSubtitleBody = downloadSubtitleBody(subtitle.url, subtitle.lang, subtitle.headers)
    return PlayerSubtitleCueParser.parseFromText(
        rawText = rawSubtitleBody,
        sourceUrl = subtitle.url
    )
        .filter { it.text.isNotBlank() }
        .ifEmpty { error(context.getString(R.string.subtitle_timing_file_no_lines)) }
}

private fun mergeAutoSyncSnapshots(
    probeSnapshots: List<SubtitleSpeechSnapshot>,
    probeFailure: String?
): SubtitleSpeechSnapshot {
    val snapshots = probeSnapshots
    return SubtitleSpeechSnapshot(
        speechSpans = SubtitleAutoSyncEngine.mergeSpans(snapshots.flatMap { it.speechSpans }, 300L),
        observedSpans = SubtitleAutoSyncEngine.mergeSpans(snapshots.flatMap { it.observedSpans }, 120L),
        pcmAvailable = snapshots.any { it.pcmAvailable },
        failureReason = snapshots.firstNotNullOfOrNull { it.failureReason }
            ?: probeFailure
    )
}

/**
 * Plans non-overlapping probe windows. Known-duration media is sampled across its timeline; when
 * duration is unknown, retries move backwards so a playhead close to EOF does not keep returning
 * the same short tail.
 */
internal fun planSubtitleAutoSyncProbePositions(
    currentPositionMs: Long,
    durationMs: Long,
    maxAttempts: Int = AUTO_SYNC_MAX_FAST_PROBES
): List<Long> {
    if (maxAttempts <= 0) return emptyList()
    val current = currentPositionMs.coerceAtLeast(0L).let { position ->
        if (durationMs > 0L) position.coerceAtMost(durationMs) else position
    }
    val candidates = if (durationMs > 0L) {
        listOf(
            current,
            durationMs / 4L,
            durationMs / 2L,
            (durationMs * 3L) / 4L,
            (durationMs - 90_000L).coerceAtLeast(0L),
            0L
        )
    } else if (current >= 120_000L) {
        listOf(
            current,
            (current - 90_000L).coerceAtLeast(0L),
            (current - 180_000L).coerceAtLeast(0L),
            current + 90_000L,
            0L
        )
    } else {
        listOf(current, current + 90_000L, current + 180_000L, 0L)
    }

    fun effectiveStart(preferredMs: Long): Long {
        val preRolled = (preferredMs - 5_000L).coerceAtLeast(0L)
        return if (durationMs > 0L) {
            preRolled.coerceAtMost((durationMs - 60_000L).coerceAtLeast(0L))
        } else {
            preRolled
        }
    }

    val selected = mutableListOf<Long>()
    val effectiveStarts = mutableListOf<Long>()
    for (candidate in candidates) {
        val preferred = candidate.coerceAtLeast(0L)
        val effective = effectiveStart(preferred)
        // Probe windows decode up to 60 seconds. Keep their starts farther apart so two samples
        // cannot mostly observe the same dialogue and masquerade as independent evidence.
        if (effectiveStarts.none { kotlin.math.abs(it - effective) < 75_000L }) {
            selected += preferred
            effectiveStarts += effective
        }
        if (selected.size >= maxAttempts) break
    }
    return selected.ifEmpty { listOf(0L) }
}

private fun PlayerRuntimeController.logAutoSyncResult(
    source: String,
    subtitle: Subtitle,
    result: SubtitleAutoSyncResult
) {
    Log.i(
        PlayerRuntimeController.TAG,
        "Subtitle Auto Sync $source id=${subtitle.id}: offset=${result.offsetMs} " +
            "confidence=${result.confidence} margin=${result.scoreMargin} sigma=${result.sigma} " +
            "agreement=${result.windowAgreement} windows=${result.evidenceWindows} " +
            "rejection=${result.rejection}"
    )
}

private fun PlayerRuntimeController.showAutoSyncFallback(
    result: SubtitleAutoSyncResult,
    alternatives: List<SubtitleAutoSyncAlternative>
) {
    val fallbackMessage = if (alternatives.isNotEmpty()) {
        context.getString(R.string.subtitle_auto_sync_alternatives_found)
    } else {
        autoSyncFallbackMessage(result)
    }
    _uiState.update {
        it.copy(
            subtitleAutoSyncLoading = false,
            subtitleAutoSyncStatus = null,
            subtitleAutoSyncError = fallbackMessage,
            subtitleAutoSyncAlternatives = alternatives
        )
    }
}

private fun PlayerRuntimeController.autoSyncFallbackMessage(result: SubtitleAutoSyncResult): String =
    when (result.rejection) {
        SubtitleAutoSyncRejection.PCM_UNAVAILABLE ->
            context.getString(R.string.subtitle_auto_sync_pcm_unavailable)
        SubtitleAutoSyncRejection.NOT_ENOUGH_AUDIO ->
            context.getString(R.string.subtitle_auto_sync_need_more_audio)
        SubtitleAutoSyncRejection.NOT_ENOUGH_DIALOGUE ->
            context.getString(R.string.subtitle_auto_sync_not_enough_dialogue)
        SubtitleAutoSyncRejection.LOW_CONFIDENCE,
        SubtitleAutoSyncRejection.NONE ->
            context.getString(
                R.string.subtitle_auto_sync_low_confidence,
                (result.confidence * 100.0).roundToInt()
            )
    }

/**
 * Downloads a remote subtitle body for sidecar rendering / auto-sync.
 *
 * Stream headers are scoped to the stream's host; see [subtitleStreamHeaders].
 */
internal suspend fun PlayerRuntimeController.downloadSubtitleBody(
    url: String,
    languageHint: String? = null,
    headers: Map<String, String>? = null
): String =
    withContext(Dispatchers.IO) {
        var lastError: Exception? = null
        repeat(SUBTITLE_DOWNLOAD_MAX_ATTEMPTS) { attempt ->
            try {
                return@withContext executeSubtitleDownload(url, languageHint, headers)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                lastError = e
                if (attempt < SUBTITLE_DOWNLOAD_MAX_ATTEMPTS - 1) {
                    delay(SUBTITLE_DOWNLOAD_RETRY_DELAY_MS * (attempt + 1))
                }
            }
        }
        throw lastError ?: IllegalStateException("Subtitle download failed")
    }

// Request control headers, never copied to a subtitle request from the stream or the subtitle.
private val SUBTITLE_REQUEST_EXCLUDED_HEADERS = setOf("range", "host", "connection", "transfer-encoding")

// Stream headers allowed on other hosts (#3328).
private val SUBTITLE_CROSS_HOST_HEADERS = setOf("referer", "origin", "user-agent", "accept-language")

// Not forwarded on an HTTPS to HTTP downgrade.
private val SUBTITLE_DOWNGRADE_EXCLUDED_HEADERS = setOf("referer", "origin")

private fun isDowngrade(requestUrl: HttpUrl, scopeUrl: HttpUrl?): Boolean =
    scopeUrl != null && scopeUrl.isHttps && !requestUrl.isHttps

/** Same host as [scopeUrl] and no HTTPS to HTTP downgrade. Sibling subdomains are out of scope. */
internal fun isInHeaderScope(requestUrl: HttpUrl, scopeUrl: HttpUrl?): Boolean {
    if (scopeUrl == null) return false
    if (isDowngrade(requestUrl, scopeUrl)) return false
    return requestUrl.host == scopeUrl.host
}

/**
 * All stream headers in scope, only [SUBTITLE_CROSS_HOST_HEADERS] outside it. Credentials can use any
 * header name, so this is an allowlist.
 */
internal fun subtitleStreamHeaders(
    streamHeaders: Map<String, String>,
    subtitleUrl: HttpUrl,
    streamUrl: HttpUrl?
): Map<String, String> {
    val inScope = isInHeaderScope(subtitleUrl, streamUrl)
    val downgrade = isDowngrade(subtitleUrl, streamUrl)
    return streamHeaders.filterKeys { name ->
        val lower = name.lowercase()
        when {
            lower in SUBTITLE_REQUEST_EXCLUDED_HEADERS -> false
            inScope -> true
            downgrade && lower in SUBTITLE_DOWNGRADE_EXCLUDED_HEADERS -> false
            else -> lower in SUBTITLE_CROSS_HOST_HEADERS
        }
    }
}

/**
 * Stream headers scoped to the stream URL: [names] are removed on a hop outside it, [downgradeNames] on an
 * HTTPS to HTTP hop. Subtitle-owned headers are tracked by [SubtitleOwnHeaders].
 */
internal class ForwardedStreamHeaders(
    val streamUrl: HttpUrl,
    val names: Set<String>,
    val downgradeNames: Set<String> = emptySet()
)

/**
 * The subtitle's own headers, scoped to the subtitle URL's host the same way: [names] are removed on a hop
 * to another host, [downgradeNames] on an HTTPS to HTTP hop.
 */
internal class SubtitleOwnHeaders(
    val subtitleUrl: HttpUrl,
    val names: Set<String>,
    val downgradeNames: Set<String>
)

/**
 * Applies [ForwardedStreamHeaders] and [SubtitleOwnHeaders] per redirect hop; OkHttp itself only strips
 * Authorization. Relies on OkHttp carrying request tags into redirects. It never adds headers back, so an
 * HTTP URL redirecting to HTTPS doesn't regain them.
 */
internal object ForwardedStreamHeaderGuard : Interceptor {
    override fun intercept(chain: Interceptor.Chain): okhttp3.Response {
        val request = chain.request()
        val url = request.url
        val removed = mutableSetOf<String>()
        request.tag(ForwardedStreamHeaders::class.java)?.let { stream ->
            if (!isInHeaderScope(url, stream.streamUrl)) removed += stream.names
            if (isDowngrade(url, stream.streamUrl)) removed += stream.downgradeNames
        }
        request.tag(SubtitleOwnHeaders::class.java)?.let { own ->
            if (!isInHeaderScope(url, own.subtitleUrl)) removed += own.names
            if (isDowngrade(url, own.subtitleUrl)) removed += own.downgradeNames
        }
        return chain.proceed(request.withoutHeaders(removed))
    }
}

/** Removes the host-scoped stream and subtitle headers, for the permissive TLS retry. */
internal fun Request.withoutCredentialHeaders(): Request =
    withoutHeaders(
        tag(ForwardedStreamHeaders::class.java)?.names.orEmpty() +
            tag(SubtitleOwnHeaders::class.java)?.names.orEmpty()
    )

private fun Request.withoutHeaders(names: Set<String>): Request =
    if (names.isEmpty()) this else newBuilder().apply { names.forEach { removeHeader(it) } }.build()

/** Scoped stream headers, then the subtitle's own headers, then defaults, tagged for the guard. */
internal fun buildSubtitleRequest(
    subtitleUrl: HttpUrl,
    streamUrl: HttpUrl?,
    streamHeaders: Map<String, String>,
    explicitHeaders: Map<String, String>?
): Request {
    val requestBuilder = Request.Builder().url(subtitleUrl)

    val scopedStreamHeaders = subtitleStreamHeaders(streamHeaders, subtitleUrl, streamUrl)
    scopedStreamHeaders.forEach { (key, value) -> requestBuilder.header(key, value) }

    // Explicit subtitle headers override stream headers.
    explicitHeaders?.forEach { (key, value) ->
        if (key.lowercase() !in SUBTITLE_REQUEST_EXCLUDED_HEADERS) {
            requestBuilder.header(key, value)
        }
    }

    // A stream User-Agent is always allowed through, so this only fills the gap.
    if (requestBuilder.build().header("User-Agent") == null) {
        requestBuilder.header(
            "User-Agent",
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
                "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
        )
    }

    if (requestBuilder.build().header("Accept") == null) {
        requestBuilder.header("Accept", "text/plain, text/vtt, application/x-subrip, */*")
    }

    val explicitNames = explicitHeaders?.keys.orEmpty().map { it.lowercase() }.toSet()
    val forwardedNames = scopedStreamHeaders.keys
        .filter { it.lowercase() !in SUBTITLE_CROSS_HOST_HEADERS && it.lowercase() !in explicitNames }
        .toSet()
    val downgradeNames = scopedStreamHeaders.keys
        .filter { it.lowercase() in SUBTITLE_DOWNGRADE_EXCLUDED_HEADERS && it.lowercase() !in explicitNames }
        .toSet()
    if (streamUrl != null && (forwardedNames.isNotEmpty() || downgradeNames.isNotEmpty())) {
        requestBuilder.tag(
            ForwardedStreamHeaders::class.java,
            ForwardedStreamHeaders(streamUrl, forwardedNames, downgradeNames)
        )
    }
    val sentOwnNames = explicitHeaders?.keys.orEmpty()
        .filter { it.lowercase() !in SUBTITLE_REQUEST_EXCLUDED_HEADERS }
    val ownNames = sentOwnNames.filter { it.lowercase() !in SUBTITLE_CROSS_HOST_HEADERS }.toSet()
    val ownDowngradeNames = sentOwnNames.filter { it.lowercase() in SUBTITLE_DOWNGRADE_EXCLUDED_HEADERS }.toSet()
    if (ownNames.isNotEmpty() || ownDowngradeNames.isNotEmpty()) {
        requestBuilder.tag(
            SubtitleOwnHeaders::class.java,
            SubtitleOwnHeaders(subtitleUrl, ownNames, ownDowngradeNames)
        )
    }
    return requestBuilder.build()
}

/**
 * Tries [validated] first. On any SSLException, reruns the whole redirect chain on [permissive] without
 * host-scoped stream or subtitle headers, so self-signed hosts still work.
 */
internal fun executeSubtitleRequest(
    request: Request,
    validated: OkHttpClient = subtitleHttpClient,
    permissive: OkHttpClient = subtitleUnvalidatedTlsHttpClient
): okhttp3.Response =
    try {
        validated.newCall(request).execute()
    } catch (e: SSLException) {
        permissive.newCall(request.withoutCredentialHeaders()).execute()
    }

private suspend fun PlayerRuntimeController.executeSubtitleDownload(
    url: String,
    languageHint: String? = null,
    customHeaders: Map<String, String>? = null
): String {
    val explicitHeaders = customHeaders
        ?: streamSubtitles.firstOrNull { it.url == url }?.headers
        ?: _uiState.value.addonSubtitles.firstOrNull { it.url == url }?.headers
        ?: _uiState.value.selectedAddonSubtitle?.takeIf { it.url == url }?.headers
    val request = buildSubtitleRequest(
        subtitleUrl = url.toHttpUrl(),
        streamUrl = currentStreamUrl.toHttpUrlOrNull(),
        streamHeaders = currentHeaders,
        explicitHeaders = explicitHeaders
    )

    return readSubtitleResponseCancellable(request) { response ->
        if (!response.isSuccessful) {
            error(context.getString(com.nuvio.tv.R.string.subtitle_download_failed_http, response.code))
        }
        val bodyBytes = response.body?.bytes()
            ?: error(context.getString(com.nuvio.tv.R.string.subtitle_download_empty_content))
        if (bodyBytes.isEmpty()) {
            error(context.getString(com.nuvio.tv.R.string.subtitle_download_empty_content))
        }
        val body = SubtitleCharsetDetector.decode(bodyBytes, languageHint = languageHint)
        if (body.isBlank()) {
            error(context.getString(com.nuvio.tv.R.string.subtitle_download_empty_content))
        }
        body
    }
}

private fun Subtitle.autoSyncTrackKey(): String = "$id|$url"

internal fun formatAutoSyncTimestamp(positionMs: Long): String {
    val totalSeconds = (positionMs / 1000L).coerceAtLeast(0L)
    val hours = totalSeconds / 3600L
    val minutes = (totalSeconds % 3600L) / 60L
    val seconds = totalSeconds % 60L
    return if (hours > 0) {
        String.format("%d:%02d:%02d", hours, minutes, seconds)
    } else {
        String.format("%02d:%02d", minutes, seconds)
    }
}

internal fun formatAutoSyncDelay(delayMs: Int): String {
    val sign = if (delayMs >= 0) "+" else "-"
    val absMs = kotlin.math.abs(delayMs)
    val seconds = absMs / 1000
    val millis = absMs % 1000
    return "$sign${seconds}.${millis.toString().padStart(3, '0')}s"
}
