@file:Suppress("unused")

package com.lagradost.cloudstream3

import android.util.Base64
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.json.JsonMapper
import com.fasterxml.jackson.module.kotlin.KotlinModule
import com.lagradost.cloudstream3.utils.ExtractorLink

// ── Top-level vals/consts that extensions reference via MainAPIKt ──

/** Jackson mapper singleton used by extensions (via `mapper`). */
val mapper: JsonMapper = JsonMapper.builder()
    .addModule(KotlinModule.Builder().build())
    .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
    .build()

// ── Base64 helpers ──

fun base64Decode(str: String): String =
    String(Base64.decode(str.trim(), Base64.DEFAULT), Charsets.UTF_8)

fun base64DecodeArray(str: String): ByteArray =
    Base64.decode(str.trim(), Base64.DEFAULT)

fun base64Encode(array: ByteArray): String =
    Base64.encodeToString(array, Base64.NO_WRAP)

// ── MainPageData / mainPageOf ──

data class MainPageData(
    val name: String,
    val data: String,
    val horizontalImages: Boolean = false
)

fun mainPageOf(vararg pairs: Pair<String, String>): List<MainPageData> =
    pairs.map { MainPageData(name = it.second, data = it.first) }

// ── Search/Load response builders (must be here so they compile into MainAPIKt) ──

fun MainAPI.newMovieSearchResponse(
    name: String,
    url: String,
    type: TvType = TvType.Movie,
    fix: Boolean = true,
    initializer: MovieSearchResponse.() -> Unit = {}
): MovieSearchResponse {
    val r = MovieSearchResponse(
        name = name,
        url = if (fix) fixUrl(url) else url,
        apiName = this.name,
        type = type
    )
    r.initializer()
    return r
}

fun MainAPI.newTvSeriesSearchResponse(
    name: String,
    url: String,
    type: TvType = TvType.TvSeries,
    fix: Boolean = true,
    initializer: TvSeriesSearchResponse.() -> Unit = {}
): TvSeriesSearchResponse {
    val r = TvSeriesSearchResponse(
        name = name,
        url = if (fix) fixUrl(url) else url,
        apiName = this.name,
        type = type
    )
    r.initializer()
    return r
}

fun MainAPI.newAnimeSearchResponse(
    name: String,
    url: String,
    type: TvType = TvType.Anime,
    fix: Boolean = true,
    initializer: AnimeSearchResponse.() -> Unit = {}
): AnimeSearchResponse {
    val r = AnimeSearchResponse(
        name = name,
        url = if (fix) fixUrl(url) else url,
        apiName = this.name,
        type = type
    )
    r.initializer()
    return r
}

suspend fun MainAPI.newMovieLoadResponse(
    name: String,
    url: String,
    type: TvType = TvType.Movie,
    dataUrl: String,
    initializer: suspend MovieLoadResponse.() -> Unit = {}
): MovieLoadResponse {
    val r = MovieLoadResponse(
        name = name, url = url, apiName = this.name, type = type, dataUrl = dataUrl
    )
    r.initializer()
    return r
}

suspend fun MainAPI.newTvSeriesLoadResponse(
    name: String,
    url: String,
    type: TvType = TvType.TvSeries,
    episodes: List<Episode>,
    initializer: suspend TvSeriesLoadResponse.() -> Unit = {}
): TvSeriesLoadResponse {
    val r = TvSeriesLoadResponse(
        name = name, url = url, apiName = this.name, type = type, episodes = episodes
    )
    r.initializer()
    return r
}

suspend fun MainAPI.newAnimeLoadResponse(
    name: String,
    url: String,
    type: TvType = TvType.Anime,
    initializer: suspend AnimeLoadResponse.() -> Unit = {}
): AnimeLoadResponse {
    val r = AnimeLoadResponse(
        name = name, url = url, apiName = this.name, type = type
    )
    r.initializer()
    return r
}

fun MainAPI.newEpisode(
    url: String,
    initializer: Episode.() -> Unit = {},
    fix: Boolean = true
): Episode {
    val ep = Episode(data = if (fix) fixUrl(url) else url)
    ep.initializer()
    return ep
}

fun <T> MainAPI.newEpisode(
    data: T,
    initializer: Episode.() -> Unit = {}
): Episode {
    val ep = Episode(data = data.toString())
    ep.initializer()
    return ep
}

fun newHomePageResponse(
    list: List<HomePageList>,
    hasNext: Boolean? = null
): HomePageResponse = HomePageResponse(items = list, hasNext = hasNext ?: false)

fun newHomePageResponse(
    vararg list: HomePageList,
    hasNext: Boolean? = null
): HomePageResponse = HomePageResponse(items = list.toList(), hasNext = hasNext ?: false)

data class SearchResponseList(
    val items: List<SearchResponse>,
    val hasNext: Boolean = false
)

fun List<SearchResponse>.toNewSearchResponseList(
    hasNext: Boolean? = null
): SearchResponseList = SearchResponseList(items = this, hasNext = hasNext ?: false)

// ── Misc helpers ──

fun fixTitle(title: String): String =
    title.replace(Regex("[^a-zA-Z0-9 ]"), "").trim().replace(Regex("\\s+"), " ")

fun imdbUrlToIdNullable(url: String?): String? {
    if (url == null) return null
    val regex = Regex("tt\\d+")
    return regex.find(url)?.value
}

// ── SubtitleFile helpers ──

fun MainAPI.newSubtitleFile(
    lang: String,
    url: String,
    type: String? = null,
    headers: Map<String, String>? = null
): SubtitleFile = SubtitleFile(lang = lang, url = fixUrl(url), type = type, headers = headers)

/** Top-level newSubtitleFile (used by extractors that don't have a MainAPI context). */
fun newSubtitleFile(
    lang: String,
    url: String,
    type: String? = null,
    headers: Map<String, String>? = null
): SubtitleFile = SubtitleFile(lang = lang, url = url, type = type, headers = headers)

// ── MainAPI class ──

abstract class MainAPI {
    companion object {
        // Extensions reference MainAPI.Companion for static-like access
    }

    abstract val name: String
    abstract val mainUrl: String
    open val lang: String = "en"
    open val supportedTypes: Set<TvType> = setOf(TvType.Movie, TvType.TvSeries)
    open val hasMainPage: Boolean = false
    open val hasQuickSearch: Boolean = false
    open val usesWebView: Boolean = false
    open val hasDownloadSupport: Boolean = true
    open val hasChromecastSupport: Boolean = false
    open val vpnStatus: VPNStatus = VPNStatus.None
    open val mainPage: List<MainPageData> = emptyList()
    open val requiresReferer: Boolean = false
    open val providerType: ProviderType = ProviderType.DirectProvider
    open val supportedSyncNames: Set<String> = emptySet()

    @Transient
    var _networkInterceptor: ExternalNetworkInterface? = null

    open suspend fun search(query: String): List<SearchResponse>? = null

    open suspend fun quickSearch(query: String): List<SearchResponse>? = search(query)

    open suspend fun load(url: String): LoadResponse? = null

    open suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean = false

    open suspend fun getMainPage(page: Int = 1, request: MainPageRequest): HomePageResponse? = null

    open suspend fun getLoadUrl(name: String, id: String): String? = null

    // Network helpers
    suspend fun get(
        url: String,
        headers: Map<String, String> = emptyMap(),
        referer: String? = null,
        params: Map<String, String> = emptyMap(),
        cookies: Map<String, String> = emptyMap(),
        cacheTime: Int = 0,
        timeout: Long = 0L,
        interceptor: Any? = null,
        verify: Boolean = true
    ): NAppResponse {
        val allHeaders = buildMap {
            putAll(headers)
            if (referer != null) put("Referer", referer)
        }
        return _networkInterceptor?.get(url, allHeaders, params, cookies, timeout)
            ?: NAppResponse("", 0, emptyMap())
    }

    suspend fun post(
        url: String,
        headers: Map<String, String> = emptyMap(),
        referer: String? = null,
        params: Map<String, String> = emptyMap(),
        cookies: Map<String, String> = emptyMap(),
        data: Map<String, String> = emptyMap(),
        requestBody: Any? = null,
        cacheTime: Int = 0,
        timeout: Long = 0L,
        interceptor: Any? = null,
        verify: Boolean = true
    ): NAppResponse {
        val allHeaders = buildMap {
            putAll(headers)
            if (referer != null) put("Referer", referer)
        }
        return _networkInterceptor?.post(url, allHeaders, params, cookies, data, requestBody, timeout)
            ?: NAppResponse("", 0, emptyMap())
    }

    fun fixUrl(url: String): String {
        if (url.startsWith("http://") || url.startsWith("https://")) return url
        if (url.startsWith("//")) return "https:$url"
        if (url.startsWith("/")) return "$mainUrl$url"
        return "$mainUrl/$url"
    }
}

enum class ProviderType {
    MetaProvider,
    DirectProvider
}

enum class VPNStatus {
    None,
    MightBeNeeded,
    Torrent
}

data class NAppResponse(
    val text: String,
    val code: Int,
    val headers: Map<String, String>
) {
    val url: String get() = ""
    val document: org.jsoup.nodes.Document get() = org.jsoup.Jsoup.parse(text)
    val isSuccessful: Boolean get() = code in 200..299
    override fun toString(): String = text
}

interface ExternalNetworkInterface {
    suspend fun get(
        url: String,
        headers: Map<String, String>,
        params: Map<String, String>,
        cookies: Map<String, String>,
        timeout: Long
    ): NAppResponse

    suspend fun post(
        url: String,
        headers: Map<String, String>,
        params: Map<String, String>,
        cookies: Map<String, String>,
        data: Map<String, String>,
        requestBody: Any?,
        timeout: Long
    ): NAppResponse
}
