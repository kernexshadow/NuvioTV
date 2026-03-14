@file:Suppress("unused")

package com.lagradost.cloudstream3.utils

import com.lagradost.cloudstream3.SubtitleFile

abstract class ExtractorApi {
    abstract val name: String
    abstract val mainUrl: String
    abstract val requiresReferer: Boolean

    open fun getExtractorUrl(id: String): String {
        return "$mainUrl/$id"
    }

    /** Primary getUrl with callbacks — most extractors override this. */
    open suspend fun getUrl(
        url: String,
        referer: String? = null,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        // Default: delegate to the List-returning overload for extractors that override that instead
        val links = getUrl(url, referer)
        links?.forEach { callback(it) }
    }

    /** Alternate getUrl returning a list — some older extractors override this. */
    open suspend fun getUrl(url: String, referer: String? = null): List<ExtractorLink>? = null

    /** Resolve relative URLs against mainUrl. */
    fun fixUrl(url: String): String {
        if (url.startsWith("http://") || url.startsWith("https://")) return url
        if (url.startsWith("//")) return "https:$url"
        if (url.startsWith("/")) return "$mainUrl$url"
        return "$mainUrl/$url"
    }
}

/**
 * Internal delegate set by ExternalExtractorRegistry.installGlobal().
 * Must NOT be called `loadExtractor` — extensions compiled against real CloudStream3
 * expect a static method `loadExtractor(...)`, not a getter `getLoadExtractor()`.
 */
var _loadExtractorDelegate: suspend (
    url: String,
    referer: String?,
    subtitleCallback: (SubtitleFile) -> Unit,
    callback: (ExtractorLink) -> Unit
) -> Boolean = { _, _, _, _ -> false }

/**
 * Global function called by extensions to resolve a URL through registered extractors.
 * Must be a real suspend function (not a var) so the JVM bytecode signature matches
 * what extensions were compiled against in real CloudStream3.
 */
suspend fun loadExtractor(
    url: String,
    referer: String?,
    subtitleCallback: (SubtitleFile) -> Unit,
    callback: (ExtractorLink) -> Unit
): Boolean {
    return _loadExtractorDelegate(url, referer, subtitleCallback, callback)
}

/** Overload used by some extensions (without referer). */
suspend fun loadExtractor(
    url: String,
    subtitleCallback: (SubtitleFile) -> Unit,
    callback: (ExtractorLink) -> Unit
): Boolean = loadExtractor(url, null, subtitleCallback, callback)

/** Global list of registered extractors (referenced by some extensions). */
val extractorApis: MutableList<ExtractorApi> = mutableListOf()

fun httpsify(url: String): String {
    return if (url.startsWith("http://")) url.replaceFirst("http://", "https://")
    else url
}

fun getAndUnpack(response: String): String {
    return if (JsUnpacker.detect(response)) {
        JsUnpacker.unpack(response)
    } else {
        response
    }
}

/** Extract packed JavaScript content from HTML/text. Returns null if not found. */
fun getPacked(text: String): String? {
    val packedRegex = Regex("""eval\(function\(p,a,c,k,e,[dr]\).*?\}\s*\(.*?\)\)""", RegexOption.DOT_MATCHES_ALL)
    val match = packedRegex.find(text)
    return match?.value
}
