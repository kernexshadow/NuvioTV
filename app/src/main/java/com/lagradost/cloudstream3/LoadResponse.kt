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

    companion object
}

// Extension functions on LoadResponse.Companion
fun LoadResponse.Companion.addTrailer(response: LoadResponse, url: String?) {
    if (url != null) response.trailers.add(TrailerData(url, null, false))
}

fun LoadResponse.addTrailer(url: String?) {
    if (url != null) trailers.add(TrailerData(url, null, false))
}

fun LoadResponse.addTrailer(urls: List<String>?) {
    urls?.forEach { url -> trailers.add(TrailerData(url, null, false)) }
}

fun LoadResponse.addImdbId(id: String?) {
    if (id != null) syncData["imdb"] = id
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

fun LoadResponse.addKitsuId(id: String?) {
    if (id != null) syncData["kitsu"] = id
}

fun LoadResponse.addActors(actors: List<Pair<Actor, String?>>?) {
    this.actors = actors?.map { (actor, roleString) ->
        ActorData(actor = actor, roleString = roleString)
    }
}

@JvmName("addActorsNames")
fun LoadResponse.addActors(actors: List<String>?) {
    this.actors = actors?.map { ActorData(actor = Actor(it)) }
}

fun LoadResponse.addDate(date: String?) {
    // Stub - date parsing not critical for stream resolution
}

fun LoadResponse.addDate(date: Long?) {
    // Stub
}

fun LoadResponse.addEpisodes(dubStatus: DubStatus, episodes: List<Episode>?) {
    if (this is AnimeLoadResponse && episodes != null) {
        this.episodes = this.episodes.toMutableMap().also {
            it[dubStatus.name] = episodes
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
    var runTime: Int? = null
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
    val showStatus: ShowStatus? = null,
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
    val showStatus: ShowStatus? = null
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

    companion object {
        fun from(value: Int?, maxScore: Int): Score? =
            if (value == null) null else Score(value * 10000 / maxScore)
        fun from(value: Double?, maxScore: Int): Score? =
            if (value == null) null else Score((value * 10000 / maxScore).toInt())
        fun from10(value: Int?): Score? = from(value, 10)
        fun from10(value: Double?): Score? = from(value, 10)
        fun from10(value: Float?): Score? = from(value?.toDouble(), 10)
        fun from100(value: Int?): Score? = from(value, 100)
        fun from5(value: Int?): Score? = from(value, 5)
    }
}
