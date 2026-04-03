package com.nuvio.tv.ui.screens.player

import android.app.Activity
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.lang.ref.WeakReference

internal fun PlayerRuntimeController.attachHostActivity(activity: Activity?) {
    hostActivityRef = activity?.let { WeakReference(it) }
}

internal fun PlayerRuntimeController.startInitialPlaybackIfNeeded() {
    if (initialPlaybackStarted) return

    initialPlaybackStarted = true

    val infoHash = navigationArgs.infoHash
    if (infoHash != null) {
        torrentStreamJob = scope.launch {
            try {
                observeTorrentState()
                val localUrl = startTorrentStream(infoHash, navigationArgs.fileIdx)
                currentStreamUrl = localUrl
                currentHeaders = emptyMap()
                preparePlaybackBeforeStart(
                    url = localUrl,
                    headers = emptyMap(),
                    loadSavedProgress = false
                )
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        error = "Failed to start torrent: ${e.message}",
                        showLoadingOverlay = false
                    )
                }
            }
        }
        return
    }

    preparePlaybackBeforeStart(
        url = currentStreamUrl,
        headers = currentHeaders,
        loadSavedProgress = false
    )
}

internal fun PlayerRuntimeController.currentHostActivity(): Activity? {
    return hostActivityRef?.get()
}
