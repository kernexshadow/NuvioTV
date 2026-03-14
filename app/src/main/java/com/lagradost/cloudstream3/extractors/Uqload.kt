package com.lagradost.cloudstream3.extractors

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.newExtractorLink

class Uqload1 : Uqload() {
    override var mainUrl = "https://uqload.com"
}

class Uqload2 : Uqload() {
    override var mainUrl = "https://uqload.co"
}

class Uqloadcx : Uqload() {
    override var mainUrl = "https://uqload.cx"
}

class Uqloadbz : Uqload() {
    override var mainUrl = "https://uqload.bz"
}

open class Uqload : ExtractorApi() {
    override var name: String = "Uqload"
    override var mainUrl: String = "https://www.uqload.com"
    override val requiresReferer = true

    private val srcRegex = Regex("""sources:.*"(.*?)".*""")

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        with(app.get(url)) {
            srcRegex.find(this.text)?.groupValues?.get(1)?.let { link ->
                callback.invoke(
                    newExtractorLink(
                        source = name,
                        name = name,
                        url = link
                    ) {
                        this.referer = "$mainUrl/"
                    }
                )
            }
        }
    }
}
