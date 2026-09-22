package com.moviebox.mobile

import android.content.ComponentName
import android.content.Context
import android.content.DialogInterface
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.content.res.Resources
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.util.DisplayMetrics
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.DialogFragment
import java.util.ArrayList

class SettingsFragment(
    private val plugin: MovieBoxProviderPlugin,
    private val sharedPref: SharedPreferences
) : DialogFragment() {

    private val res: Resources = plugin.resources ?: throw Exception("Unable to access plugin resources")
    
    // Explicit fixed URLs pool mapping
    private val HOST_POOL = listOf(
        "https://api6.aoneroom.com",     // Node 1
        "https://api5.aoneroom.com",     // Node 2
        "https://api4sg.aoneroom.com",   // Node 3
        "https://api4.aoneroom.com",     // Node 4
        "https://api3.aoneroom.com"      // Node 5
    )

    // Descriptions to display in the user selection dialog
    private val HOST_LABELS = arrayOf(
        "Node 1: High-Speed India Server",
        "Node 2: Primary India BFF Cluster",
        "Node 3: Singapore Regional Gateway",
        "Node 4: Global Fallback Mirror 1",
        "Node 5: Global Fallback Mirror 2"
    )

    private fun getDrawable(name: String): Drawable {
        val id = res.getIdentifier(name, "drawable", "com.xtron")
        val drawable = res.getDrawable(id, null)
        if (drawable != null) return drawable
        throw Exception("Drawable $name not found")
    }

    private fun <T : View> findView(view: View, name: String): T {
        val id = res.getIdentifier(name, "id", "com.xtron")
        if (id == 0) throw Exception("View ID $name not found.")
        return view.findViewById(id) as T
    }

    private fun makeTvCompatible(view: View) {
        val outlineId = res.getIdentifier("outline", "drawable", "com.xtron")
        if (outlineId != 0) {
            view.background = res.getDrawable(outlineId, null)
        }
    }

    override fun onStart() {
        super.onStart()
        dialog?.window?.apply {
            val displayMetrics = resources.displayMetrics
            val maxDialogWidth = (500 * displayMetrics.density).toInt()
            val width = if (displayMetrics.widthPixels > 0 && displayMetrics.widthPixels > maxDialogWidth) {
                maxDialogWidth
            } else {
                (displayMetrics.widthPixels * 0.9f).toInt()
            }
            setLayout(width, ViewGroup.LayoutParams.WRAP_CONTENT)
            setBackgroundDrawable(ColorDrawable(0))
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val ctx = requireContext()

        val layoutId = res.getIdentifier("fragment_moviebox_settings", "layout", "com.xtron")
        val layoutParser = res.getLayout(layoutId)
        val view = inflater.inflate(layoutParser, container, false)

        val drawableId = res.getIdentifier("dialog_background", "drawable", "com.xtron")
        if (drawableId != 0) {
            view.background = res.getDrawable(drawableId, null)
        }

        val saveIcon = findView<ImageView>(view, "saveIcon")
        val hostIcon = findView<ImageView>(view, "hostIcon")
        val hostRow = findView<View>(view, "hostRow")
        val hostSubtitle = findView<TextView>(view, "hostSubtitle")

        saveIcon.setImageDrawable(getDrawable("save_icon"))
        hostIcon.setImageDrawable(getDrawable("settings_icon"))
        
        val saveContainer = try { findView<View>(view, "saveContainer") } catch(_: Exception) { null }
        if (saveContainer != null) {
            makeTvCompatible(saveContainer)
            saveContainer.setOnClickListener { saveIcon.performClick() }
        } else {
            makeTvCompatible(saveIcon)
        }

        try { hostRow.background = getDrawable("settings_item_background") } catch(_: Exception) {}
        try { findView<View>(view, "host_container").background = getDrawable("ic_icon_bg_blue") } catch(_: Exception) {}
        try { findView<ImageView>(view, "chevron_host").setImageDrawable(getDrawable("ic_chevron")) } catch(_: Exception) {}

        var currentHostIndex = HOST_POOL.indexOf(sharedPref.getString("moviebox_host", HOST_POOL[0])).coerceAtLeast(0)

        // Show a clean user-friendly label under the menu entry
        hostSubtitle.text = "Current: ${HOST_LABELS[currentHostIndex].substringAfter(": ")}"

        hostRow.setOnClickListener {
            AlertDialog.Builder(ctx)
                .setTitle("Select API Host Node")
                .setSingleChoiceItems(HOST_LABELS, currentHostIndex) { dialog, which ->
                    currentHostIndex = which
                    val selectedUrl = HOST_POOL[which]
                    sharedPref.edit().putString("moviebox_host", selectedUrl).apply()
                    hostSubtitle.text = "Current: ${HOST_LABELS[which].substringAfter(": ")}"
                    dialog.dismiss()
                }
                .setNegativeButton("Cancel", null)
                .show()
        }

        saveIcon.setOnClickListener {
            AlertDialog.Builder(ctx)
                .setTitle("Save & Reload")
                .setMessage("Changes have been saved. Do you want to restart the app to apply them?")
                .setPositiveButton("Yes") { _, _ ->
                    dismiss()
                    restartApp()
                }
                .setNegativeButton("No", null)
                .show()
        }

        return view
    }

    private fun restartApp() {
        val context = requireContext().applicationContext
        val packageManager = context.packageManager
        val intent = packageManager.getLaunchIntentForPackage(context.packageName)
        val componentName = intent?.component

        if (componentName != null) {
            val restartIntent = Intent.makeRestartActivityTask(componentName)
            context.startActivity(restartIntent)
            Runtime.getRuntime().exit(0)
        }
    }
}
