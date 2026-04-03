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
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.libtorrent4j.AlertListener
import org.libtorrent4j.Priority
import org.libtorrent4j.SessionManager
import org.libtorrent4j.SessionParams
import org.libtorrent4j.SettingsPack
import org.libtorrent4j.Sha1Hash
import org.libtorrent4j.TorrentFlags
import org.libtorrent4j.TorrentHandle
import org.libtorrent4j.TorrentInfo
import org.libtorrent4j.alerts.AddTorrentAlert
import org.libtorrent4j.alerts.Alert
import org.libtorrent4j.alerts.AlertType
import org.libtorrent4j.alerts.MetadataReceivedAlert
import org.libtorrent4j.alerts.TorrentErrorAlert
import org.libtorrent4j.swig.torrent_flags_t
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume

@Singleton
class TorrentEngine @Inject constructor(
    @ApplicationContext private val context: Context,
    private val torrentSettings: TorrentSettings
) {
    companion object {
        private const val TAG = "TorrentEngine"
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

    private val cacheDir: File
        get() = File(context.cacheDir, "torrent_cache").also { it.mkdirs() }

    /**
     * Start streaming a torrent. Returns the local HTTP URL for ExoPlayer.
     */
    suspend fun startStream(
        infoHash: String,
        fileIdx: Int?,
        trackers: List<String> = emptyList()
    ): String = withContext(Dispatchers.IO) {
        stopCurrentStream()
        _state.value = TorrentState.Connecting

        currentSettings = torrentSettings.settings.first()
        evictCacheIfNeeded()

        val session = getOrCreateSession()
        val magnetUri = buildMagnetUri(infoHash, trackers)

        Log.d(TAG, "Starting torrent stream: $magnetUri")

        val handle = addTorrentAndAwaitMetadata(session, magnetUri)
            ?: throw TorrentException("Failed to fetch torrent metadata")

        currentHandle = handle
        val torrentInfo = handle.torrentFile() ?: throw TorrentException("No torrent info available")

        currentFileIndex = resolveFileIndex(torrentInfo, fileIdx)
        val selectedFile = torrentInfo.files().filePath(currentFileIndex)
        val selectedFileSize = torrentInfo.files().fileSize(currentFileIndex)
        totalPieces = torrentInfo.numPieces()

        Log.d(TAG, "Selected file[$currentFileIndex]: $selectedFile ($selectedFileSize bytes), $totalPieces pieces")

        configureFilePriorities(handle, torrentInfo, currentFileIndex)
        handle.setFlags(TorrentFlags.SEQUENTIAL_DOWNLOAD)
        prioritizePiecesForPosition(handle, torrentInfo, currentFileIndex, 0)

        _state.value = TorrentState.Buffering(
            progress = 0f,
            downloadSpeed = 0,
            peers = 0,
            seeds = 0
        )

        val bufferPieces = currentSettings.bufferPiecesBeforePlayback
        awaitBufferReady(handle, torrentInfo, currentFileIndex, bufferPieces)

        val server = startStreamServer(handle, torrentInfo, currentFileIndex)
        streamServer = server

        server.servingFile = File(cacheDir, selectedFile)
        server.fileLength = selectedFileSize
        server.pieceLength = torrentInfo.pieceLength().toLong()
        server.fileOffset = torrentInfo.files().fileOffset(currentFileIndex)
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

        currentHandle?.let { handle ->
            try {
                sessionManager?.remove(handle)
            } catch (e: Exception) {
                Log.w(TAG, "Error removing torrent handle", e)
            }
        }
        currentHandle = null
        currentFileIndex = -1
        totalPieces = 0

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
        val torrentInfo = handle.torrentFile() ?: return
        if (durationMs <= 0) return

        val progress = positionMs.toFloat() / durationMs
        val fileSize = torrentInfo.files().fileSize(currentFileIndex)
        val bytePosition = (progress * fileSize).toLong()

        prioritizePiecesForPosition(handle, torrentInfo, currentFileIndex, bytePosition)
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
    ): TorrentHandle? = suspendCancellableCoroutine { cont ->
        var resumed = false

        val listener = object : AlertListener {
            override fun types(): IntArray = intArrayOf(
                AlertType.ADD_TORRENT.swig(),
                AlertType.METADATA_RECEIVED.swig(),
                AlertType.TORRENT_ERROR.swig()
            )

            override fun alert(alert: Alert<*>) {
                when (alert) {
                    is AddTorrentAlert -> {
                        if (alert.error().isError) {
                            if (!resumed) {
                                resumed = true
                                session.removeListener(this)
                                cont.resume(null)
                            }
                        }
                    }
                    is MetadataReceivedAlert -> {
                        if (!resumed) {
                            resumed = true
                            session.removeListener(this)
                            cont.resume(alert.handle())
                        }
                    }
                    is TorrentErrorAlert -> {
                        if (!resumed) {
                            resumed = true
                            session.removeListener(this)
                            cont.resume(null)
                        }
                    }
                }
            }
        }

        session.addListener(listener)

        cont.invokeOnCancellation {
            session.removeListener(listener)
        }

        try {
            session.download(magnetUri, cacheDir, torrent_flags_t())
            // If torrent info is already cached, handle may already be available
            val hash = Sha1Hash.parseHex(
                magnetUri.substringAfter("btih:").substringBefore("&")
            )
            val handle = session.find(hash)
            if (handle != null && handle.torrentFile() != null && !resumed) {
                resumed = true
                session.removeListener(listener)
                cont.resume(handle)
            }
        } catch (e: Exception) {
            if (!resumed) {
                resumed = true
                session.removeListener(listener)
                cont.resume(null)
            }
        }
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
        val pieceLength = torrentInfo.pieceLength().toLong()
        val fileOffset = torrentInfo.files().fileOffset(fileIndex)
        val fileSize = torrentInfo.files().fileSize(fileIndex)
        val numPieces = torrentInfo.numPieces()

        val firstPiece = (fileOffset / pieceLength).toInt()
        val lastPiece = ((fileOffset + fileSize - 1) / pieceLength).toInt().coerceAtMost(numPieces - 1)

        val currentPiece = ((fileOffset + bytePosition) / pieceLength).toInt().coerceIn(firstPiece, lastPiece)

        for (i in firstPiece..lastPiece) {
            val priority = when {
                handle.havePiece(i) -> Priority.IGNORE
                // First few pieces (container header)
                i in firstPiece..(firstPiece + 4).coerceAtMost(lastPiece) -> Priority.TOP_PRIORITY
                // Last piece (container trailer/moov atom)
                i == lastPiece -> Priority.TOP_PRIORITY
                // Streaming window ahead of playback
                i in currentPiece..(currentPiece + STREAMING_WINDOW_SIZE).coerceAtMost(lastPiece) -> Priority.TOP_PRIORITY
                // Lookahead window
                i in (currentPiece + STREAMING_WINDOW_SIZE + 1)..(currentPiece + STREAMING_WINDOW_SIZE + LOOKAHEAD_WINDOW_SIZE).coerceAtMost(lastPiece) -> Priority.DEFAULT
                else -> Priority.LOW
            }
            handle.piecePriority(i, priority)
        }
    }

    private suspend fun awaitBufferReady(
        handle: TorrentHandle,
        torrentInfo: TorrentInfo,
        fileIndex: Int,
        requiredPieces: Int
    ) {
        val pieceLength = torrentInfo.pieceLength().toLong()
        val fileOffset = torrentInfo.files().fileOffset(fileIndex)
        val fileSize = torrentInfo.files().fileSize(fileIndex)
        val firstPiece = (fileOffset / pieceLength).toInt()
        val lastPiece = ((fileOffset + fileSize - 1) / pieceLength).toInt().coerceAtMost(torrentInfo.numPieces() - 1)
        val piecesToCheck = requiredPieces.coerceAtMost(lastPiece - firstPiece + 1)

        while (true) {
            var readyCount = 0
            for (i in firstPiece until (firstPiece + piecesToCheck).coerceAtMost(lastPiece + 1)) {
                if (handle.havePiece(i)) readyCount++
            }

            // Also require last piece for container metadata
            val hasLastPiece = handle.havePiece(lastPiece)

            val progress = readyCount.toFloat() / piecesToCheck
            val status = handle.status()

            _state.value = TorrentState.Buffering(
                progress = progress,
                downloadSpeed = status.downloadPayloadRate().toLong(),
                peers = status.numPeers(),
                seeds = status.numSeeds()
            )

            if (readyCount >= piecesToCheck && hasLastPiece) {
                break
            }

            delay(500)
        }
    }

    private fun startStreamServer(
        handle: TorrentHandle,
        torrentInfo: TorrentInfo,
        fileIndex: Int
    ): TorrentStreamServer {
        return TorrentStreamServer.startOnAvailablePort(
            onPiecesNeeded = { startPiece, endPiece ->
                prioritizePiecesForRange(handle, torrentInfo, fileIndex, startPiece, endPiece)
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
