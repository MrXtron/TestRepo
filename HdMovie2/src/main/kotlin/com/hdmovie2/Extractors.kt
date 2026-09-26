package com.hdmovie2

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.AppUtils
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.getQualityFromName
import com.lagradost.cloudstream3.utils.newExtractorLink
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.Locale

class HDm2 : ExtractorApi() {
    override var name = "Ultra Stream V3"
    // Synced flawlessly with updated doofast theme routing rules
    override var mainUrl = "https://hdm2.biz"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val requestHeaders = mapOf(
            "user-agent" to "okhttp/4.12.0"
        )

        try {
            val pageMarkup = app.get(url, headers = requestHeaders).text
            // Scrapes raw parameter tokens out of template stream values dynamically
            val regex = Regex("data-stream-url=[\"'](.*?)[\"\']")
            val match = regex.find(pageMarkup)?.groupValues?.getOrNull(1) ?: return

            val safe = safeUrl(match)
            val finalStreamUrl = if (safe.startsWith("http")) safe else "https:$safe"

            val m3u8Links = com.lagradost.cloudstream3.utils.M3u8Helper.generateM3u8(
                this.name,
                finalStreamUrl,
                url,
                headers = mapOf(
                    "Referer" to "https://playhydrax.com/",
                    "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:137.0) Gecko/20100101 Firefox/137.0"
                )
            )
            
            m3u8Links.forEach { callback(it) }
        } catch (_: Throwable) {}
    }

    private fun safeUrl(raw: String): String {
        val cleaned = raw.replace("&amp;", "&")
        if (!cleaned.contains("?")) return cleaned
        val base = cleaned.substringBefore("?")
        val matchResult = Regex("[?&]tok=([^&]+)").find(cleaned)
        val token = matchResult?.groupValues?.getOrNull(1)
        
        return if (!token.isNullOrBlank()) "$base?tok=$token" else cleaned
    }
}

class Abyass : ExtractorApi() {
    override var name = "Abyass"
    // Dynamic matching framework linked to the script updates engine
    override var mainUrl = "https://hunts439kow.com"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val requestHeaders = mapOf(
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:137.0) Gecko/20100101 Firefox/137.0",
            "Origin" to "https://playhydrax.com/",
            "Referer" to "https://playhydrax.com/"
        )

        try {
            val pageMarkup = app.get(url, headers = requestHeaders).text
            
            // Re-mapped line-by-line from decompiler output to catch the text block
            val dataRegex = Regex("const\\s+datas\\s*=\\s*\"([^\"]*)\"")
            val encryptedData = dataRegex.find(pageMarkup)?.groupValues?.getOrNull(1) ?: return

            // Constructing standard json envelope required by the remote enc-dec endpoint
            val jsonBody = """{"text": "$encryptedData"}""".trimIndent()
            val mediaType = "application/json".toMediaType()
            
            // FIXED: Explicitly hitting the correct live sub-route API found in decompiler mappings
            val apiResponse = app.post(
                "https://enc-dec.app/api/dec-abyss",
                requestBody = jsonBody.toRequestBody(mediaType)
            ).text

            val response = AppUtils.parseJson<AbyssResponse>(apiResponse)
            response.result.sources.filter { it.status }.forEach { sourceElement ->
                val qualityName = "${this.name} [${sourceElement.codec.lowercase(Locale.ROOT).uppercase(Locale.ROOT)}]"
                
                val extractorLink = newExtractorLink(
                    source = qualityName,
                    name = qualityName,
                    url = sourceElement.url,
                    type = com.lagradost.cloudstream3.utils.ExtractorLinkType.M3U8
                ) {
                    this.headers = mapOf(
                        "Referer" to "https://playhydrax.com/",
                        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:137.0) Gecko/20100101 Firefox/137.0"
                    )
                    this.quality = getQualityFromName(sourceElement.type)
                }
                callback(extractorLink)
            }
        } catch (_: Throwable) {}
    }

    data class AbyssResponse(
        @JsonProperty("status") val status: Long,
        @JsonProperty("result") val result: Result
    )

    data class Result(
        @JsonProperty("sources") val sources: List<AbyssSource>
    )

    data class AbyssSource(
        @JsonProperty("url") val url: String,
        @JsonProperty("size") val size: Long,
        @JsonProperty("type") val type: String,
        @JsonProperty("codec") val codec: String,
        @JsonProperty("status") val status: Boolean
    )
}
