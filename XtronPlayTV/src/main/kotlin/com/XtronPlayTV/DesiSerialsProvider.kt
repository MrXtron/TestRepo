package com.XtronPlayTV

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.mvvm.amap
import org.jsoup.nodes.Element
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.getAndUnpack
import com.lagradost.cloudstream3.ui.settings.Globals.TV
import com.lagradost.cloudstream3.ui.settings.Globals.isLayout
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper

class DesiSerialsProvider : MainAPI() {
    override var mainUrl = "https://desi-serials.to"
    override var name = "DesiSerials"
    override val hasMainPage = true
    override var lang = "hi"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(
        TvType.TvSeries
    )

    override val mainPage = mainPageOf(
        "latest-episodes" to "Latest Episodes",
        "and-tv" to "& TV",
        "color-tv-hd" to "Colors TV",
        "sab-tv-hd" to "Sab TV",
        "sony-tv" to "Sony TV",
        "star-bharat" to "Star Bharat",
        "star-plus-hdepisodes" to "Star Plus",
        "zee-tv" to "Zee TV"
    )

    // High-quality channel thumbnails directly mapped from resource nodes to prevent layout blanks
    private val channelLogos = mapOf(
        "and-tv" to "https://desi-serials.to/wp-content/uploads/2020/08/And-Tv.jpg",
        "color-tv-hd" to "https://desi-serials.to/wp-content/uploads/2020/08/Colors-Tv.jpg",
        "sab-tv-hd" to "https://desi-serials.to/wp-content/uploads/2020/08/Sab-Tv.jpg",
        "sony-tv" to "https://desi-serials.to/wp-content/uploads/2020/08/Sony-Tv.jpg",
        "star-bharat" to "https://desi-serials.to/wp-content/uploads/2020/08/Star-Bharat.jpg",
        "star-plus-hdepisodes" to "https://desi-serials.to/wp-content/uploads/2025/08/Anupamaa.jpg", 
        "zee-tv" to "https://desi-serials.to/wp-content/uploads/2020/08/Zee-Tv.jpg"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val targetPath = if (page == 1) request.data else "${request.data}/page/$page/"
        val cleanPath = targetPath.replace(mainUrl, "").replace("//", "/").trimStart('/')
        val url = "${XtronPlayTVPlugin.proxy}/?url=$mainUrl/$cleanPath"
        
        val document = app.get(url).document
        val home = mutableListOf<SearchResponse>()

        // 1. Directly parse regular grid items with native images to ensure instant loading
        val regularPosts = document.select("article.type-post, article.post-grid, .porto-sicon-wrapper")
        regularPosts.forEach {
            it.toSearchResult()?.let { response -> home.add(response) }
        }

        // 2. OPTIMIZED JUGAD: Map text-only nodes instantly using fallbacks to solve latency bottlenecks
        val fallbackLogo = channelLogos.entries.firstOrNull { request.data.contains(it.key) }?.value ?: ""
        val completedItems = document.select("li.cat-item")
        
        completedItems.forEach { item ->
            val titleElement = item.selectFirst("a")
            val href = titleElement?.attr("href")?.let { this.fixUrl(it) }
            val title = titleElement?.text()?.trim() ?: "Unknown Series"
            
            if (!href.isNullOrBlank()) {
                home.add(
                    newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
                        this.posterUrl = fallbackLogo
                        this.posterHeaders = mapOf(
                            "referer" to "$mainUrl/",
                            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36"
                        )
                    }
                )
            }
        }

        if (home.isEmpty()) {
            val docTitle = document.title().ifBlank { "No Title" }
            val firstText = document.text().take(50)
            home.add(newTvSeriesSearchResponse("Debug: $docTitle | $firstText", url, TvType.TvSeries) {
                this.posterUrl = ""
            })
        }

        return newHomePageResponse(arrayListOf(HomePageList(request.name, home, isHorizontalImages = true)), hasNext = home.isNotEmpty())
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val titleElement = this.selectFirst("h3.thumb-info-inner a, h2.entry-title a, h5 a.porto-sicon-title-link, h3.porto-post-title a") ?: this.selectFirst("a") ?: return null
        val title = titleElement.text().trim().takeIf { it.isNotBlank() } ?: titleElement.attr("title").trim().takeIf { it.isNotBlank() } ?: "Unknown Series"
        val href = fixUrl(titleElement.attr("href"))
        
        val imgElement = this.selectFirst("div.post-image img, span.post-image img") ?: this.selectFirst("img")
        var rawPoster = imgElement?.attr("data-oi")
        if (rawPoster.isNullOrBlank()) {
            rawPoster = imgElement?.attr("src")
        }
        val posterUrl = fixUrlNull(rawPoster)

        return newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
            this.posterUrl = if (!posterUrl.isNullOrBlank() && !posterUrl.startsWith(XtronPlayTVPlugin.proxy)) {
                "${XtronPlayTVPlugin.proxy}/?url=$posterUrl"
            } else {
                posterUrl
            }
            this.posterHeaders = mapOf(
                "referer" to "$mainUrl/",
                "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36"
            )
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val encodedQuery = query.replace(" ", "+").lowercase()
        val searchUrl = "${XtronPlayTVPlugin.proxy}/?url=$mainUrl&s=$encodedQuery"
        val document = app.get(searchUrl).document
        val results = document.select("div.post-item, article.type-post, article.post-grid, article.post")
        
        return results.mapNotNull {
            it.toSearchResult()
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        val proxiedUrl = if (!url.startsWith(XtronPlayTVPlugin.proxy)) {
            "${XtronPlayTVPlugin.proxy}/?url=$url"
        } else {
            url
        }
        val doc = app.get(proxiedUrl).document
        
        val title = doc.selectFirst("h1.page-title")?.text()?.trim() 
            ?: doc.selectFirst("h2.heading-primary")?.text()?.trim()
            ?: doc.selectFirst("h1.entry-title")?.text()?.trim()
            ?: return null

        val rawPoster = doc.selectFirst("div[style*=\"float: right\"] img, div.page-image img")?.attr("src")
        val poster = if (!rawPoster.isNullOrBlank() && !rawPoster.startsWith(XtronPlayTVPlugin.proxy)) {
            "${XtronPlayTVPlugin.proxy}/?url=$rawPoster"
        } else {
            rawPoster
        }

        val episodes = mutableListOf<Episode>()
        var currentPage = 1
        var hasNextPage = true
        val cleanBaseUrl = url.trimEnd('/')

        while (hasNextPage) {
            val pageUrl = if (currentPage == 1) {
                "${XtronPlayTVPlugin.proxy}/?url=$cleanBaseUrl/"
            } else {
                "${XtronPlayTVPlugin.proxy}/?url=$cleanBaseUrl/page/$currentPage/"
            }

            try {
                val pageDoc = if (currentPage == 1) doc else app.get(pageUrl).document
                val posts = pageDoc.select("article.type-post")
                
                if (posts.isEmpty()) {
                    hasNextPage = false
                    break
                }

                posts.forEach { element ->
                    val a = element.selectFirst("h3.thumb-info-inner a, h2.entry-title a")
                    if (a != null) {
                        val epHref = fixUrl(a.attr("href"))
                        val epTitle = a.text().trim()
                        if (epHref != url) {
                            episodes.add(newEpisode(data = epHref) {
                                name = epTitle
                                this.posterUrl = poster
                            })
                        }
                    }
                }

                val nextButton = pageDoc.selectFirst("a.next.page-numbers")
                if (nextButton != null) {
                    currentPage++
                } else {
                    hasNextPage = false
                }
            } catch (_: Exception) {
                hasNextPage = false
            }
        }

        if (episodes.isEmpty()) {
            episodes.add(
                newEpisode(data = url) {
                    name = title
                    season = 1
                    episode = 1
                    this.posterUrl = poster
                }
            )
        }

        return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes.reversed()) {
            this.posterUrl = poster?.trim()
            this.posterHeaders = mapOf("referer" to "$mainUrl/")
        }
    }
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {

        suspend fun handleIframe(url: String, referer: String, targetPlayerName: String) {
            if (url.contains("desi-snation") || url.contains("tvarticles") || url.contains("desi-serials") || url.contains("bolly")) {
                val secureIframeUrl = if (!url.startsWith(XtronPlayTVPlugin.proxy)) {
                    "${XtronPlayTVPlugin.proxy}/?url=$url"
                } else {
                    url
                }
                val vidResponse = app.get(secureIframeUrl, referer = referer)
                val vidText = vidResponse.text
                val vidDoc = vidResponse.document
                val nestedIframes = vidDoc.select("iframe[src]")
                
                // Track redirects explicitly matching Tvlogy infrastructure routing
                if (vidText.contains("flow.tvlogy") || vidText.contains("tvlogy.to") || nestedIframes.any { it.attr("src").contains("tvlogy") }) {
                    val finalNestedUrl = nestedIframes.firstOrNull { it.attr("src").contains("tvlogy") }?.attr("src") 
                        ?: Regex("""src"\s*:\s*"([^"]+)""").find(vidText)?.groupValues?.getOrNull(1) ?: url
                    
                    val absoluteNestedUrl = if (finalNestedUrl.startsWith("//")) "https:$finalNestedUrl" else finalNestedUrl
                    Tvlogyflow(targetPlayerName).getUrl(absoluteNestedUrl, url, subtitleCallback, callback)
                    return
                }

                // Dedicated execution block routing unpacked SpeedWatch streams directly to link callbacks
                if (targetPlayerName == "SpeedWatch" || url.contains("speedwatch")) {
                    try {
                        val base64Match = Regex("""JuicyCodes\.Run\s*\(\s*["']([^"']+)["']\s*\)""").find(vidText)
                        if (base64Match != null) {
                            val base64Code = base64Match.groupValues[1]
                            val decodedStr = String(android.util.Base64.decode(base64Code, android.util.Base64.DEFAULT))
                            val unpacked = getAndUnpack(decodedStr)
                            val m3u8Regex = Regex("""(https?://[^"']+\.m3u8[^"']*)""")
                            val m3u8Links = m3u8Regex.findAll(unpacked).map { it.groupValues[1] }.distinct().toList()
                            
                            m3u8Links.forEach { source ->
                                callback.invoke(
                                    newExtractorLink(targetPlayerName, targetPlayerName, source, type = ExtractorLinkType.M3U8) {
                                        this.referer = url
                                        this.quality = Qualities.Unknown.value
                                    }
                                )
                            }
                            return
                        }
                    } catch (_: Exception) {}
                }

                // Structured fallback expression matching universal direct video streams
                val videoRegex = Regex("""(https?://[^"']+\.(?:m3u8|mp4)[^"']*)""")
                val sources = videoRegex.findAll(vidText).map { it.groupValues[1] }.distinct().toList()
                
                if (sources.isNotEmpty()) {
                    sources.forEach { source ->
                        val isM3u8 = source.contains(".m3u8")
                        callback.invoke(
                            newExtractorLink(targetPlayerName, targetPlayerName, source, type = if (isM3u8) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO) {
                                this.referer = url
                                this.quality = if (isM3u8) Qualities.Unknown.value else Qualities.P720.value
                            }
                        )
                    }
                } else {
                    loadExtractor(url, referer, subtitleCallback, callback)
                }
            } else {
                loadExtractor(url, referer, subtitleCallback, callback)
            }
        }

        if (data.startsWith("http")) {
            val secureDataUrl = if (!data.startsWith(XtronPlayTVPlugin.proxy)) {
                "${XtronPlayTVPlugin.proxy}/?url=$data"
            } else {
                data
            }
            val doc = app.get(secureDataUrl).document
            
            // DYNAMIC PARSING: Maps label nodes sequentially to bypass hidden layout drops
            val paragraphs = doc.select("div.entry-content p")
            var activePlayerName = "Tvlogy"

            paragraphs.forEach { p ->
                val text = p.text().trim()
                if (text.contains("Online Links", ignoreCase = true)) {
                    activePlayerName = when {
                        text.contains("Flash", ignoreCase = true) -> "Flash Player"
                        text.contains("Dailymotion", ignoreCase = true) -> "Dailymotion"
                        text.contains("NetFlix", ignoreCase = true) -> "NetFlix"
                        text.contains("SpeedWatch", ignoreCase = true) -> "SpeedWatch"
                        text.contains("VkPrime", ignoreCase = true) -> "VkPrime"
                        else -> "Tvlogy"
                    }
                }
                
                val aLink = p.selectFirst("a[href]")?.attr("href")
                if (!aLink.isNullOrBlank() && (aLink.contains("tvarticles") || aLink.contains("desi") || aLink.contains("speedwatch") || aLink.contains("vkprime"))) {
                    handleIframe(aLink, data, activePlayerName)
                }
            }
        } else if (data.startsWith("[")) {
            try {
                val links = parseJson<List<String>>(data)
                links.amap { link ->
                    handleIframe(link, "$mainUrl/", "Tvlogy")
                }
            } catch (_: Exception) {}
        }
        return true
    }
}
