package be.magin.launcher.model

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream

/** What replaces the background while an app has focus: its animation, or a still as a fallback. */
data class AppMedia(val video: Uri?, val still: ImageBitmap?) {
    val isEmpty get() = video == null && still == null
}

/**
 * Wallpapers (photos/) and focus animations (anim/<key>.mp4) come from, in order:
 *  1. /sdcard/Android/data/be.magin.launcher/files/{photos,anim}/ — what tools/extract_tv_assets.py
 *     pushes, no rebuild needed;
 *  2. the APK's assets/{photos,anim}/ if you copied the files there before building.
 */
class MediaLibrary(private val context: Context) {
    private val imageExtensions = setOf("jpg", "jpeg", "png", "webp")
    private val videoExtensions = setOf("mp4", "webm", "mkv")

    private fun externalFiles(dir: String): List<File> =
        context.getExternalFilesDir(dir)?.listFiles()?.filter { it.isFile }?.sortedBy { it.name }.orEmpty()

    private fun assetFiles(dir: String): List<String> =
        runCatching { context.assets.list(dir)?.sorted() }.getOrNull().orEmpty()

    private fun String.ext() = substringAfterLast('.', "").lowercase()

    /** Cheap fingerprint so wallpapers are only decoded again when the files changed. */
    fun wallpaperSignature(): String =
        externalFiles("photos").joinToString { "${it.name}:${it.lastModified()}" } + "|" + assetFiles("photos").joinToString()

    suspend fun loadWallpapers(): List<ImageBitmap> = withContext(Dispatchers.IO) {
        val external = externalFiles("photos").filter { it.extension.lowercase() in imageExtensions }
        if (external.isNotEmpty()) {
            external.mapNotNull { file -> decode { file.inputStream() } }
        } else {
            assetFiles("photos").filter { it.ext() in imageExtensions }
                .mapNotNull { name -> decode { context.assets.open("photos/$name") } }
        }
    }

    /** Blocking; call from Dispatchers.IO. */
    fun appMedia(key: String): AppMedia {
        val external = externalFiles("anim").filter { it.nameWithoutExtension == key }
        val assets = assetFiles("anim").filter { it.substringBeforeLast('.') == key }

        val video = external.firstOrNull { it.extension.lowercase() in videoExtensions }?.let(Uri::fromFile)
            ?: assets.firstOrNull { it.ext() in videoExtensions }?.let { Uri.parse("asset:///anim/$it") }
        if (video != null) return AppMedia(video, null)

        val still = external.firstOrNull { it.extension.lowercase() in imageExtensions }?.let { f -> decode { f.inputStream() } }
            ?: assets.firstOrNull { it.ext() in imageExtensions }?.let { n -> decode { context.assets.open("anim/$n") } }
        return AppMedia(null, still)
    }

    private fun decode(maxWidth: Int = 1920, open: () -> InputStream): ImageBitmap? = runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        open().use { BitmapFactory.decodeStream(it, null, bounds) }
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= maxWidth) sample *= 2
        open().use { BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample }) }
            ?.asImageBitmap()
    }.getOrNull()
}
