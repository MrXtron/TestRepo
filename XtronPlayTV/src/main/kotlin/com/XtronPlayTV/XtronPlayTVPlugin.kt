package com.XtronPlayTV

import com.lagradost.cloudstream3.plugins.BasePlugin
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin

@CloudstreamPlugin
class XtronPlayTVPlugin: BasePlugin() {
    override fun load() {
        // All providers should be added in this manner. Please don't edit the providers list directly.
        val provider =  DesiSerialsProvider()
        registerMainAPI(provider)
        registerMainAPI(BollyzoneProvider())
        registerExtractorAPI(Tvlogyflow((provider.name)))
        registerExtractorAPI(Tellygossips((provider.name)))
        registerExtractorAPI(Tvlogyflow((provider.name)))
    }
}


