package com.XtronPlayTV

import com.lagradost.cloudstream3.HomePageList
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.mvvm.amap
import com.lagradost.cloudstream3.fixUrlNull
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import com.lagradost.cloudstream3.newTvSeriesSearchResponse
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.XtronPlayTV.UtilsKt.resolveIframeSrc
import com.XtronPlayTV.UtilsKt.loadSourceNameExtractor
import org.jsoup.nodes.Element

class BollyzoneProvider : DesiSerialsProvider() {
    override val supportedTypes = setOf(
        TvType.TvSeries
    )
    override var lang = "hi"
    override var mainUrl = "https://bollyzone.to"
    override var name = "Bollyzone"

    override val mainPage = mainPageOf(
        "series/" to "Episodes",
        "tv-channels/" to "Series",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val targetPath = if (page == 1) request.data else "${request.data}page/$page/"
        val cleanPath = targetPath.replace(mainUrl, "").replace("//", "/").trimStart('/')
        val url = "${XtronPlayTVPlugin.proxy}/?url=$mainUrl/$cleanPath"
        
        val doc = app.get(url, referer = "$mainUrl/").document
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
        val url = "${XtronPlayTVPlugin.proxy}/?url=$mainUrl&s=$encodedQuery"
        val doc = app.get(url, referer = "$mainUrl/").document

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

        return newTvSeriesSearchResponse(title, href) {
            this.posterUrl = if (!posterUrl.isNullOrBlank() && !posterUrl.startsWith(XtronPlayTVPlugin.proxy)) {
                "${XtronPlayTVPlugin.proxy}/?url=$posterUrl"
            } else {
                posterUrl
            }
            this.posterHeaders = mapOf(
                "referer" to "$mainUrl/",
                "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:139.0) Gecko/20100101 Firefox/139.0"
            )
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        val proxiedUrl = if (!url.startsWith(XtronPlayTVPlugin.proxy)) {
            "${XtronPlayTVPlugin.proxy}/?url=$url"
        } else {
            url
        }
        val doc = app.get(proxiedUrl, referer = mainUrl, timeout = 10000).document

        if (url.contains("/series/")) {
            val title = doc.selectFirst("h1")?.text()?.trim() ?: return null
            val rawPoster = fixUrlNull(doc.selectFirst(".Image img")?.getImageAttr())
            val securePoster = if (!rawPoster.isNullOrBlank() && !rawPoster.startsWith(XtronPlayTVPlugin.proxy)) {
                "${XtronPlayTVPlugin.proxy}/?url=$rawPoster"
            } else {
                rawPoster
            }

            return newTvSeriesLoadResponse(title, url, TvType.TvSeries, listOf(newEpisode(url) { name = title })) {
                this.posterUrl = securePoster
                plot = doc.selectFirst(".Description p")?.text()
                tags = doc.select(".Genre a").map { it.text() }
            }
        }

        val title = doc.select("meta[property=og:title]").attr("content")
        val rawPoster = doc.selectFirst("div.Image img")?.getImageAttr()
        val posterUrl = if (!rawPoster.isNullOrBlank() && !rawPoster.startsWith(XtronPlayTVPlugin.proxy)) {
            "${XtronPlayTVPlugin.proxy}/?url=$rawPoster"
        } else {
            rawPoster
        }
        val description = doc.select("meta[property=og:description]").attr("content")
        val tags = doc.select(".Genre a").map { it.text() }.distinct()

        val lastPageNumber = doc.select("section > nav > div > a")
            .mapNotNull { it.text().toIntOrNull() }
            .maxOrNull() ?: 1

        val dateRegex = Regex("""\b\d{1,2}(st|nd|rd|th)?\s+(January|February|March|April|May|June|July|August|September|October|November|December)\s+\d{4}\b""")

        val episodes = (1..lastPageNumber).flatMap { page ->
            val pageUrl = "${XtronPlayTVPlugin.proxy}/?url=$url/page/$page/"
            val pageDoc = app.get(pageUrl, referer = mainUrl, timeout = 10000).document

            pageDoc.select("ul.MovieList li").mapNotNull { element ->
                val epUrl = fixUrlNull(element.select("a").attr("href")) ?: return@mapNotNull null
                val titleText = element.selectFirst("a h2")?.text()?.trim()
                val match = titleText?.let { dateRegex.find(it) }
                val epName = match?.value ?: titleText ?: "Episode"
                val epPoster = element.select("img").attr("src")

                newEpisode(epUrl) {
                    name = epName
                    this.posterUrl = if (!epPoster.isNullOrBlank() && !epPoster.startsWith(XtronPlayTVPlugin.proxy)) {
                        "${XtronPlayTVPlugin.proxy}/?url=$epPoster"
                    } else {
                        epPoster
                    }
                }
            }
        }.toMutableList()

        return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
            this.posterUrl = posterUrl
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
        val secureDataUrl = if (!data.startsWith(XtronPlayTVPlugin.proxy)) {
            "${XtronPlayTVPlugin.proxy}/?url=$data"
        } else {
            data
        }

        app.get(secureDataUrl, referer = mainUrl)
            .document.select(".MovieList .OptionBx")
            .amap {
                val name = it.select("p.AAIco-dns").text().trim()
                val link = it.select("a").attr("href")

                val headers = mapOf(
                    "referer" to mainUrl,
                    "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:139.0) Gecko/20100101 Firefox/139.0",
                    "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
                    "Accept-Language" to "en-US,en;q=0.5",
                    "Connection" to "keep-alive",
                    "Cache-Control" to "no-cache"
                )

                val secureLink = if (!link.startsWith(XtronPlayTVPlugin.proxy)) {
                    "${XtronPlayTVPlugin.proxy}/?url=$link"
                } else {
                    link
                }
                val src = app.get(secureLink, headers = headers)
                val doc = src.document

                val iframe = doc.selectFirst("#Proceed a[href], a.button.button1, a.button1")
                    ?.attr("href")
                    .orEmpty()

                val iframeURL = resolveIframeSrc(iframe) ?: doc.selectFirst("IFRAME")?.attr("src")

                if (iframeURL.isNullOrBlank()) {
                    if (iframe.isBlank()) return@amap

                    val pathParts = iframe.trimEnd('/').split('/')
                    if (pathParts.size < 2) return@amap

                    val token = pathParts.last()
                    val type = pathParts.dropLast(1).last()
                    val playerUrl = "https://flow.tvlogy.to/$type/$token/"

                    loadSourceNameExtractor(name, playerUrl, mainUrl, subtitleCallback, callback)
                    return@amap
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
