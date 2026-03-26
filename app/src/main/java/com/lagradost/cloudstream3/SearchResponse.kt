@file:Suppress("unused")

package com.lagradost.cloudstream3

abstract class SearchResponse {
    abstract val name: String
    abstract val url: String
    abstract val apiName: String
    open var type: TvType? = null
    open var posterUrl: String? = null
    open var year: Int? = null
    open var id: Int? = null
    open var quality: SearchQuality? = null
    open var posterHeaders: Map<String, String>? = null
    open var score: Score? = null
}

data class MovieSearchResponse(
    override val name: String,
    override val url: String,
    override val apiName: String,
    override var type: TvType? = TvType.Movie,
    override var posterUrl: String? = null,
    override var year: Int? = null,
    override var id: Int? = null,
    override var quality: SearchQuality? = null,
    override var posterHeaders: Map<String, String>? = null
) : SearchResponse()

data class TvSeriesSearchResponse(
    override val name: String,
    override val url: String,
    override val apiName: String,
    override var type: TvType? = TvType.TvSeries,
    override var posterUrl: String? = null,
    override var year: Int? = null,
    override var id: Int? = null,
    override var quality: SearchQuality? = null,
    override var posterHeaders: Map<String, String>? = null,
    val episodes: Int? = null
) : SearchResponse()

data class AnimeSearchResponse(
    override val name: String,
    override val url: String,
    override val apiName: String,
    override var type: TvType? = TvType.Anime,
    override var posterUrl: String? = null,
    override var year: Int? = null,
    override var id: Int? = null,
    override var quality: SearchQuality? = null,
    override var posterHeaders: Map<String, String>? = null,
    var dubStatus: Set<DubStatus>? = null,
    var dubEpisodes: MutableMap<DubStatus, Int> = mutableMapOf(),
    var episodes: MutableMap<DubStatus, Int> = mutableMapOf(),
    var otherName: String? = null
) : SearchResponse()

data class LiveSearchResponse(
    override val name: String,
    override val url: String,
    override val apiName: String,
    override var type: TvType? = TvType.Live,
    override var posterUrl: String? = null,
    override var year: Int? = null,
    override var id: Int? = null,
    override var quality: SearchQuality? = null,
    override var posterHeaders: Map<String, String>? = null
) : SearchResponse()

data class TorrentSearchResponse(
    override val name: String,
    override val url: String,
    override val apiName: String,
    override var type: TvType? = TvType.Torrent,
    override var posterUrl: String? = null,
    override var year: Int? = null,
    override var id: Int? = null,
    override var quality: SearchQuality? = null,
    override var posterHeaders: Map<String, String>? = null
) : SearchResponse()

enum class SearchQuality(val value: Int?) {
    Cam(1), CamRip(2), HdCam(3), Telesync(4), WorkPrint(5), Telecine(6),
    HQ(7), HD(8), HDR(9), BlueRay(10), DVD(11), SD(12), FourK(13),
    UHD(14), SDR(15), WebRip(16)
}

enum class DubStatus(val id: Int) {
    None(-1), Dubbed(1), Subbed(0)
}
