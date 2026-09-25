package com.XtronPlayTV

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class XtronPlayTVPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(DesiSerialsProvider(), "DesiSerials")
        registerMainAPI(BollyzoneProvider(), "Bollyzone")

        registerExtractorAPI(Tvlogyflow(), "Tvlogyflow")
        registerExtractorAPI(Tellygossips(), "Tellygossips")
        registerExtractorAPI(Tvlogy(), "Tvlogy")
    }

    companion object {
        const val proxy = "https://desicinemas.phisherdesicinema.workers.dev"
    }
}
