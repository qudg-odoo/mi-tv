package be.magin.launcher.ui

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.widget.Toast
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import be.magin.launcher.AppLauncher
import be.magin.launcher.Config
import be.magin.launcher.model.AppEntry
import be.magin.launcher.model.AppRepository
import be.magin.launcher.model.Brands
import be.magin.launcher.model.Favorite
import be.magin.launcher.model.LauncherPrefs
import be.magin.launcher.model.MediaLibrary
import be.magin.launcher.model.Weather
import be.magin.launcher.model.WeatherRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private enum class Screen { Home, Apps }

@Composable
fun LauncherApp(resumeTick: Int, homeTick: Int, active: Boolean, launcher: AppLauncher) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val repository = remember { AppRepository(context) }
    val prefs = remember { LauncherPrefs(context) }
    val media = remember { MediaLibrary(context) }

    // Installed apps: reloaded on resume and whenever a package is added/removed/updated.
    var packageTick by remember { mutableIntStateOf(0) }
    DisposableEffect(Unit) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent) {
                packageTick++
            }
        }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_REMOVED)
            addAction(Intent.ACTION_PACKAGE_CHANGED)
            addDataScheme("package")
        }
        ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        onDispose { context.unregisterReceiver(receiver) }
    }
    var apps by remember { mutableStateOf(emptyList<AppEntry>()) }
    LaunchedEffect(resumeTick, packageTick) {
        apps = withContext(Dispatchers.IO) { repository.load() }
    }

    val settings by prefs.settings.collectAsState(initial = null)
    val favoritePackages = settings?.let { it.favorites ?: Brands.defaultFavorites(apps) }.orEmpty()
    val favorites = remember(favoritePackages, apps) { favoritePackages.map { Favorite.of(it, apps) } }
    val hidden = settings?.hidden.orEmpty()

    // Photos are only decoded again when the files on disk changed.
    var wallpapers by remember { mutableStateOf(emptyList<ImageBitmap>()) }
    var wallpaperSignature by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(resumeTick) {
        val signature = withContext(Dispatchers.IO) { media.wallpaperSignature() }
        if (signature != wallpaperSignature) {
            wallpaperSignature = signature
            wallpapers = media.loadWallpapers()
        }
    }

    val weather by produceState<Weather?>(null) {
        while (true) {
            runCatching { WeatherRepository.fetch() }.onSuccess { value = it }
            delay(if (value == null) 60_000L else 30 * 60_000L)
        }
    }

    var screen by remember { mutableStateOf(Screen.Home) }
    var homeFocus by remember { mutableStateOf(HomeFocus.None) }
    var menu by remember { mutableStateOf<MenuTarget?>(null) }
    var moving by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(homeTick) {
        if (homeTick > 0) {
            menu = null
            moving = null
            homeFocus = HomeFocus.None
            screen = Screen.Home
        }
    }

    fun saveFavorites(list: List<String>) = scope.launch { prefs.setFavorites(list) }

    fun togglePin(pkg: String) {
        when {
            pkg in favoritePackages -> saveFavorites(favoritePackages - pkg)
            favoritePackages.size >= Config.MAX_FAVORITES ->
                Toast.makeText(context, "Le dock est plein (${Config.MAX_FAVORITES} favoris max).", Toast.LENGTH_SHORT).show()
            else -> saveFavorites(favoritePackages + pkg)
        }
    }

    fun move(pkg: String, delta: Int) {
        val from = favoritePackages.indexOf(pkg)
        val to = from + delta
        if (from < 0 || to !in favoritePackages.indices) return
        saveFavorites(favoritePackages.toMutableList().apply { add(to, removeAt(from)) })
    }

    DesignCanvas {
        Crossfade(screen, animationSpec = tween(250), label = "screen") { current ->
            when (current) {
                Screen.Home -> HomeScreen(
                    favorites = favorites,
                    wallpapers = wallpapers,
                    media = media,
                    weather = weather,
                    active = active && menu == null,
                    resumeTick = resumeTick,
                    homeTick = homeTick,
                    initialFocus = homeFocus,
                    moving = moving,
                    onLaunch = { launcher.launch(it.packageName) },
                    onOpenApps = { screen = Screen.Apps },
                    onSources = { launcher.openSources() },
                    onSettings = { launcher.openSettings() },
                    onFavoriteMenu = { menu = MenuTarget(it.packageName, it.title, fromHome = true) },
                    onMove = ::move,
                    onMoveDone = { moving = null },
                )
                Screen.Apps -> AllAppsScreen(
                    apps = apps,
                    favorites = favorites,
                    hidden = hidden,
                    onOpen = { launcher.launch(it) },
                    onMenu = { menu = MenuTarget(it.packageName, it.label, fromHome = false) },
                    onBack = {
                        homeFocus = HomeFocus.Apps
                        screen = Screen.Home
                    },
                )
            }
        }

        menu?.let { target ->
            AppMenu(
                target = target,
                isFavorite = target.packageName in favoritePackages,
                isHidden = target.packageName in hidden,
                onDismiss = { menu = null },
                onTogglePin = { togglePin(target.packageName) },
                onMove = { moving = target.packageName },
                onToggleHidden = {
                    scope.launch {
                        prefs.setHidden(if (target.packageName in hidden) hidden - target.packageName else hidden + target.packageName)
                    }
                },
                onInfo = { launcher.openAppInfo(target.packageName) },
            )
        }
    }
}
