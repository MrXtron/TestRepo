package com.XtronPlayTV

import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.HomePageList
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.fixUrlNull
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import com.lagradost.cloudstream3.newTvSeriesSearchResponse
import com.lagradost.cloudstream3.utils.ExtractorLink
import org.jsoup.nodes.Element
import org.jsoup.nodes.Document

class BollyzoneProvider : DesiSerialsProvider() {
    override val supportedTypes = setOf(
        TvType.TvSeries
    )
    override var lang = "hi"
    override var mainUrl = "https://bollyzone.to"
    override var name = "XtronPlayTV (Source 2)"

    override val mainPage = mainPageOf(
        "series/" to "Episodes",
        "tv-channels/" to "Series"
    )

    // Your optimized HTML failover mechanism
    private suspend fun getProxyDocument(
        targetUrl: String,
        refererUrl: String = "$mainUrl/"
    ): Document {
        var lastError: Exception? = null

        for (proxy in XtronPlayTVPlugin.proxy) {
            try {
                return app.get(
                    "$proxy/?url=$targetUrl",
                    referer = refererUrl,
                    timeout = 10000
                ).document
            } catch (e: Exception) {
                lastError = e
            }
        }

        throw lastError ?: Exception("All proxies failed")
    }

    // Your optimized image proxy logic
    private fun proxyImage(url: String?): String? {
        if (url.isNullOrBlank()) return url

        val firstProxy = XtronPlayTVPlugin.proxy.first()

        return if (!url.startsWith(firstProxy)) {
            "$firstProxy/?url=$url"
        } else {
            url
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val targetPath = if (page == 1) request.data else "${request.data}page/$page/"
        val cleanPath = targetPath.replace(mainUrl, "").replace("//", "/").trimStart('/')
        val targetUrl = "$mainUrl/$cleanPath"
        
        // Using your clean implementation here
        val doc = getProxyDocument(targetUrl)
        val homePageList = mutableListOf<HomePageList>()

        val headers = doc.select("h2.Title").filter {
            it.text().contains("Shows", ignoreCase = true)
        }

        for (header in headers) {
            val sectionName = header.selectFirst("a")?.text()?.trim() ?: continue
            val movieListDiv = header.nextElementSiblings()
                .firstOrNull { it.tagName() == "div" && it.hasClass("MovieListTop") } ?: continue

            val list = movieListDiv.toHomePageList(sectionName)
            if (list.list.isNotEmpty()) {
                homePageList.add(list)
            }
        }

        if (homePageList.isEmpty()) {
            val fallbackItems = doc.select("ul.MovieList li.TPostMv")
                .mapNotNull { it.toHomePageResult() }

            if (fallbackItems.isNotEmpty()) {
                homePageList.add(HomePageList("Latest", fallbackItems))
            }
        }

        val hasNext = homePageList.any { it.list.isNotEmpty() }
        return newHomePageResponse(homePageList, hasNext)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val encodedQuery = query.replace(" ", "+").lowercase()
        val targetUrl = "$mainUrl&s=$encodedQuery"
        val doc = getProxyDocument(targetUrl)

        return doc.select("ul.MovieList li.TPostMv")
            .mapNotNull { it.toHomePageResult() }
    }

    private fun Element.toHomePageList(name: String): HomePageList {
        val items = select("div.TPostMv")
            .mapNotNull {
                it.toHomePageResult()
            }
        return HomePageList(name, items)
    }

    private fun Element.toHomePageResult(): SearchResponse? {
        val title = selectFirst("h2.Title")?.text()?.trim() ?: return null
        val href = fixUrlNull(selectFirst("a")?.attr("href")) ?: return null
        val img = selectFirst("img")
        val posterUrl = fixUrlNull(img?.getImageAttr())

        return newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
            this.posterUrl = proxyImage(posterUrl)
            this.posterHeaders = mapOf(
                "referer" to "$mainUrl/",
                "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:139.0) Gecko/20100101 Firefox/139.0"
            )
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        val doc = getProxyDocument(url)

        if (url.contains("/series/")) {
            val title = doc.selectFirst("h1")?.text()?.trim() ?: return null
            val rawPoster = fixUrlNull(doc.selectFirst(".Image img")?.getImageAttr())

            return newTvSeriesLoadResponse(title, url, TvType.TvSeries, listOf(newEpisode(url) { name = title })) {
                this.posterUrl = proxyImage(rawPoster)
                plot = doc.selectFirst(".Description p")?.text()
                tags = doc.select(".Genre a").map { it.text() }
            }
        }

        val title = doc.select("meta[property=og:title]").attr("content")
        val rawPoster = doc.selectFirst("div.Image img")?.getImageAttr()
        val description = doc.select("meta[property=og:description]").attr("content")
        val tags = doc.select(".Genre a").map { it.text() }.distinct()

        val lastPageNumber = doc.select("section > nav > div > a")
            .mapNotNull { it.text().toIntOrNull() }
            .maxOrNull() ?: 1

        val dateRegex = Regex("""\b\d{1,2}(st|nd|rd|th)?\s+(January|February|March|April|May|June|July|August|September|October|November|December)\s+\d{4}\b""")

        val episodes = (1..lastPageNumber).flatMap { page ->
            val pageUrl = "$url/page/$page/"
            val pageDoc = getProxyDocument(pageUrl)

            pageDoc.select("ul.MovieList li").mapNotNull { element ->
                val epUrl = fixUrlNull(element.select("a").attr("href")) ?: return@mapNotNull null
                val titleText = element.selectFirst("a h2")?.text()?.trim()
                val match = titleText?.let { dateRegex.find(it) }
                val epName = match?.value ?: titleText ?: "Episode"
                val epPoster = element.select("img").attr("src")

                newEpisode(epUrl) {
                    name = epName
                    this.posterUrl = proxyImage(epPoster)
                }
            }
        }.toMutableList()

        return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes.reversed()) {
            this.posterUrl = proxyImage(rawPoster)
            this.plot = description
            this.tags = tags
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        // Direct document calling with your custom helper
        val options = getProxyDocument(data).select(".MovieList .OptionBx")
        
        for (element in options) {
            val name = element.select("p.AAIco-dns").text().trim()
            val link = element.select("a").attr("href")

            val headers = mapOf(
                "referer" to mainUrl,
                "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:139.0) Gecko/20100101 Firefox/139.0",
                "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
                "Accept-Language" to "en-US,en;q=0.5",
                "Connection" to "keep-alive",
                "Cache-Control" to "no-cache"
            )

            // Using proxy document loader for checking specific elements inside loops safely
            val doc = getProxyDocument(link, refererUrl = mainUrl)

            val iframe = doc.selectFirst("#Proceed a[href], a.button.button1, a.button1")
                ?.attr("href")
                .orEmpty()

            val iframeURL = resolveIframeSrc(iframe) ?: doc.selectFirst("IFRAME")?.attr("src")

            if (iframeURL.isNullOrBlank()) {
                if (iframe.isBlank()) continue

                val pathParts = iframe.trimEnd('/').split('/')
                if (pathParts.size < 2) continue

                val token = pathParts.last()
                val type = pathParts.dropLast(1).last()
                val playerUrl = "https://flow.tvlogy.to/$type/$token/"

                loadSourceNameExtractor(name, playerUrl, mainUrl, subtitleCallback, callback)
                continue
            }

            loadSourceNameExtractor(name, iframeURL, mainUrl, subtitleCallback, callback)
        }
        return true
    }

    private fun Element.getImageAttr(): String {
        return when {
            this.hasAttr("data-src") -> this.attr("data-src")
            this.hasAttr("src") -> this.attr("src")
            else -> this.attr("src")
        }
    }
}
