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
            isTorrentStream = true
        )
    }

    return torrentEngine.startStream(infoHash, fileIdx)
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
                    _uiState.update {
                        it.copy(
                            showLoadingOverlay = true,
                            loadingMessage = "Connecting to peers..."
                        )
                    }
                }
                is TorrentState.Buffering -> {
                    val pct = (torrentState.progress * 100).toInt()
                    _uiState.update {
                        it.copy(
                            showLoadingOverlay = true,
                            loadingMessage = "Buffering: $pct% (${torrentState.peers} peers)",
                            torrentDownloadSpeed = torrentState.downloadSpeed,
                            torrentPeers = torrentState.peers,
                            torrentSeeds = torrentState.seeds
                        )
                    }
                }
                is TorrentState.Streaming -> {
                    _uiState.update {
                        it.copy(
                            torrentDownloadSpeed = torrentState.downloadSpeed,
                            torrentUploadSpeed = torrentState.uploadSpeed,
                            torrentPeers = torrentState.peers,
                            torrentSeeds = torrentState.seeds,
                            torrentBufferProgress = torrentState.bufferProgress,
                            torrentTotalProgress = torrentState.totalProgress
                        )
                    }
                }
                is TorrentState.Error -> {
                    Log.e(TAG, "Torrent error: ${torrentState.message}")
                    _uiState.update {
                        it.copy(
                            error = "Torrent error: ${torrentState.message}",
                            showLoadingOverlay = false
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
            val localUrl = startTorrentStream(infoHash, stream.fileIdx)

            currentStreamUrl = localUrl
            currentHeaders = emptyMap()
            currentStreamMimeType = null

            observeTorrentState()

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
                    showLoadingOverlay = false
                )
            }
        }
    }
}
