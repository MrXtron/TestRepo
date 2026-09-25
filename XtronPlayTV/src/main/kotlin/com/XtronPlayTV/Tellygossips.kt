package com.XtronPlayTV

import android.util.Log
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.utils.AppUtils.parseJson

class Tellygossips(private val source: String) : ExtractorApi() {
    override val mainUrl = "https://flow.tellygossips.net"
    override val name = "Tellygossips"
    override val requiresReferer = false
    private val refererUrl = "http://tellygossips.net/"
    private val configRegex = "var config = ([\\s\\S]*?);".toRegex()

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val proxiedUrl = if (!url.startsWith(XtronPlayTVPlugin.proxy)) {
            "${XtronPlayTVPlugin.proxy}/?url=$url"
        } else {   
            url
        }
        
        try {
            val doc = app.get(proxiedUrl, referer = this.refererUrl).document
            val configStr = doc.select("script")
                .map { it.data() }
                .firstOrNull { it.contains("var config = ") }
                ?.let { configRegex.find(it.trim())?.groupValues?.getOrNull(1) } ?: return

            // FIXED: Replaced non-existent tryParseJson with core AppUtils parseJson utility
            val config = parseJson<Config>(configStr)
            
            // FIXED: Defined core localized map to safely bypass cross-origin connection blocks
            val streamHeaders = mapOf(
                "Referer" to "https://tellygossips.net",
                "Origin" to "https://flow.tellygossips.net",
                "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:139.0) Gecko/20100101 Firefox/139.0"
            )

            for (link in config.sources) {
                val videoUrl = link.file ?: link.src ?: continue
                
                callback(
                    newExtractorLink(
                        name = "$name $source", // Dynamic display string shown in app interface rows
                        source = this.name,     // Sync core key targeting internal cloudstream registry
                        url = videoUrl,
                        type = ExtractorLinkType.M3U8
                    ) {
                        this.referer = "https://tellygossips.net"
                        this.quality = Qualities.Unknown.value
                        this.headers = streamHeaders
                    }
                )
            }
        } catch (e: Exception) {
            Log.e("Tellygossips", "Extraction failed: ${e.message}")
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
