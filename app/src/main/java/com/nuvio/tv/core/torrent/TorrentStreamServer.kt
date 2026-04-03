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
    private val onPiecesNeeded: (startPiece: Int, endPiece: Int) -> Unit,
    private val arePiecesReady: (startPiece: Int, endPiece: Int) -> Boolean
) : NanoHTTPD(port) {

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
        waitForPieces(0, totalLength)

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

        waitForPieces(start, end)

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
     * Requests the needed pieces and blocks until they are all downloaded.
     * Times out after [PIECE_WAIT_TIMEOUT_MS] to avoid hanging forever.
     */
    private fun waitForPieces(start: Long, end: Long) {
        if (pieceLength <= 0) return
        val startPiece = (start / pieceLength).toInt()
        val endPiece = (end / pieceLength).toInt()

        try {
            onPiecesNeeded(startPiece, endPiece)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to request pieces $startPiece-$endPiece", e)
            return
        }

        val deadline = System.currentTimeMillis() + PIECE_WAIT_TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            try {
                if (arePiecesReady(startPiece, endPiece)) return
            } catch (e: Exception) {
                Log.w(TAG, "Error checking piece readiness", e)
                return
            }
            try {
                Thread.sleep(100)
            } catch (e: InterruptedException) {
                return
            }
        }
        Log.w(TAG, "Timed out waiting for pieces $startPiece-$endPiece")
    }

    companion object {
        private const val TAG = "TorrentStreamServer"
        private const val PIECE_WAIT_TIMEOUT_MS = 30_000L

        fun startOnAvailablePort(
            onPiecesNeeded: (startPiece: Int, endPiece: Int) -> Unit,
            arePiecesReady: (startPiece: Int, endPiece: Int) -> Boolean,
            startPort: Int = 9080,
            maxAttempts: Int = 20
        ): TorrentStreamServer? {
            for (port in startPort until startPort + maxAttempts) {
                try {
                    val server = TorrentStreamServer(port, onPiecesNeeded, arePiecesReady)
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
