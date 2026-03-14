package com.lagradost.cloudstream3.extractors

import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink

class Watchadsontape : StreamTape() {
    override var mainUrl = "https://watchadsontape.com"
}

class StreamTapeNet : StreamTape() {
    override var mainUrl = "https://streamtape.net"
}

class StreamTapeXyz : StreamTape() {
    override var mainUrl = "https://streamtape.xyz"
}

class ShaveTape : StreamTape() {
    override var mainUrl = "https://shavetape.cash"
}

open class StreamTape : ExtractorApi() {
    override var name = "StreamTape"
    override var mainUrl = "https://streamtape.com"
    override val requiresReferer = false

    override suspend fun getUrl(url: String, referer: String?): List<ExtractorLink>? {
        with(app.get(url)) {
            // Find the line that constructs the stream URL via JS string concatenation
            val scriptLines = this.document.select("script")
                .firstOrNull { it.html().contains("botlink').innerHTML") }
                ?.html()?.lines()

            val urlLine = scriptLines?.firstOrNull { it.contains("botlink').innerHTML") }
                ?: return null

            // Extract URL parts: the line typically looks like:
            // document.getElementById('blah').innerHTML = "//streamtape.com/get_video?id=..." + '..token..';
            val parts = urlLine.substringAfter("innerHTML").substringAfter("\"")
            val firstPart = parts.substringBefore("\"")

            // The second part is concatenated after a +
            val secondPart = parts.substringAfter("+ ('").substringBefore("')")

            if (firstPart.isNotEmpty()) {
                val extractedUrl = "https:${firstPart}${secondPart}&stream=1"
                return listOf(
                    newExtractorLink(
                        name,
                        name,
                        extractedUrl,
                    ) {
                        this.referer = url
                        this.quality = Qualities.Unknown.value
                    }
                )
            }
        }
        return null
    }
}
