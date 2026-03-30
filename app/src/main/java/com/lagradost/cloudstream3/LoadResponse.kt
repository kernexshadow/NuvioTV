@file:Suppress("unused")

package com.lagradost.cloudstream3

abstract class LoadResponse {
    abstract val name: String
    abstract val url: String
    abstract val apiName: String
    abstract val type: TvType
    open var posterUrl: String? = null
    open var year: Int? = null
    open var plot: String? = null
    open var rating: Int? = null
    open var score: Score? = null
    open var tags: List<String>? = null
    open var duration: Int? = null
    open var trailerUrl: String? = null
    open var trailers: MutableList<TrailerData> = mutableListOf()
    open var recommendations: List<SearchResponse>? = null
    open var actors: List<ActorData>? = null
    open var comingSoon: Boolean = false
    open var syncData: MutableMap<String, String> = mutableMapOf()
    open var posterHeaders: Map<String, String>? = null
    open var backgroundPosterUrl: String? = null
    open var logoUrl: String? = null
    open var contentRating: String? = null

    companion object {
        var isTrailersEnabled = true

        // ── Poster ──

        fun LoadResponse.addPoster(url: String?, headers: Map<String, String>? = null) {
            this.posterUrl = url
            this.posterHeaders = headers
        }

        // ── Trailer ──

        fun LoadResponse.addTrailer(url: String?) {
            if (url != null) trailers.add(TrailerData(url, null, false))
        }

        fun LoadResponse.addTrailer(urls: List<String>?) {
            urls?.forEach { url -> trailers.add(TrailerData(url, null, false)) }
        }

        @Suppress("RedundantSuspendModifier")
        suspend fun LoadResponse.addTrailer(
            trailerUrl: String?,
            referer: String? = null,
            addRaw: Boolean = false
        ) {
            if (trailerUrl != null) trailers.add(TrailerData(trailerUrl, referer, addRaw))
        }

        @JvmName("addTrailerList")
        @Suppress("RedundantSuspendModifier")
        suspend fun LoadResponse.addTrailer(
            trailerUrls: List<String>?,
            referer: String? = null,
            addRaw: Boolean = false
        ) {
            trailerUrls?.forEach { url ->
                trailers.add(TrailerData(url, referer, addRaw))
            }
        }

        // ── Sync IDs ──

        fun LoadResponse.addImdbId(id: String?) {
            if (id != null) syncData["imdb"] = id
        }

        fun LoadResponse.addImdbUrl(url: String?) {
            addImdbId(imdbUrlToIdNullable(url))
        }

        fun LoadResponse.addTMDbId(id: String?) {
            if (id != null) syncData["tmdb"] = id
        }

        fun LoadResponse.addAniListId(id: Int?) {
            if (id != null) syncData["anilist"] = id.toString()
        }

        fun LoadResponse.addMalId(id: Int?) {
            if (id != null) syncData["mal"] = id.toString()
        }

        fun LoadResponse.addKitsuId(id: Int?) {
            if (id != null) syncData["kitsu"] = id.toString()
        }

        @JvmName("addKitsuIdString")
        fun LoadResponse.addKitsuId(id: String?) {
            if (id != null) syncData["kitsu"] = id
        }

        fun LoadResponse.addSimklId(id: Int?) {
            if (id != null) syncData["simkl"] = id.toString()
        }

        @Suppress("UNUSED_PARAMETER")
        fun LoadResponse.addTraktId(id: String?) {
            // Stub — Trakt sync not implemented
        }

        // ── Score / Rating ──

        fun LoadResponse.addScore(score: String?, maxValue: Int = 10) {
            this.score = Score.from(score, maxValue)
        }

        fun LoadResponse.addScore(score: Score?) {
            this.score = score
        }

        @Deprecated("Use addScore", level = DeprecationLevel.ERROR)
        fun LoadResponse.addRating(text: String?) {
            this.score = Score.from10(text)
        }

        @Suppress("DEPRECATION_ERROR")
        @Deprecated("Use addScore", level = DeprecationLevel.ERROR)
        fun LoadResponse.addRating(value: Int?) {
            this.score = Score.fromOld(value)
        }

        // ── Duration ──

        fun LoadResponse.addDuration(input: String?) {
            this.duration = getDurationFromString(input) ?: this.duration
        }

        // ── Actors ──

        fun LoadResponse.addActors(actors: List<Pair<Actor, String?>>?) {
            this.actors = actors?.map { (actor, roleString) ->
                ActorData(actor = actor, roleString = roleString)
            }
        }

        @JvmName("addActorsRole")
        fun LoadResponse.addActors(actors: List<Pair<Actor, ActorRole?>>?) {
            this.actors = actors?.map { (actor, role) ->
                ActorData(actor = actor, role = role)
            }
        }

        @JvmName("addActorsNames")
        fun LoadResponse.addActors(actors: List<String>?) {
            this.actors = actors?.map { ActorData(actor = Actor(it)) }
        }

        @JvmName("addActorsOnly")
        fun LoadResponse.addActors(actors: List<Actor>?) {
            this.actors = actors?.map { ActorData(actor = it) }
        }

        // ── Date ──

        fun LoadResponse.addDate(date: String?) {
            // Stub — date parsing not critical for stream resolution
        }

        fun LoadResponse.addDate(date: Long?) {
            // Stub
        }

        // ── Episodes ──

        fun LoadResponse.addEpisodes(dubStatus: DubStatus, episodes: List<Episode>?) {
            if (this is AnimeLoadResponse && episodes != null) {
                this.episodes = this.episodes.toMutableMap().also {
                    it[dubStatus.name] = episodes
                }
            }
        }
    }
}

data class TrailerData(
    val extractorUrl: String,
    val referer: String?,
    val raw: Boolean,
    val headers: Map<String, String> = mapOf()
)

data class Episode(
    var data: String,
    var name: String? = null,
    var season: Int? = null,
    var episode: Int? = null,
    var posterUrl: String? = null,
    var rating: Int? = null,
    var description: String? = null,
    var date: Long? = null,
    var runTime: Int? = null,
    var score: Score? = null
)

data class MovieLoadResponse(
    override val name: String,
    override val url: String,
    override val apiName: String,
    override val type: TvType = TvType.Movie,
    var dataUrl: String,
    override var posterUrl: String? = null,
    override var year: Int? = null,
    override var plot: String? = null,
    override var rating: Int? = null,
    override var tags: List<String>? = null,
    override var duration: Int? = null,
    override var recommendations: List<SearchResponse>? = null,
    override var actors: List<ActorData>? = null,
    override var comingSoon: Boolean = false,
    override var posterHeaders: Map<String, String>? = null,
    override var backgroundPosterUrl: String? = null
) : LoadResponse()

data class TvSeriesLoadResponse(
    override val name: String,
    override val url: String,
    override val apiName: String,
    override val type: TvType = TvType.TvSeries,
    var episodes: List<Episode>,
    override var posterUrl: String? = null,
    override var year: Int? = null,
    override var plot: String? = null,
    override var rating: Int? = null,
    override var tags: List<String>? = null,
    override var duration: Int? = null,
    override var recommendations: List<SearchResponse>? = null,
    override var actors: List<ActorData>? = null,
    override var comingSoon: Boolean = false,
    override var posterHeaders: Map<String, String>? = null,
    override var backgroundPosterUrl: String? = null,
    var showStatus: ShowStatus? = null,
    val seasonNames: List<SeasonData>? = null,
    val nextAiring: NextAiring? = null
) : LoadResponse()

data class AnimeLoadResponse(
    override val name: String,
    override val url: String,
    override val apiName: String,
    override val type: TvType = TvType.Anime,
    var episodes: Map<String, List<Episode>> = emptyMap(),
    override var posterUrl: String? = null,
    override var year: Int? = null,
    override var plot: String? = null,
    override var rating: Int? = null,
    override var tags: List<String>? = null,
    override var duration: Int? = null,
    override var recommendations: List<SearchResponse>? = null,
    override var actors: List<ActorData>? = null,
    override var comingSoon: Boolean = false,
    override var posterHeaders: Map<String, String>? = null,
    override var backgroundPosterUrl: String? = null,
    var showStatus: ShowStatus? = null
) : LoadResponse()

data class LiveLoadResponse(
    override val name: String,
    override val url: String,
    override val apiName: String,
    override val type: TvType = TvType.Live,
    var dataUrl: String,
    override var posterUrl: String? = null,
    override var year: Int? = null,
    override var plot: String? = null,
    override var rating: Int? = null,
    override var tags: List<String>? = null,
    override var duration: Int? = null,
    override var recommendations: List<SearchResponse>? = null,
    override var actors: List<ActorData>? = null,
    override var comingSoon: Boolean = false,
    override var posterHeaders: Map<String, String>? = null,
    override var backgroundPosterUrl: String? = null
) : LoadResponse()

enum class ShowStatus { Completed, Ongoing }

data class SeasonData(val season: Int, val name: String? = null, val displaySeason: Int? = null)

data class NextAiring(val episode: Int, val unixTime: Long, val season: Int? = null)

class Score private constructor(private val data: Int) {
    fun toInt(maxScore: Int = 10): Int = data * maxScore / 10000
    fun toFloat(maxScore: Int = 10): Float = data * maxScore / 10000f
    fun toDouble(maxScore: Int = 10): Double = data * maxScore / 10000.0

    @Deprecated("Use Score.from instead")
    fun toOld(): Int = data

    companion object {
        fun from(value: Int?, maxScore: Int): Score? =
            if (value == null) null else Score(value * 10000 / maxScore)
        fun from(value: Double?, maxScore: Int): Score? =
            if (value == null) null else Score((value * 10000 / maxScore).toInt())
        fun from(value: String?, maxScore: Int): Score? =
            from(value?.trim()?.toDoubleOrNull()?.let { kotlin.math.abs(it) }, maxScore)
        fun from10(value: Int?): Score? = from(value, 10)
        fun from10(value: Double?): Score? = from(value, 10)
        fun from10(value: Float?): Score? = from(value?.toDouble(), 10)
        fun from10(value: String?): Score? = from(value, 10)
        fun from100(value: Int?): Score? = from(value, 100)
        fun from100(value: String?): Score? = from(value, 100)
        fun from5(value: Int?): Score? = from(value, 5)

        /** Legacy: old CS3 stored rating as raw int (0-10000). */
        @Suppress("DEPRECATION")
        fun fromOld(value: Int?): Score? =
            if (value == null) null else Score(value)
    }
}
