package com.nuvio.tv.core.torrent

import android.content.Context
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.libtorrent4j.Priority
import org.libtorrent4j.SessionManager
import org.libtorrent4j.SessionParams
import org.libtorrent4j.SettingsPack
import org.libtorrent4j.Sha1Hash
import org.libtorrent4j.TorrentFlags
import org.libtorrent4j.TorrentHandle
import org.libtorrent4j.TorrentInfo
import org.libtorrent4j.swig.torrent_flags_t
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class TorrentEngine @Inject constructor(
    @ApplicationContext private val context: Context,
    private val torrentSettings: TorrentSettings
) {
    companion object {
        private const val TAG = "TorrentEngine"
        private const val BUFFER_SECONDS = 30L
        private const val FALLBACK_BITRATE_BYTES_PER_SEC = 500_000L // ~4 Mbps fallback estimate
        private const val STREAMING_WINDOW_SIZE = 50
        private const val LOOKAHEAD_WINDOW_SIZE = 100
        private val DEFAULT_TRACKERS = listOf(
            "udp://tracker.opentrackr.org:1337/announce",
            "udp://open.stealth.si:80/announce",
            "udp://tracker.openbittorrent.com:6969/announce",
            "udp://exodus.desync.com:6969/announce",
            "udp://tracker.torrent.eu.org:451/announce",
            "udp://open.demonii.com:1337/announce",
            "udp://tracker.moeking.me:6969/announce",
            "udp://explodie.org:6969/announce",
            "udp://tracker.tiny-vps.com:6969/announce"
        )
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _state = MutableStateFlow<TorrentState>(TorrentState.Idle)
    val state: StateFlow<TorrentState> = _state.asStateFlow()

    private var sessionManager: SessionManager? = null
    private var currentHandle: TorrentHandle? = null
    private var streamServer: TorrentStreamServer? = null
    private var statsJob: Job? = null
    private var currentFileIndex: Int = -1
    private var totalPieces: Int = 0
    private var currentSettings: TorrentSettingsData = TorrentSettingsData()
    private var estimatedBytesPerSec: Long = FALLBACK_BITRATE_BYTES_PER_SEC

    private val cacheDir: File
        get() = File(context.cacheDir, "torrent_cache").also { it.mkdirs() }

    /**
     * Start streaming a torrent. Returns the local HTTP URL for ExoPlayer.
     */
    /**
     * Start streaming a torrent. Returns the local HTTP URL for ExoPlayer.
     *
     * @param resumePositionMs  Saved playback position (ms) to start downloading from.
     * @param durationMs        Known content duration (ms) for bitrate estimation. 0 = unknown.
     */
    suspend fun startStream(
        infoHash: String,
        fileIdx: Int?,
        trackers: List<String> = emptyList(),
        resumePositionMs: Long = 0,
        durationMs: Long = 0
    ): String = withContext(Dispatchers.IO) {
        stopCurrentStream()
        _state.value = TorrentState.Connecting

        currentSettings = torrentSettings.settings.first()
        evictCacheIfNeeded()

        val session = getOrCreateSession()
        val magnetUri = buildMagnetUri(infoHash, trackers)

        Log.d(TAG, "Starting torrent stream: $magnetUri")

        val handle = addTorrentAndAwaitMetadata(session, magnetUri)
            ?: throw TorrentException("Failed to fetch torrent metadata (timed out)")

        currentHandle = handle
        val torrentInfo = handle.torrentFile() ?: throw TorrentException("No torrent info available")

        currentFileIndex = resolveFileIndex(torrentInfo, fileIdx)
        val selectedFile = torrentInfo.files().filePath(currentFileIndex)
        val selectedFileSize = torrentInfo.files().fileSize(currentFileIndex)
        totalPieces = torrentInfo.numPieces()

        // Estimate bytes-per-second from known duration or fall back to a conservative guess
        estimatedBytesPerSec = if (durationMs > 0) {
            (selectedFileSize / (durationMs / 1000L)).coerceAtLeast(100_000)
        } else {
            FALLBACK_BITRATE_BYTES_PER_SEC
        }

        // Calculate the byte offset to start downloading from the resume position
        val startByteOffset = if (resumePositionMs > 0 && durationMs > 0) {
            ((resumePositionMs.toDouble() / durationMs) * selectedFileSize).toLong()
                .coerceIn(0, selectedFileSize - 1)
        } else {
            0L
        }

        Log.d(TAG, "Selected file[$currentFileIndex]: $selectedFile ($selectedFileSize bytes), " +
                "$totalPieces pieces, startOffset=${startByteOffset}, " +
                "estimatedRate=${estimatedBytesPerSec}B/s")

        configureFilePriorities(handle, torrentInfo, currentFileIndex)
        handle.setFlags(TorrentFlags.SEQUENTIAL_DOWNLOAD)
        prioritizePiecesForPosition(handle, torrentInfo, currentFileIndex, startByteOffset)

        _state.value = TorrentState.Buffering(
            progress = 0f,
            downloadSpeed = 0,
            peers = 0,
            seeds = 0
        )

        // Buffer 30 seconds worth of data from the start position
        val bufferBytes = estimatedBytesPerSec * BUFFER_SECONDS
        awaitBufferReady(handle, torrentInfo, currentFileIndex, startByteOffset, bufferBytes)

        val server = startStreamServer(handle, torrentInfo, currentFileIndex)
        streamServer = server

        server.servingFile = File(cacheDir, selectedFile)
        server.fileLength = selectedFileSize
        server.pieceLength = torrentInfo.pieceLength().toLong()
        server.mimeType = inferMimeType(selectedFile)

        val localUrl = "http://127.0.0.1:${server.listeningPort}/stream"

        startStatsPolling(handle)

        _state.value = TorrentState.Streaming(
            localUrl = localUrl,
            downloadSpeed = 0,
            uploadSpeed = 0,
            peers = 0,
            seeds = 0,
            bufferProgress = 0f,
            totalProgress = 0f
        )

        Log.d(TAG, "Torrent stream ready at $localUrl")
        localUrl
    }

    fun stopCurrentStream() {
        statsJob?.cancel()
        statsJob = null

        streamServer?.stop()
        streamServer = null

        // Null out the handle FIRST to signal all concurrent readers to stop,
        // then remove from session. This prevents native SIGSEGV from accessing
        // an invalidated C++ shared_ptr.
        val handle = currentHandle
        currentHandle = null
        currentFileIndex = -1
        totalPieces = 0

        handle?.let {
            try {
                sessionManager?.remove(it)
            } catch (e: Exception) {
                Log.w(TAG, "Error removing torrent handle", e)
            }
        }

        if (currentSettings.autoClearCacheOnExit) {
            clearCache()
        }

        _state.value = TorrentState.Idle
    }

    fun release() {
        stopCurrentStream()
        try {
            sessionManager?.stop()
        } catch (e: Exception) {
            Log.w(TAG, "Error stopping session manager", e)
        }
        sessionManager = null
    }

    fun onPlaybackSeek(positionMs: Long, durationMs: Long) {
        val handle = currentHandle ?: return
        if (durationMs <= 0) return

        try {
            val torrentInfo = handle.torrentFile() ?: return
            val fileSize = torrentInfo.files().fileSize(currentFileIndex)
            val bytePosition = ((positionMs.toDouble() / durationMs) * fileSize).toLong()

            // Update bitrate estimate from actual duration now that ExoPlayer knows it
            estimatedBytesPerSec = (fileSize / (durationMs / 1000L)).coerceAtLeast(100_000)

            prioritizePiecesForPosition(handle, torrentInfo, currentFileIndex, bytePosition)
        } catch (e: Exception) {
            Log.w(TAG, "Error during seek priority update", e)
        }
    }

    fun clearCache() {
        scope.launch(Dispatchers.IO) {
            try {
                cacheDir.deleteRecursively()
                cacheDir.mkdirs()
                Log.d(TAG, "Torrent cache cleared")
            } catch (e: Exception) {
                Log.w(TAG, "Failed to clear torrent cache", e)
            }
        }
    }

    // ── Private helpers ──────────────────────────────────────────────────────────

    private fun getOrCreateSession(): SessionManager {
        sessionManager?.let { return it }

        val settings = SettingsPack().apply {
            activeDownloads(1)
            activeSeeds(1)
            connectionsLimit(currentSettings.maxConnections)
            setEnableDht(currentSettings.enableDht)
            setEnableLsd(true)

            if (!currentSettings.enableUpload) {
                uploadRateLimit(1024) // 1 KB/s minimum
            }
        }

        val session = SessionManager(false)
        session.start(SessionParams(settings))

        if (currentSettings.enableDht) {
            session.startDht()
        }

        sessionManager = session
        Log.d(TAG, "Session manager started")
        return session
    }

    private fun buildMagnetUri(infoHash: String, extraTrackers: List<String>): String {
        val trackers = (DEFAULT_TRACKERS + extraTrackers).distinct()
        val trackerParams = trackers.joinToString("") { "&tr=$it" }
        return "magnet:?xt=urn:btih:$infoHash$trackerParams"
    }

    private suspend fun addTorrentAndAwaitMetadata(
        session: SessionManager,
        magnetUri: String
    ): TorrentHandle? {
        val hexHash = magnetUri.substringAfter("btih:").substringBefore("&")
        val hash = Sha1Hash.parseHex(hexHash)

        // Start the download
        try {
            session.download(magnetUri, cacheDir, torrent_flags_t())
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start download", e)
            return null
        }

        // Immediate check — metadata may already be cached from a previous session
        session.find(hash)?.let { handle ->
            if (handle.torrentFile() != null) {
                Log.d(TAG, "Metadata available immediately (cached)")
                return handle
            }
        }

        // Poll for metadata with a timeout. This is more reliable than relying
        // solely on alerts, which can be missed if they fire between listener
        // registration and the download() call, or if AddTorrentAlert fires
        // with metadata already resolved (no separate MetadataReceivedAlert).
        val timeoutMs = 60_000L
        val startTime = System.currentTimeMillis()

        while (System.currentTimeMillis() - startTime < timeoutMs) {
            if (currentHandle == null && _state.value is TorrentState.Idle) {
                // stopCurrentStream() was called — abort
                return null
            }

            try {
                val handle = session.find(hash)
                if (handle != null && handle.torrentFile() != null) {
                    Log.d(TAG, "Metadata resolved after ${System.currentTimeMillis() - startTime}ms")
                    return handle
                }
            } catch (e: Exception) {
                Log.w(TAG, "Error checking for metadata", e)
            }

            delay(300)
        }

        Log.e(TAG, "Timed out waiting for torrent metadata")
        return null
    }

    private fun resolveFileIndex(torrentInfo: TorrentInfo, requestedIdx: Int?): Int {
        val numFiles = torrentInfo.files().numFiles()

        if (requestedIdx != null && requestedIdx in 0 until numFiles) {
            return requestedIdx
        }

        // Auto-select: pick the largest file (usually the video)
        var largestIdx = 0
        var largestSize = 0L
        for (i in 0 until numFiles) {
            val size = torrentInfo.files().fileSize(i)
            if (size > largestSize) {
                largestSize = size
                largestIdx = i
            }
        }
        return largestIdx
    }

    private fun configureFilePriorities(
        handle: TorrentHandle,
        torrentInfo: TorrentInfo,
        selectedFileIndex: Int
    ) {
        val numFiles = torrentInfo.files().numFiles()
        val priorities = Array(numFiles) { i ->
            if (i == selectedFileIndex) Priority.DEFAULT else Priority.IGNORE
        }
        handle.prioritizeFiles(priorities)
    }

    private fun prioritizePiecesForPosition(
        handle: TorrentHandle,
        torrentInfo: TorrentInfo,
        fileIndex: Int,
        bytePosition: Long
    ) {
        if (currentHandle == null) return
        try {
            val pieceLength = torrentInfo.pieceLength().toLong()
            val fileOffset = torrentInfo.files().fileOffset(fileIndex)
            val fileSize = torrentInfo.files().fileSize(fileIndex)
            val numPieces = torrentInfo.numPieces()

            val firstPiece = (fileOffset / pieceLength).toInt()
            val lastPiece = ((fileOffset + fileSize - 1) / pieceLength).toInt().coerceAtMost(numPieces - 1)

            val currentPiece = ((fileOffset + bytePosition) / pieceLength).toInt().coerceIn(firstPiece, lastPiece)

            // 30 seconds ahead = high priority, 60 seconds ahead = normal priority
            val bufferBytes30s = estimatedBytesPerSec * BUFFER_SECONDS
            val bufferBytes60s = estimatedBytesPerSec * BUFFER_SECONDS * 2
            val streamWindowEnd = ((fileOffset + bytePosition + bufferBytes30s) / pieceLength).toInt().coerceAtMost(lastPiece)
            val lookaheadEnd = ((fileOffset + bytePosition + bufferBytes60s) / pieceLength).toInt().coerceAtMost(lastPiece)

            for (i in firstPiece..lastPiece) {
                val priority = when {
                    handle.havePiece(i) -> Priority.IGNORE
                    // First few pieces (container header)
                    i in firstPiece..(firstPiece + 4).coerceAtMost(lastPiece) -> Priority.TOP_PRIORITY
                    // Last piece (container trailer / moov atom)
                    i == lastPiece -> Priority.TOP_PRIORITY
                    // 30s streaming window ahead of playback
                    i in currentPiece..streamWindowEnd -> Priority.TOP_PRIORITY
                    // 30-60s lookahead
                    i in (streamWindowEnd + 1)..lookaheadEnd -> Priority.DEFAULT
                    else -> Priority.LOW
                }
                handle.piecePriority(i, priority)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error prioritizing pieces for position", e)
        }
    }

    /**
     * Waits until at least [bufferBytes] worth of pieces are downloaded starting
     * from [startByteOffset], plus the last piece (container metadata).
     */
    private suspend fun awaitBufferReady(
        handle: TorrentHandle,
        torrentInfo: TorrentInfo,
        fileIndex: Int,
        startByteOffset: Long,
        bufferBytes: Long
    ) {
        val pieceLength = torrentInfo.pieceLength().toLong()
        val fileOffset = torrentInfo.files().fileOffset(fileIndex)
        val fileSize = torrentInfo.files().fileSize(fileIndex)
        val firstPiece = (fileOffset / pieceLength).toInt()
        val lastPiece = ((fileOffset + fileSize - 1) / pieceLength).toInt()
            .coerceAtMost(torrentInfo.numPieces() - 1)

        // Pieces that cover the 30s buffer from the start position
        val bufferStartPiece = ((fileOffset + startByteOffset) / pieceLength).toInt()
            .coerceIn(firstPiece, lastPiece)
        val bufferEndPiece = ((fileOffset + startByteOffset + bufferBytes) / pieceLength).toInt()
            .coerceIn(bufferStartPiece, lastPiece)
        val requiredPieces = bufferEndPiece - bufferStartPiece + 1

        Log.d(TAG, "Awaiting buffer: pieces $bufferStartPiece-$bufferEndPiece " +
                "($requiredPieces pieces, ${bufferBytes / 1024}KB) + last piece $lastPiece")

        while (true) {
            if (currentHandle == null) throw TorrentException("Torrent stopped during buffering")

            try {
                var readyCount = 0
                for (i in bufferStartPiece..bufferEndPiece) {
                    if (handle.havePiece(i)) readyCount++
                }

                // Also require first few pieces (container header) and last piece (moov atom)
                val hasHeaderPieces = (firstPiece..(firstPiece + 4).coerceAtMost(lastPiece)).all {
                    handle.havePiece(it)
                }
                val hasLastPiece = handle.havePiece(lastPiece)

                val progress = readyCount.toFloat() / requiredPieces
                val status = handle.status()

                _state.value = TorrentState.Buffering(
                    progress = progress,
                    downloadSpeed = status.downloadPayloadRate().toLong(),
                    peers = status.numPeers(),
                    seeds = status.numSeeds()
                )

                if (readyCount >= requiredPieces && hasLastPiece && hasHeaderPieces) {
                    break
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Error checking buffer state, retrying", e)
            }

            delay(500)
        }
    }

    private fun startStreamServer(
        handle: TorrentHandle,
        torrentInfo: TorrentInfo,
        fileIndex: Int
    ): TorrentStreamServer {
        val pieceLength = torrentInfo.pieceLength().toLong()
        val fileOffset = torrentInfo.files().fileOffset(fileIndex)
        val firstFilePiece = (fileOffset / pieceLength).toInt()

        return TorrentStreamServer.startOnAvailablePort(
            onPiecesNeeded = { startPiece, endPiece ->
                prioritizePiecesForRange(handle, torrentInfo, fileIndex, startPiece, endPiece)
            },
            arePiecesReady = { startPiece, endPiece ->
                // Check that all pieces in the requested range are downloaded
                val h = currentHandle ?: return@startOnAvailablePort false
                try {
                    val adjStart = firstFilePiece + startPiece
                    val adjEnd = (firstFilePiece + endPiece).coerceAtMost(torrentInfo.numPieces() - 1)
                    (adjStart..adjEnd).all { h.havePiece(it) }
                } catch (e: Exception) {
                    false
                }
            }
        ) ?: throw TorrentException("Failed to start stream server - no available port")
    }

    private fun prioritizePiecesForRange(
        handle: TorrentHandle,
        torrentInfo: TorrentInfo,
        fileIndex: Int,
        startPiece: Int,
        endPiece: Int
    ) {
        if (currentHandle == null) return
        try {
            val pieceLength = torrentInfo.pieceLength().toLong()
            val fileOffset = torrentInfo.files().fileOffset(fileIndex)
            val firstFilePiece = (fileOffset / pieceLength).toInt()
            val adjustedStart = firstFilePiece + startPiece
            val adjustedEnd = firstFilePiece + endPiece

            for (i in adjustedStart..adjustedEnd.coerceAtMost(torrentInfo.numPieces() - 1)) {
                if (!handle.havePiece(i)) {
                    handle.piecePriority(i, Priority.TOP_PRIORITY)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error prioritizing pieces for range", e)
        }
    }

    private fun startStatsPolling(handle: TorrentHandle) {
        statsJob?.cancel()
        statsJob = scope.launch {
            while (isActive) {
                try {
                    val status = handle.status()
                    val currentState = _state.value
                    if (currentState is TorrentState.Streaming) {
                        _state.value = currentState.copy(
                            downloadSpeed = status.downloadPayloadRate().toLong(),
                            uploadSpeed = status.uploadPayloadRate().toLong(),
                            peers = status.numPeers(),
                            seeds = status.numSeeds(),
                            totalProgress = status.progress()
                        )
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "Stats polling error", e)
                }
                delay(1000)
            }
        }
    }

    private fun evictCacheIfNeeded() {
        val maxBytes = currentSettings.maxCacheSizeMb.toLong() * 1024 * 1024
        val dir = cacheDir
        if (!dir.exists()) return

        val files = dir.listFiles()?.toMutableList() ?: return
        files.sortBy { it.lastModified() }

        var totalSize = files.sumOf { it.length() }
        while (totalSize > maxBytes && files.isNotEmpty()) {
            val oldest = files.removeAt(0)
            totalSize -= oldest.length()
            oldest.deleteRecursively()
        }
    }

    private fun inferMimeType(filename: String): String {
        val lower = filename.lowercase()
        return when {
            lower.endsWith(".mkv") -> "video/x-matroska"
            lower.endsWith(".mp4") || lower.endsWith(".m4v") -> "video/mp4"
            lower.endsWith(".avi") -> "video/x-msvideo"
            lower.endsWith(".webm") -> "video/webm"
            lower.endsWith(".ts") -> "video/mp2t"
            lower.endsWith(".flv") -> "video/x-flv"
            lower.endsWith(".wmv") -> "video/x-ms-wmv"
            lower.endsWith(".mov") -> "video/quicktime"
            else -> "video/mp4"
        }
    }
}

class TorrentException(message: String) : Exception(message)
