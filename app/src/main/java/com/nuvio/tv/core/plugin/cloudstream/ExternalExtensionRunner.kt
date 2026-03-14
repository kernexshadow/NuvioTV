package com.nuvio.tv.core.plugin.cloudstream

import android.util.Log
import com.lagradost.cloudstream3.AnimeLoadResponse
import com.lagradost.cloudstream3.Episode
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.LiveLoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MovieLoadResponse
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvSeriesLoadResponse
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.metaproviders.TmdbLink
import com.lagradost.cloudstream3.metaproviders.TmdbProvider
import com.lagradost.cloudstream3.utils.toJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.nuvio.tv.core.plugin.TestDiagnostics
import com.nuvio.tv.core.tmdb.TmdbMetadataService
import com.nuvio.tv.core.tmdb.TmdbService
import com.nuvio.tv.domain.model.ContentType
import com.nuvio.tv.domain.model.LocalScraperResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "ExtExtensionRunner"
private const val EXECUTION_TIMEOUT_MS = 60_000L
private const val MIN_TITLE_SIMILARITY = 0.5

/**
 * Executes external DEX extensions by bridging between NuvioTV's TMDB ID-based system
 * and the extensions' text search-based API.
 *
 * Flow: TMDB ID → title lookup → search() → match → load() → loadLinks() → LocalScraperResult
 */
@Singleton
class ExternalExtensionRunner @Inject constructor(
    private val extensionLoader: ExternalExtensionLoader,
    private val extractorRegistry: ExternalExtractorRegistry,
    private val tmdbMetadataService: TmdbMetadataService,
    private val tmdbService: TmdbService
) {
    /**
     * Execute an external extension scraper.
     *
     * @param scraperId The unique scraper identifier
     * @param tmdbId The TMDB ID of the content
     * @param mediaType "movie" or "tv"/"series"
     * @param season Season number (for TV)
     * @param episode Episode number (for TV)
     * @return List of scraper results, or empty on failure
     */
    suspend fun execute(
        scraperId: String,
        tmdbId: String,
        mediaType: String,
        season: Int?,
        episode: Int?
    ): List<LocalScraperResult> = withContext(Dispatchers.IO) {
        // Ensure extractors are loaded (no-op if already done by PluginManager)
        extensionLoader.ensureExtractorsLoaded(listOf(scraperId))

        val api = extensionLoader.getApi(scraperId)
        if (api == null) {
            Log.e(TAG, "No API loaded for scraper: $scraperId")
            return@withContext emptyList()
        }

        withTimeoutOrNull(EXECUTION_TIMEOUT_MS) {
            try {
                executeInternal(api, tmdbId, mediaType, season, episode)
            } catch (e: Exception) {
                Log.e(TAG, "Extension ${api.name} failed: ${e.message}", e)
                emptyList()
            } catch (e: Error) {
                Log.e(TAG, "Extension ${api.name} linkage error: ${e.message}", e)
                emptyList()
            }
        } ?: run {
            Log.w(TAG, "Extension ${api.name} timed out after ${EXECUTION_TIMEOUT_MS}ms")
            emptyList()
        }
    }

    /**
     * Execute with diagnostics for the test UI. Captures each step so the user
     * can see exactly where the extension fails.
     */
    suspend fun executeWithDiagnostics(
        scraperId: String,
        tmdbId: String,
        mediaType: String,
        season: Int?,
        episode: Int?,
        diagnostics: TestDiagnostics
    ): List<LocalScraperResult> = withContext(Dispatchers.IO) {
        // Ensure extractors are loaded (no-op if already done by PluginManager)
        extensionLoader.ensureExtractorsLoaded(listOf(scraperId), diagnostics)

        diagnostics.addStep("Loading DEX extension...")
        val apis = extensionLoader.loadExtensionWithDiagnostics(scraperId, diagnostics)
        val api = apis.firstOrNull()

        if (api == null) {
            diagnostics.addStep("No MainAPI available after load")
            return@withContext emptyList()
        }

        diagnostics.addStep("Using MainAPI: ${api.name} (${api.javaClass.simpleName})")
        val isTmdb = api is TmdbProvider
        diagnostics.addStep("Provider type: ${if (isTmdb) "TmdbProvider" else "search-based"}")

        withTimeoutOrNull(EXECUTION_TIMEOUT_MS) {
            try {
                if (isTmdb) {
                    executeTmdbProviderWithDiagnostics(api, tmdbId, mediaType, season, episode, diagnostics)
                } else {
                    executeSearchBasedWithDiagnostics(api, tmdbId, mediaType, season, episode, diagnostics)
                }
            } catch (e: Error) {
                val missing = extractMissingClass(e)
                diagnostics.addStep("Runtime error: ${e.javaClass.simpleName}")
                if (missing != null) diagnostics.addStep("Missing class: $missing")
                else diagnostics.addStep("Error: ${e.message?.take(200)}")
                emptyList()
            } catch (e: Exception) {
                diagnostics.addStep("Runtime exception: ${e.javaClass.simpleName}: ${e.message?.take(200)}")
                emptyList()
            }
        } ?: run {
            diagnostics.addStep("TIMEOUT after ${EXECUTION_TIMEOUT_MS}ms")
            emptyList()
        }
    }

    private suspend fun executeTmdbProviderWithDiagnostics(
        api: MainAPI,
        tmdbId: String,
        mediaType: String,
        season: Int?,
        episode: Int?,
        diagnostics: TestDiagnostics
    ): List<LocalScraperResult> {
        val tmdbIdInt = tmdbId.toIntOrNull()
        val contentType = when (mediaType.lowercase()) {
            "movie" -> ContentType.MOVIE
            else -> ContentType.SERIES
        }

        diagnostics.addStep("Fetching TMDB metadata...")
        val enrichment = tmdbMetadataService.fetchEnrichment(tmdbId, contentType)
        val movieName = enrichment?.localizedTitle
        diagnostics.addStep("TMDB title: ${movieName ?: "(null)"}")

        val imdbId = if (tmdbIdInt != null) tmdbService.tmdbToImdb(tmdbIdInt, mediaType) else null
        diagnostics.addStep("IMDB ID: ${imdbId ?: "(not found)"}")

        val year = enrichment?.releaseInfo?.take(4)?.toIntOrNull()
        val tmdbLink = TmdbLink(
            imdbID = imdbId,
            tmdbID = tmdbIdInt,
            episode = episode,
            season = season,
            movieName = movieName,
            year = year,
            orgTitle = movieName
        )
        val data = tmdbLink.toJson()
        diagnostics.addStep("TmdbLink JSON: ${data.take(120)}")

        diagnostics.addStep("Calling loadLinks()...")
        val links = mutableListOf<ExtractorLink>()
        val subtitles = mutableListOf<SubtitleFile>()

        // Instrument loadExtractor to see what URLs the extension tries
        val extractorUrls = mutableListOf<String>()
        val originalDelegate = com.lagradost.cloudstream3.utils._loadExtractorDelegate
        com.lagradost.cloudstream3.utils._loadExtractorDelegate = { url, referer, subCb, cb ->
            extractorUrls.add(url)
            originalDelegate(url, referer, subCb, cb)
        }

        val success = try {
            api.loadLinks(
                data = data,
                isCasting = false,
                subtitleCallback = { subtitles.add(it) },
                callback = { links.add(it) }
            )
        } catch (e: Throwable) {
            diagnostics.addStep("loadLinks THREW: ${e.javaClass.simpleName}: ${e.message?.take(120)}")
            false
        } finally {
            com.lagradost.cloudstream3.utils._loadExtractorDelegate = originalDelegate
        }

        diagnostics.addStep("loadLinks returned: success=$success, ${links.size} links, ${subtitles.size} subs")
        if (extractorUrls.isNotEmpty()) {
            diagnostics.addStep("loadExtractor called ${extractorUrls.size}x:")
            extractorUrls.take(5).forEach { url ->
                val domain = try { java.net.URI(url).host } catch (_: Exception) { url.take(40) }
                diagnostics.addStep("  → $domain")
            }
            if (extractorUrls.size > 5) diagnostics.addStep("  ... and ${extractorUrls.size - 5} more")
        } else {
            diagnostics.addStep("loadExtractor was NOT called (extension may not use it, or HTTP requests failed)")
        }
        // Show missing extractor domains
        val missing = extractorRegistry.getMissingExtractorDomains()
        if (missing.isNotEmpty()) {
            diagnostics.addStep("Missing extractors: ${missing.take(5).joinToString()}")
        }

        return links.map { it.toLocalScraperResult(api.name) }
    }

    private suspend fun executeSearchBasedWithDiagnostics(
        api: MainAPI,
        tmdbId: String,
        mediaType: String,
        season: Int?,
        episode: Int?,
        diagnostics: TestDiagnostics
    ): List<LocalScraperResult> {
        val contentType = when (mediaType.lowercase()) {
            "movie" -> ContentType.MOVIE
            else -> ContentType.SERIES
        }

        diagnostics.addStep("Fetching TMDB metadata...")
        val enrichment = tmdbMetadataService.fetchEnrichment(tmdbId, contentType)
        if (enrichment == null) {
            diagnostics.addStep("TMDB enrichment FAILED")
            return emptyList()
        }

        val title = enrichment.localizedTitle
        if (title == null) {
            diagnostics.addStep("TMDB returned no title")
            return emptyList()
        }
        val year = enrichment.releaseInfo?.take(4)?.toIntOrNull()
        diagnostics.addStep("TMDB: \"$title\" ($year)")

        val query = if (year != null) "$title $year" else title
        diagnostics.addStep("Searching for: \"$query\"")
        val searchResults = api.search(query)
        diagnostics.addStep("Search results: ${searchResults?.size ?: 0}")

        if (searchResults.isNullOrEmpty()) return emptyList()

        val bestMatch = findBestMatch(searchResults, title, year, mediaType)
        if (bestMatch == null) {
            diagnostics.addStep("No match above similarity threshold ($MIN_TITLE_SIMILARITY)")
            searchResults.take(3).forEachIndexed { i, r ->
                val sim = calculateSimilarity(r.name, title)
                diagnostics.addStep("  [$i] \"${r.name}\" (sim=${String.format("%.2f", sim)})")
            }
            return emptyList()
        }
        diagnostics.addStep("Best match: \"${bestMatch.name}\" (${bestMatch.url.take(80)})")

        diagnostics.addStep("Loading page...")
        val loadResponse = api.load(bestMatch.url)
        if (loadResponse == null) {
            diagnostics.addStep("load() returned null")
            return emptyList()
        }
        diagnostics.addStep("Loaded: ${loadResponse.javaClass.simpleName}")

        val data = extractData(loadResponse, mediaType, season, episode)
        if (data == null) {
            diagnostics.addStep("No episode data for S${season}E${episode}")
            return emptyList()
        }

        diagnostics.addStep("Calling loadLinks()...")
        val links = mutableListOf<ExtractorLink>()
        val subtitles = mutableListOf<SubtitleFile>()

        val success = api.loadLinks(
            data = data,
            isCasting = false,
            subtitleCallback = { subtitles.add(it) },
            callback = { links.add(it) }
        )

        diagnostics.addStep("loadLinks returned: success=$success, ${links.size} links, ${subtitles.size} subs")
        return links.map { it.toLocalScraperResult(api.name) }
    }

    private fun extractMissingClass(e: Error): String? {
        val msg = e.message ?: return null
        // NoClassDefFoundError messages look like: "com/lagradost/cloudstream3/SomeClass"
        // or "Failed resolution of: Lcom/lagradost/cloudstream3/SomeClass;"
        val match = Regex("""(?:L?)([\w/.]+)(?:;)?""").find(msg)
        return match?.groupValues?.get(1)?.replace('/', '.')
    }

    private suspend fun executeInternal(
        api: MainAPI,
        tmdbId: String,
        mediaType: String,
        season: Int?,
        episode: Int?
    ): List<LocalScraperResult> {
        // TmdbProvider extensions receive TmdbLink JSON directly in loadLinks()
        // instead of going through search → load → extractData
        if (api is TmdbProvider) {
            return executeTmdbProvider(api, tmdbId, mediaType, season, episode)
        }

        return executeSearchBased(api, tmdbId, mediaType, season, episode)
    }

    /**
     * Fast path for TmdbProvider-based extensions.
     * These extensions override loadLinks() and expect a JSON-serialized TmdbLink
     * containing TMDB ID, IMDB ID, season/episode info.
     */
    private suspend fun executeTmdbProvider(
        api: MainAPI,
        tmdbId: String,
        mediaType: String,
        season: Int?,
        episode: Int?
    ): List<LocalScraperResult> {
        val tmdbIdInt = tmdbId.toIntOrNull()

        // Get enrichment for the movie name
        val contentType = when (mediaType.lowercase()) {
            "movie" -> ContentType.MOVIE
            else -> ContentType.SERIES
        }
        val enrichment = tmdbMetadataService.fetchEnrichment(tmdbId, contentType)
        val movieName = enrichment?.localizedTitle

        // Look up IMDB ID from TMDB ID
        val imdbId = if (tmdbIdInt != null) {
            tmdbService.tmdbToImdb(tmdbIdInt, mediaType)
        } else null

        val year = enrichment?.releaseInfo?.take(4)?.toIntOrNull()
        val tmdbLink = TmdbLink(
            imdbID = imdbId,
            tmdbID = tmdbIdInt,
            episode = episode,
            season = season,
            movieName = movieName,
            year = year,
            orgTitle = movieName
        )

        val data = tmdbLink.toJson()
        Log.d(TAG, "TmdbProvider ${api.name}: calling loadLinks with TmdbLink: $data")

        val links = mutableListOf<ExtractorLink>()
        val subtitles = mutableListOf<SubtitleFile>()

        val success = api.loadLinks(
            data = data,
            isCasting = false,
            subtitleCallback = { subtitles.add(it) },
            callback = { links.add(it) }
        )

        if (!success && links.isEmpty()) {
            Log.d(TAG, "loadLinks returned false and no links from ${api.name}")
            return emptyList()
        }

        Log.d(TAG, "Got ${links.size} links and ${subtitles.size} subtitles from ${api.name}")
        return links.map { link -> link.toLocalScraperResult(api.name) }
    }

    /**
     * Standard path for regular MainAPI extensions.
     * Uses text search to find content, then loads and extracts links.
     */
    private suspend fun executeSearchBased(
        api: MainAPI,
        tmdbId: String,
        mediaType: String,
        season: Int?,
        episode: Int?
    ): List<LocalScraperResult> {
        // Step 1: Get title and year from TMDB
        val contentType = when (mediaType.lowercase()) {
            "movie" -> ContentType.MOVIE
            else -> ContentType.SERIES
        }
        val enrichment = tmdbMetadataService.fetchEnrichment(tmdbId, contentType)
        if (enrichment == null) {
            Log.e(TAG, "Failed to fetch TMDB enrichment for $tmdbId")
            return emptyList()
        }

        val title = enrichment.localizedTitle ?: return emptyList()
        val year = enrichment.releaseInfo?.take(4)?.toIntOrNull()

        // Step 2: Search the extension
        val query = if (year != null) "$title $year" else title
        Log.d(TAG, "Searching ${api.name} for: $query")
        val searchResults = api.search(query)
        if (searchResults.isNullOrEmpty()) {
            Log.d(TAG, "No search results from ${api.name} for: $query")
            return emptyList()
        }

        // Step 3: Find best match
        val bestMatch = findBestMatch(searchResults, title, year, mediaType)
        if (bestMatch == null) {
            Log.d(TAG, "No suitable match in ${api.name} results for: $title ($year)")
            return emptyList()
        }
        Log.d(TAG, "Best match from ${api.name}: ${bestMatch.name} (${bestMatch.url})")

        // Step 4: Load the full page
        val loadResponse = api.load(bestMatch.url)
        if (loadResponse == null) {
            Log.e(TAG, "Failed to load ${bestMatch.url} from ${api.name}")
            return emptyList()
        }

        // Step 5: Extract the data string for loadLinks
        val data = extractData(loadResponse, mediaType, season, episode)
        if (data == null) {
            Log.d(TAG, "No data extracted from ${api.name} for S${season}E${episode}")
            return emptyList()
        }

        // Step 6: Call loadLinks and collect results
        val links = mutableListOf<ExtractorLink>()
        val subtitles = mutableListOf<SubtitleFile>()

        val success = api.loadLinks(
            data = data,
            isCasting = false,
            subtitleCallback = { subtitles.add(it) },
            callback = { links.add(it) }
        )

        if (!success && links.isEmpty()) {
            Log.d(TAG, "loadLinks returned false and no links from ${api.name}")
            return emptyList()
        }

        Log.d(TAG, "Got ${links.size} links and ${subtitles.size} subtitles from ${api.name}")

        // Step 7: Map ExtractorLink → LocalScraperResult
        return links.map { link -> link.toLocalScraperResult(api.name) }
    }

    /**
     * Find the best matching search result by title similarity and type.
     */
    private fun findBestMatch(
        results: List<SearchResponse>,
        targetTitle: String,
        targetYear: Int?,
        mediaType: String
    ): SearchResponse? {
        val isMovie = mediaType.lowercase() == "movie"

        return results
            .map { result ->
                val titleSimilarity = calculateSimilarity(result.name, targetTitle)
                val typeBonus = when {
                    result.type == null -> 0.0
                    isMovie && result.type in listOf(TvType.Movie, TvType.AnimeMovie, TvType.Documentary) -> 0.1
                    !isMovie && result.type in listOf(TvType.TvSeries, TvType.Anime, TvType.OVA, TvType.Cartoon, TvType.AsianDrama) -> 0.1
                    else -> -0.1
                }
                val yearBonus = if (targetYear != null && result.year == targetYear) 0.1 else 0.0
                val score = titleSimilarity + typeBonus + yearBonus
                result to score
            }
            .filter { it.second >= MIN_TITLE_SIMILARITY }
            .maxByOrNull { it.second }
            ?.first
    }

    /**
     * Extract the data string needed for loadLinks from a LoadResponse.
     */
    private fun extractData(
        response: LoadResponse,
        mediaType: String,
        season: Int?,
        episode: Int?
    ): String? = when (response) {
        is MovieLoadResponse -> response.dataUrl
        is LiveLoadResponse -> response.dataUrl
        is TvSeriesLoadResponse -> {
            findEpisode(response.episodes, season, episode)?.data
        }
        is AnimeLoadResponse -> {
            // Anime episodes are keyed by dub status (e.g., "sub", "dub")
            val allEpisodes = response.episodes.values.flatten()
            findEpisode(allEpisodes, season, episode)?.data
        }
        else -> null
    }

    /**
     * Find a matching episode from a list of episodes.
     */
    private fun findEpisode(episodes: List<Episode>, season: Int?, episode: Int?): Episode? {
        if (episodes.isEmpty()) return null

        // Try exact season + episode match
        if (season != null && episode != null) {
            episodes.firstOrNull { it.season == season && it.episode == episode }?.let { return it }
        }

        // Try episode-only match (some extensions don't set season)
        if (episode != null) {
            episodes.firstOrNull { it.episode == episode && (it.season == null || it.season == season) }
                ?.let { return it }
        }

        // For single-season shows, try matching by episode number with absolute numbering
        if (season != null && episode != null) {
            val absoluteEpisode = episodes.indexOfFirst {
                (it.season == season || it.season == null) && it.episode == episode
            }
            if (absoluteEpisode >= 0) return episodes[absoluteEpisode]
        }

        return null
    }

    /**
     * Simple Jaro-Winkler inspired string similarity (0.0 to 1.0).
     */
    private fun calculateSimilarity(s1: String, s2: String): Double {
        val a = s1.lowercase().trim()
        val b = s2.lowercase().trim()
        if (a == b) return 1.0
        if (a.isEmpty() || b.isEmpty()) return 0.0

        // Normalize: remove common noise like year suffixes, extra whitespace
        val aNorm = a.replace(Regex("\\(\\d{4}\\)"), "").trim()
        val bNorm = b.replace(Regex("\\(\\d{4}\\)"), "").trim()
        if (aNorm == bNorm) return 0.95

        // Check containment
        if (aNorm.contains(bNorm) || bNorm.contains(aNorm)) return 0.85

        // Levenshtein-based similarity
        val distance = levenshteinDistance(aNorm, bNorm)
        val maxLen = maxOf(aNorm.length, bNorm.length)
        return 1.0 - (distance.toDouble() / maxLen)
    }

    private fun levenshteinDistance(s1: String, s2: String): Int {
        val m = s1.length
        val n = s2.length
        val dp = Array(m + 1) { IntArray(n + 1) }

        for (i in 0..m) dp[i][0] = i
        for (j in 0..n) dp[0][j] = j

        for (i in 1..m) {
            for (j in 1..n) {
                val cost = if (s1[i - 1] == s2[j - 1]) 0 else 1
                dp[i][j] = minOf(
                    dp[i - 1][j] + 1,
                    dp[i][j - 1] + 1,
                    dp[i - 1][j - 1] + cost
                )
            }
        }
        return dp[m][n]
    }

    private fun ExtractorLink.toLocalScraperResult(providerName: String): LocalScraperResult {
        val qualityStr = Qualities.getStringByInt(quality).ifEmpty { null }
        val streamType = when (type) {
            ExtractorLinkType.M3U8 -> "hls"
            ExtractorLinkType.DASH -> "dash"
            else -> null
        }
        val allHeaders = buildMap {
            putAll(headers)
            if (referer.isNotBlank()) put("Referer", referer)
        }

        return LocalScraperResult(
            title = name,
            name = source,
            url = url,
            quality = qualityStr,
            type = streamType,
            headers = allHeaders.ifEmpty { null },
            provider = providerName
        )
    }
}
