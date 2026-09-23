package com.nuvio.tv.ui.screens.player

import android.content.Context
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.audio.AudioCapabilities
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.extractor.DefaultExtractorsFactory
import com.nuvio.tv.core.player.DolbyVisionConversionConfig
import com.nuvio.tv.core.player.DolbyVisionExtractorsFactory
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.coroutineContext

private const val DEFAULT_FAST_AUDIO_PROBE_MAX_WALL_CLOCK_MS = 20_000L
private const val DEFAULT_FAST_AUDIO_PROBE_STARTUP_TIMEOUT_MS = 20_000L

internal data class SubtitleFastAudioProbeRequest(
    val streamUrl: String,
    val headers: Map<String, String>,
    val preferredStartMs: Long,
    val mediaDurationMs: Long,
    val selectedAudioTrack: TrackInfo?,
    val playbackSpeed: Float = 8f,
    /** Amount of decoded media-time PCM to collect before completing this probe. */
    val targetAudioDurationMs: Long = 60_000L,
    /** Window used for EOF clamping; scouts keep the same start as the later full probe. */
    val windowDurationMs: Long = targetAudioDurationMs,
    /** PCM already decoded by a short scout at the same position. */
    val seedSnapshot: SubtitleSpeechSnapshot? = null,
    /** Time allowed after the first PCM frame, excluding stream/index startup. */
    val maxWallClockMs: Long = DEFAULT_FAST_AUDIO_PROBE_MAX_WALL_CLOCK_MS,
    val startupTimeoutMs: Long = DEFAULT_FAST_AUDIO_PROBE_STARTUP_TIMEOUT_MS,
    /** Return true to finish, false to extend in-place to the next 15 s checkpoint. */
    val onCheckpoint: (suspend (SubtitleSpeechSnapshot) -> Boolean)? = null
)

internal enum class SubtitleFastAudioProbeTermination {
    TARGET_REACHED,
    EOF,
    STALLED,
    WALL_TIMEOUT,
    ERROR
}

internal data class SubtitleFastAudioProbeResult(
    val snapshot: SubtitleSpeechSnapshot?,
    val decodedStartMs: Long?,
    val decodedEndMs: Long?,
    val failureReason: String? = null,
    val termination: SubtitleFastAudioProbeTermination = SubtitleFastAudioProbeTermination.ERROR,
    val wallClockMs: Long = 0L,
    val startupDurationMs: Long = 0L,
    val activeDecodeDurationMs: Long = 0L
) {
    val decodedDurationMs: Long
        get() = if (decodedStartMs != null && decodedEndMs != null) {
            (decodedEndMs - decodedStartMs).coerceAtLeast(0L)
        } else {
            0L
        }

    val observedDurationMs: Long
        get() = snapshot?.let { mergedObservedDurationMs(it.observedSpans) } ?: 0L
}

/**
 * On-demand, audio-only ExoPlayer used by Auto Sync.
 *
 * The player is created on the first [probe], reused for later seeks in the same Auto Sync run,
 * and released explicitly after discovery/validation. It uses its own MediaSource/LoadControl,
 * never attaches a video surface, and has video/text/image/metadata tracks disabled. Its data
 * source deliberately bypasses [PlayerMediaSourceFactory]'s shared VOD cache/session state while
 * retaining the same Nuvio HTTP stack and request headers.
 *
 * Audio focus is disabled. [SubtitleAnalysisAudioSink] consumes PCM without playback pacing,
 * pausing decoder consumption at analysis checkpoints. Adaptive-speed AudioTrack output is only
 * a compatibility fallback. PCM is mapped using the renderer stream offset. Player and sink
 * resources are always released from the application looper, including cancellation.
 */
internal class SubtitleFastAudioProbe(
    context: Context
) {
    private val appContext = context.applicationContext
    private var session: ProbeSession? = null
    private var useLegacySink = false

    private companion object {
        const val TAG = "SubtitleFastProbe"
        const val MULTI_PERIOD_FAILURE = "Auto Sync cannot safely map a multi-period media timeline"
        const val PRE_ROLL_MS = 5_000L
        const val POLL_INTERVAL_MS = 40L
        const val RELEASE_TIMEOUT_MS = 2_000L

        const val MIN_BUFFER_MS = 2_000
        const val MAX_BUFFER_MS = 15_000
        const val BUFFER_FOR_PLAYBACK_MS = 250
        const val BUFFER_AFTER_REBUFFER_MS = 500
        const val TARGET_BUFFER_BYTES = 12 * 1024 * 1024
    }

    private data class ProbeSession(
        val key: String,
        val player: ExoPlayer,
        val collector: SubtitleSpeechProfileCollector,
        val readGate: SubtitleAnalysisReadGate,
        var activeRequest: SubtitleFastAudioProbeRequest? = null,
        var requestedStartMs: Long = 0L,
        var playerError: PlaybackException? = null,
        var playbackEnded: Boolean = false,
        var audioOverrideResolved: Boolean = false,
        var trackSelectionFailure: String? = null
    )

    suspend fun probe(request: SubtitleFastAudioProbeRequest): SubtitleFastAudioProbeResult {
        val startedAtMs = SystemClock.elapsedRealtime()
        val result = probeOnce(request)
        if (shouldRetrySubtitleProbeWithCompatibility(
                useLegacySink, result, unsupportedTimeline = result.failureReason == MULTI_PERIOD_FAILURE
            )
        ) {
            Log.w(TAG, "Analysis output ${result.termination} after ${result.observedDurationMs}ms PCM; " +
                "retrying once with compatibility output and retaining valid PCM")
            useLegacySink = true
            release()
            coroutineContext.ensureActive()
            val retry = probeOnce(subtitleProbeCompatibilityRequest(request, result))
            return retry.copy(
                wallClockMs = SystemClock.elapsedRealtime() - startedAtMs,
                startupDurationMs = result.startupDurationMs + retry.startupDurationMs,
                activeDecodeDurationMs = result.activeDecodeDurationMs + retry.activeDecodeDurationMs
            )
        }
        return result
    }

    private suspend fun probeOnce(request: SubtitleFastAudioProbeRequest): SubtitleFastAudioProbeResult =
        withContext(Dispatchers.Main.immediate) {
            if (request.streamUrl.isBlank()) {
                return@withContext errorResult("Missing stream URL")
            }

            val seedSnapshot = request.seedSnapshot?.takeIf { it.pcmAvailable }
            val seedObservedMs = seedSnapshot?.observedDurationMs() ?: 0L
            val totalTargetMs = request.targetAudioDurationMs.coerceAtLeast(1L)
            val completeSeed = seedSnapshot?.takeIf { seedObservedMs >= totalTargetMs }
            if (completeSeed != null) {
                return@withContext completeSeed.toProbeResult(
                    termination = SubtitleFastAudioProbeTermination.TARGET_REACHED,
                    failureReason = null
                )
            }
            val requestedStartMs = seedSnapshot
                ?.observedSpans
                ?.maxOfOrNull { it.endMs }
                ?: resolveWindowStartMs(request)
            val remainingTargetMs = (totalTargetMs - seedObservedMs).coerceAtLeast(1L)
            var checkpointMs = if (request.onCheckpoint != null) {
                minOf(totalTargetMs, (seedObservedMs / 15_000L + 1L) * 15_000L)
            } else totalTargetMs
            var analysisDurationMs = 0L
            val startedAtMs = SystemClock.elapsedRealtime()
            var firstPcmAtMs: Long? = null
            var activeSession: ProbeSession? = null

            try {
                val playbackSpeed = request.playbackSpeed
                    .takeIf { it.isFinite() && it > 0f }
                    ?.coerceIn(1f, 8f)
                    ?: 1f
                val currentSession = ensureSession(request, playbackSpeed)
                activeSession = currentSession
                val collector = currentSession.collector
                currentSession.activeRequest = request
                currentSession.requestedStartMs = requestedStartMs
                currentSession.playerError = null
                currentSession.playbackEnded = false
                currentSession.audioOverrideResolved = request.selectedAudioTrack == null
                currentSession.trackSelectionFailure = null
                currentSession.readGate.close()
                currentSession.readGate.begin(requestedStartMs, checkpointMs - seedObservedMs)
                collector.beginSession(
                    key = "exo-fast:${request.streamUrl.hashCode()}:$requestedStartMs",
                    timelineAnchorMs = null
                )
                collector.stopCollecting(clearExisting = true)

                val localPlayer = currentSession.player
                localPlayer.playbackParameters = PlaybackParameters(if (useLegacySink) playbackSpeed else 1f, 1f)
                localPlayer.seekTo(requestedStartMs)
                if (request.selectedAudioTrack == null) {
                    collector.startCollecting(clearExisting = true)
                    currentSession.readGate.enable()
                } else if (localPlayer.currentTracks.groups.any { it.type == C.TRACK_TYPE_AUDIO }) {
                    resolveSelectedAudioTrack(currentSession, localPlayer.currentTracks)
                }
                if (localPlayer.playbackState == Player.STATE_IDLE) localPlayer.prepare()
                localPlayer.play()

                val playbackStartedAtMs = SystemClock.elapsedRealtime()
                val activeTimeoutMs = request.maxWallClockMs.coerceAtLeast(1L)
                val startupTimeoutMs = request.startupTimeoutMs.coerceAtLeast(1L)
                val progressWatchdog = SubtitleProbeProgressWatchdog()
                val termination = withTimeoutOrNull<SubtitleFastAudioProbeTermination>(
                    startupTimeoutMs + activeTimeoutMs + 120_000L
                ) {
                    while (true) {
                        coroutineContext.ensureActive()
                        currentSession.playerError?.let { throw it }
                        // Subtracting a renderer's output offset yields PERIOD time. For ordinary
                        // single-period files/HLS that is media time; multi-period manifests need
                        // an explicit period-to-window mapping before automatic changes are safe.
                        if (currentSession.player.currentTimeline.periodCount > 1) {
                            currentSession.trackSelectionFailure = MULTI_PERIOD_FAILURE
                            return@withTimeoutOrNull SubtitleFastAudioProbeTermination.ERROR
                        }
                        if (
                            !currentSession.audioOverrideResolved &&
                            currentSession.player.playbackState == Player.STATE_READY
                        ) {
                            resolveSelectedAudioTrack(
                                currentSession,
                                currentSession.player.currentTracks
                            )
                        }
                        if (currentSession.trackSelectionFailure != null) {
                            return@withTimeoutOrNull SubtitleFastAudioProbeTermination.ERROR
                        }

                        val snapshot = collector.snapshot()
                        val nowMs = SystemClock.elapsedRealtime()
                        if (firstPcmAtMs == null && snapshot.observedDurationMs() > 0L) {
                            firstPcmAtMs = nowMs
                        }
                        if (snapshot.failureReason != null) {
                            return@withTimeoutOrNull SubtitleFastAudioProbeTermination.ERROR
                        }

                        val observedMs = seedObservedMs + snapshot.observedDurationMs()
                        if (request.onCheckpoint != null &&
                            (observedMs >= checkpointMs || (!useLegacySink && currentSession.readGate.atCheckpoint()))
                        ) {
                            val analysisStartedMs = SystemClock.elapsedRealtime()
                            val stop = request.onCheckpoint.invoke(
                                mergeSubtitleFastAudioProbeSnapshots(seedSnapshot, snapshot)
                            )
                            analysisDurationMs += SystemClock.elapsedRealtime() - analysisStartedMs
                            if (stop || checkpointMs >= totalTargetMs) {
                                return@withTimeoutOrNull SubtitleFastAudioProbeTermination.TARGET_REACHED
                            }
                            checkpointMs = minOf(totalTargetMs, checkpointMs + 15_000L)
                            currentSession.readGate.extend(checkpointMs - seedObservedMs)
                        }

                        // ExoPlayer timestamps can start beyond the requested seek position
                        // (notably with large MKV cue tables). An absolute end timestamp therefore
                        // cannot prove that a full window was decoded. Count the union of PCM that
                        // the collector actually observed instead.
                        if (
                            hasReachedSubtitleFastAudioTarget(
                                observedSpans = snapshot.observedSpans,
                                targetDurationMs = remainingTargetMs
                            )
                        ) {
                            return@withTimeoutOrNull SubtitleFastAudioProbeTermination.TARGET_REACHED
                        }
                        if (currentSession.playbackEnded) {
                            return@withTimeoutOrNull SubtitleFastAudioProbeTermination.EOF
                        }
                        val timeoutNowMs = SystemClock.elapsedRealtime()
                        if (progressWatchdog.isStalled(
                                snapshot.observedDurationMs(),
                                timeoutNowMs - playbackStartedAtMs - analysisDurationMs
                            )
                        ) {
                            Log.w(TAG, "PCM stalled: mode=${if (useLegacySink) "compatibility" else "analysis"} " +
                                "decoded=${snapshot.observedDurationMs()}ms " + probeState(currentSession))
                            return@withTimeoutOrNull SubtitleFastAudioProbeTermination.STALLED
                        }
                        val pcmStartedAtMs = firstPcmAtMs
                        if (pcmStartedAtMs == null) {
                            if (timeoutNowMs - playbackStartedAtMs >= startupTimeoutMs) {
                                return@withTimeoutOrNull SubtitleFastAudioProbeTermination.WALL_TIMEOUT
                            }
                        } else if (timeoutNowMs - pcmStartedAtMs - analysisDurationMs >= activeTimeoutMs) {
                            return@withTimeoutOrNull SubtitleFastAudioProbeTermination.WALL_TIMEOUT
                        }
                        delay(POLL_INTERVAL_MS)
                    }
                    @Suppress("UNREACHABLE_CODE")
                    SubtitleFastAudioProbeTermination.ERROR
                } ?: SubtitleFastAudioProbeTermination.WALL_TIMEOUT

                val collectedSnapshot = collector.snapshot()
                val snapshot = mergeSubtitleFastAudioProbeSnapshots(seedSnapshot, collectedSnapshot)
                val finishedAtMs = SystemClock.elapsedRealtime()
                val startupDurationMs = firstPcmAtMs
                    ?.minus(playbackStartedAtMs)
                    ?.coerceAtLeast(0L)
                    ?: (finishedAtMs - playbackStartedAtMs).coerceAtLeast(0L)
                val activeDecodeDurationMs = firstPcmAtMs
                    ?.let { (finishedAtMs - it - analysisDurationMs).coerceAtLeast(0L) }
                    ?: 0L
                val failureReason = when {
                    currentSession.playerError != null -> currentSession.playerError?.message
                    currentSession.trackSelectionFailure != null ->
                        currentSession.trackSelectionFailure
                    snapshot.failureReason != null -> snapshot.failureReason
                    termination == SubtitleFastAudioProbeTermination.STALLED ->
                        "Audio probe stopped producing new PCM for 8000 ms"
                    termination == SubtitleFastAudioProbeTermination.WALL_TIMEOUT ->
                        if (firstPcmAtMs == null) {
                            "Audio probe timed out before receiving PCM after $startupTimeoutMs ms"
                        } else {
                            "Audio probe timed out after $activeTimeoutMs ms of active decoding"
                        }
                    termination == SubtitleFastAudioProbeTermination.ERROR ->
                        "PCM speech analysis failed"
                    else -> null
                }
                Log.i(
                    TAG,
                    "Exo audio probe finished: termination=$termination mode=${if (useLegacySink) "compatibility" else "analysis"} " +
                        "compatibilitySpeed=${playbackSpeed}x analysis=${analysisDurationMs}ms " +
                        "wall=${finishedAtMs - startedAtMs}ms " +
                        "firstPcm=${firstPcmAtMs?.minus(playbackStartedAtMs) ?: -1L}ms " +
                        "decoded=${snapshot.observedDurationMs()}ms " +
                        "reused=${seedObservedMs}ms target=${totalTargetMs}ms " + probeState(currentSession)
                )
                snapshot.toProbeResult(
                    termination = termination,
                    failureReason = failureReason,
                    wallClockMs = finishedAtMs - startedAtMs,
                    startupDurationMs = startupDurationMs,
                    activeDecodeDurationMs = activeDecodeDurationMs
                )
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                Log.w(TAG, "Exo audio probe unavailable: ${error.message}", error)
                mergeSubtitleFastAudioProbeSnapshots(
                    seedSnapshot, activeSession?.collector?.snapshot().orEmptyProbeSnapshot()
                ).toProbeResult(
                    termination = SubtitleFastAudioProbeTermination.ERROR,
                    failureReason = error.message ?: error.javaClass.simpleName,
                    wallClockMs = SystemClock.elapsedRealtime() - startedAtMs
                )
            } finally {
                activeSession?.let { current ->
                    current.readGate.close()
                    current.collector.stopCollecting(clearExisting = false)
                    current.activeRequest = null
                    runCatching { current.player.pause() }
                    if (current.playerError != null) releaseSession()
                }
            }
        }

    /** Releases the one on-demand player after discovery and validation have both completed. */
    suspend fun release() = withContext(NonCancellable + Dispatchers.Main.immediate) {
        releaseSession()
    }

    private fun probeState(current: ProbeSession): String = with(current.player) {
        "state=$playbackState loading=$isLoading playing=$isPlaying " +
            "position=${currentPosition}ms buffered=${totalBufferedDuration}ms " +
            "gate=[${current.readGate.describe()}]"
    }

    private fun ensureSession(
        request: SubtitleFastAudioProbeRequest,
        playbackSpeed: Float
    ): ProbeSession {
        val key = buildString {
            append(request.streamUrl)
            append('|').append(request.headers.toSortedMap().hashCode())
            append('|').append(request.selectedAudioTrack?.trackId)
            append('|').append(request.selectedAudioTrack?.index)
        }
        session?.takeIf { it.key == key }?.let { return it }
        releaseSession()

        val normalizedRequest = PlayerMediaSourceFactory.normalizePlaybackRequest(
            request.streamUrl,
            request.headers
        )
        val dataSourceFactory = PlayerPlaybackNetworking.createDataSourceFactory(
            appContext,
            normalizedRequest.headers
        )
        val extractorsFactory = DolbyVisionExtractorsFactory(
            delegate = DefaultExtractorsFactory(),
            config = DolbyVisionConversionConfig(active = false)
        )
        val mediaSourceFactory = DefaultMediaSourceFactory(dataSourceFactory, extractorsFactory)
        val trackSelector = DefaultTrackSelector(appContext).apply {
            val parametersBuilder = buildUponParameters()
                .setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, true)
                .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
                .setTrackTypeDisabled(C.TRACK_TYPE_IMAGE, true)
                .setTrackTypeDisabled(C.TRACK_TYPE_METADATA, true)
            request.selectedAudioTrack?.language
                ?.takeIf { it.isNotBlank() && !it.equals("und", ignoreCase = true) }
                ?.let(parametersBuilder::setPreferredAudioLanguage)
            setParameters(parametersBuilder)
        }
        val collector = SubtitleSpeechProfileCollector(timelineAnchorMs = null)
        val readGate = SubtitleAnalysisReadGate()
        val renderersFactory = SubtitleProbeRenderersFactory(
            context = appContext,
            collector = collector,
            playbackSpeed = playbackSpeed,
            readGate = readGate,
            useLegacySink = useLegacySink
        )
            .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON)
            .setEnableDecoderFallback(true)
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                MIN_BUFFER_MS,
                MAX_BUFFER_MS,
                BUFFER_FOR_PLAYBACK_MS,
                BUFFER_AFTER_REBUFFER_MS
            )
            .setTargetBufferBytes(TARGET_BUFFER_BYTES)
            .setPrioritizeTimeOverSizeThresholds(true)
            .setBackBuffer(0, false)
            .build()
        val player = ExoPlayer.Builder(appContext, renderersFactory)
            .setLooper(Looper.getMainLooper())
            .setTrackSelector(trackSelector)
            .setMediaSourceFactory(mediaSourceFactory)
            .setLoadControl(loadControl)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_SPEECH)
                    .build(),
                /* handleAudioFocus = */ false
            )
            .setHandleAudioBecomingNoisy(false)
            .setReleaseTimeoutMs(RELEASE_TIMEOUT_MS)
            .build()
        val created = ProbeSession(key = key, player = player, collector = collector, readGate = readGate)
        player.addListener(object : Player.Listener {
            override fun onTracksChanged(tracks: Tracks) {
                resolveSelectedAudioTrack(created, tracks)
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                created.playbackEnded = playbackState == Player.STATE_ENDED
            }

            override fun onPlayerError(error: PlaybackException) {
                created.playerError = error
            }
        })
        val mediaItemBuilder = MediaItem.Builder().setUri(normalizedRequest.url)
        PlayerMediaSourceFactory.inferMimeType(
            url = normalizedRequest.url,
            filename = null
        )?.let(mediaItemBuilder::setMimeType)
        player.volume = 0f
        player.setMediaItem(mediaItemBuilder.build())
        session = created
        return created
    }

    private fun resolveSelectedAudioTrack(current: ProbeSession, tracks: Tracks) {
        val request = current.activeRequest ?: return
        val selected = request.selectedAudioTrack
        if (selected == null || current.audioOverrideResolved) return
        val target = findBestAudioTrack(tracks, selected)
        if (target == null) {
            if (
                current.player.playbackState == Player.STATE_READY &&
                tracks.groups.any { it.type == C.TRACK_TYPE_AUDIO }
            ) {
                current.trackSelectionFailure =
                    "Could not confidently match the selected audio track"
            }
            return
        }
        current.trackSelectionFailure = null
        if (!target.group.isTrackSelected(target.trackIndex)) {
            current.collector.resetForAudioTrackChange()
            current.player.trackSelectionParameters =
                current.player.trackSelectionParameters
                    .buildUpon()
                    .setOverrideForType(
                        TrackSelectionOverride(target.group.mediaTrackGroup, target.trackIndex)
                    )
                    .build()
            current.player.seekTo(current.requestedStartMs)
            return
        }

        current.audioOverrideResolved = true
        // probe() already sought before resolving the track. If an override was required, the
        // branch above also sought after applying it. A third seek here only repeats startup work.
        current.collector.startCollecting(clearExisting = true)
        current.readGate.enable()
        Log.i(
            TAG,
            "Matched audio track ordinal=${target.audioOrdinal} id=${target.format.id} " +
                "language=${target.format.language} channels=${target.format.channelCount} " +
                "score=${audioTrackMatchScore(target, selected)}"
        )
    }

    private fun releaseSession() {
        val current = session ?: return
        session = null
        current.readGate.close()
        current.collector.stopCollecting(clearExisting = true)
        runCatching { current.player.stop() }
        runCatching { current.player.release() }
    }

    private fun resolveWindowStartMs(request: SubtitleFastAudioProbeRequest): Long {
        val preferred = (request.preferredStartMs - PRE_ROLL_MS).coerceAtLeast(0L)
        if (request.mediaDurationMs <= 0L) return preferred
        val windowDurationMs = request.windowDurationMs.coerceAtLeast(1L)
        return preferred.coerceAtMost(
            (request.mediaDurationMs - windowDurationMs).coerceAtLeast(0L)
        )
    }

    private fun errorResult(reason: String) = SubtitleFastAudioProbeResult(
        snapshot = null,
        decodedStartMs = null,
        decodedEndMs = null,
        failureReason = reason,
        termination = SubtitleFastAudioProbeTermination.ERROR
    )
}

private fun SubtitleSpeechSnapshot?.orEmptyProbeSnapshot(): SubtitleSpeechSnapshot = this
    ?: SubtitleSpeechSnapshot(
        speechSpans = emptyList(),
        observedSpans = emptyList(),
        pcmAvailable = false
    )

/** PCM-only sink factory for the reusable probe player. */
private class SubtitleProbeRenderersFactory(
    context: Context,
    private val collector: SubtitleSpeechProfileCollector,
    private val playbackSpeed: Float,
    private val readGate: SubtitleAnalysisReadGate,
    private val useLegacySink: Boolean
) : DefaultRenderersFactory(context) {
    override fun buildAudioSink(
        context: Context,
        enableFloatOutput: Boolean,
        enableAudioTrackPlaybackParams: Boolean
    ): AudioSink {
        if (!useLegacySink) return SubtitleAnalysisAudioSink(collector, readGate)
        // DEFAULT_AUDIO_CAPABILITIES deliberately excludes encoded passthrough. This guarantees
        // that the forwarding sink sees PCM even when the TV advertises AC3/DTS/TrueHD support.
        val pcmSink = DefaultAudioSink.Builder()
            .setAudioCapabilities(AudioCapabilities.DEFAULT_AUDIO_CAPABILITIES)
            .setEnableFloatOutput(false)
            .setEnableAudioTrackPlaybackParams(false)
            .build()
        return PlaybackSpeedAwareAudioSink(
            sink = pcmSink,
            initialForcePcm = true,
            forcePcmForBluetooth = false,
            pcmConsumer = collector
        ).apply {
            setInitialPlaybackSpeed(playbackSpeed)
        }
    }
}

private data class ProbeAudioTrack(
    val group: Tracks.Group,
    val trackIndex: Int,
    val audioOrdinal: Int,
    val format: Format
)

private fun findBestAudioTrack(tracks: Tracks, selected: TrackInfo): ProbeAudioTrack? {
    val candidates = buildList {
        var audioOrdinal = 0
        tracks.groups.forEach { group ->
            if (group.type != C.TRACK_TYPE_AUDIO) return@forEach
            for (trackIndex in 0 until group.length) {
                add(
                    ProbeAudioTrack(
                        group = group,
                        trackIndex = trackIndex,
                        audioOrdinal = audioOrdinal++,
                        format = group.getTrackFormat(trackIndex)
                    )
                )
            }
        }
    }
    if (candidates.size == 1) return candidates.first()
    val ranked = candidates
        .map { candidate -> candidate to audioTrackMatchScore(candidate, selected) }
        .sortedByDescending { it.second }
    val best = ranked.firstOrNull() ?: return null
    val runnerUpScore = ranked.getOrNull(1)?.second ?: Int.MIN_VALUE
    val hasStrongIdentity = best.second >= 90
    val hasUsefulIdentity = best.second >= 40 && best.second - runnerUpScore >= 10
    return best.first.takeIf { hasStrongIdentity || hasUsefulIdentity }
}

private fun audioTrackMatchScore(candidate: ProbeAudioTrack, selected: TrackInfo): Int {
    val format = candidate.format
    var score = 0
    if (!selected.trackId.isNullOrBlank() && format.id == selected.trackId) score += 100
    if (candidate.audioOrdinal == selected.index) score += 50
    if (!selected.language.isNullOrBlank() &&
        PlayerSubtitleUtils.matchesLanguageCode(format.language, selected.language)
    ) {
        score += 40
    }
    if (selected.channelCount != null && format.channelCount == selected.channelCount) score += 12
    if (selected.sampleRate != null && format.sampleRate == selected.sampleRate) score += 6
    val codecHint = selected.codec?.lowercase().orEmpty()
    if (codecHint.isNotBlank()) {
        val formatHints = listOfNotNull(format.sampleMimeType, format.codecs)
        if (formatHints.any { hint ->
                val normalizedHint = hint.substringAfter('/').replace('-', ' ')
                codecHint.contains(normalizedHint, ignoreCase = true) ||
                    normalizedHint.contains(codecHint, ignoreCase = true)
            }
        ) {
            score += 4
        }
    }
    return score
}

private fun SubtitleSpeechSnapshot.toProbeResult(
    termination: SubtitleFastAudioProbeTermination,
    failureReason: String?,
    wallClockMs: Long = 0L,
    startupDurationMs: Long = 0L,
    activeDecodeDurationMs: Long = 0L
): SubtitleFastAudioProbeResult = SubtitleFastAudioProbeResult(
    snapshot = takeIf { it.pcmAvailable },
    decodedStartMs = observedSpans.minOfOrNull { it.startMs },
    decodedEndMs = observedSpans.maxOfOrNull { it.endMs },
    failureReason = failureReason,
    termination = termination,
    wallClockMs = wallClockMs,
    startupDurationMs = startupDurationMs,
    activeDecodeDurationMs = activeDecodeDurationMs
)

internal fun mergeSubtitleFastAudioProbeSnapshots(
    seed: SubtitleSpeechSnapshot?,
    collected: SubtitleSpeechSnapshot
): SubtitleSpeechSnapshot {
    if (seed == null || !seed.pcmAvailable) return collected
    return SubtitleSpeechSnapshot(
        speechSpans = SubtitleAutoSyncEngine.mergeSpans(
            seed.speechSpans + collected.speechSpans,
            allowedGapMs = 300L
        ),
        observedSpans = SubtitleAutoSyncEngine.mergeSpans(
            seed.observedSpans + collected.observedSpans,
            allowedGapMs = 120L
        ),
        pcmAvailable = seed.pcmAvailable || collected.pcmAvailable,
        failureReason = collected.failureReason ?: seed.failureReason
    )
}

private fun SubtitleSpeechSnapshot.observedDurationMs(): Long =
    mergedObservedDurationMs(observedSpans)

internal fun hasReachedSubtitleFastAudioTarget(
    observedSpans: List<SubtitleSyncSpan>,
    targetDurationMs: Long = 60_000L
): Boolean = mergedObservedDurationMs(observedSpans) >= targetDurationMs.coerceAtLeast(1L)

private fun mergedObservedDurationMs(observedSpans: List<SubtitleSyncSpan>): Long =
    SubtitleAutoSyncEngine.mergeSpans(observedSpans, allowedGapMs = 120L)
        .sumOf { span -> (span.endMs - span.startMs).coerceAtLeast(0L) }
