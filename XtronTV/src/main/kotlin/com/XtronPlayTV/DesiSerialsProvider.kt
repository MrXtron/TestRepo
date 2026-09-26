package com.XtronTV

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element
import org.jsoup.nodes.Document
import com.lagradost.cloudstream3.utils.AppUtils.parseJson

open class DesiSerialsProvider : MainAPI() {

    override var mainUrl = "https://desi-serials.to"
    override var name = "XtronTV (Source 1)"
    override val hasMainPage = true
    override var lang = "hi"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(
        TvType.TvSeries
    )

    // Using the list of proxies from XtronTVPlugin to generate dynamic entry points
    override val mainPage = mainPageOf(
        "latest-episodes" to "Latest Episodes",
        "and-tv" to "& TV",
        "color-tv-hd" to "Colors TV",
        "sab-tv-hd" to "Sab TV",
        "sony-tv" to "Sony TV",
        "star-bharat" to "Star Bharat",
        "star-plus-hdepisodes" to "Star Plus",
        "zee-tv" to "Zee TV",
        "news-and-promos" to "News & Promos"
    )

    // Robust HTML failover mechanism rotating through available proxies
    private suspend fun getProxyDocument(
        targetUrl: String,
        refererUrl: String = "$mainUrl/"
    ): Document {
        var lastError: Exception? = null

        for (proxy in XtronTVPlugin.proxy) {
            try {
                return app.get(
                    "$proxy/?url=$targetUrl",
                    referer = refererUrl,
                    headers = mapOf(
                        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
                        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8"
                    ),
                    timeout = 10000
                ).document
            } catch (e: Exception) {
                lastError = e
            }
        }

        throw lastError ?: Exception("All proxies failed")
    }

    // Secure proxy wrapper for static assets like images to bypass network blocks
    private fun proxyImage(url: String?): String? {
        if (url.isNullOrBlank()) return url

        val firstProxy = XtronTVPlugin.proxy.firstOrNull() ?: return url

        return if (!url.startsWith(firstProxy)) {
            "$firstProxy/?url=$url"
        } else {
            url
        }
    }

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val subPath = if (page == 1) request.data else "${request.data.trimEnd('/')}/page/$page/"
        val targetUrl = "$mainUrl/$subPath"

        // Route connection through the proxy fallback engine
        val document = getProxyDocument(targetUrl)
        
        android.util.Log.d("DesiSerials", "getMainPage url=$targetUrl title=${document.title()}")

        // Scrapes the unique top category banner from the current page layout dynamically
        val categoryFallbackBanner = document.selectFirst("div.page-image img")?.attr("data-src")
            ?: document.selectFirst("div.page-image img")?.attr("src")
            ?: document.select("meta[property=og:image]").attr("content")
            ?: ""
        
        val posts = document.select("article.type-post, article.post-grid, .porto-sicon-wrapper, li.cat-item")
        
        val home = posts.mapNotNull {
            // Feeds the dynamic background banner downward to fill empty thumbnail slots
            it.toSearchResult(categoryFallbackBanner)
        }.toMutableList()


        if (home.isEmpty()) {
            val docTitle = document.title().ifBlank { "No Title" }
            val firstText = document.text().take(50)
            home.add(newTvSeriesSearchResponse("Debug: $docTitle | $firstText", targetUrl, TvType.TvSeries) {
                this.posterUrl = ""
            })
        }

        return newHomePageResponse(arrayListOf(HomePageList(request.name, home, isHorizontalImages = true)), hasNext = home.isNotEmpty())
    }

    // Function signature updated with fallback string parameter
    private fun Element.toSearchResult(fallbackPoster: String = ""): SearchResponse? {
        val titleElement = this.selectFirst("h3.thumb-info-inner a, h2.entry-title a, h5 a.porto-sicon-title-link, h3.porto-post-title a") ?: this.selectFirst("a") ?: return null
        val title = titleElement.text().trim().takeIf { it.isNotBlank() } ?: titleElement.attr("title").trim().takeIf { it.isNotBlank() } ?: "Unknown Series"
        val href = fixUrl(titleElement.attr("href"))
        
        val imgElement = this.selectFirst("div.post-image img, span.post-image img") ?: this.selectFirst("img")
        var posterUrl = imgElement?.attr("data-oi")
        if (posterUrl.isNullOrBlank()) {
            posterUrl = imgElement?.attr("src")
        }

        // Binds the dynamic category asset link when individual show thumbnail is missing
        if (posterUrl.isNullOrBlank() && fallbackPoster.isNotBlank()) {
            posterUrl = fallbackPoster
        }
        
        posterUrl = fixUrlNull(posterUrl)


        return newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
            // Wrapped with proxyImage to fix broken rendering
            this.posterUrl = proxyImage(posterUrl)
            this.posterHeaders = mapOf(
                "referer" to "$mainUrl/",
                "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
            )
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val encodedQuery = query.replace(" ", "+").lowercase()
        val searchUrl = "$mainUrl/?s=$encodedQuery"
        
        val document = getProxyDocument(searchUrl)
        val results = document.select("div.post-item, article.type-post, article.post-grid, article.post")
        
        return results.mapNotNull {
            it.toSearchResult()
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        val doc = getProxyDocument(url)
        
        val title = doc.selectFirst("h1.page-title")?.text()?.trim() 
            ?: doc.selectFirst("h2.heading-primary")?.text()?.trim()
            ?: doc.selectFirst("h1.entry-title")?.text()?.trim()
            ?: return null

        val posterRegex = Regex("(https?:\\/\\/(www\\.)?[-a-zA-Z0-9@:%._\\+~#=]{1,256}\\.[a-zA-Z0-9()]{1,6}\\b([-a-zA-Z0-9()@:%_\\+.~#?&\\/\\/=]*jpg))")
        val posterRaw = doc.selectFirst("div.page-image img")?.attr("src") ?: doc.html()
        val poster = posterRegex.find(posterRaw)?.value?.trim()
        val securePoster = proxyImage(poster)

        val episodes = mutableListOf<Episode>()
        // 1. Extract the maximum page number using the correct 'doc' variable reference
        val lastPageNumber = doc.select("div.pagination a.page-numbers")
            .mapNotNull { element -> element.text().toIntOrNull() }
            .maxOrNull() 
            ?: doc.select("ul.page-numbers a.page-numbers, .next.page-numbers")
                .mapNotNull { element -> element.text().toIntOrNull() }
                .maxOrNull() 
            ?: 1

        // 2. Iterate through all discovered subpages sequentially to extract episodes
        for (page in 1..lastPageNumber) {
            val pageDoc = if (page == 1) doc else getProxyDocument("${url.trimEnd('/')}/page/$page/")
            
            pageDoc.select("article.post, article.type-post, div.post-item, .post-grid").forEach { element ->
                val a = element.selectFirst("h2.entry-title a, h3.thumb-info-inner a, h3.porto-post-title a")
                if (a != null) {
                    val epHref = fixUrl(a.attr("href"))
                    if (epHref != url) {
                        episodes.add(newEpisode(data = epHref) {
                            name = a.text().trim()
                            this.posterUrl = securePoster
                        })
                    }
                }
            }
        }

        if (episodes.isEmpty()) {
            episodes.add(
                newEpisode(data = url) {
                    name = title
                    season = 1
                    episode = 1
                    this.posterUrl = securePoster
                }
            )
        }

        return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes.reversed()) {
            this.posterUrl = securePoster
            this.posterHeaders = mapOf(
                "referer" to "$mainUrl/",
                "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
            )
        }
    }
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        if (!data.startsWith("http")) {
            if (data.startsWith("[")) {
                try {
                    val links = parseJson<List<String>>(data)
                    links.amap { link ->
                        handleIframe(link, "$mainUrl/", subtitleCallback, callback)
                    }
                } catch (e: Exception) {
                    android.util.Log.d("DesiSerials", "loadLinks JSON parse error: " + e.message)
                }
            }
            return true
        }

        // Fetching structural documents inside loadLinks using the proxy pool logic
        val doc = getProxyDocument(data)
        val aLinks = doc.select("div.entry-content p a[href]")
        
        for (element in aLinks) {
            val href = element.attr("href")
            if (href.contains("desi-snation") || href.contains("tvarticles") || href.contains("desi-serials") || 
                href.contains("bolly") || href.contains("dai.ly") || href.contains("dailymotion.com") || 
                href.contains("vkprime") || href.contains("speedwatch") || href.contains("vkspeed")) {
                
                // Navigate HTML siblings to extract server names from the preceding bold tag
                val parentParagraph = element.parent()
                val previousParagraph = parentParagraph?.previousElementSibling()
                
                val extractedName = previousParagraph?.selectFirst("b")?.text()
                    ?.replace("720p HD Quality Online Links", "")
                    ?.replace("Quality Online Links", "")
                    ?.trim()
                    ?.takeIf { it.isNotBlank() } 
                    ?: "XtronTV"
                
                handleIframe(href, data, extractedName, subtitleCallback, callback)
            }
        }

        val iframes = doc.select("iframe[src]").map { it.attr("src") }
        iframes.distinct().forEach {
            handleIframe(it, data, "XtronTV", subtitleCallback, callback)
        }

        return true
    }

    private suspend fun handleIframe(
        href: String, 
        referer: String,
        sourceName: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val url = if (href.startsWith("//")) "https:$href" else href
        
        if (url.contains("desi-snation") || url.contains("tvarticles") || url.contains("desi-serials") || url.contains("bolly")) {
            
            // Using proxy document pipeline inside the parsing structure block
            val vidDoc = getProxyDocument(url, refererUrl = referer)
            val nestedIframes = vidDoc.select("iframe[src]")
            val nestedMatches = nestedIframes.map { it.attr("src") }
            
            nestedMatches.forEach { nestedSrc ->
                val fullNestedUrl = if (nestedSrc.startsWith("//")) "https:$nestedSrc" else nestedSrc
                
                if (fullNestedUrl.contains("flow.tvlogy")) {
                    try {
                        // Hand off execution straight to the modular Tvlogyflow extractor script
                        Tvlogyflow(sourceName).getUrl(fullNestedUrl, url, subtitleCallback, callback)
                    } catch (e: Exception) {
                        loadExtractor(fullNestedUrl, subtitleCallback, callback)
                    }
                }
                else if (fullNestedUrl.contains("speedwatch")) {
                    try {
                        val playerHtml = getProxyDocument(fullNestedUrl, refererUrl = url).html()
                        val base64Match = Regex("""JuicyCodes\.Run\s*\(\s*["']([^"']+)["']\s*\)""").find(playerHtml)
                        if (base64Match != null) {
                            val base64Code = base64Match.groupValues[1]
                            val decodedStr = String(android.util.Base64.decode(base64Code, android.util.Base64.DEFAULT))
                            val unpacked = getAndUnpack(decodedStr)
                            val m3u8Regex = Regex("""(https?://[^"']+\.m3u8[^"']*)""")
                            val m3u8Links = m3u8Regex.findAll(unpacked).map { it.groupValues[1] }.distinct().toList()
                            m3u8Links.forEach { source ->
                                callback.invoke(
                                    newExtractorLink(
                                        "SpeedWatch",
                                        "SpeedWatch",
                                        source,
                                        type = ExtractorLinkType.M3U8
                                    ) {
                                        this.referer = fullNestedUrl
                                        this.quality = Qualities.Unknown.value
                                    }
                                )
                            }
                        } else {
                            loadExtractor(fullNestedUrl, subtitleCallback, callback)
                        }
                    } catch (e: Exception) {
                        loadExtractor(fullNestedUrl, subtitleCallback, callback)
                    }
                } else if (fullNestedUrl.contains("vkprime") || fullNestedUrl.contains("vkspeed")) {
                    try {
                        val playerHtml = getProxyDocument(fullNestedUrl, refererUrl = url).html()
                        val unpacked = getAndUnpack(playerHtml)
                        
                        val videoRegex = Regex("""(https?://[^"']+\.(?:mp4|m3u8)[^"']*)""")
                        val videoLinks = videoRegex.findAll(unpacked).map { it.groupValues[1] }.toList()
                        
                        if (videoLinks.isNotEmpty()) {
                            videoLinks.forEach { source ->
                                val isM3u8 = source.contains(".m3u8")
                                callback.invoke(
                                    newExtractorLink(
                                        "VkPrime",
                                        "VkPrime",
                                        source,
                                        type = if (isM3u8) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                                    ) {
                                        this.referer = fullNestedUrl
                                        this.quality = Qualities.P720.value
                                    }
                                )
                            }
                        } else {
                            loadExtractor(fullNestedUrl, subtitleCallback, callback)
                        }
                    } catch (e: Exception) {
                        loadExtractor(fullNestedUrl, subtitleCallback, callback)
                    }
                } else {
                    loadExtractor(fullNestedUrl, subtitleCallback, callback)
                }
            }
        } else {
            loadExtractor(url, subtitleCallback, callback)
        }
    }
}
