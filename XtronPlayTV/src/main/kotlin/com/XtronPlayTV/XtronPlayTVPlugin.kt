package com.XtronPlayTV

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import android.content.Context

@CloudstreamPlugin
class XtronPlayTVPlugin : Plugin() {
    
    companion object {
        // Central shared proxy URL declared strictly without a trailing slash
        val proxy = "https://desicinemas.phisherdesicinema.workers.dev"
    }

    override fun load(context: Context) {
        val provider = DesiSerialsProvider()
        
        // Ordered intentionally to establish DesiSerials as the primary home screen tab
        registerMainAPI(provider)
        registerMainAPI(BollyzoneProvider())
        
        // Register distinct video streaming extractors for the plugin pipeline
        registerExtractorAPI(Tvlogyflow(provider.name))
        registerExtractorAPI(Tvlogy(provider.name))
        registerExtractorAPI(Tellygossips(provider.name))
    }
}
