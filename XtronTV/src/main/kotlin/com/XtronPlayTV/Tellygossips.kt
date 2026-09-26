package com.XtronTV

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink

class Tellygossips(
    private val source: String
) : ExtractorApi() {

    override val mainUrl = "https://flow.tellygossips.net"
    override val name = "Tellygossips"
    override val requiresReferer = false

    private val siteReferer = "http://tellygossips.net/"
    private val configRegex = "var config = ([\\s\\S]*?);".toRegex()

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        for (proxy in XtronTVPlugin.proxy) {
            try {
                val proxiedUrl = "$proxy/?url=$url"

                val doc = app.get(
                    proxiedUrl,
                    referer = siteReferer
                ).document

                val configStr = doc.select("script")
                    .map { it.data() }
                    .firstOrNull { it.contains("var config = ") }
                    ?.let {
                        configRegex.find(it.trim())?.groupValues?.get(1)
                    }
                    ?: continue

                val config = tryParseJson<Config>(configStr)
                    ?: continue

                for (link in config.sources) {
                    val videoUrl = link.file ?: link.src ?: continue

                    callback(
                        newExtractorLink(
                            "$name $source",
                            name,
                            url = videoUrl,
                            type = ExtractorLinkType.M3U8
                        ) {
                            this.referer = ""
                            this.quality = Qualities.Unknown.value
                            this.headers = headers
                        }
                    )
                }

                return

            } catch (_: Exception) {
                continue
            }
        }
    }

    data class Config(
        val sources: List<VideoLink>
    )

    data class VideoLink(
        val file: String?,
        val src: String?,
        val label: String,
        val type: String
    )
}
