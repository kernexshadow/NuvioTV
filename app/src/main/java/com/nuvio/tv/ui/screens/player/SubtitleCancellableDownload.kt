package com.nuvio.tv.ui.screens.player

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import javax.net.ssl.SSLException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Cancellation owns the Call until the BODY has been read, not merely until headers arrive. */
internal suspend fun <T> readSubtitleResponseCancellable(
    request: Request,
    validated: OkHttpClient = subtitleHttpClient,
    permissive: OkHttpClient = subtitleUnvalidatedTlsHttpClient,
    read: (Response) -> T
): T = try {
    validated.readCancellable(request, read)
} catch (error: CancellationException) {
    throw error
} catch (error: SSLException) {
    // Keep the existing credential-scoping rule on an unvalidated TLS retry.
    permissive.readCancellable(request.withoutCredentialHeaders(), read)
}

private suspend fun <T> OkHttpClient.readCancellable(request: Request, read: (Response) -> T): T =
    suspendCancellableCoroutine { continuation ->
        val call = newCall(request)
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (continuation.isActive) continuation.resumeWithException(e)
            }

            override fun onResponse(call: Call, response: Response) {
                try {
                    response.use {
                        if (continuation.isActive) continuation.resume(read(it))
                    }
                } catch (error: Exception) {
                    if (continuation.isActive) continuation.resumeWithException(error)
                }
            }
        })
    }
