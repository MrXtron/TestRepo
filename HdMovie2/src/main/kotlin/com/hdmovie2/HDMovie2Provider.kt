package com.hdmovie2

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import java.text.Normalizer
import java.util.Calendar
import java.util.Locale
import okhttp3.Interceptor
import kotlinx.coroutines.runBlocking

class Hdmovie2 : MainAPI() {

    override var mainUrl = runBlocking {
        try {
            HdMovie2Plugin.getDomains(false)?.hdmovie2 ?: "https://hdmovie2a.biz"
        } catch (e: Throwable) {
            "https://hdmovie2a.biz"
        }
    }

    var directUrl: String = mainUrl
    override var name = "Hdmovie2"
    override val hasMainPage = true
    override var lang = "hi"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    override val mainPage = mainPageOf(
        "release/${Calendar.getInstance().get(Calendar.YEAR)}" to "Latest",
        "genre/bollywood" to "BollyWood",
        "movies" to "Movies",
        "genre/hindi-webseries" to "Hindi Web Series",
        "genre/netflix" to "Netflix",
        "genre/zee5" to "Zee5",
        "genre/hindi-dubbed" to "Hindi Dubbed",
        "genre/comedy" to "Comedy",
        "genre/science-fiction" to "Science Fiction"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val categoryPath = request.data
        val targetUrl = if (page <= 1) "$mainUrl/$categoryPath/" else "$mainUrl/$categoryPath/page/$page/"

        val htmlContent = app.get(targetUrl).text
        val parsedDocument = Jsoup.parse(htmlContent)
        
        // Changed from "div.movies-list div.ml-item" to support the new grid layout
        val extractedItems = parsedDocument.select(".grid article.card, div.movies-list div.ml-item").mapNotNull { element ->
            toSearchResult(element)
        }

        return newHomePageResponse(request.name, extractedItems, true)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val searchQueryUrl = "$mainUrl/?s=${query.replace(" ", "+")}"
        val htmlContent = app.get(searchQueryUrl).text
        val parsedDocument = Jsoup.parse(htmlContent)

        // Added modern card selectors while keeping backward compatibility for fallback items
        return parsedDocument.select(".grid article.card, div.movies-list div.ml-item, div.result-item article, article.card").mapNotNull { element ->
            if (element.tagName() == "article" && element.hasClass("card").not() && element.selectFirst("h3 > a") != null) {
                val anchorElement = element.selectFirst("h3 > a") ?: return@mapNotNull null
                val rawTitle = anchorElement.text()
                val targetHref = getProperLink(fixUrl(anchorElement.attr("href")))
                
                val posterImgElement = element.select("div.poster img").last()
                var resolvedPoster: String? = null
                if (posterImgElement != null) {
                    resolvedPoster = getImageAttr(posterImgElement)
                }
                if (resolvedPoster?.contains(".gif", ignoreCase = true) == true) {
                    resolvedPoster = fixUrlNull(element.select("div.poster img").attr("data-wpfc-original-src"))
                }

                val mediaQuality = getSearchQuality(element.select("span.quality").text())
                val dynamicRatingScore = element.select("div.rating").text()

                newMovieSearchResponse(rawTitle, targetHref, TvType.Movie) {
                    this.posterUrl = resolvedPoster
                    this.quality = mediaQuality
                    this.score = Score.from10(dynamicRatingScore.trim())
                }
            } else {
                toSearchResult(element)
            }
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val htmlContent = app.get(url).text
        val parsedDocument = Jsoup.parse(htmlContent)

        val titleElement = parsedDocument.selectFirst("section.sheader h1")
        var parsedTitle = titleElement?.text()

        if (parsedTitle.isNullOrBlank()) {
            parsedTitle = parsedDocument.selectFirst("div.poster img")?.attr("alt")
        }

        if (parsedTitle.isNullOrBlank()) {
            throw ErrorLoadingException("Invalid Title")
        }

        val generatedPosterElement = parsedDocument.selectFirst("section.sheader div.poster img, div.poster img")
        val generatedPoster = generatedPosterElement?.let { getImageAttr(it) }
        
        val bannerBackgroundElement = parsedDocument.selectFirst("button.overlay-player")
        val bannerBackground = bannerBackgroundElement?.attr("data-source")

        val calculatedReleaseYear = parsedDocument.selectFirst("div.custom_fields:contains(Release) span.valor")?.text()?.substringBefore("-")?.toIntOrNull()
        val mediaDescription = parsedDocument.selectFirst("section#info div.wp-content p")?.text()
        val liveRating = parsedDocument.selectFirst("span.dt_rating_vgs")?.text() ?: ""
        
        val tagsCollection = parsedDocument.select("div.sgeneros a").map { it.text() }
        val actorsCollection = parsedDocument.select("section#cast div.persons article.person").map { 
            ActorData(
                com.lagradost.cloudstream3.Actor(it.select("div.name").text(), null)
            )
        }
        val dynamicRecommendations = parsedDocument.select(".grid article.card").mapNotNull { element ->
            toSearchResult(element)
        }

        val isSeriesContainer = url.contains("/tvshows/") || parsedDocument.selectFirst("div.les-title") != null

        // Collect all target data URLs from stream links layout
        val parsedLinksList = mutableListOf<String>()
        
        // Fix: Extract the primary streaming source directly from the main movie-player container first
        parsedDocument.selectFirst("#movie-player, div.play")?.attr("data-first-source")?.let { mainSource ->
            if (mainSource.isNotBlank() && !mainSource.contains("youtube.com")) {
                parsedLinksList.add(fixUrl(mainSource))
            }
        }

        // Loop through the alternative player options anchors
        parsedDocument.select(".options ul li a, li.dooplay_player_option a").forEach { option ->
            val sourceUrl = option.attr("data-source")
            if (sourceUrl.isNotBlank() && !sourceUrl.contains("youtube.com")) {
                if (sourceUrl.startsWith("http") || sourceUrl.startsWith("//")) {
                    parsedLinksList.add(fixUrl(sourceUrl))
                }
            }
        }

        // FIXED: Corrected path parameter to scrape dynamic tracking engines flawlessly
        try {
            val scriptUrl = "https://route7ind.com/player.js?v=124"
            val jsContent = app.get(scriptUrl).text
            val match = Regex("const\\s+AwsIndStreamDomain\\s*=\\s*['\"]([^'\"]+)['\"]").find(jsContent)
            val extractedDomain = match?.groupValues?.getOrNull(1)?.trim()
            
            val imdbIdElement = parsedDocument.selectFirst(".custom_fields:contains(IMDb ID) .valor, .custom_fields:contains(IMDb ID) span.valor")?.text()?.trim()
            if (!extractedDomain.isNullOrBlank() && !imdbIdElement.isNullOrBlank()) {
                val processedImdb = imdbIdElement.replace("f", "")
                parsedLinksList.add(0, "$extractedDomain/play/$imdbIdElement")
                parsedLinksList.add(0, "$extractedDomain/play/$processedImdb")
            }
        } catch (e: Throwable) {
            // Safe fallback logging block execution execution framework
        }

        return if (isSeriesContainer) {
            val episodesMapList = mutableListOf<Episode>()
            parsedDocument.select("div.les-content a").forEach { episodeAnchor ->
                val episodeHref = episodeAnchor.attr("href")
                val detailedTitle = episodeAnchor.attr("title")
                
                var resolvedSeason = 1
                var resolvedEpisodeNumber = 1

                val seasonMatchResult = Regex("(?i)season\\s*(\\d+)").find(detailedTitle)
                val episodeMatchResult = Regex("(?i)episode\\s*(\\d+)").find(detailedTitle)

                seasonMatchResult?.groupValues?.getOrNull(1)?.toIntOrNull()?.let { resolvedSeason = it }
                episodeMatchResult?.groupValues?.getOrNull(1)?.toIntOrNull()?.let { resolvedEpisodeNumber = it }

                episodesMapList.add(
                    newEpisode(episodeHref) {
                        this.name = detailedTitle
                        this.season = resolvedSeason
                        this.episode = resolvedEpisodeNumber
                        this.posterUrl = generatedPoster
                    }
                )
            }

            newTvSeriesLoadResponse(
                name = parsedTitle,
                url = url,
                type = TvType.TvSeries,
                episodes = episodesMapList
            ) {
                this.posterUrl = generatedPoster
                this.backgroundPosterUrl = bannerBackground
                this.year = calculatedReleaseYear
                this.plot = mediaDescription
                this.tags = tagsCollection
                this.score = Score.from10(liveRating.trim())
                this.recommendations = dynamicRecommendations.filterIsInstance<TvSeriesSearchResponse>()
                this.actors = actorsCollection
            }
        } else {
            newMovieLoadResponse(
                name = parsedTitle,
                url = url,
                type = TvType.Movie,
                dataUrl = AppUtils.toJson(parsedLinksList)
            ) {
                this.posterUrl = generatedPoster
                this.backgroundPosterUrl = bannerBackground
                this.year = calculatedReleaseYear
                this.plot = mediaDescription
                this.tags = tagsCollection
                this.score = Score.from10(liveRating.trim())
                this.recommendations = dynamicRecommendations
                this.actors = actorsCollection
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        if (data.isBlank()) return false

        val baseDomain = if (this.directUrl.isBlank()) mainUrl else this.directUrl

        if (data.startsWith("[")) {
            try {
                // Fallback custom text array splitting strategy to avoid Jackson strict types exceptions
                val cleanData = data.replace("[", "").replace("]", "").replace("\"", "").trim()
                val urlsList = cleanData.split(",").map { it.trim() }
                urlsList.forEach { singleUrl ->
                    if (singleUrl.startsWith("http")) {
                        loadExtractor(singleUrl, "$baseDomain/", subtitleCallback, callback)
                    }
                }
                return true
            } catch (_: Throwable) {}
        }

        if (data.startsWith("http")) {
            if (!data.contains("youtube.com", ignoreCase = true)) {
                loadExtractor(data, "$baseDomain/", subtitleCallback, callback)
            }
        }
        return true
    }

    fun getProperLink(uri: String): String {
        if (uri.contains("/episodes/")) {
            val title = uri.substringAfter(mainUrl + "/episodes/")
            val match = Regex("(.+?)-season").find(title)
            val trackingSegment = match?.groupValues?.getOrNull(1).orEmpty()
            return "$mainUrl/tvshows/$trackingSegment"
        }
        if (uri.contains("/seasons/")) {
            val title = uri.substringAfter(mainUrl + "/seasons/")
            val match = Regex("(.+?)-season").find(title)
            val trackingSegment = match?.groupValues?.getOrNull(1).orEmpty()
            return "$mainUrl/tvshows/$trackingSegment"
        }
        return uri
    }

    fun getImageAttr(element: Element): String {
        return when {
            element.hasAttr("data-src") -> element.attr("abs:data-src")
            element.hasAttr("data-lazy-src") -> element.attr("abs:data-lazy-src")
            element.hasAttr("srcset") -> element.attr("abs:srcset").substringBefore(" ")
            else -> element.attr("abs:src")
        }
    }

    fun getSearchQuality(check: String?): SearchQuality? {
        if (check == null) return null
        val u = Normalizer.normalize(check, Normalizer.Form.NFKC).lowercase(Locale.ROOT)
        val qualityPatterns = listOf(
            Regex("\\b(4k|ds4k|uhd|2160p)\\b", RegexOption.IGNORE_CASE) to SearchQuality.FourK,
            Regex("\\b(hdts|hdcam|hdtc)\\b", RegexOption.IGNORE_CASE) to SearchQuality.HdCam,
            Regex("\\b(camrip|cam[- ]?rip)\\b", RegexOption.IGNORE_CASE) to SearchQuality.CamRip,
            Regex("\\b(cam)\\b", RegexOption.IGNORE_CASE) to SearchQuality.Cam,
            Regex("\\b(web[- ]?dl|webrip|webdl)\\b", RegexOption.IGNORE_CASE) to SearchQuality.WebRip,
            Regex("\\b(bluray|bdrip|blu[- ]?ray)\\b", RegexOption.IGNORE_CASE) to SearchQuality.BlueRay,
            Regex("\\b(1440p|qhd)\\b", RegexOption.IGNORE_CASE) to SearchQuality.BlueRay,
            Regex("\\b(1080p|fullhd)\\b", RegexOption.IGNORE_CASE) to SearchQuality.HD,
            Regex("\\b(720p)\\b", RegexOption.IGNORE_CASE) to SearchQuality.SD,
            Regex("\\b(hdrip|hdtv|HD)\\b", RegexOption.IGNORE_CASE) to SearchQuality.HD,
            Regex("\\b(dvd)\\b", RegexOption.IGNORE_CASE) to SearchQuality.DVD,
            Regex("\\b(hq)\\b", RegexOption.IGNORE_CASE) to SearchQuality.HQ,
            Regex("\\b(rip)\\b", RegexOption.IGNORE_CASE) to SearchQuality.CamRip
        )
        for ((regex, quality) in qualityPatterns) {
            if (regex.containsMatchIn(u)) return quality
        }
        return null
    }

    private fun toSearchResult(element: Element): SearchResponse? {
        val anchorElement = element.selectFirst("h3.card-title > a, div.title > a, h2.result-title > a") ?: return null
        
        val cleanTitle = Regex("\\b(\\d{4})\\b").replace(anchorElement.text(), "").trim()
        val href = getProperLink(fixUrl(anchorElement.attr("href")))
        val imgElement = element.selectFirst("div.poster img, img")
        val poster = imgElement?.let {
            when {
                it.hasAttr("data-src") -> it.attr("abs:data-src")
                it.hasAttr("data-lazy-src") -> it.attr("abs:data-lazy-src")
                it.hasAttr("srcset") -> it.attr("abs:srcset").substringBefore(" ")
                else -> it.attr("abs:src")
            }
        } ?: imgElement?.attr("src")
        
        // Extracting quality flags from the new ".quality-label" class
        val qualityLabel = element.selectFirst(".quality-label, span.quality")?.text()
        val mediaQuality = getSearchQuality(qualityLabel)
        
        // Extracting IMDb ratings directly from the card container
        val dynamicRatingScore = element.selectFirst("div.rating")?.text()
        
        // Determining type by scanning tags like "Hindi WebSeries" or "New Episode" inside the quality container
        val typeStr = element.selectFirst(".result-type, .quality-label")?.text().orEmpty()
        val isTvSeries = typeStr.contains("Series", ignoreCase = true) || 
                         typeStr.contains("Episode", ignoreCase = true) || 
                         href.contains("/tvshows/")

        return if (isTvSeries) {
            newTvSeriesSearchResponse(cleanTitle, href, TvType.TvSeries, false) {
                this.posterUrl = poster
                this.quality = mediaQuality
                if (!dynamicRatingScore.isNullOrBlank()) {
                    this.score = Score.from10(dynamicRatingScore.trim())
                }
            }
        } else {
            newMovieSearchResponse(cleanTitle, href, TvType.Movie, false) {
                this.posterUrl = poster
                this.quality = mediaQuality
                if (!dynamicRatingScore.isNullOrBlank()) {
                    this.score = Score.from10(dynamicRatingScore.trim())
                }
            }
        }
    }

    override fun getVideoInterceptor(extractorLink: ExtractorLink): Interceptor {
        return Interceptor { chain ->
            val request = chain.request()
            val requestUrl = request.url.toString()
            
            when {
                requestUrl.contains("hdm2.biz") || requestUrl.contains("hdm2.xyz") || requestUrl.contains("hdm2.ink") -> {
                    val modifiedRequest = request.newBuilder()
                        .header("Accept", "*/*")
                        .header("Origin", mainUrl)
                        .header("Referer", "$mainUrl/")
                        .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:137.0) Gecko/20100101 Firefox/137.0")
                        .build()
                    chain.proceed(modifiedRequest)
                }
                // Updated block handles prvs, abyss, and the new live node script domain hunts439kow
                requestUrl.contains("prvs.top") || requestUrl.contains("abyss.to") || requestUrl.contains("hunts439kow.com") || requestUrl.contains("irada438esc.com") -> {
                    val modifiedRequest = request.newBuilder()
                        .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8")
                        .header("Origin", mainUrl)
                        .header("Referer", "$mainUrl/")
                        .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:137.0) Gecko/20100101 Firefox/137.0")
                        .build()
                    chain.proceed(modifiedRequest)
                }
                requestUrl.contains("sukumsanghas.com") -> {
                    val modifiedRequest = request.newBuilder()
                        .header("Accept", "*/*")
                        .header("Origin", "https://molop.art")
                        .header("Referer", "https://molop.art")
                        .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:137.0) Gecko/20100101 Firefox/137.0")
                        .build()
                    chain.proceed(modifiedRequest)
                }
                else -> chain.proceed(request)
            }
        }
    }
}
