package be.magin.launcher.model

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.graphics.drawable.toBitmap
import java.text.Collator
import java.text.Normalizer
import java.util.Locale

data class AppEntry(
    val packageName: String,
    val label: String,
    val banner: ImageBitmap?,
    val icon: ImageBitmap?,
)

/** A favourite's tile style: the service's colours with its name set in Inter (no official logos). */
data class Brand(
    /** Also the file name of its focus animation: anim/<key>.mp4. */
    val key: String,
    val label: String,
    val kicker: String,
    val background: Brush,
    val foreground: Color,
    val packages: List<String>,
    val labelHint: String,
) {
    val title get() = if (kicker.isEmpty()) label else "$kicker $label"
}

object Brands {
    private val Light = Color(0xFFF3F5FE)

    val all = listOf(
        Brand(
            "youtube", "YouTube", "", SolidColor(Color(0xFFFF0033)), Light,
            listOf("com.google.android.youtube.tv", "com.google.android.youtube.googletv"), "youtube",
        ),
        Brand(
            "auvio", "auvio", "RTBF", Brush.linearGradient(listOf(Color(0xFF0C1033), Color(0xFF23145A))), Light,
            listOf("be.rtbf.auvio", "be.rtbf.auvio.tv", "be.rtbf.auvio.androidtv"), "auvio",
        ),
        Brand(
            "crunchyroll", "crunchyroll", "", SolidColor(Color(0xFFF47521)), Light,
            listOf("com.crunchyroll.crunchyroid"), "crunchyroll",
        ),
        Brand(
            "prime", "prime video", "", SolidColor(Color(0xFF0F171E)), Color(0xFF1A98FF),
            listOf("com.amazon.amazonvideo.livingroom", "com.amazon.avod.thirdpartyclient"), "prime video",
        ),
    )

    fun forPackage(pkg: String, app: AppEntry?): Brand? =
        all.firstOrNull { pkg in it.packages }
            ?: app?.let { a -> all.firstOrNull { a.label.contains(it.labelHint, ignoreCase = true) } }

    /** The four default favourites, resolved to whichever package is actually installed. */
    fun defaultFavorites(apps: List<AppEntry>): List<String> = all.map { brand ->
        apps.firstOrNull { forPackage(it.packageName, it) == brand }?.packageName ?: brand.packages.first()
    }
}

/** A dock entry. [app] is null when the package isn't installed (OK then opens the Play Store). */
data class Favorite(val packageName: String, val app: AppEntry?, val brand: Brand?) {
    val mediaKey get() = brand?.key ?: packageName
    val title get() = brand?.title ?: app?.label ?: packageName

    companion object {
        fun of(pkg: String, apps: List<AppEntry>): Favorite {
            val app = apps.firstOrNull { it.packageName == pkg }
            return Favorite(pkg, app, Brands.forPackage(pkg, app))
        }
    }
}

class AppRepository(private val context: Context) {

    /** Every launchable app (TV apps first, then phone-style ones), sorted A–Z. */
    fun load(): List<AppEntry> {
        val pm = context.packageManager
        val seen = LinkedHashMap<String, AppEntry>()
        for (category in listOf(Intent.CATEGORY_LEANBACK_LAUNCHER, Intent.CATEGORY_LAUNCHER)) {
            val intent = Intent(Intent.ACTION_MAIN).addCategory(category)
            for (info in pm.queryIntentActivities(intent, 0)) {
                val activity = info.activityInfo
                val pkg = activity.packageName
                if (pkg == context.packageName || pkg in seen) continue
                val banner = activity.loadBanner(pm) ?: activity.applicationInfo.loadBanner(pm)
                seen[pkg] = AppEntry(
                    packageName = pkg,
                    label = info.loadLabel(pm).toString(),
                    banner = runCatching { banner?.toBitmap(320, 180, Bitmap.Config.ARGB_8888)?.asImageBitmap() }.getOrNull(),
                    icon = runCatching { info.loadIcon(pm).toBitmap(96, 96, Bitmap.Config.ARGB_8888).asImageBitmap() }.getOrNull(),
                )
            }
        }
        val collator = Collator.getInstance(Locale.FRENCH).apply { strength = Collator.PRIMARY }
        return seen.values.sortedWith { a, b -> collator.compare(a.label, b.label) }
    }
}

/** A–Z rail letter for a label: accents stripped, digits and symbols grouped under "#". */
fun initialOf(label: String): String {
    val c = Normalizer.normalize(label.trim().take(1), Normalizer.Form.NFD).firstOrNull()?.uppercaseChar() ?: return "#"
    return if (c in 'A'..'Z') c.toString() else "#"
}
