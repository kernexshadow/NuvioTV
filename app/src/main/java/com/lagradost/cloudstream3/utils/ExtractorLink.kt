@file:Suppress("unused")

package com.lagradost.cloudstream3.utils

enum class ExtractorLinkType {
    VIDEO,
    M3U8,
    DASH
}

/** Sentinel value indicating the type should be inferred. */
val INFER_TYPE: ExtractorLinkType? = null

open class ExtractorLink(
    open val source: String,
    open val name: String,
    open val url: String,
    open val referer: String,
    open val quality: Int = Qualities.Unknown.value,
    open val type: ExtractorLinkType = ExtractorLinkType.VIDEO,
    open val headers: Map<String, String> = emptyMap(),
    open val extractorData: String? = null,
    @Deprecated("Use type instead") open val isM3u8: Boolean = type == ExtractorLinkType.M3U8,
    @Deprecated("Use type instead") open val isDash: Boolean = type == ExtractorLinkType.DASH
) {
    constructor(
        source: String,
        name: String,
        url: String,
        referer: String,
        quality: Int,
        isM3u8: Boolean = false,
        headers: Map<String, String> = emptyMap(),
        extractorData: String? = null,
        isDash: Boolean = false
    ) : this(
        source = source,
        name = name,
        url = url,
        referer = referer,
        quality = quality,
        type = when {
            isM3u8 -> ExtractorLinkType.M3U8
            isDash -> ExtractorLinkType.DASH
            else -> ExtractorLinkType.VIDEO
        },
        headers = headers,
        extractorData = extractorData
    )
}

fun newExtractorLink(
    source: String,
    name: String,
    url: String,
    referer: String = "",
    quality: Int = Qualities.Unknown.value,
    type: ExtractorLinkType = ExtractorLinkType.VIDEO,
    headers: Map<String, String> = emptyMap(),
    extractorData: String? = null
): ExtractorLink = ExtractorLink(
    source = source,
    name = name,
    url = url,
    referer = referer,
    quality = quality,
    type = type,
    headers = headers,
    extractorData = extractorData
)

/** Builder-pattern overload used by CloudStream3 extractors. */
class ExtractorLinkBuilder(
    val source: String,
    val name: String,
    val url: String,
    val type: ExtractorLinkType?
) {
    var referer: String = ""
    var quality: Int = Qualities.Unknown.value
    var headers: Map<String, String> = emptyMap()
    var extractorData: String? = null
}

fun newExtractorLink(
    source: String,
    name: String,
    url: String,
    type: ExtractorLinkType? = null,
    initializer: ExtractorLinkBuilder.() -> Unit
): ExtractorLink {
    val builder = ExtractorLinkBuilder(source, name, url, type)
    builder.initializer()
    return ExtractorLink(
        source = source,
        name = name,
        url = url,
        referer = builder.referer,
        quality = builder.quality,
        type = builder.type ?: ExtractorLinkType.VIDEO,
        headers = builder.headers,
        extractorData = builder.extractorData
    )
}
