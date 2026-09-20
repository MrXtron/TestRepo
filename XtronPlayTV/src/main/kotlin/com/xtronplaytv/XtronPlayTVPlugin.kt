package com.xtronplaytv

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import android.content.Context

@CloudstreamPlugin
class XtronPlayTVPlugin: Plugin() {
    
    companion object {
        // Pool of alternate proxy networks available for the entire plugin system
        val proxyPool = listOf(
            "https://proxy.phisher2.workers.dev",
            "https://desicinemas.phisherdesicinema.workers.dev"
        )

        /**
         * Safely wraps any source URL with an available proxy from the pool.
         * Falls back to index 0 if out of bounds or default routing is needed.
         */
        fun getProxyUrl(targetUrl: String, useAlternative: Boolean = false): String {
            val baseProxy = if (useAlternative && proxyPool.size > 1) proxyPool[1] else proxyPool[0]
            return "$baseProxy/?url=$targetUrl"
        }
    }

    override fun load(context: Context) {
        // Priority registration: DesiSerials is loaded first to make it the primary UI view
        registerMainAPI(DesiSerialsProvider())
        registerMainAPI(BollyzoneProvider())
    }
}
