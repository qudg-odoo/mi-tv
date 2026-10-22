package be.magin.launcher.ui

import android.os.Build
import android.view.KeyEvent
import androidx.activity.compose.BackHandler
import androidx.annotation.DrawableRes
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.lerp
import androidx.compose.ui.util.lerp
import androidx.compose.ui.zIndex
import be.magin.launcher.R
import be.magin.launcher.model.AppMedia
import be.magin.launcher.model.Favorite
import be.magin.launcher.model.MediaLibrary
import be.magin.launcher.model.Weather
import be.magin.launcher.model.weatherIcon
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt

private const val SLIDE_MS = 7_000L
private const val FOCUS_TO_BACKGROUND_MS = 450L
private const val FOCUS_MS = 180

enum class HomeFocus { None, Apps }

private enum class LaunchPhase { Expanding, Launched, Returning }

private class LaunchState(val favorite: Favorite, val from: Rect) {
    var phase by mutableStateOf(LaunchPhase.Expanding)
}

/** Option 3a of the prototype: photo background, clock + weather, glass dock. */
@Composable
fun HomeScreen(
    favorites: List<Favorite>,
    wallpapers: List<ImageBitmap>,
    media: MediaLibrary,
    weather: Weather?,
    active: Boolean,
    resumeTick: Int,
    homeTick: Int,
    initialFocus: HomeFocus,
    moving: String?,
    onLaunch: (Favorite) -> Boolean,
    onOpenApps: () -> Unit,
    onSources: () -> Unit,
    onSettings: () -> Unit,
    onFavoriteMenu: (Favorite) -> Unit,
    onMove: (String, Int) -> Unit,
    onMoveDone: () -> Unit,
) {
    val requesters = remember { mutableMapOf<String, FocusRequester>() }
    fun requester(pkg: String) = requesters.getOrPut(pkg) { FocusRequester() }
    val appsRequester = remember { FocusRequester() }
    val parkRequester = remember { FocusRequester() }
    val tileBounds = remember { HashMap<String, Rect>() }

    var parked by remember { mutableStateOf(false) }
    var focusedKey by remember { mutableStateOf<String?>(null) }
    var backgroundKey by remember { mutableStateOf<String?>(null) }
    var wallpaperIndex by remember { mutableIntStateOf(0) }
    var launching by remember { mutableStateOf<LaunchState?>(null) }

    // 450 ms after a favourite takes focus, its animation replaces the background.
    LaunchedEffect(focusedKey) {
        if (focusedKey == null) {
            backgroundKey = null
        } else {
            delay(FOCUS_TO_BACKGROUND_MS)
            backgroundKey = focusedKey
        }
    }

    val appMedia by produceState(emptyMap<String, AppMedia>(), favorites.map { it.mediaKey }, resumeTick) {
        value = withContext(Dispatchers.IO) { favorites.associate { it.mediaKey to media.appMedia(it.mediaKey) } }
    }
    val backgroundFavorite = favorites.firstOrNull { it.packageName == backgroundKey }
    val appBackgroundShown = backgroundFavorite?.let { appMedia[it.mediaKey] }?.isEmpty == false

    val currentBackground by rememberUpdatedState(backgroundKey)
    LaunchedEffect(wallpapers.size, active) {
        while (active && wallpapers.size > 1) {
            delay(SLIDE_MS)
            if (currentBackground == null) wallpaperIndex = (wallpaperIndex + 1) % wallpapers.size
        }
    }

    // First entry honours initialFocus; pressing Home again goes back to the slideshow.
    var firstFocus by remember { mutableStateOf(true) }
    LaunchedEffect(homeTick) {
        val target = if (firstFocus && initialFocus == HomeFocus.Apps) appsRequester else parkRequester
        firstFocus = false
        runCatching { target.requestFocus() }
    }

    // Back from the launched app: the full-screen tile fades out over the home screen.
    LaunchedEffect(resumeTick) {
        launching?.let { if (it.phase == LaunchPhase.Launched) it.phase = LaunchPhase.Returning }
    }

    LaunchedEffect(moving, favorites) {
        moving?.let { runCatching { requester(it).requestFocus() } }
    }

    BackHandler {
        when {
            moving != null -> onMoveDone()
            launching == null -> runCatching { parkRequester.requestFocus() }
        }
    }

    fun focusDock() {
        val first = favorites.firstOrNull()
        runCatching { (if (first != null) requester(first.packageName) else appsRequester).requestFocus() }
    }

    fun launch(fav: Favorite) {
        if (launching != null || moving != null) return
        focusedKey = fav.packageName
        backgroundKey = fav.packageName
        launching = LaunchState(fav, tileBounds[fav.packageName] ?: Rect.Zero)
    }

    val ui by animateFloatAsState(if (launching != null) 1f else 0f, tween(600, easing = CssEase), label = "ui")

    Box(
        Modifier
            .fillMaxSize()
            .onPreviewKeyEvent { e ->
                if (launching != null) return@onPreviewKeyEvent true
                val m = moving ?: return@onPreviewKeyEvent false
                if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent true
                when (e.nativeKeyEvent.keyCode) {
                    KeyEvent.KEYCODE_DPAD_LEFT -> onMove(m, -1)
                    KeyEvent.KEYCODE_DPAD_RIGHT -> onMove(m, 1)
                    KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_BACK -> onMoveDone()
                }
                true
            },
    ) {
        HomeBackground(wallpapers, wallpaperIndex, backgroundFavorite?.mediaKey, appMedia, active)

        // UI layer: blurs, dims and recedes while an app launches.
        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer {
                    val s = 1f - 0.04f * ui
                    scaleX = s
                    scaleY = s
                    compositingStrategy = if (ui > 0f) CompositingStrategy.Offscreen else CompositingStrategy.Auto
                    val blur = 10.dp.toPx() * ui
                    renderEffect = if (Build.VERSION.SDK_INT >= 31 && blur >= 0.5f) BlurEffect(blur, blur, TileMode.Decal) else null
                }
                .drawWithContent {
                    drawContent()
                    if (ui > 0f) drawRect(Color.Black.copy(alpha = 0.4f * ui), blendMode = BlendMode.SrcAtop)
                },
        ) {
            Scrim()
            ClockAndWeather(weather, Modifier.offset(56.dp, 110.dp))
            if (wallpapers.size > 1) {
                SlideDots(wallpapers.size, wallpaperIndex, !appBackgroundShown, Modifier.align(Alignment.TopEnd).padding(top = 40.dp, end = 56.dp))
            }
            Dock(Modifier.align(Alignment.BottomStart).padding(start = 56.dp, bottom = 40.dp)) {
                favorites.forEachIndexed { i, fav ->
                    key(fav.packageName) {
                        FavoriteTile(
                            favorite = fav,
                            requester = requester(fav.packageName),
                            isFirst = i == 0,
                            moving = moving == fav.packageName,
                            onFocus = { focused ->
                                if (focused) focusedKey = fav.packageName
                                else if (focusedKey == fav.packageName) focusedKey = null
                            },
                            onBounds = { tileBounds[fav.packageName] = it },
                            onClick = { launch(fav) },
                            onLongClick = { if (moving == null) onFavoriteMenu(fav) },
                        )
                    }
                }
                if (favorites.isNotEmpty()) Box(Modifier.padding(horizontal = 4.dp).size(1.dp, 44.dp).background(Nocturne.Neutral700))
                DockAction(R.drawable.ph_squares_four, "Apps", isFirst = favorites.isEmpty(), requester = appsRequester, onClick = onOpenApps)
                DockAction(R.drawable.ph_plugs, "Sources", onClick = onSources)
                DockAction(R.drawable.ph_gear_six, "Réglages", isLast = true, onClick = onSettings)
            }
            Txt(
                if (moving != null) "◀ ▶ déplacer · OK terminer" else "◀ ▶ choisir · OK lancer · ≡ options · Retour",
                11f,
                Modifier.align(Alignment.BottomEnd).padding(end = 56.dp, bottom = 20.dp),
                color = Nocturne.Neutral500,
            )
        }

        launching?.let { state ->
            key(state) {
                LaunchOverlay(
                    state = state,
                    onExpanded = {
                        state.phase = if (onLaunch(state.favorite)) LaunchPhase.Launched else LaunchPhase.Returning
                    },
                    onFinished = { launching = null },
                )
            }
            // Safety net: if the app never came to the foreground, give the home screen back.
            val stillActive by rememberUpdatedState(active)
            LaunchedEffect(state, state.phase) {
                if (state.phase == LaunchPhase.Launched) {
                    delay(5_000)
                    if (stillActive && state.phase == LaunchPhase.Launched) state.phase = LaunchPhase.Returning
                }
            }
        }

        // Invisible parking spot: holding focus here means "no app selected", so the slideshow runs.
        Box(
            Modifier
                .size(1.dp)
                .focusRequester(parkRequester)
                .onFocusChanged { parked = it.isFocused }
                .onKeyEvent { e ->
                    val code = e.nativeKeyEvent.keyCode
                    if (parked && e.type == KeyEventType.KeyDown &&
                        (code == KeyEvent.KEYCODE_DPAD_LEFT || code == KeyEvent.KEYCODE_DPAD_RIGHT ||
                            code == KeyEvent.KEYCODE_DPAD_UP || code == KeyEvent.KEYCODE_DPAD_DOWN)
                    ) {
                        focusDock(); true
                    } else {
                        false
                    }
                }
                .focusable(),
        )
    }
}

/** linear-gradient(90deg, bg/85% 0%, transparent 55%), linear-gradient(0deg, bg/90% 0%, transparent 45%) */
@Composable
private fun Scrim() {
    Canvas(Modifier.fillMaxSize()) {
        drawRect(Brush.horizontalGradient(0f to Nocturne.Bg.copy(alpha = 0.85f), 0.55f to Nocturne.Bg.copy(alpha = 0f)))
        drawRect(Brush.verticalGradient(0.55f to Nocturne.Bg.copy(alpha = 0f), 1f to Nocturne.Bg.copy(alpha = 0.9f)))
    }
}

private val timeFormat = DateTimeFormatter.ofPattern("HH:mm", Locale.FRENCH)
private val dateFormat = DateTimeFormatter.ofPattern("EEEE d MMMM", Locale.FRENCH)

@Composable
private fun ClockAndWeather(weather: Weather?, modifier: Modifier = Modifier) {
    val now by produceState(LocalDateTime.now()) {
        while (true) {
            value = LocalDateTime.now()
            delay(60_000L - System.currentTimeMillis() % 60_000L)
        }
    }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Txt(now.format(timeFormat), 96f, weight = FontWeight.Light, tracking = -0.04f, lineHeight = 0.9f)
        Txt(now.format(dateFormat).replaceFirstChar { it.titlecase(Locale.FRENCH) }, 18f, color = Nocturne.Neutral300)
        WeatherCard(weather, Modifier.padding(top = 18.dp))
    }
}

@Composable
private fun WeatherCard(weather: Weather?, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(Nocturne.RadiusLg)
    Row(
        modifier
            .elevation(Elevation.Md, Nocturne.RadiusLg)
            .background(Nocturne.Glass, shape)
            .border(1.dp, Nocturne.Neutral800, shape)
            .padding(horizontal = 18.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(22.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            PhIcon(weather?.let { weatherIcon(it.code, it.isDay) } ?: R.drawable.ph_cloud, 36.dp, Nocturne.Accent300)
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Txt(weather?.let { "${it.temperature}°" } ?: "–°", 28f, lineHeight = 1f)
                Txt(
                    weather?.let { "${it.city} · ${it.min}° / ${it.max}°" } ?: "Météo…",
                    11f,
                    color = Nocturne.Neutral400,
                )
            }
        }
        if (weather != null && weather.days.isNotEmpty()) {
            Box(Modifier.size(1.dp, 38.dp).background(Nocturne.Neutral800))
            Row(horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                weather.days.forEach { d ->
                    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Txt(d.day, 11f, color = Nocturne.Neutral400)
                        PhIcon(weatherIcon(d.code), 20.dp, Nocturne.Neutral200)
                        Txt("${d.max}°", 11f)
                    }
                }
            }
        }
    }
}

@Composable
private fun SlideDots(count: Int, index: Int, slideshowShown: Boolean, modifier: Modifier = Modifier) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        repeat(count) { i ->
            val on = slideshowShown && i == index
            val width by animateDpAsState(if (on) 20.dp else 6.dp, tween(400, easing = CssEase), label = "dot$i")
            Box(
                Modifier
                    .size(width, 4.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(if (on) Nocturne.Text else Nocturne.Neutral600),
            )
        }
    }
}

@Composable
private fun Dock(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val shape = RoundedCornerShape(Nocturne.RadiusLg)
    Row(
        modifier
            .elevation(Elevation.Lg, Nocturne.RadiusLg)
            .background(Nocturne.Glass, shape)
            .border(1.dp, Nocturne.Neutral800, shape)
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) { content() }
}

@Composable
private fun FavoriteTile(
    favorite: Favorite,
    requester: FocusRequester,
    isFirst: Boolean,
    moving: Boolean,
    onFocus: (Boolean) -> Unit,
    onBounds: (Rect) -> Unit,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    val p by animateFloatAsState(if (focused) 1f else 0f, tween(FOCUS_MS, easing = CssEase), label = "focus")
    Box(
        Modifier
            .zIndex(if (focused) 1f else 0f)
            .focusRequester(requester)
            .focusProperties {
                up = FocusRequester.Cancel
                down = FocusRequester.Cancel
                if (isFirst) left = FocusRequester.Cancel
            }
            .onFocusChanged { focused = it.isFocused; onFocus(it.isFocused) }
            .dpadClickable(onClick, onLongClick)
            .onGloballyPositioned { onBounds(it.boundsInRoot()) }
            .graphicsLayer {
                val s = 1f + 0.1f * p
                scaleX = s
                scaleY = s
            }
            .focusRing({ p }, Nocturne.RadiusMd, ring = if (moving) Nocturne.Accent else Nocturne.Accent200)
            .size(128.dp, 72.dp)
            .clip(RoundedCornerShape(Nocturne.RadiusMd)),
    ) {
        TileFace(favorite)
        if (moving) {
            Txt("◀ ▶", 11f, Modifier.align(Alignment.TopCenter), color = Nocturne.Neutral100.copy(alpha = 0.8f))
        }
    }
}

/** A favourite's face: brand colours and name, else the app's banner, else icon + label. */
@Composable
private fun TileFace(favorite: Favorite, labelScale: Float = 1f) {
    val brand = favorite.brand
    val banner = favorite.app?.banner
    when {
        brand != null -> Box(Modifier.fillMaxSize().background(brand.background), contentAlignment = Alignment.Center) {
            Column(
                Modifier.graphicsLayer { scaleX = labelScale; scaleY = labelScale },
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                if (brand.kicker.isNotEmpty()) {
                    Txt(brand.kicker, 8f, color = brand.foreground.copy(alpha = 0.7f), weight = FontWeight.Medium, tracking = 0.18f, lineHeight = 1.21f)
                }
                Txt(brand.label, 16f, color = brand.foreground, weight = FontWeight.SemiBold, tracking = -0.03f, lineHeight = 1.21f)
            }
        }
        banner != null -> Image(banner, favorite.title, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        else -> Column(
            Modifier.fillMaxSize().background(Nocturne.Neutral900).graphicsLayer { scaleX = labelScale; scaleY = labelScale },
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterVertically),
        ) {
            favorite.app?.icon?.let { Image(it, null, Modifier.size(28.dp)) }
            Txt(favorite.title, 11f, color = Nocturne.Neutral300)
        }
    }
}

@Composable
private fun DockAction(
    @DrawableRes icon: Int,
    label: String,
    isFirst: Boolean = false,
    isLast: Boolean = false,
    requester: FocusRequester? = null,
    onClick: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    val background by animateColorAsState(if (focused) Nocturne.Neutral800 else Color.Transparent, tween(FOCUS_MS), label = "bg")
    val content by animateColorAsState(if (focused) Nocturne.Text else Nocturne.Neutral400, tween(FOCUS_MS), label = "fg")
    Column(
        Modifier
            .then(if (requester != null) Modifier.focusRequester(requester) else Modifier)
            .focusProperties {
                up = FocusRequester.Cancel
                down = FocusRequester.Cancel
                if (isFirst) left = FocusRequester.Cancel
                if (isLast) right = FocusRequester.Cancel
            }
            .onFocusChanged { focused = it.isFocused }
            .dpadClickable(onClick)
            .size(64.dp, 72.dp)
            .background(background, RoundedCornerShape(Nocturne.RadiusMd)),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterVertically),
    ) {
        PhIcon(icon, 24.dp, content)
        Txt(label, 11f, color = content)
    }
}

/**
 * OK on a favourite: the tile grows from its dock rectangle to full screen (650 ms,
 * cubic-bezier(.3,0,.1,1), radius 8 → 0, label ×3), then the app starts. When we come back
 * it swells a little more and fades out (500 ms).
 */
@Composable
private fun LaunchOverlay(state: LaunchState, onExpanded: () -> Unit, onFinished: () -> Unit) {
    val progress = remember { Animatable(0f) }
    val swell = remember { Animatable(0f) }
    val alpha = remember { Animatable(1f) }

    LaunchedEffect(Unit) {
        progress.animateTo(1f, tween(650, easing = LaunchEasing))
        onExpanded()
    }
    LaunchedEffect(state.phase) {
        if (state.phase == LaunchPhase.Returning) {
            launch { swell.animateTo(1f, tween(650, easing = LaunchEasing)) }
            alpha.animateTo(0f, tween(500, easing = CssEase))
            onFinished()
        }
    }

    BoxWithConstraints(Modifier.fillMaxSize().graphicsLayer { this.alpha = alpha.value }) {
        val density = LocalDensity.current
        val p = progress.value
        val fullW = constraints.maxWidth.toFloat()
        val fullH = constraints.maxHeight.toFloat()
        val from = state.from.takeIf { it.width > 0f && it.height > 0f } ?: Rect(fullW * 0.45f, fullH * 0.45f, fullW * 0.55f, fullH * 0.55f)
        val left = lerp(from.left, 0f, p)
        val top = lerp(from.top, 0f, p)
        val width = lerp(from.width, fullW, p)
        val height = lerp(from.height, fullH, p)
        val shape = RoundedCornerShape(lerp(Nocturne.RadiusMd, 0.dp, p))
        Box(
            Modifier
                .offset { IntOffset(left.roundToInt(), top.roundToInt()) }
                .size(with(density) { width.toDp() }, with(density) { height.toDp() })
                .drawWithContent {
                    boxShadow(Color.Black.copy(alpha = 0.6f), 80.dp, lerp(Nocturne.RadiusMd, 0.dp, p), dy = 30.dp)
                    drawContent()
                }
                .clip(shape),
        ) {
            TileFace(state.favorite, labelScale = lerp(1f, 3f, p) + 0.4f * swell.value)
        }
    }
}
