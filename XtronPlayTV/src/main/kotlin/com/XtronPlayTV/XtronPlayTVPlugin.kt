package com.XtronPlayTV

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import android.content.Context

@CloudstreamPlugin
class XtronPlayTVPlugin : Plugin() {

    companion object {
        const val proxy = "https://desicinemas.phisherdesicinema.workers.dev"
    }

    override fun load(context: Context) {
        registerMainAPI(DesiSerialsProvider())
        registerMainAPI(BollyzoneProvider())

        registerExtractorAPI(Tvlogyflow("DesiSerials"))
        registerExtractorAPI(Tvlogy("DesiSerials"))
        registerExtractorAPI(Tellygossips("DesiSerials"))
    }
}
