package com.XtronPlayTV

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class XtronPlayTVPlugin: Plugin() {
    override fun load(context: Context) {
        // All providers should be added in this manner. Please don't edit the providers list directly.
        registerMainAPI(DesiSerialsProvider())
        registerMainAPI(BollyzoneProvider())
        registerExtractorAPI(Tvlogyflow())
        registerExtractorAPI(Tellygossips())
        registerExtractorAPI(Tvlogy())
    }
    companion object {
        const val proxy = "https://desicinemas.phisherdesicinema.workers.dev"
    }
}
