package com.nuvio.tv.ui.screens.player

import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.Call
import okhttp3.EventListener
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.Protocol
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test
import java.net.ServerSocket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import javax.net.ssl.SSLHandshakeException
import kotlin.concurrent.thread

class SubtitleCancellableDownloadTest {
    @Test fun `cancellable TLS fallback never resends stream or subtitle credentials`() = runBlocking {
        val url = "https://subtitle.example/track.srt".toHttpUrl()
        val request = buildSubtitleRequest(url, url,
            streamHeaders = mapOf("Authorization" to "Bearer stream-secret", "Cookie" to "session=secret"),
            explicitHeaders = mapOf("X-Subtitle-Key" to "subtitle-secret"))
        val validatedRequest = AtomicReference<Request>()
        val fallbackRequest = AtomicReference<Request>()
        val validated = OkHttpClient.Builder().addInterceptor { chain ->
            validatedRequest.set(chain.request())
            throw SSLHandshakeException("Synthetic certificate failure")
        }.build()
        val permissive = OkHttpClient.Builder().addInterceptor { chain ->
            fallbackRequest.set(chain.request())
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                .code(200).message("OK").body("subtitle text".toResponseBody()).build()
        }.build()
        try {
            val body = withTimeout(5_000L) {
                readSubtitleResponseCancellable(request, validated, permissive) { it.body!!.string() }
            }
            assertEquals("subtitle text", body)
            assertEquals("Bearer stream-secret", validatedRequest.get().header("Authorization"))
            assertEquals("subtitle-secret", validatedRequest.get().header("X-Subtitle-Key"))
            val retry = fallbackRequest.get()
            assertNotNull(retry)
            assertNull(retry.header("Authorization"))
            assertNull(retry.header("Cookie"))
            assertNull(retry.header("X-Subtitle-Key"))
            assertEquals(request.header("User-Agent"), retry.header("User-Agent"))
        } finally {
            for (client in listOf(validated, permissive)) {
                client.connectionPool.evictAll()
                client.dispatcher.executorService.shutdown()
            }
        }
    }

    @Test fun `cancel closes a stalled subtitle body without waiting for its read timeout`() = runBlocking {
        ServerSocket(0).use { server ->
            val readingBody = CountDownLatch(1)
            val canceled = CountDownLatch(1)
            val disconnected = CountDownLatch(1)
            val client = OkHttpClient.Builder().readTimeout(15, TimeUnit.SECONDS)
                .eventListener(object : EventListener() {
                    override fun canceled(call: Call) { canceled.countDown() }
                }).build()
            val worker = thread(isDaemon = true) {
                server.accept().use { socket ->
                    socket.soTimeout = 5_000
                    val input = socket.getInputStream().bufferedReader()
                    while (!input.readLine().isNullOrEmpty()) { /* HTTP headers */ }
                    socket.getOutputStream().apply {
                        write("HTTP/1.1 200 OK\r\nContent-Length: 10000\r\n\r\nx".toByteArray())
                        flush()
                    }
                    // Deliberately leave the advertised body incomplete.
                    try {
                        while (input.read() != -1) { }
                    } catch (_: java.io.IOException) {
                        // Reset/EOF are both valid ways for cancellation to close the socket.
                    } finally { disconnected.countDown() }
                }
            }
            val job = launch {
                readSubtitleResponseCancellable(
                    Request.Builder().url("http://127.0.0.1:${server.localPort}/s.srt").build(),
                    validated = client, permissive = client
                ) { response ->
                    readingBody.countDown()
                    response.body!!.bytes()
                }
                fail("Canceled download must not produce a result")
            }
            // Let the coroutine enqueue its call before waiting for the OkHttp callback.
            kotlinx.coroutines.yield()
            assertTrue(readingBody.await(5, TimeUnit.SECONDS))
            withTimeout(1_000L) { job.cancelAndJoin() }
            assertTrue(canceled.await(1, TimeUnit.SECONDS))
            assertTrue(disconnected.await(2, TimeUnit.SECONDS))
            worker.join(1_000L)
            client.connectionPool.evictAll()
            client.dispatcher.executorService.shutdown()
        }
    }
}
