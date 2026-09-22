package com.XtronPlayTV

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.*
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

    override var mainUrl = "https://www.desi-serials.to"
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

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val targetPath = if (page == 1) "${request.data}/" else "${request.data}/page/$page/"
        val url = "${XtronPlayTVPlugin.proxy}/?url=$mainUrl/$targetPath"
        val document = app.get(url).document
        
        android.util.Log.d("DesiSerials", "getMainPage url=$url title=${document.title()}")
        
        // Use a more specific article selector to avoid matching the main page container
        val posts = document.select("article.type-post, article.post-grid, .porto-sicon-wrapper, li.cat-item")
        android.util.Log.d("DesiSerials", "getMainPage posts=${posts.size}")
        
        val home = posts.mapNotNull {
            it.toSearchResult()
        }.toMutableList()
        android.util.Log.d("DesiSerials", "getMainPage home=${home.size}")

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
        var posterUrl = imgElement?.attr("data-oi")
        if (posterUrl.isNullOrBlank()) {
            posterUrl = imgElement?.attr("src")
        }
        posterUrl = fixUrlNull(posterUrl)

        return newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
            this.posterUrl = posterUrl
            this.posterHeaders = mapOf("referer" to "$mainUrl/")
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        
        val encodedQuery = query.replace(" ", "+").lowercase()
        val searchUrl = "${XtronPlayTVPlugin.proxy}/?url=$mainUrl/?s=$encodedQuery"
        val document = app.get(searchUrl).document
        
        // Search results use div.post-item with h3.porto-post-title
        val results = document.select("div.post-item, article.type-post, article.post-grid, article.post")
        
        return results.mapNotNull {
            it.toSearchResult()
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        
        val doc = app.get("${XtronPlayTVPlugin.proxy}/?url=$url").document
        
        val title = doc.selectFirst("h1.page-title")?.text()?.trim() 
            ?: doc.selectFirst("h2.heading-primary")?.text()?.trim()
            ?: doc.selectFirst("h1.entry-title")?.text()?.trim()
            ?: return null

        val posterRegex = Regex("(https?:\\/\\/(www\\.)?[-a-zA-Z0-9@:%._\\+~#=]{1,256}\\.[a-zA-Z0-9()]{1,6}\\b([-a-zA-Z0-9()@:%_\\+.~#?&\\/\\/=]*jpg))")
        val posterRaw = doc.selectFirst("div.page-image img")?.attr("src") ?: doc.html()
        val poster = posterRegex.find(posterRaw)?.value?.trim()

        val episodes = mutableListOf<Episode>()
        
        // 1. Check if it's a list of episodes (Index page)
        val posts = doc.select("article.type-post")
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

        // 2. Single episode page - use the URL itself as the episode data
        //    loadLinks will handle extracting iframes and a[href] links from the page
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

    private fun <T, R : Any> Iterable<T>.mapNotBlank(transform: (T) -> R?): List<R> {
        return mapNotNull(transform).filter { 
            val s = it.toString()
            s.isNotBlank() && s != "null"
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        android.util.Log.d("DesiSerials", "loadLinks data: " + data)

        suspend fun handleIframe(href: String, referer: String) {
            val url = if (href.startsWith("//")) "https:$href" else href
            
            if (url.contains("desi-snation") || url.contains("tvarticles") || url.contains("desi-serials") || url.contains("bolly")) {
                // Proprietary iframe wrapper, we need to dig deeper
                android.util.Log.d("DesiSerials", "handleIframe fetch wrapper: $url")
                val vidResponse = app.get("${XtronPlayTVPlugin.proxy}/?url=$url", referer = referer)
                val vidText = vidResponse.text
                
                val vidDoc = vidResponse.document
                val nestedIframes = vidDoc.select("iframe[src]")
                val nestedMatches = nestedIframes.map { it.attr("src") }
                android.util.Log.d("DesiSerials", "handleIframe nested iframes: ${nestedMatches.size}")
                
                nestedMatches.forEach { nestedSrc ->
                    val nestedUrl = nestedSrc
                    val fullNestedUrl = if (nestedUrl.startsWith("//")) "https:$nestedUrl" else nestedUrl
                    android.util.Log.d("DesiSerials", "handleIframe found nestedUrl: $fullNestedUrl")
                    
                    if (fullNestedUrl.contains("flow.tvlogy") || fullNestedUrl.contains("tvlogy.to")) {
                        try {
                            Tvlogyflow(this.name).getUrl(fullNestedUrl, url, subtitleCallback, callback)
                        } catch (e: Exception) {
                            android.util.Log.d("DesiSerials", "Tvlogyflow delegation failed: ${e.message}")
                            loadExtractor(fullNestedUrl, subtitleCallback, callback)
                        }
                    } else {
                        // Built-in extractors automatically handle speedwatch, vkprime, and other video hosts safely via dynamic engine
                        loadExtractor(fullNestedUrl, url, subtitleCallback, callback)
                    }
                }
                
                // Also look for direct video links just in case
                val videoRegex = Regex("""(https?://[^"']+\.(?:m3u8|mp4)[^"']*)""")
                val sources = videoRegex.findAll(vidText).map { it.groupValues[1] }.distinct().toList()
                
                sources.forEach { source ->
                    val isM3u8 = source.contains(".m3u8")
                    callback.invoke(
                        newExtractorLink(
                            this.name,
                            this.name,
                            source,
                            type = if (isM3u8) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                        ) {
                            this.referer = url
                            this.quality = if (isM3u8) Qualities.Unknown.value else Qualities.P720.value
                        }
                    )
                }
            } else {
                loadExtractor(url, subtitleCallback, callback)
            }
        }

        if (data.startsWith("http")) {
            // Load iframes dynamically from the episode URL via global proxy
            val doc = app.get("${XtronPlayTVPlugin.proxy}/?url=$data").document

            val iframes = doc.select("iframe[src]").map { it.attr("src") }
            val aLinks = doc.select("div.entry-content p a[href]").map { it.attr("href") }.filter { href ->
                href.contains("desi-snation") || href.contains("tvarticles") || href.contains("desi-serials") || href.contains("bolly") || href.contains("dai.ly") || href.contains("dailymotion.com") || href.contains("vkprime") || href.contains("speedwatch")
            }
            val allLinks = (iframes + aLinks).distinct()
            
            android.util.Log.d("DesiSerials", "loadLinks links found: \${allLinks.size}")
            allLinks.forEach {
                android.util.Log.d("DesiSerials", "loadLinks handleIframe: " + it)
                handleIframe(it, data)
            }
        } else if (data.startsWith("[")) {
            // Load directly from JSON payload (fallback for older loads)
            try {
                // Try parsing as a list of strings (iframe src)
                val links = parseJson<List<String>>(data)
                links.amap { link ->
                    handleIframe(link, "$mainUrl/")
                }
            } catch (e: Exception) {
                // Ignore parsing error for Episode objects because Episode object contains the url inside data field which is handled in next iteration
                android.util.Log.d("DesiSerials", "loadLinks JSON parse error: " + e.message)
            }
        }
        return true
    }
}
