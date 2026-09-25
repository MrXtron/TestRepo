package com.XtronPlayTV

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class XtronPlayTVPlugin: BasePlugin() {
    override fun load() {
        // All providers should be added in this manner. Please don't edit the providers list directly.
        val provider = DesiSerialsProvider()
        registerMainAPI(provider)
        registerMainAPI(BollyzoneProvider())
        registerExtractorAPI(Tvlogyflow((provider.name)))
        registerExtractorAPI(Tellygossips((provider.name)))
        registerExtractorAPI(Tvlogy((provider.name)))
    }
    companion object {
        const val proxy = "https://desicinemas.phisherdesicinema.workers.dev"
    }
}
