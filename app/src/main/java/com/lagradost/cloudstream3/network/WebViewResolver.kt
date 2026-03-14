@file:Suppress("unused")

package com.lagradost.cloudstream3.network

import okhttp3.Interceptor
import okhttp3.Response

/**
 * Stub for WebViewResolver. WebView-based bypass is not supported in NuvioTV,
 * but the class must exist with the right constructors so extractors can instantiate it.
 */
class WebViewResolver(
    val interceptUrl: Regex,
    val additionalUrls: List<Regex> = emptyList(),
    val useOkhttp: Boolean = true,
    val timeout: Long = 0L
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response = chain.proceed(chain.request())
}
