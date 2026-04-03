package com.nuvio.tv.ui.screens.player

import android.util.Log
import com.nuvio.tv.core.torrent.TorrentState
import com.nuvio.tv.domain.model.Stream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

private const val TAG = "PlayerTorrent"

/**
 * Starts a torrent stream: resolves the infoHash via TorrentEngine, waits for
 * buffering, and returns the local HTTP URL for ExoPlayer.
 */
internal suspend fun PlayerRuntimeController.startTorrentStream(
    infoHash: String,
    fileIdx: Int?
): String {
    isTorrentStream = true
    currentInfoHash = infoHash
    currentFileIdx = fileIdx

    _uiState.update {
        it.copy(
            showLoadingOverlay = true,
            loadingMessage = "Connecting to peers...",
            loadingProgress = null,
            isTorrentStream = true
        )
    }

    // Use saved watch progress to start downloading from the resume position
    val savedProgress = pendingResumeProgress
    val resumeMs = savedProgress?.position ?: 0L
    val durationMs = savedProgress?.duration ?: 0L

    return torrentEngine.startStream(
        infoHash = infoHash,
        fileIdx = fileIdx,
        resumePositionMs = resumeMs,
        durationMs = durationMs
    )
}

/**
 * Stops the current torrent stream and cleans up all related state.
 */
internal fun PlayerRuntimeController.stopTorrentStream() {
    torrentStreamJob?.cancel()
    torrentStreamJob = null
    torrentStateObserverJob?.cancel()
    torrentStateObserverJob = null

    if (isTorrentStream) {
        torrentEngine.stopCurrentStream()
    }

    isTorrentStream = false
    currentInfoHash = null
    currentFileIdx = null
}

/**
 * Starts collecting TorrentEngine state and maps it to PlayerUiState fields.
 */
internal fun PlayerRuntimeController.observeTorrentState() {
    torrentStateObserverJob?.cancel()
    torrentStateObserverJob = scope.launch {
        torrentEngine.state.collectLatest { torrentState ->
            when (torrentState) {
                is TorrentState.Idle -> {
                    // No-op
                }
                is TorrentState.Connecting -> {
                    // Only show the full loading overlay before first frame;
                    // after that the player surface stays visible.
                    if (!hasRenderedFirstFrame) {
                        _uiState.update {
                            it.copy(
                                showLoadingOverlay = true,
                                loadingMessage = "Connecting to peers...",
                                loadingProgress = null,
                                torrentBufferingMessage = null
                            )
                        }
                    }
                }
                is TorrentState.Buffering -> {
                    val speed = formatSpeed(torrentState.downloadSpeed)
                    val peerInfo = "${torrentState.seeds} seeds \u00B7 ${torrentState.peers} peers"
                    val message = "$peerInfo \u00B7 $speed"

                    if (!hasRenderedFirstFrame) {
                        // Initial load: full loading overlay with progress bar
                        _uiState.update {
                            it.copy(
                                showLoadingOverlay = true,
                                loadingMessage = message,
                                loadingProgress = torrentState.progress,
                                torrentDownloadSpeed = torrentState.downloadSpeed,
                                torrentPeers = torrentState.peers,
                                torrentSeeds = torrentState.seeds,
                                torrentBufferingMessage = null
                            )
                        }
                    } else {
                        // Mid-playback rebuffer (e.g. seek to unbuffered area):
                        // keep player visible, show stats on the buffering spinner
                        _uiState.update {
                            it.copy(
                                torrentDownloadSpeed = torrentState.downloadSpeed,
                                torrentPeers = torrentState.peers,
                                torrentSeeds = torrentState.seeds,
                                torrentBufferingMessage = message,
                                torrentBufferingProgress = torrentState.progress
                            )
                        }
                    }
                }
                is TorrentState.Streaming -> {
                    _uiState.update {
                        it.copy(
                            loadingProgress = null,
                            torrentDownloadSpeed = torrentState.downloadSpeed,
                            torrentUploadSpeed = torrentState.uploadSpeed,
                            torrentPeers = torrentState.peers,
                            torrentSeeds = torrentState.seeds,
                            torrentBufferProgress = torrentState.bufferProgress,
                            torrentTotalProgress = torrentState.totalProgress,
                            torrentBufferingMessage = null
                        )
                    }
                }
                is TorrentState.Error -> {
                    Log.e(TAG, "Torrent error: ${torrentState.message}")
                    _uiState.update {
                        it.copy(
                            error = "Torrent error: ${torrentState.message}",
                            showLoadingOverlay = false,
                            torrentBufferingMessage = null
                        )
                    }
                }
            }
        }
    }
}

/**
 * Called when the user seeks during torrent playback to re-prioritize pieces.
 */
internal fun PlayerRuntimeController.onTorrentSeek(positionMs: Long, durationMs: Long) {
    if (!isTorrentStream) return
    torrentEngine.onPlaybackSeek(positionMs, durationMs)
}

/**
 * Launches a torrent stream for source stream switching.
 * Handles the full flow: start torrent → wait for buffer → play.
 */
internal fun PlayerRuntimeController.launchTorrentSourceStream(
    stream: Stream,
    infoHash: String,
    loadSavedProgress: Boolean
) {
    torrentStreamJob?.cancel()
    torrentStreamJob = scope.launch {
        try {
            // Start observing BEFORE the blocking startTorrentStream() call
            // so buffering progress updates are visible in the UI immediately.
            observeTorrentState()

            val localUrl = startTorrentStream(infoHash, stream.fileIdx)

            currentStreamUrl = localUrl
            currentHeaders = emptyMap()
            currentStreamMimeType = null

            preparePlaybackBeforeStart(
                url = localUrl,
                headers = emptyMap(),
                loadSavedProgress = loadSavedProgress
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start torrent stream", e)
            _uiState.update {
                it.copy(
                    error = "Failed to start torrent: ${e.message}",
                    showLoadingOverlay = false,
                    loadingProgress = null
                )
            }
        }
    }
}

private fun formatSpeed(bytesPerSec: Long): String {
    return when {
        bytesPerSec >= 1_048_576 -> String.format("%.1f MB/s", bytesPerSec / 1_048_576.0)
        bytesPerSec >= 1_024 -> String.format("%.0f KB/s", bytesPerSec / 1_024.0)
        else -> "$bytesPerSec B/s"
    }
}
