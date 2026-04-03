package com.nuvio.tv.core.torrent

import android.util.Log
import fi.iki.elonen.NanoHTTPD
import java.io.File
import java.io.FileInputStream
import java.io.InputStream

/**
 * Local HTTP server that serves torrent file data to ExoPlayer.
 * Supports Range requests for seeking within the media file.
 */
class TorrentStreamServer(
    port: Int = 9080,
    private val onPiecesNeeded: (startPiece: Int, endPiece: Int) -> Unit
) : NanoHTTPD(port) {

    @Volatile
    var servingFile: File? = null

    @Volatile
    var fileLength: Long = 0L

    @Volatile
    var pieceLength: Long = 0L

    @Volatile
    var fileOffset: Long = 0L

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
        requestPiecesForRange(0, totalLength)

        val inputStream: InputStream = FileInputStream(file).also {
            if (fileOffset > 0) it.skip(fileOffset)
        }
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

        requestPiecesForRange(start, end)

        val contentLength = end - start + 1
        val inputStream: InputStream = FileInputStream(file).also {
            it.skip(fileOffset + start)
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

    private fun requestPiecesForRange(start: Long, end: Long) {
        if (pieceLength <= 0) return
        val startPiece = (start / pieceLength).toInt()
        val endPiece = (end / pieceLength).toInt()
        try {
            onPiecesNeeded(startPiece, endPiece)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to request pieces $startPiece-$endPiece", e)
        }
    }

    companion object {
        private const val TAG = "TorrentStreamServer"

        fun startOnAvailablePort(
            onPiecesNeeded: (startPiece: Int, endPiece: Int) -> Unit,
            startPort: Int = 9080,
            maxAttempts: Int = 20
        ): TorrentStreamServer? {
            for (port in startPort until startPort + maxAttempts) {
                try {
                    val server = TorrentStreamServer(port, onPiecesNeeded)
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
