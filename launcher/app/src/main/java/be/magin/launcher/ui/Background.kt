package be.magin.launcher.ui

import android.graphics.Matrix
import android.net.Uri
import android.view.TextureView
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.exoplayer.ExoPlayer
import be.magin.launcher.model.AppMedia
import kotlinx.coroutines.delay
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

private const val FADE_MS = 1400

/**
 * The home background: TV photos crossfading (1.4 s) with a slow Ken Burns zoom, replaced by the
 * focused app's animation once [appKey] is set. Everything goes through `.lighten` like the prototype.
 */
@Composable
fun HomeBackground(
    wallpapers: List<ImageBitmap>,
    wallpaperIndex: Int,
    appKey: String?,
    appMedia: Map<String, AppMedia>,
    active: Boolean,
) {
    val media = appKey?.let { appMedia[it] }?.takeUnless { it.isEmpty }
    val showSlides = media == null

    // @keyframes kb { from { scale(1) } to { scale(1.1) translate(-1.5%,-1%) } } 16s ease-in-out infinite alternate
    val kb by rememberInfiniteTransition(label = "kb").animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(16_000, easing = CssEaseInOut), RepeatMode.Reverse),
        label = "kb",
    )

    Box(Modifier.fillMaxSize().background(Nocturne.Bg)) {
        if (wallpapers.isEmpty()) FallbackGround()

        wallpapers.forEachIndexed { i, image ->
            val alpha by animateFloatAsState(
                if (showSlides && i == wallpaperIndex) 1f else 0f, tween(FADE_MS, easing = CssEase), label = "wp$i",
            )
            if (alpha > 0f) {
                Canvas(
                    Modifier.fillMaxSize().graphicsLayer {
                        this.alpha = alpha
                        val s = 1f + 0.1f * kb
                        scaleX = s
                        scaleY = s
                        translationX = -0.015f * size.width * s * kb
                        translationY = -0.01f * size.height * s * kb
                    },
                ) { drawLighten(image) }
            }
        }

        // Stills (when an app has no video) crossfade like the photos.
        appMedia.forEach { (key, m) ->
            val still = m.still ?: return@forEach
            val alpha by animateFloatAsState(
                if (key == appKey) 1f else 0f, tween(FADE_MS, easing = CssEase), label = "still-$key",
            )
            if (alpha > 0f) Canvas(Modifier.fillMaxSize().graphicsLayer { this.alpha = alpha }) { drawLighten(still) }
        }

        // Two players, so moving A → B crossfades instead of cutting.
        val target = media?.video
        var slots by remember { mutableStateOf(listOf<Uri?>(null, null)) }
        var current by remember { mutableIntStateOf(0) }
        LaunchedEffect(target) {
            if (target != null && slots[current] != target) {
                val next = 1 - current
                if (slots[next] != target) slots = slots.toMutableList().also { it[next] = target }
                current = next
            }
        }
        for (i in 0..1) {
            VideoSlot(uri = slots[i], visible = target != null && i == current && slots[i] == target, active = active)
        }
    }
}

/** `radial-gradient(90% 80% at 15% 0%, neutral-900, bg 65%)` while no photo has been provided. */
@Composable
private fun FallbackGround() {
    Canvas(Modifier.fillMaxSize()) {
        drawRect(
            Brush.radialGradient(
                0f to Nocturne.Neutral900,
                0.65f to Nocturne.Bg,
                center = Offset(size.width * 0.15f, 0f),
                radius = size.width * 0.9f,
            ),
        )
    }
}

/** `.lighten` + `object-fit: cover`: anything darker than the ground falls away. */
private fun DrawScope.drawLighten(image: ImageBitmap) {
    drawRect(Nocturne.Bg)
    val scale = max(size.width / image.width, size.height / image.height)
    val srcW = min(image.width, (size.width / scale).roundToInt())
    val srcH = min(image.height, (size.height / scale).roundToInt())
    drawImage(
        image = image,
        srcOffset = IntOffset((image.width - srcW) / 2, (image.height - srcH) / 2),
        srcSize = IntSize(srcW, srcH),
        dstSize = IntSize(size.width.roundToInt(), size.height.roundToInt()),
        blendMode = BlendMode.Lighten,
        filterQuality = FilterQuality.Medium,
    )
}

/** One looping, muted animation. It restarts from the beginning each time it is shown. */
@Composable
private fun VideoSlot(uri: Uri?, visible: Boolean, active: Boolean) {
    val context = LocalContext.current
    var ready by remember { mutableStateOf(false) }
    var view by remember { mutableStateOf<TextureView?>(null) }
    val player = remember {
        ExoPlayer.Builder(context).build().apply {
            volume = 0f
            repeatMode = Player.REPEAT_MODE_ONE
        }
    }

    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onRenderedFirstFrame() {
                ready = true
            }

            override fun onVideoSizeChanged(videoSize: VideoSize) {
                view?.let { fitCover(it, videoSize) }
            }

            override fun onPlayerError(error: PlaybackException) {
                ready = false
            }
        }
        player.addListener(listener)
        onDispose {
            player.removeListener(listener)
            player.release()
        }
    }

    LaunchedEffect(uri) {
        ready = false
        if (uri == null) {
            player.clearMediaItems()
        } else {
            player.setMediaItem(MediaItem.fromUri(uri))
            player.prepare()
        }
    }

    LaunchedEffect(visible, active, uri) {
        if (visible && active && uri != null) {
            player.seekTo(0)
            player.play()
        } else {
            delay(FADE_MS.toLong())
            player.pause()
        }
    }

    val alpha by animateFloatAsState(
        if (visible && ready) 1f else 0f, tween(FADE_MS, easing = CssEase), label = "video",
    )
    AndroidView(
        factory = { ctx ->
            TextureView(ctx).also { tv ->
                view = tv
                player.setVideoTextureView(tv)
                tv.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> fitCover(tv, player.videoSize) }
            }
        },
        modifier = Modifier.fillMaxSize().graphicsLayer { this.alpha = alpha },
    )
}

/** `object-fit: cover` for a TextureView, which otherwise stretches the video. */
private fun fitCover(view: TextureView, size: VideoSize) {
    val w = view.width.toFloat()
    val h = view.height.toFloat()
    if (w == 0f || h == 0f || size.width == 0 || size.height == 0) return
    val videoW = size.width * size.pixelWidthHeightRatio
    val videoH = size.height.toFloat()
    val s = max(w / videoW, h / videoH)
    view.setTransform(Matrix().apply { setScale(videoW * s / w, videoH * s / h, w / 2f, h / 2f) })
}
