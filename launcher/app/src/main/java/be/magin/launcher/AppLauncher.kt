package be.magin.launcher

import android.app.Activity
import android.app.ActivityOptions
import android.content.ActivityNotFoundException
import android.content.Intent
import android.media.tv.TvContract
import android.net.Uri
import android.os.Bundle
import android.provider.Settings

/** Starts apps and system screens from the launcher. Every method returns false if nothing could open. */
class AppLauncher(private val activity: Activity) {

    /** The home tile has already grown to full screen, so the app just fades in over it. */
    fun launch(pkg: String): Boolean {
        val pm = activity.packageManager
        val intent = pm.getLeanbackLaunchIntentForPackage(pkg) ?: pm.getLaunchIntentForPackage(pkg)
            ?: return start(Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$pkg")))
        val options = ActivityOptions.makeCustomAnimation(activity, android.R.anim.fade_in, android.R.anim.fade_out)
        return start(intent, options.toBundle())
    }

    fun openSettings(): Boolean = start(Intent(Settings.ACTION_SETTINGS))

    /** HDMI / antenna inputs. Set Config.SOURCES_PACKAGE if your firmware needs a specific app. */
    fun openSources(): Boolean {
        Config.SOURCES_PACKAGE?.let { pkg ->
            val pm = activity.packageManager
            val intent = pm.getLeanbackLaunchIntentForPackage(pkg) ?: pm.getLaunchIntentForPackage(pkg)
            if (intent != null && start(intent)) return true
        }
        return start(Intent("com.android.tv.action.VIEW_INPUTS")) ||
            start(Intent(Intent.ACTION_VIEW, TvContract.Channels.CONTENT_URI)) ||
            openSettings()
    }

    fun openAppInfo(pkg: String): Boolean =
        start(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$pkg")))

    private fun start(intent: Intent, options: Bundle? = null): Boolean = try {
        activity.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), options)
        true
    } catch (e: ActivityNotFoundException) {
        false
    } catch (e: SecurityException) {
        false
    }
}
