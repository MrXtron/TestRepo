package com.hdmovie2

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.plugins.BasePlugin
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.app

@CloudstreamPlugin
class HdMovie2Plugin : BasePlugin() {

    override fun load() {
        // Register API providers and scrapers to Cloudstream runtime context
        registerMainAPI(Hdmovie2())
        registerExtractorAPI(HDm2())
        registerExtractorAPI(Abyass())
    }

    companion object {
        // Updated active fallback repository target verified from decoded DEX classes
        private const val DOMAINS_URL = "https://raw.githubusercontent.com/MrXtron/CloudStream-Extension/refs/heads/main/Files/domains.json"
        private var cachedDomains: Domains? = null

        data class Domains(
            @JsonProperty("hdmovie2") val hdmovie2: String
        )

        // Asynchronous channel network operation for fetching operational mirrors dynamically
        suspend fun getDomains(forceRefresh: Boolean = false): Domains? {
            if (!forceRefresh && cachedDomains != null) {
                return cachedDomains
            }
            return try {
                // Parse response utilizing nicehttp extension layout model
                val response = app.get(DOMAINS_URL).parsed<Domains>()
                cachedDomains = response
                response
            } catch (e: Throwable) {
                cachedDomains
            }
        }
    }
}