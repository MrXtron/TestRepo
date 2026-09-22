package com.moviebox.mobile

import android.content.SharedPreferences
import android.annotation.SuppressLint
import com.fasterxml.jackson.databind.JsonNode
import com.lagradost.cloudstream3.Actor
import com.lagradost.cloudstream3.ActorData
import com.lagradost.cloudstream3.Episode
import com.lagradost.cloudstream3.ErrorLoadingException
import com.lagradost.cloudstream3.HomePageList
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.LoadResponse.Companion.addImdbId
import com.lagradost.cloudstream3.LoadResponse.Companion.addTMDbId
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.Score
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SearchResponseList
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.addDate
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.base64Decode
import com.lagradost.cloudstream3.base64DecodeArray
import com.lagradost.cloudstream3.base64Encode
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.mapper
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.newSearchResponseList
import com.lagradost.cloudstream3.newSubtitleFile
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import com.lagradost.cloudstream3.toNewSearchResponseList
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.INFER_TYPE
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.amap
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.net.URI
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.ArrayList
import java.util.Arrays
import java.util.Comparator
import java.util.Locale
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.math.max


class MovieBoxProvider(private val sharedPref: SharedPreferences? = null) : MainAPI() {

    companion object {
        @JvmStatic
        val HOST_POOL = listOf(
            "https://api6.aoneroom.com",
            "https://api5.aoneroom.com",
            "https://api4.aoneroom.com",
            "https://api4sg.aoneroom.com",
            "https://api3.aoneroom.com"
        )

        @Volatile
        var bearerToken: String? = null

        fun decodeJwtExpiry(token: String): Long {
            return try {
                val payload = token.split(".").getOrNull(1) ?: return 0L
                val padded = payload.replace("-", "+").replace("_", "/")
                    .let { it + "=".repeat((4 - it.length % 4) % 4) }
                val json = android.util.Base64.decode(padded, android.util.Base64.DEFAULT)
                    .toString(Charsets.UTF_8)
                org.json.JSONObject(json).getLong("exp")
            } catch (_: Exception) { 0L }
        }

        fun isTokenValid(token: String?): Boolean {
            if (token.isNullOrBlank()) return false
            val exp = decodeJwtExpiry(token)
            return exp > (System.currentTimeMillis() / 1000) + 3600
        }
    }


    override var mainUrl = sharedPref?.getString("moviebox_host", HOST_POOL[1]) ?: HOST_POOL[1]
    override var name = "MovieBox"
    override val hasMainPage = true
    override var lang = "hi"
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    private val PREF_TOKEN_KEY = "moviebox_bearer_token_v3"
    private var tokenTimestamp: Long = 0L
    val deviceId = generateDeviceId()
    val modernUserAgent = "com.community.mbox.in/50020150 (Linux; U; Android 16; en_IN; google; Build/BP22.250325.006; Cronet/145.0.7582.0)"
    
    fun getDynamicClientInfo(): String {
        val currentRegion = try { java.util.Locale.getDefault().country.ifBlank { "IN" } } catch(_: Exception) { "IN" }
        val currentTimezone = try { java.util.TimeZone.getDefault().id.ifBlank { "Asia/Calcutta" } } catch(_: Exception) { "Asia/Calcutta" }
        val currentLanguage = try { java.util.Locale.getDefault().language.ifBlank { "en" } } catch(_: Exception) { "en" }
        
        return "{\"package_name\":\"com.community.mbox.in\",\"version_name\":\"4.1.05.0112.01\",\"version_code\":50020150,\"os\":\"android\",\"os_version\":\"16\",\"device_id\":\"$deviceId\",\"install_store\":\"ps\",\"gaid\":\"d7578036d13336cc\",\"brand\":\"google\",\"model\":\"google\",\"system_language\":\"$currentLanguage\",\"net\":\"NETWORK_WIFI\",\"region\":\"$currentRegion\",\"timezone\":\"$currentTimezone\",\"sp_code\":\"\"}"
    }


    private val secretKeyDefault = base64Decode("NzZpUmwwN3MweFNOOWpxbUVXQXQ3OUVCSlp1bElRSXNWNjRGWnIyTw==")
    private val secretKeyAlt = base64Decode("WHFuMm5uTzQxL0w5Mm8xaXVYaFNMSFRiWHZZNFo1Wlo2Mm04bVNMQQ==")
    private val random = SecureRandom()

    private fun generateDeviceId(): String {
        val bytes = ByteArray(16)
        random.nextBytes(bytes)
        return bytes.joinToString("") { String.format("%02x", it) }
    }

    private fun md5(input: ByteArray): String {
        return MessageDigest.getInstance("MD5").digest(input)
            .joinToString("") { String.format("%02x", it) }
    }

    private fun reverseString(input: String): String = input.reversed()

    private fun generateXClientToken(hardcodedTimestamp: Long? = null): String {
        val timestamp = (hardcodedTimestamp ?: System.currentTimeMillis()).toString()
        val reversed = reverseString(timestamp)
        val bytes = reversed.toByteArray(Charsets.UTF_8)
        val hash = md5(bytes)
        return "$timestamp,$hash"
    }


    private fun buildCanonicalString(
        method: String,
        accept: String?,
        contentType: String?,
        url: String,
        body: String?,
        timestamp: Long
    ): String {
        val parsed = try { java.net.URI(url) } catch (_: Exception) { null }
        val path = parsed?.path ?: ""
        val queryRaw = parsed?.query
        
        val query = if (!queryRaw.isNullOrBlank()) {
            queryRaw.split("&")
                .mapNotNull { 
                    val parts = it.split("=")
                    if (parts.isNotEmpty()) {
                        val key = parts[0]
                        val value = parts.getOrNull(1) ?: ""
                        key to value
                    } else null
                }
                .sortedWith(Comparator { t1, t2 -> t1.first.compareTo(t2.first) })
                .joinToString("&") { "${it.first}=${it.second}" }
        } else ""

        val canonicalUrl = if (query.isNotEmpty()) "$path?$query" else path
        val bodyBytes = body?.toByteArray(Charsets.UTF_8)
        val bodyHash = if (bodyBytes != null) {
            val trimmed = if (bodyBytes.size > 102400) bodyBytes.copyOfRange(0, 102400) else bodyBytes
            md5(trimmed)
        } else ""

        val bodyLength = bodyBytes?.size?.toString() ?: ""
        val upperMethod = method.uppercase(Locale.ROOT)
        return "$upperMethod\n${accept ?: ""}\n${contentType ?: ""}\n$bodyLength\n$timestamp\n$bodyHash\n$canonicalUrl"
    }

    private fun generateXTrSignature(
        method: String,
        accept: String?,
        contentType: String?,
        url: String,
        body: String? = null,
        useAltKey: Boolean = false,
        hardcodedTimestamp: Long? = null
    ): String {
        val timestamp = hardcodedTimestamp ?: System.currentTimeMillis()
        val canonical = buildCanonicalString(method, accept, contentType, url, body, timestamp)
        val secret = if (useAltKey) secretKeyAlt else secretKeyDefault
        val secretBytes = base64DecodeArray(secret)

        val mac = Mac.getInstance("HmacMD5")
        mac.init(SecretKeySpec(secretBytes, "HmacMD5"))
        val signature = mac.doFinal(canonical.toByteArray(Charsets.UTF_8))
        val signatureB64 = base64Encode(signature)

        return "$timestamp|2|$signatureB64"
    }


    private fun saveToken(token: String?) {
        if (token.isNullOrBlank()) return
        if (!isTokenValid(token)) return
        bearerToken = token
        tokenTimestamp = System.currentTimeMillis()
        sharedPref?.edit()?.putString(PREF_TOKEN_KEY, token)?.apply()
    }

    private fun persistTokenFromXUser(xUserHeader: String?) {
        if (xUserHeader.isNullOrBlank()) return
        try {
            val token = mapper.readTree(xUserHeader)["token"]?.asText() ?: return
            saveToken(token)
        } catch (_: Exception) {}
    }

    private suspend fun fetchAnonymousToken(forceRefresh: Boolean = false): String {
        val saved = sharedPref?.getString(PREF_TOKEN_KEY, null)
        if (!forceRefresh && isTokenValid(saved)) {
            bearerToken = saved
            return saved!!
        }
        val rankUrl = "$mainUrl/wefeed-mobile-bff/tab/ranking-list?tabId=0&categoryType=r|0|9167640870324258216&page=1&perPage=1"
        val xClientToken = generateXClientToken()
        val xTrSig = generateXTrSignature("GET", "application/json", "application/json", rankUrl)
        
        val headers = mapOf(
            "user-agent" to modernUserAgent,
            "accept" to "application/json",
            "content-type" to "application/json",
            "connection" to "keep-alive",
            "x-client-token" to xClientToken,
            "x-tr-signature" to xTrSig,
            "X-M-Version" to "4.0.02",
            "x-client-info" to getDynamicClientInfo(),
            "x-client-status" to "0"
        )
        
        try {
            val response = app.get(rankUrl, headers = headers)
            persistTokenFromXUser(response.headers["x-user"])
        } catch (_: Exception) {}
        
        return bearerToken ?: saved ?: ""
    }

    private suspend fun buildAuthHeaders(
        method: String,
        url: String,
        contentType: String = "application/json",
        accept: String = "application/json",
        body: String? = null,
        useToken: Boolean = true
    ): Map<String, String> {
        val xClientToken = generateXClientToken()
        val xTrSig = generateXTrSignature(method, accept, contentType, url, body)
        
        val headers = mutableMapOf(
            "user-agent" to modernUserAgent,
            "accept" to accept,
            "content-type" to contentType,
            "connection" to "keep-alive",
            "x-client-token" to xClientToken,
            "x-tr-signature" to xTrSig,
            "X-M-Version" to "4.1.05", // Strict updated version tracking handshake
            "x-client-info" to getDynamicClientInfo(),
            "x-client-status" to "0",
            "x-play-mode" to "2"
        )
        
        if (useToken) {
            val token = fetchAnonymousToken()
            if (token.isNotBlank()) {
                headers["Authorization"] = "Bearer $token"
            }
        }
        return headers
    }
    
    private fun extractPolicyResource(signCookie: String?): String? = null

    override val mainPage = mainPageOf(
        "r|0|9167640870324258216" to "Trending Movies",
        "r|0|5692654647815587592" to "In Cinema",
        "r|0|1488104699998914056" to "New Release",
        "r|0|414907768299210008"  to "Bollywood",
        "r|0|8019599703232971616" to "Hollywood",
        "r|0|3859721901924910512" to "South Indian",
        "r|5|719331337777440448"  to "Top Series",
        "r|5|4903182713986896328" to "Indian Drama",
        "r|5|1255898847918934600" to "Reality TV",
        "r|5|1976033493293449744" to "Asian Drama",
        "r|5|3910636007619709856" to "Western TV",
        "r|5|5177200225164885656" to "Turkish Drama",
        "1|1" to "Movies",
        "1|2" to "Series",
        "1|1006" to "Anime",
        // ── PURE TV-SERIES GENRE FILTERS BLOCK ──
        "1|1;country=India" to "Indian (Movies)",
        "1|1;classify=Hindi dub;genre=Action;sort=Latest" to "Action (Movies)",
        "1|1;classify=Hindi dub;genre=Adventure;sort=Latest" to "Adventure (Movies)",
        "1|1;classify=Hindi dub;genre=Animation;sort=Latest" to "Animation (Movies)",
        "1|1;classify=Hindi dub;genre=Biography;sort=Latest" to "Biography (Movies)",
        "1|1;classify=Hindi dub;genre=Comedy;sort=Latest" to "Comedy (Movies)",
        "1|1;classify=Hindi dub;genre=Crime;sort=Latest" to "Crime (Movies)",
        "1|1;classify=Hindi dub;genre=Drama;sort=Latest" to "Drama (Movies)",
        "1|1;classify=Hindi dub;genre=Family;sort=Latest" to "Family (Movies)",
        "1|1;classify=Hindi dub;genre=History;sort=Latest" to "History (Movies)",
        "1|1;classify=Hindi dub;genre=Horror;sort=Latest" to "Horror (Movies)",
        "1|1;classify=Hindi dub;genre=Music;sort=Latest" to "Music (Movies)",
        "1|1;classify=Hindi dub;genre=Musical;sort=Latest" to "Musical (Movies)",
        "1|1;classify=Hindi dub;genre=Mystery;sort=Latest" to "Mystery (Movies)",
        "1|1;classify=Hindi dub;genre=Romance;sort=Latest" to "Romance (Movies)",
        "1|1;classify=Hindi dub;genre=Sci-Fi;sort=Latest" to "Sci-Fi (Movies)",
        "1|1;classify=Hindi dub;genre=Sport;sort=Latest" to "Sport (Movies)",
        "1|1;classify=Hindi dub;genre=Thriller;sort=Latest" to "Thriller (Movies)",
        "1|1;classify=Hindi dub;genre=War;sort=Latest" to "War (Movies)",
        // ── PURE TV-SERIES GENRE FILTERS BLOCK ──
        "1|2;country=India" to "Indian (Series)",
        "1|2;classify=Hindi dub;genre=Action;sort=Latest" to "Action (Series)",
        "1|2;classify=Hindi dub;genre=Adventure;sort=Latest" to "Adventure (Series)",
        "1|2;classify=Hindi dub;genre=Animation;sort=Latest" to "Animation (Series)",
        "1|2;classify=Hindi dub;genre=Biography;sort=Latest" to "Biography (Series)",
        "1|2;classify=Hindi dub;genre=Comedy;sort=Latest" to "Comedy (Series)",
        "1|2;classify=Hindi dub;genre=Crime;sort=Latest" to "Crime (Series)",
        "1|2;classify=Hindi dub;genre=Documentary;sort=Latest" to "Documentary (Series)",
        "1|2;classify=Hindi dub;genre=Drama;sort=Latest" to "Drama (Series)",
        "1|2;classify=Hindi dub;genre=Family;sort=Latest" to "Family (Series)",
        "1|2;classify=Hindi dub;genre=Game-Show;sort=Latest" to "Game-Show (Series)",
        "1|2;classify=Hindi dub;genre=History;sort=Latest" to "History (Series)",
        "1|2;classify=Hindi dub;genre=Horror;sort=Latest" to "Horror (Series)",
        "1|2;classify=Hindi dub;genre=Music;sort=Latest" to "Music (Series)",
        "1|2;classify=Hindi dub;genre=Musical;sort=Latest" to "Musical (Series)",
        "1|2;classify=Hindi dub;genre=Mystery;sort=Latest" to "Mystery (Series)",
        "1|2;classify=Hindi dub;genre=Reality-TV;sort=Latest" to "Reality-TV (Series)",
        "1|2;classify=Hindi dub;genre=Romance;sort=Latest" to "Romance (Series)",
        "1|2;classify=Hindi dub;genre=Sci-Fi;sort=Latest" to "Sci-Fi (Series)",
        "1|2;classify=Hindi dub;genre=Sport;sort=Latest" to "Sport (Series)",
        "1|2;classify=Hindi dub;genre=Talk-Show;sort=Latest" to "Talk-Show (Series)",
        "1|2;classify=Hindi dub;genre=Thriller;sort=Latest" to "Thriller (Series)"
    )


    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val perPage = 20
        val data1 = request.data
        val isRanking = data1.startsWith("r|")

        val url = if (isRanking) {
            val parts = data1.split("|")
            val categoryType = parts.getOrNull(1) ?: "0"
            val tabId = parts.getOrNull(2) ?: ""
            "$mainUrl/wefeed-mobile-bff/tab/ranking-list?tabId=$tabId&categoryType=$categoryType&page=$page&perPage=$perPage"
        } else {
            "$mainUrl/wefeed-mobile-bff/subject-api/list"
        }

        val headers = if (isRanking) {
            buildAuthHeaders("GET", url, useToken = true)
        } else {
            val mainParts = data1.substringBefore(";").split("|")
            val channelId = mainParts.getOrNull(1) ?: "1"

            val options = mutableMapOf<String, String>()
            if (data1.contains(";")) {
                data1.substringAfter(";", "")
                    .split(";")
                    .forEach {
                        val p = it.split("=")
                        val k = p.getOrNull(0)
                        val v = p.getOrNull(1)
                        if (!k.isNullOrBlank() && !v.isNullOrBlank()) {
                            options[k] = v
                        }
                    }
            }

            val classify = options["classify"] ?: "All"
            val currentDeviceRegion = try { java.util.Locale.getDefault().country.ifBlank { "IN" } } catch(_: Exception) { "IN" }
            
            // Dynamic Geo-Location Mapping: Agar default setup field "India" ho to local region tag assign hoga
            val targetCountry = if (options["country"] == "India") {
                when (currentDeviceRegion.uppercase(Locale.ROOT)) {
                    "IN" -> "India"
                    "US" -> "United States"
                    "SG" -> "Singapore"
                    "KR" -> "Korea"
                    "JP" -> "Japan"
                    else -> "India" // Safe universal backup route
                }
            } else {
                options["country"] ?: "All"
            }
            
            val year = options["year"] ?: "All"
            val genre = options["genre"] ?: "All"
            val sort = options["sort"] ?: "Latest"

            val jsonBody = "{\"page\":$page,\"perPage\":$perPage,\"channelId\":\"$channelId\",\"classify\":\"$classify\",\"country\":\"$targetCountry\",\"year\":\"$year\",\"genre\":\"$genre\",\"sort\":\"$sort\"}"
            buildAuthHeaders("POST", url, body = jsonBody, useToken = true)
        }

        val response = if (isRanking) {
            app.get(url, headers = headers)
        } else {
            val mainParts = data1.substringBefore(";").split("|")
            val channelId = mainParts.getOrNull(1) ?: "1"

            val options = mutableMapOf<String, String>()
            if (data1.contains(";")) {
                data1.substringAfter(";", "")
                    .split(";")
                    .forEach {
                        val p = it.split("=")
                        val k = p.getOrNull(0)
                        val v = p.getOrNull(1)
                        if (!k.isNullOrBlank() && !v.isNullOrBlank()) {
                            options[k] = v
                        }
                    }
            }

            val classify = options["classify"] ?: "All"
            val currentDeviceRegion = try { java.util.Locale.getDefault().country.ifBlank { "IN" } } catch(_: Exception) { "IN" }
            
            val targetCountry = if (options["country"] == "India") {
                when (currentDeviceRegion.uppercase(Locale.ROOT)) {
                    "IN" -> "India"
                    "US" -> "United States"
                    "SG" -> "Singapore"
                    "KR" -> "Korea"
                    "JP" -> "Japan"
                    else -> "India"
                }
            } else {
                options["country"] ?: "All"
            }
            
            val year = options["year"] ?: "All"
            val genre = options["genre"] ?: "All"
            val sort = options["sort"] ?: "Latest"

            val jsonBody = "{\"page\":$page,\"perPage\":$perPage,\"channelId\":\"$channelId\",\"classify\":\"$classify\",\"country\":\"$targetCountry\",\"year\":\"$year\",\"genre\":\"$genre\",\"sort\":\"$sort\"}"
            val requestBody = jsonBody.toRequestBody("application/json".toMediaType())
            app.post(url, headers = headers, requestBody = requestBody)
        }

        persistTokenFromXUser(response.headers["x-user"])

        val responseBody = response.text
        val data = try {
            val root = mapper.readTree(responseBody)
            val items = root["data"]?.get("items") ?: root["data"]?.get("subjects") ?: return newHomePageResponse(emptyList())
            
            val searchList = mutableListOf<SearchResponse>()
            for (item in items) {
                val titleRaw = item["title"]?.asText() ?: continue
                val title = titleRaw.substringBefore("[]")
                val id = item["subjectId"]?.asText() ?: continue
                val coverImg = item["cover"]?.get("url")?.asText()
                val subjectType = item["subjectType"]?.asInt() ?: 1
                val type = when (subjectType) {
                    1 -> TvType.Movie
                    2 -> TvType.TvSeries
                    else -> TvType.Movie
                }
                
                searchList.add(
                    newMovieSearchResponse(name = title, url = id, type = type) {
                        this.posterUrl = coverImg
                        val ratingNode = item["imdbRatingValue"]
                        this.score = Score.Companion.from10(ratingNode?.asText())
                    }
                )
            }
            searchList
        } catch (_: Exception) {
            emptyList()
        }

        return newHomePageResponse(
            listOf(HomePageList(request.name, data)),
            hasNext = data.isNotEmpty()
        )
    }


    override suspend fun search(query: String, page: Int): SearchResponseList {
        val url = "$mainUrl/wefeed-mobile-bff/subject-api/search/v2"
        val jsonBody = "{\"page\":$page,\"perPage\":20,\"keyword\":\"$query\"}"
        
        val headers = buildAuthHeaders("POST", url, body = jsonBody, useToken = true)
        val requestBody = jsonBody.toRequestBody("application/json".toMediaType())
        
        val response = app.post(url, headers = headers, requestBody = requestBody)
        persistTokenFromXUser(response.headers["x-user"])

        val responseBody = response.text
        val mapper = com.lagradost.cloudstream3.mapper
        val root = mapper.readTree(responseBody)
        val results = root["data"]?.get("results") ?: return newSearchResponseList(emptyList())
        
        val searchList = mutableListOf<SearchResponse>()
        for (result in results) {
            val subjects = result["subjects"] ?: continue
            for (subject in subjects) {
                val titleRaw = subject["title"]?.asText() ?: continue
                val title = if (titleRaw.contains("[]")) titleRaw.substringBefore("[]") else titleRaw
                val id = subject["subjectId"]?.asText() ?: continue
                val coverImg = subject["cover"]?.get("url")?.asText()
                val subjectType = subject["subjectType"]?.asInt() ?: 1
                val type = when (subjectType) {
                    1 -> TvType.Movie
                    2 -> TvType.TvSeries
                    else -> TvType.Movie
                }
                
                searchList.add(
                    newMovieSearchResponse(name = title, url = id, type = type) {
                        this.posterUrl = coverImg
                        this.score = Score.Companion.from10(subject["imdbRatingValue"]?.asText())
                    }
                )
            }
        }
        return searchList.toNewSearchResponseList()
    }

    override suspend fun load(url: String): LoadResponse {
        val id = if (url.contains("subjectId=")) {
            Regex("""subjectId=([^&]+)""").find(url)?.groupValues?.getOrNull(1) ?: url.substringAfterLast('/')
        } else {
            url.substringAfterLast('/')
        }

        val finalUrl = "$mainUrl/wefeed-mobile-bff/subject-api/get?subjectId=$id"
        val headers = buildAuthHeaders("GET", finalUrl, useToken = true)

        val response = app.get(finalUrl, headers = headers)
        persistTokenFromXUser(response.headers["x-user"])
        
        if (response.code != 200) {
            throw ErrorLoadingException("Failed to load data: ${response.text}")
        }

        val body = response.text
        val mapper = com.lagradost.cloudstream3.mapper
        val root = mapper.readTree(body)
        val data = root["data"] ?: throw ErrorLoadingException("No data found")

        val titleRaw = data["title"]?.asText() ?: throw ErrorLoadingException("No title found")
        val title = if (titleRaw.contains("[]")) titleRaw.substringBefore("[]") else titleRaw
        val description = data["description"]?.asText()
        val releaseDate = data["releaseDate"]?.asText()
        val duration = data["duration"]?.asText()
        val genre = data["genre"]?.asText()
        val imdbRating = data["imdbRatingValue"]?.asText()?.toDoubleOrNull()?.times(10)?.toInt()
        val year = releaseDate?.take(4)?.toIntOrNull()

        val coverUrl = data["cover"]?.get("url")?.asText()
        val backgroundUrl = data["cover"]?.get("url")?.asText()
        val subjectType = data["subjectType"]?.asInt() ?: 1

        val actors = data["staffList"]
            ?.mapNotNull { staff ->
                val staffType = staff["staffType"]?.asInt()
                if (staffType == 1) {
                    val name = staff["name"]?.asText() ?: return@mapNotNull null
                    val character = staff["character"]?.asText()
                    val avatarUrl = staff["avatarUrl"]?.asText()
                    ActorData(Actor(name, avatarUrl), roleString = character)
                } else null
            }
            ?.distinctBy { it.actor.name }
            ?: emptyList()

        val tags = genre?.split(",")?.map { it.trim() } ?: emptyList()

        val durationMinutes = duration?.let { dur ->
            val regex = """(\d+)h\s*(\d+)m""".toRegex()
            val m = regex.find(dur)
            if (m != null) {
                val h = m.groupValues.getOrNull(1)?.toIntOrNull() ?: 0
                val min = m.groupValues.getOrNull(2)?.toIntOrNull() ?: 0
                h * 60 + min
            } else dur.replace("m", "").trim().toIntOrNull()
        }

        val type = when (subjectType) {
            1 -> TvType.Movie
            2, 7 -> TvType.TvSeries
            else -> TvType.Movie
        }

        val (tmdbId, imdbId) = identifyID(
            title = title.substringBefore("()").trim(),
            year = year,
            imdbRatingValue = imdbRating?.toDouble()?.div(10)
        )

        val logoUrl = fetchTmdbLogoUrl(
            tmdbAPI = "https://api.themoviedb.org/3",
            apiKey = "98ae14df2b8d8f8f8136499daf79f0e0",
            type = type,
            tmdbId = tmdbId,
            appLangCode = "en"
        )

        val meta = if (!imdbId.isNullOrBlank()) fetchMetaData(imdbId, type) else null
        val metaVideos = meta?.get("videos")?.toList() ?: emptyList()

        val posterFinal = meta?.get("poster")?.asText() ?: coverUrl
        val backgroundFinal = meta?.get("background")?.asText() ?: backgroundUrl
        val descriptionFinal = meta?.get("overview")?.asText() ?: description
        val imdbRatingFinal = meta?.get("imdbRating")?.asText()

        if (type == TvType.TvSeries) {
            val allSubjectIds = mutableListOf<String>()
            allSubjectIds.add(id)
            data["dubs"]?.forEach {
                val sid = it["subjectId"]?.asText()
                if (!sid.isNullOrBlank() && sid !in allSubjectIds) {
                    allSubjectIds.add(sid)
                }
            }

            val episodeMap = mutableMapOf<Int, MutableSet<Int>>()

            for (subjectId in allSubjectIds) {
                val seasonUrl = "$mainUrl/wefeed-mobile-bff/subject-api/season-info?subjectId=$subjectId"
                val seasonSigHeaders = buildAuthHeaders("GET", seasonUrl, useToken = true)

                val seasonResponse = try { app.get(seasonUrl, headers = seasonSigHeaders) } catch (_: Exception) { null }
                if (seasonResponse == null || seasonResponse.code != 200) continue

                val seasonRoot = mapper.readTree(seasonResponse.text)
                val seasons = seasonRoot["data"]?.get("seasons")

                if (seasons != null && seasons.isArray) {
                    seasons.forEach { season ->
                        val seasonNumber = season["se"]?.asInt() ?: 1
                        val maxEp = season["maxEp"]?.asInt() ?: 1
                        val epSet = episodeMap.getOrPut(seasonNumber) { mutableSetOf() }
                        for (ep in 1..maxEp) {
                            epSet.add(ep)
                        }
                    }
                }
            }

            val episodes = mutableListOf<Episode>()
            episodeMap.forEach { (seasonNumber, epSet) ->
                epSet.sorted().forEach { episodeNumber ->
                    val epMeta = metaVideos.firstOrNull {
                        it["season"]?.asInt() == seasonNumber && it["episode"]?.asInt() == episodeNumber
                    }

                    val epName = epMeta?.get("name")?.asText()
                        ?: epMeta?.get("title")?.asText()?.takeIf { it.isNotBlank() }
                        ?: "S${seasonNumber}E${episodeNumber}"

                    val epDesc = epMeta?.get("overview")?.asText()
                        ?: epMeta?.get("description")?.asText()
                        ?: "Season $seasonNumber Episode $episodeNumber"

                    val epThumb = epMeta?.get("thumbnail")?.asText()?.takeIf { it.isNotBlank() } ?: coverUrl

                    val runtime = epMeta?.get("runtime")?.asText()?.filter { it.isDigit() }?.toIntOrNull()
                    val aired = epMeta?.get("released")?.asText()?.takeIf { it.isNotBlank() } ?: ""

                    episodes.add(
                        newEpisode("$id|$seasonNumber|$episodeNumber") {
                            this.name = epName
                            this.season = seasonNumber
                            this.episode = episodeNumber
                            this.posterUrl = epThumb
                            this.description = epDesc
                            this.runTime = runtime
                            addDate(aired)
                        }
                    )
                }
            }

            if (episodes.isEmpty()) {
                episodes.add(
                    newEpisode("$id|1|1") {
                        this.name = "Episode 1"
                        this.season = 1
                        this.episode = 1
                        this.posterUrl = coverUrl
                    }
                )
            }

            return newTvSeriesLoadResponse(title, finalUrl, type, episodes) {
                this.posterUrl = posterFinal
                this.backgroundPosterUrl = backgroundFinal
                try { this.logoUrl = logoUrl } catch (_: Throwable) {}
                this.plot = descriptionFinal
                this.year = year
                this.tags = tags
                this.actors = actors
                this.score = Score.Companion.from10(imdbRatingFinal) ?: imdbRating?.let { Score.Companion.from10((it / 10.0).toString()) }
                this.duration = durationMinutes
                addImdbId(imdbId)
                addTMDbId(tmdbId.toString())
            }
        }

        return newMovieLoadResponse(title, finalUrl, type, id) {
            this.posterUrl = posterFinal
            this.backgroundPosterUrl = backgroundFinal
            try { this.logoUrl = logoUrl } catch (_: Throwable) {}
            this.plot = descriptionFinal
            this.year = year
            this.tags = tags
            this.actors = actors
            this.score = Score.Companion.from10(imdbRatingFinal) ?: imdbRating?.let { Score.Companion.from10((it / 10.0).toString()) }
            this.duration = durationMinutes
            addImdbId(imdbId)
            addTMDbId(tmdbId.toString())
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        try {
            val parts = data.split("|")
            val originalSubjectId = when {
                data.contains("get?subjectId") -> {
                    Regex("""subjectId=([^&]+)""").find(data)?.groupValues?.getOrNull(1) ?: data.substringAfterLast('/')
                }
                data.contains("/") -> data.substringAfterLast('/')
                else -> parts.firstOrNull() ?: data
            }

            val season = if (parts.size > 1) parts[1].toIntOrNull() ?: 0 else 0
            val episode = if (parts.size > 2) parts[2].toIntOrNull() ?: 0 else 0
            
            val subjectUrl = "$mainUrl/wefeed-mobile-bff/subject-api/get?subjectId=$originalSubjectId"
            val subjectHeaders = buildAuthHeaders("GET", subjectUrl, useToken = true)

            val subjectResponse = app.get(subjectUrl, headers = subjectHeaders)
            persistTokenFromXUser(subjectResponse.headers["x-user"])

            val mapper = com.lagradost.cloudstream3.mapper
            val subjectIds = mutableListOf<Pair<String, String>>()
            var originalLanguageName = "Original"

            if (subjectResponse.code == 200) {
                val subjectRoot = mapper.readTree(subjectResponse.text)
                val dubs = subjectRoot["data"]?.get("dubs")
                if (dubs != null && dubs.isArray) {
                    for (dub in dubs) {
                        val dubSubjectId = dub["subjectId"]?.asText()
                        val lanName = dub["lanName"]?.asText()
                        if (dubSubjectId != null && lanName != null) {
                            if (dubSubjectId == originalSubjectId) {
                                originalLanguageName = lanName
                            } else {
                                subjectIds.add(Pair(dubSubjectId, lanName))
                            }
                        }
                    }
                }
            }

            subjectIds.add(0, Pair(originalSubjectId, originalLanguageName))

            subjectIds.amap { (subjectId, language) ->
                try {
                    val playUrl = "$mainUrl/wefeed-mobile-bff/subject-api/play-info?subjectId=$subjectId&se=$season&ep=$episode"
                    val playHeaders = buildAuthHeaders("GET", playUrl, useToken = true)

                    val response = app.get(playUrl, headers = playHeaders)
                    if (response.code == 200) {
                        val root = mapper.readTree(response.text)
                        val playData = root["data"]
                        val streams = playData?.get("streams")
                        if (streams != null && streams.isArray && streams.size() > 0) {
                            for (stream in streams) {
                                val actionStatus = stream["action"]?.asText() ?: ""
                                if (actionStatus.equals("UPDATE_APP", ignoreCase = true)) continue

                                val streamUrlRaw = stream["url"]?.asText() ?: continue
                                val format = stream["format"]?.asText() ?: ""
                                val resolutions = stream["resolutions"]?.asText() ?: ""
                                val signCookieRaw = stream["signCookie"]?.asText()
                                val signCookie = if (signCookieRaw.isNullOrEmpty()) null else signCookieRaw
                                val streamId = stream["id"]?.asText() ?: "$subjectId|$season|$episode"
                                
                                val quality = getHighestQuality(resolutions)
                                val policyUrl = extractPolicyResource(signCookie)
                                val finalStreamUrl = policyUrl ?: streamUrlRaw

                                callback.invoke(
                                    newExtractorLink(
                                        source = "$name ${language.replace("dub", "Audio")}",
                                        name = "$name (${language.replace("dub", "Audio")})",
                                        url = finalStreamUrl,
                                        type = when {
                                            finalStreamUrl.startsWith("magnet:", ignoreCase = true) -> ExtractorLinkType.MAGNET
                                            finalStreamUrl.contains(".mpd", ignoreCase = true) -> ExtractorLinkType.DASH
                                            finalStreamUrl.substringAfterLast('.', "").equals("torrent", ignoreCase = true) -> ExtractorLinkType.TORRENT
                                            format.equals("HLS", ignoreCase = true) || finalStreamUrl.substringAfterLast('.', "").equals("m3u8", ignoreCase = true) -> ExtractorLinkType.M3U8
                                            finalStreamUrl.contains(".mp4", ignoreCase = true) || finalStreamUrl.contains(".mkv", ignoreCase = true) -> ExtractorLinkType.VIDEO
                                            else -> INFER_TYPE
                                        }
                                    ) {
                                        this.headers = mapOf("Referer" to "$mainUrl/") + (if (!modernUserAgent.isNullOrBlank()) mapOf("User-Agent" to modernUserAgent) else emptyMap())
                                        if (quality != null) this.quality = quality
                                        if (signCookie != null) this.headers = this.headers + mapOf("Cookie" to signCookie)
                                    }
                                )

                                val subLink = "$mainUrl/wefeed-mobile-bff/subject-api/get-stream-captions?subjectId=$subjectId&streamId=$streamId"
                                val subHeaders = buildAuthHeaders("GET", subLink, useToken = true)
                                try {
                                    val subResponse = app.get(subLink, headers = subHeaders)
                                    val subRoot = mapper.readTree(subResponse.text)
                                    val extCaptions = subRoot["data"]?.get("extCaptions")
                                    if (extCaptions != null && extCaptions.isArray) {
                                        for (caption in extCaptions) {
                                            val captionUrl = caption["url"]?.asText() ?: continue
                                            val langLabel = caption["language"]?.asText() ?: caption["lanName"]?.asText() ?: "Unknown"
                                            subtitleCallback.invoke(newSubtitleFile(url = captionUrl, lang = "$langLabel (${language.replace("dub", "Audio")})"))
                                        }
                                    }
                                } catch (_: Exception) {}

                                val subLink1 = "$mainUrl/wefeed-mobile-bff/subject-api/get-ext-captions?subjectId=$subjectId&resourceId=$streamId&episode=0"
                                val subHeaders1 = buildAuthHeaders("GET", subLink1, useToken = true)
                                try {
                                    val subResponse1 = app.get(subLink1, headers = subHeaders1)
                                    val subRoot1 = mapper.readTree(subResponse1.text)
                                    val extCaptions1 = subRoot1["data"]?.get("extCaptions")
                                    if (extCaptions1 != null && extCaptions1.isArray) {
                                        for (caption in extCaptions1) {
                                            val captionUrl = caption["url"]?.asText() ?: continue
                                            val langLabel = caption["lan"]?.asText() ?: caption["lanName"]?.asText() ?: "Unknown"
                                            subtitleCallback.invoke(newSubtitleFile(url = captionUrl, lang = "$langLabel (${language.replace("dub", "Audio")})"))
                                        }
                                    }
                                } catch (_: Exception) {}
                            }
                        }

                        if (streams == null || !streams.isArray || streams.size() == 0) {
                            val fallbackUrl = "$mainUrl/wefeed-mobile-bff/subject-api/get?subjectId=$subjectId"
                            val fallbackHeaders = buildAuthHeaders("GET", fallbackUrl, useToken = true)
                            val fallbackResponse = app.get(fallbackUrl, headers = fallbackHeaders)

                            if (fallbackResponse.code == 200) {
                                val fallbackRoot = mapper.readTree(fallbackResponse.text)
                                val detectors = fallbackRoot["data"]?.get("resourceDetectors")
                                detectors?.forEach { detector ->
                                    detector["resolutionList"]?.forEach { video ->
                                        val link = video["resourceLink"]?.asText() ?: return@forEach
                                        val quality = video["resolution"]?.asInt() ?: 0
                                        val se = video["se"]?.asInt() ?: season
                                        val ep = video["ep"]?.asInt() ?: episode

                                        callback.invoke(
                                            newExtractorLink(
                                                source = "$name ${language.replace("dub", "Audio")}",
                                                name = "$name S${se}E${ep} ${quality}p (${language.replace("dub", "Audio")})",
                                                url = link,
                                                type = ExtractorLinkType.VIDEO
                                            ) {
                                                this.headers = mapOf("Referer" to "$mainUrl/")
                                                this.quality = quality
                                            }
                                        )
                                    }
                                }
                            }
                        }
                    }
                } catch (_: Exception) {}
            }
            return true
        } catch (_: Exception) {
            return false
        }
    }
}


fun getHighestQuality(input: String): Int? {
    val qualities = listOf(
        "2160" to Qualities.P2160.value,
        "1440" to Qualities.P1440.value,
        "1080" to Qualities.P1080.value,
        "720"  to Qualities.P720.value,
        "480"  to Qualities.P480.value,
        "360"  to Qualities.P360.value,
        "240"  to Qualities.P240.value
    )

    for ((label, mappedValue) in qualities) {
        if (input.contains(label, ignoreCase = true)) {
            return mappedValue
        }
    }
    return null
}


private fun cleanTitle(s: String): String {
    return s.lowercase()
        .replace("[^a-z0-9 ]".toRegex(), " ")
        .replace("\\s+".toRegex(), " ")
        .trim()
}
private suspend fun identifyID(
    title: String,
    year: Int?,
    imdbRatingValue: Double?
): Pair<Int?, String?> {
    val normTitle = normalize(title)
    val res = searchAndPick(normTitle, year, imdbRatingValue)
    if (res.first != null) return res

    return Pair(null, null)
}

private suspend fun searchAndPick(
    normTitle: String,
    year: Int?,
    imdbRatingValue: Double?,
): Pair<Int?, String?> {

    suspend fun doSearch(endpoint: String, extraParams: String = ""): org.json.JSONArray? {
        val url = buildString {
            append("https://api.themoviedb.org/3/").append(endpoint)
            append("?api_key=").append("1865f43a0549ca50d341dd9ab8b29f49")
            append(extraParams)
            append("&include_adult=false&page=1")
        }
        val text = app.get(url).text
        return JSONObject(text).optJSONArray("results")
    }

    val multiResults = doSearch("search/multi", "&query=$normTitle" + (if (year != null) "&year=$year" else ""))
    val searchQueues: List<Pair<String, org.json.JSONArray?>> = listOf(
        "multi" to multiResults,
        "tv" to doSearch("search/tv", "&query=$normTitle" + (if (year != null) "&first_air_date_year=$year" else "")),
        "movie" to doSearch("search/movie", "&query=$normTitle" + (if (year != null) "&year=$year" else ""))
    )

    var bestId: Int? = null
    var bestScore = -1.0
    var bestIsTv = false

    for ((sourceType, results) in searchQueues) {
        if (results == null) continue
        for (i in 0 until results.length()) {
            val o = results.getJSONObject(i)

            val mediaType = when (sourceType) {
                "multi" -> o.optString("media_type", "")
                "tv" -> "tv"
                else -> "movie"
            }

            val candidateId = o.optInt("id", -1)
            if (candidateId == -1) continue

            val titles = listOf(
                o.optString("title"),
                o.optString("name"),
                o.optString("original_title"),
                o.optString("original_name")
            ).filter { it.isNotBlank() }

            val candDate = when (mediaType) {
                "tv" -> o.optString("first_air_date", "")
                else -> o.optString("release_date", "")
            }
            val candYear = candDate.take(4).toIntOrNull()
            val candRating = o.optDouble("vote_average", Double.NaN)

            // scoring
            var score = 0.0
            val normClean = cleanTitle(normTitle)

            var titleScore = 0.0
            for (t in titles) {
                val candClean = cleanTitle(t)

                if (tokenEquals(candClean, normClean)) {
                    titleScore = 50.0
                    break
                }

                if (candClean.contains(normClean) || normClean.contains(candClean)) {
                    titleScore = maxOf(titleScore, 20.0)
                }
            }
            score += titleScore


            if (candYear != null && year != null && candYear == year) score += 35.0

            if (imdbRatingValue != null && !candRating.isNaN()) {
                val diff = kotlin.math.abs(candRating - imdbRatingValue)
                if (diff <= 0.5) score += 10.0 else if (diff <= 1.0) score += 5.0
            }

            if (o.has("popularity")) score += (o.optDouble("popularity", 0.0) / 100.0).coerceAtMost(5.0)

            if (score > bestScore) {
                bestScore = score
                bestId = candidateId
                bestIsTv = (mediaType == "tv")
            }
        }
    }

    if (bestId == null || bestScore < 40.0) return Pair(null, null)

    // fetch details for external_ids
    val detailKind = if (bestIsTv) "tv" else "movie"
    val detailUrl = "https://api.themoviedb.org/3/$detailKind/$bestId?api_key=1865f43a0549ca50d341dd9ab8b29f49&append_to_response=external_ids"
    val detailText = app.get(detailUrl).text
    val detailJson = JSONObject(detailText)
    val imdbId = detailJson.optJSONObject("external_ids")?.optString("imdb_id")

    return Pair(bestId, imdbId)
}

private fun tokenEquals(a: String, b: String): Boolean {
    val sa = a.split("\\s+".toRegex()).filter { it.isNotBlank() }.toSet()
    val sb = b.split("\\s+".toRegex()).filter { it.isNotBlank() }.toSet()
    if (sa.isEmpty() || sb.isEmpty()) return false
    val inter = sa.intersect(sb).size
    return inter >= max(1, minOf(sa.size, sb.size) * 3 / 4)
}

private fun normalize(s: String): String {
    val t = s.replace("\\[.*?]".toRegex(), " ")
        .replace("\\(.*?\\)".toRegex(), " ")
        .replace("(?i)\\b(dub|dubbed|hd|4k|hindi|tamil|telugu|dual audio)\\b".toRegex(), " ")
        .trim()
        .lowercase()
        .replace(":", " ")
        .replace("\\p{Punct}".toRegex(), " ")
        .replace("\\s+".toRegex(), " ")
    return t
}

private suspend fun fetchMetaData(imdbId: String?, type: TvType): JsonNode? {
    if (imdbId.isNullOrBlank()) return null

    val metaType = if (type == TvType.TvSeries) "series" else "movie"
    val url = "https://v3-cinemeta.strem.io/meta/$metaType/$imdbId.json"

    return try {
        val resp = app.get(url).text
        mapper.readTree(resp)["meta"]
    } catch (_: Exception) {
        null
    }
}

suspend fun fetchTmdbLogoUrl(
    tmdbAPI: String,
    apiKey: String,
    type: TvType,
    tmdbId: Int?,
    appLangCode: String?
): String? {

    if (tmdbId == null) return null

    val url = if (type == TvType.Movie)
        "$tmdbAPI/movie/$tmdbId/images?api_key=$apiKey"
    else
        "$tmdbAPI/tv/$tmdbId/images?api_key=$apiKey"

    val json = runCatching { JSONObject(app.get(url).text) }.getOrNull() ?: return null
    val logos = json.optJSONArray("logos") ?: return null
    if (logos.length() == 0) return null

    val lang = appLangCode?.trim()?.lowercase()

    fun path(o: JSONObject) = o.optString("file_path")
    fun isSvg(o: JSONObject) = path(o).endsWith(".svg", true)
    fun urlOf(o: JSONObject) = "https://image.tmdb.org/t/p/w500${path(o)}"

    // Language match
    var svgFallback: JSONObject? = null

    for (i in 0 until logos.length()) {
        val logo = logos.optJSONObject(i) ?: continue
        val p = path(logo)
        if (p.isBlank()) continue

        val l = logo.optString("iso_639_1").trim().lowercase()
        if (l == lang) {
            if (!isSvg(logo)) return urlOf(logo)
            if (svgFallback == null) svgFallback = logo
        }
    }
    svgFallback?.let { return urlOf(it) }

    // Highest voted fallback
    var best: JSONObject? = null
    var bestSvg: JSONObject? = null

    fun voted(o: JSONObject) = o.optDouble("vote_average", 0.0) > 0 && o.optInt("vote_count", 0) > 0

    fun better(a: JSONObject?, b: JSONObject): Boolean {
        if (a == null) return true
        val aAvg = a.optDouble("vote_average", 0.0)
        val aCnt = a.optInt("vote_count", 0)
        val bAvg = b.optDouble("vote_average", 0.0)
        val bCnt = b.optInt("vote_count", 0)
        return bAvg > aAvg || (bAvg == aAvg && bCnt > aCnt)
    }

    for (i in 0 until logos.length()) {
        val logo = logos.optJSONObject(i) ?: continue
        if (!voted(logo)) continue

        if (isSvg(logo)) {
            if (better(bestSvg, logo)) bestSvg = logo
        } else {
            if (better(best, logo)) best = logo
        }
    }

    best?.let { return urlOf(it) }
    bestSvg?.let { return urlOf(it) }

    // No language match & no voted logos
    return null
}
