package de.mirranet.ondura.mail

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.browser.customtabs.CustomTabsIntent

object Browser {
    /**
     * Opens `url` in a Custom Tab, inside our own task when an activity is on
     * screen so the user comes back to Ondura after signing in.
     */
    fun open(context: Context, url: String): Boolean {
        // Called from a thread of the Rust core: start the activity on the main thread.
        Handler(Looper.getMainLooper()).post { launch(context, url) }
        return true
    }

    private fun launch(context: Context, url: String): Boolean {
        val activity: Activity? = (context.applicationContext as? OnduraMailApp)?.foregroundActivity
        return try {
            val intent = CustomTabsIntent.Builder().setShowTitle(true).build()
            if (activity != null) {
                intent.launchUrl(activity, Uri.parse(url))
            } else {
                intent.intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                intent.launchUrl(context, Uri.parse(url))
            }
            true
        } catch (e: Exception) {
            try {
                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                true
            } catch (_: Exception) {
                false
            }
        }
    }

    /** Back to Ondura after the sign-in page said it is done (closes the tab). */
    fun returnToApp(context: Context) {
        val intent = Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        runCatching { context.startActivity(intent) }
    }
}
