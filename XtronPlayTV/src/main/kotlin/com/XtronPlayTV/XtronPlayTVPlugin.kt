package com.XtronPlayTV

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class XtronPlayTVPlugin : Plugin() {
    override fun load(context: Context) {
        // Removed the string names; the name is now handled inside the Provider class
        registerMainAPI(DesiSerialsProvider())
        registerMainAPI(BollyzoneProvider())

        // Changed the strings to 'this' to satisfy the 'source' parameter requirement
        registerExtractorAPI(Tvlogyflow(), this)
        registerExtractorAPI(Tellygossips(), this)
        registerExtractorAPI(Tvlogy(), this)
    }

    companion object {
        const val proxy = "https://desicinemas.phisherdesicinema.workers.dev"
    }
}
