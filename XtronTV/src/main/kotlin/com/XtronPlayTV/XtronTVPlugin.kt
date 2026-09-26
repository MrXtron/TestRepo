package com.XtronTV

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class XtronTVPlugin : Plugin() {

    override fun load(context: Context) {
        registerMainAPI(DesiSerialsProvider())
        registerMainAPI(BollyzoneProvider())

        registerExtractorAPI(Tvlogyflow("Tvlogyflow"))
        registerExtractorAPI(Tellygossips("Tellygossips"))
        registerExtractorAPI(Tvlogy("Tvlogy"))
    }

    companion object {
        val proxy = listOf(
            "https://proxy.phisher2.workers.dev",
            "https://desicinemas.phisherdesicinema.workers.dev"
        )
    }
}
