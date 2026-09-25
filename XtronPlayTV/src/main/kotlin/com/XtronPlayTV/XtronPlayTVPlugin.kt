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
        
        // FIXED: Hardcoded clean string targets to prevent internal provider property reference leaks
        registerExtractorAPI(Tvlogyflow("DesiSerials"))
        registerExtractorAPI(Tvlogy("DesiSerials"))
        registerExtractorAPI(Tellygossips("DesiSerials"))
    }
}
