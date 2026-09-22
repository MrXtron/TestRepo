package com.moviebox.mobile

import android.content.Context
import android.content.ContextWrapper
import androidx.appcompat.app.AppCompatActivity
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class MovieBoxProviderPlugin : Plugin() {

    private fun getActivity(context: Context): AppCompatActivity? {
        var currentContext = context
        while (currentContext is ContextWrapper) {
            if (currentContext is AppCompatActivity) {
                return currentContext
            }
            currentContext = currentContext.baseContext
        }
        return null
    }

    override fun load(context: Context) {
        val sharedPref = context.getSharedPreferences("MovieBox", Context.MODE_PRIVATE)
        registerMainAPI(MovieBoxProvider(sharedPref))
        
        openSettings = {
            val activity = getActivity(context)
            if (activity != null && !activity.isFinishing && !activity.isDestroyed) {
                try {
                    val fragment = SettingsFragment(this, sharedPref)
                    fragment.show(activity.supportFragmentManager, "MovieBoxSettings")
                } catch (e: Throwable) {
                    e.printStackTrace()
                }
            }
        }
    }
}
