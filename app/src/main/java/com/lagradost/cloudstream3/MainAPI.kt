@file:Suppress("unused")

package com.lagradost.cloudstream3

import android.util.Base64
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.json.JsonMapper
import com.fasterxml.jackson.module.kotlin.KotlinModule
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.toJson
import java.text.SimpleDateFormat
import java.util.Date
import java.util.EnumSet
import java.util.Locale

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

fun base64Encode(str: String): String =
    Base64.encodeToString(str.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)

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

suspend fun <T> MainAPI.newMovieLoadResponse(
    name: String,
    url: String,
    type: TvType,
    data: T?,
    initializer: suspend MovieLoadResponse.() -> Unit = {}
): MovieLoadResponse {
    if (data is String) return newMovieLoadResponse(name, url, type, dataUrl = data, initializer = initializer)
    val dataUrl = data?.toJson() ?: ""
    val r = MovieLoadResponse(
        name = name, url = url, apiName = this.name, type = type, dataUrl = dataUrl
    )
    r.comingSoon = dataUrl.isBlank()
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
    r.comingSoon = dataUrl.isBlank()
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
    r.comingSoon = episodes.isEmpty()
    r.initializer()
    return r
}

suspend fun MainAPI.newAnimeLoadResponse(
    name: String,
    url: String,
    type: TvType = TvType.Anime,
    comingSoonIfNone: Boolean = true,
    initializer: suspend AnimeLoadResponse.() -> Unit = {}
): AnimeLoadResponse {
    val r = AnimeLoadResponse(
        name = name, url = url, apiName = this.name, type = type
    )
    r.initializer()
    if (comingSoonIfNone && r.episodes.isEmpty()) {
        r.comingSoon = true
    }
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

fun newSearchResponseList(
    list: List<SearchResponse>,
    hasNext: Boolean? = null
): SearchResponseList = SearchResponseList(items = list, hasNext = hasNext ?: false)

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

// ── URL helpers ──

fun fixUrlNull(url: String?, baseUrl: String? = null): String? {
    if (url.isNullOrBlank()) return null
    if (url.startsWith("http://") || url.startsWith("https://")) return url
    if (url.startsWith("//")) return "https:$url"
    if (baseUrl != null && url.startsWith("/")) return "${baseUrl.trimEnd('/')}$url"
    return url
}

fun getBaseUrl(url: String): String {
    return try {
        val uri = java.net.URI(url)
        "${uri.scheme}://${uri.host}"
    } catch (_: Exception) {
        url
    }
}

fun fixUrl(url: String, baseUrl: String): String {
    if (url.startsWith("http://") || url.startsWith("https://")) return url
    if (url.startsWith("//")) return "https:$url"
    if (url.startsWith("/")) return "${baseUrl.trimEnd('/')}$url"
    return "${baseUrl.trimEnd('/')}/$url"
}

// ── MainAPI class ──

/** Stub settings class for extensions that read provider settings. */
data class SettingsJson(
    val enableAdult: Boolean = false
)

abstract class MainAPI {
    companion object {
        var settingsForProvider: SettingsJson = SettingsJson()
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

    /** Paginated search, starts with page: 1 */
    open suspend fun search(query: String, page: Int): SearchResponseList? {
        val searchResults = search(query) ?: return null
        return newSearchResponseList(searchResults, false)
    }

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

    // fixUrl/fixUrlNull are defined as extension functions below (not member functions)
    // so the JVM bytecode matches what extensions expect (static methods in MainAPIKt)
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
    val headers: Map<String, String>,
    val url: String = ""
) {
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

// ── Extension functions expected by CloudStream3 extensions ──
// These compile to static methods in MainAPIKt, matching the JVM signatures
// that pre-compiled .cs3 DEX extensions call.

fun MainAPI.fixUrl(url: String): String {
    if (url.startsWith("http") || url.startsWith("{\"") || url.startsWith("[")) return url
    if (url.isEmpty()) return ""
    if (url.startsWith("//")) return "https:$url"
    if (url.startsWith("/")) return mainUrl + url
    return "$mainUrl/$url"
}

fun MainAPI.fixUrlNull(url: String?): String? {
    if (url.isNullOrEmpty()) return null
    return fixUrl(url)
}

// ── AnimeSearchResponse helpers ──

fun AnimeSearchResponse.addDubStatus(status: DubStatus, episodes: Int? = null) {
    this.dubStatus = (this.dubStatus as? MutableSet)?.also { it.add(status) }
        ?: EnumSet.of(status)
    if (episodes != null && episodes > 0) {
        this.dubEpisodes[status] = episodes
    }
}

fun AnimeSearchResponse.addDubStatus(isDub: Boolean, episodes: Int? = null) {
    addDubStatus(if (isDub) DubStatus.Dubbed else DubStatus.Subbed, episodes)
}

fun AnimeSearchResponse.addDubStatus(
    dubExist: Boolean,
    subExist: Boolean,
    dubEpisodes: Int? = null,
    subEpisodes: Int? = null
) {
    if (dubExist) addDubStatus(DubStatus.Dubbed, dubEpisodes)
    if (subExist) addDubStatus(DubStatus.Subbed, subEpisodes)
}

fun AnimeSearchResponse.addDubStatus(status: String, episodes: Int? = null) {
    if (status.contains("(dub)", ignoreCase = true)) {
        addDubStatus(DubStatus.Dubbed, episodes)
    } else if (status.contains("(sub)", ignoreCase = true)) {
        addDubStatus(DubStatus.Subbed, episodes)
    }
}

// ── AnimeSearchResponse shorthand helpers ──

fun AnimeSearchResponse.addDub(episodes: Int?) {
    addDubStatus(DubStatus.Dubbed, episodes)
}

fun AnimeSearchResponse.addSub(episodes: Int?) {
    addDubStatus(DubStatus.Subbed, episodes)
}

// ── AnimeLoadResponse helpers (top-level, expected in MainAPIKt) ──

fun AnimeLoadResponse.addEpisodes(status: DubStatus, episodes: List<Episode>?) {
    if (episodes.isNullOrEmpty()) return
    this.episodes = this.episodes.toMutableMap().also {
        it[status.name] = (it[status.name] ?: emptyList()) + episodes
    }
}

// ── SearchResponse helpers ──

fun SearchResponse.addPoster(url: String?, headers: Map<String, String>? = null) {
    this.posterUrl = url
    this.posterHeaders = headers
}

fun SearchResponse.addQuality(quality: String) {
    this.quality = getSearchQuality(quality)
}

fun getSearchQuality(quality: String?): SearchQuality? {
    if (quality == null) return null
    val lower = quality.lowercase().trim()
    return when {
        lower.contains("cam") -> SearchQuality.Cam
        lower.contains("hdcam") -> SearchQuality.HdCam
        lower.contains("webdl") || lower.contains("web-dl") || lower.contains("webrip") -> SearchQuality.WebRip
        lower.contains("bluray") || lower.contains("blu-ray") -> SearchQuality.BlueRay
        lower.contains("4k") || lower.contains("2160") || lower.contains("uhd") -> SearchQuality.FourK
        lower.contains("hdrip") || lower.contains("hd") -> SearchQuality.HD
        lower.contains("dvd") -> SearchQuality.DVD
        lower.contains("sd") -> SearchQuality.SD
        lower.contains("hq") -> SearchQuality.HQ
        else -> null
    }
}

// ── Duration parsing ──

fun getDurationFromString(input: String?): Int? {
    if (input == null) return null
    val cleaned = input.trim().lowercase()

    // "X h Y min" / "Xh Ym" / "X hr Y min"
    Regex("""(\d+)\s*(?:h|hr|hour)s?\s*(?:(\d+)\s*(?:m|min|minute)s?)?""").find(cleaned)?.let { match ->
        val hours = match.groupValues[1].toIntOrNull() ?: 0
        val mins = match.groupValues[2].toIntOrNull() ?: 0
        return hours * 60 + mins
    }

    // "X min" / "X minutes"
    Regex("""(\d+)\s*(?:m|min|minute)s?""").find(cleaned)?.let { match ->
        return match.groupValues[1].toIntOrNull()
    }

    // Bare number (assume minutes)
    return cleaned.toIntOrNull()
}

// ── Episode helpers ──

fun Episode.addDate(date: String?, format: String = "yyyy-MM-dd") {
    try {
        this.date = SimpleDateFormat(format, Locale.getDefault()).parse(date ?: return)?.time
    } catch (_: Exception) { }
}

fun Episode.addDate(date: Date?) {
    this.date = date?.time
}

// ── Additional builder functions ──

fun MainAPI.newTorrentSearchResponse(
    name: String,
    url: String,
    type: TvType = TvType.Torrent,
    fix: Boolean = true,
    initializer: TorrentSearchResponse.() -> Unit = {}
): TorrentSearchResponse {
    val r = TorrentSearchResponse(
        name = name,
        url = if (fix) fixUrl(url) else url,
        apiName = this.name,
        type = type
    )
    r.initializer()
    return r
}

fun MainAPI.newLiveSearchResponse(
    name: String,
    url: String,
    type: TvType = TvType.Live,
    fix: Boolean = true,
    initializer: LiveSearchResponse.() -> Unit = {}
): LiveSearchResponse {
    val r = LiveSearchResponse(
        name = name,
        url = if (fix) fixUrl(url) else url,
        apiName = this.name,
        type = type
    )
    r.initializer()
    return r
}

suspend fun MainAPI.newLiveLoadResponse(
    name: String,
    url: String,
    type: TvType = TvType.Live,
    dataUrl: String,
    initializer: suspend LiveLoadResponse.() -> Unit = {}
): LiveLoadResponse {
    val r = LiveLoadResponse(
        name = name, url = url, apiName = this.name, type = type, dataUrl = dataUrl
    )
    r.initializer()
    return r
}
