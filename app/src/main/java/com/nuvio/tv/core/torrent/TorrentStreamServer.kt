package com.nuvio.tv.core.torrent

import android.util.Log
import fi.iki.elonen.NanoHTTPD
import java.io.File
import java.io.FileInputStream
import java.io.InputStream

/**
 * Local HTTP server that serves torrent file data to ExoPlayer.
 * Supports Range requests for seeking within the media file.
 *
 * Before serving any byte range, the server waits for the corresponding
 * torrent pieces to be fully downloaded — this prevents ExoPlayer from
 * reading zero-filled holes in the sparse file.
 */
class TorrentStreamServer(
    port: Int = 9080,
    private val onBytesNeeded: (startByte: Long, endByte: Long) -> Unit,
    private val areBytesReady: (startByte: Long, endByte: Long) -> Boolean
) : NanoHTTPD(port) {

    @Volatile
    var active: Boolean = true

    @Volatile
    var servingFile: File? = null

    @Volatile
    var fileLength: Long = 0L

    @Volatile
    var pieceLength: Long = 0L

    @Volatile
    var mimeType: String = "video/mp4"

    override fun serve(session: IHTTPSession): Response {
        if (session.uri != "/stream") {
            return newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "Not found")
        }

        val file = servingFile
        if (file == null || !file.exists()) {
            return newFixedLengthResponse(
                Response.Status.SERVICE_UNAVAILABLE,
                MIME_PLAINTEXT,
                "File not ready"
            )
        }

        val totalLength = fileLength
        if (totalLength <= 0) {
            return newFixedLengthResponse(
                Response.Status.SERVICE_UNAVAILABLE,
                MIME_PLAINTEXT,
                "File length unknown"
            )
        }

        val rangeHeader = session.headers["range"]
        return if (rangeHeader != null) {
            serveRangeRequest(file, totalLength, rangeHeader)
        } else {
            serveFullRequest(file, totalLength)
        }
    }

    private fun serveFullRequest(file: File, totalLength: Long): Response {
        waitForBytes(0, totalLength)

        val inputStream: InputStream = FileInputStream(file)
        val response = newFixedLengthResponse(
            Response.Status.OK,
            mimeType,
            inputStream,
            totalLength
        )
        response.addHeader("Accept-Ranges", "bytes")
        response.addHeader("Content-Length", totalLength.toString())
        return response
    }

    private fun serveRangeRequest(file: File, totalLength: Long, rangeHeader: String): Response {
        val rangeValue = rangeHeader.replace("bytes=", "").trim()
        val parts = rangeValue.split("-")

        val start = parts[0].toLongOrNull() ?: 0L
        val end = if (parts.size > 1 && parts[1].isNotEmpty()) {
            parts[1].toLongOrNull() ?: (totalLength - 1)
        } else {
            totalLength - 1
        }

        if (start >= totalLength || end >= totalLength || start > end) {
            val response = newFixedLengthResponse(
                Response.Status.RANGE_NOT_SATISFIABLE,
                MIME_PLAINTEXT,
                "Range not satisfiable"
            )
            response.addHeader("Content-Range", "bytes */$totalLength")
            return response
        }

        waitForBytes(start, end)

        val contentLength = end - start + 1
        val inputStream: InputStream = FileInputStream(file).also {
            if (start > 0) it.skip(start)
        }

        val response = newFixedLengthResponse(
            Response.Status.PARTIAL_CONTENT,
            mimeType,
            inputStream,
            contentLength
        )
        response.addHeader("Accept-Ranges", "bytes")
        response.addHeader("Content-Range", "bytes $start-$end/$totalLength")
        response.addHeader("Content-Length", contentLength.toString())
        return response
    }

    /**
     * Requests the needed bytes and blocks until the immediate read window
     * is downloaded. Waits indefinitely — only returns when data is ready
     * or the server is stopped.
     */
    private fun waitForBytes(start: Long, end: Long) {
        if (pieceLength <= 0) return

        // Only wait for a small window from the read start, not the whole range
        val windowBytes = MAX_WAIT_PIECES.toLong() * pieceLength
        val waitEnd = (start + windowBytes).coerceAtMost(end)

        try {
            onBytesNeeded(start, waitEnd)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to request bytes $start-$waitEnd", e)
            return
        }

        while (active) {
            try {
                if (areBytesReady(start, waitEnd)) return
            } catch (e: Exception) {
                Log.w(TAG, "Error checking byte readiness", e)
                return
            }
            try {
                Thread.sleep(200)
            } catch (e: InterruptedException) {
                return
            }
        }
    }

    companion object {
        private const val TAG = "TorrentStreamServer"
        private const val MAX_WAIT_PIECES = 5

        fun startOnAvailablePort(
            onBytesNeeded: (startByte: Long, endByte: Long) -> Unit,
            areBytesReady: (startByte: Long, endByte: Long) -> Boolean,
            startPort: Int = 9080,
            maxAttempts: Int = 20
        ): TorrentStreamServer? {
            for (port in startPort until startPort + maxAttempts) {
                try {
                    val server = TorrentStreamServer(port, onBytesNeeded, areBytesReady)
                    server.start(SOCKET_READ_TIMEOUT, false)
                    return server
                } catch (e: Exception) {
                    // Port in use, try next
                }
            }
            return null
        }
    }
}
