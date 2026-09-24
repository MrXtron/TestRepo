package com.XtronPlayTV

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.base64Decode
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.INFER_TYPE
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink

class Tvlogyflow(val source: String) : ExtractorApi() {
    override val mainUrl = "https://flow.tvlogy.to"
    override val name = "Tvlogy"
    override val requiresReferer = false

    private val proxyUrl = "https://proxy.phisher2.workers.dev/?url="

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {

        fun extractJuicy(doc: String): String? {
            return try {
                val juicy = Regex("""JuicyCodes\.Run\("(.*?)"\);""")
                    .find(doc)?.groupValues?.getOrNull(1)
                    ?: return null

                val encoded = juicy
                    .replace("\"", "")
                    .replace("+", "")
                    .replace("\\s".toRegex(), "")

                val decoded = base64Decode(encoded)
                var unpacked = decoded

                // Standardized character-escape expression to safely parse packed javascript structures
                val packedMatch = Regex("\\}\\('(.*)',\\d+,\\d+,'(.*)'\\.split")
                    .find(decoded)

                if (packedMatch != null) {
                    // Extract payload values cleanly using explicit string array array indices
                    val payload = packedMatch.groupValues[1]
                    val symtab = packedMatch.groupValues[2].split("|")

                    unpacked = Regex("""\b(\w+)\b""")
                        .replace(payload) { match ->
                            val index = match.value.toIntOrNull(36)
                            if (index != null && index < symtab.size) symtab[index] else match.value
                        }
                }

                Regex("""file":\s*"(.*?)"""")
                    .find(unpacked)
                    ?.groupValues?.getOrNull(1)

            } catch (_: Exception) {
                null
            }
        }

        fun extractDirect(doc: String): String? {
            return Regex(""""src"\s*:\s*"(https?://.*?\.m3u8.*?)"""")
                .find(doc)
                ?.groupValues
                ?.getOrNull(1)
        }

        // Dynamic name detection based on the URL path sub-strings
        val displayName = when {
            url.contains("embed020A") -> "Flash Player"
            url.contains("plyr020A") -> "Dailymotion"
            url.contains("nflix020A") -> "NetFlix"
            else -> this.name // Fallback to "Tvlogy" if pattern doesn't match
        }

        suspend fun process(doc: String): Boolean {
            val direct = extractDirect(doc)
            if (!direct.isNullOrEmpty()) {
                callback(
                    newExtractorLink(
                        "$displayName $source",
                        displayName, // Set the custom source name dynamically here
                        direct,
                        type = INFER_TYPE
                    ) {
                        this.referer = mainUrl
                        this.quality = Qualities.P720.value
                    }
                )
                return true
            }

            val juicy = extractJuicy(doc)
            if (!juicy.isNullOrEmpty()) {
                callback(
                    newExtractorLink(
                        "$displayName $source",
                        displayName, // Set the custom source name dynamically here
                        juicy,
                        type = INFER_TYPE
                    ) {
                        this.referer = mainUrl
                        this.quality = Qualities.P720.value
                    }
                )
                return true
            }

            return false
        }

        try {
            val directDoc = app.get(url, referer = mainUrl).text
            if (process(directDoc)) return
        } catch (_: Exception) {}

        try {
            val proxyDoc = app.get("$proxyUrl$url", referer = mainUrl).text
            process(proxyDoc)
        } catch (_: Exception) {}
    }
}

class Tvlogy(private val source: String) : ExtractorApi() {
    override val mainUrl = "https://tvlogy.to"
    override val name = "Tvlogy"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val id = url.substringAfter("data=")
        val data = mapOf(
            "hash" to id,
            "r" to "http%3A%2F%2Ftellygossips.net%2F"
        )
        val headers = mapOf("X-Requested-With" to "XMLHttpRequest")
        
        // Wrap dynamic asynchronous post requests safely using the centralized proxy network tunnel
        val ajaxTargetUrl = "$url&do=getVideo"
        val securePostUrl = if (!ajaxTargetUrl.startsWith(XtronPlayTVPlugin.proxy)) {
            "${XtronPlayTVPlugin.proxy}/?url=$ajaxTargetUrl"
        } else {
            ajaxTargetUrl
        }

        val meta = app.post(securePostUrl, headers = headers, referer = referer, data = data)
            .parsedSafe<MetaData>() ?: return

        // Dynamic name detection for the secondary Tvlogy ajax class
        val displayName = when {
            url.contains("embed020A") -> "Flash Player"
            url.contains("plyr020A") -> "Dailymotion"
            url.contains("nflix020A") -> "NetFlix"
            else -> this.name
        }

        callback(
            newExtractorLink(
                "$displayName $source",
                displayName, // Set the custom source name dynamically here
                url = meta.videoSource,
                ExtractorLinkType.M3U8
            ) {
                this.referer = url
                this.quality = Qualities.Unknown.value
            }
        )
    }

    data class MetaData(
        val hls: Boolean,
        val videoSource: String
    )
}
