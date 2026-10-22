package be.magin.launcher.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import be.magin.launcher.R
import be.magin.launcher.model.AppEntry
import be.magin.launcher.model.Favorite
import be.magin.launcher.model.initialOf
import kotlinx.coroutines.launch

/**
 * Option 1f, drawn in the 3a style: A–Z rail, search, pinned favourites first, then every app.
 * ≡ Menu or a long OK on an app opens pin / hide / info.
 */
@Composable
fun AllAppsScreen(
    apps: List<AppEntry>,
    favorites: List<Favorite>,
    hidden: Set<String>,
    onOpen: (String) -> Unit,
    onMenu: (AppEntry) -> Unit,
    onBack: () -> Unit,
) {
    var query by remember { mutableStateOf("") }
    var showHidden by remember { mutableStateOf(false) }
    val favoritePackages = favorites.map { it.packageName }.toSet()
    val shown = remember(apps, hidden, showHidden, query) {
        apps.filter {
            (showHidden || it.packageName !in hidden) &&
                (query.isBlank() || it.label.contains(query.trim(), ignoreCase = true))
        }
    }
    val letters = remember(shown) { shown.map { initialOf(it.label) }.distinct() }
    val showFavorites = favorites.isNotEmpty() && query.isBlank()
    val headerItems = if (showFavorites) 1 else 0

    val grid = rememberLazyGridState()
    val scope = rememberCoroutineScope()
    val firstRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { firstRequester.requestFocus() } }
    BackHandler { onBack() }

    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0f to Nocturne.Neutral900, 0.5f to Nocturne.Bg))) {
        Row(
            Modifier.fillMaxSize().padding(start = 32.dp, top = 37.dp, end = 48.dp, bottom = 30.dp),
            horizontalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            LazyColumn(
                Modifier.width(44.dp).padding(top = 52.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                item { RailLetter("★", star = true) { scope.launch { grid.scrollToItem(0) } } }
                items(letters) { letter ->
                    RailLetter(letter) {
                        val i = shown.indexOfFirst { initialOf(it.label) == letter }
                        if (i >= 0) scope.launch { grid.scrollToItem(headerItems + i) }
                    }
                }
            }

            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Txt("Toutes les apps", 26f, Modifier.weight(1f), weight = FontWeight.Medium, lineHeight = 1.2f)
                    SearchField(query, { query = it }, Modifier.width(240.dp))
                }
                LazyVerticalGrid(
                    columns = GridCells.Fixed(6),
                    state = grid,
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(8.dp),
                    horizontalArrangement = Arrangement.spacedBy(13.dp),
                    verticalArrangement = Arrangement.spacedBy(13.dp),
                ) {
                    if (showFavorites) {
                        item(span = { GridItemSpan(maxLineSpan) }, key = "favorites") {
                            Row(Modifier.padding(bottom = 6.dp), horizontalArrangement = Arrangement.spacedBy(13.dp)) {
                                favorites.forEachIndexed { i, fav ->
                                    PinnedTile(
                                        fav,
                                        Modifier.weight(1f).then(if (i == 0) Modifier.focusRequester(firstRequester) else Modifier),
                                        onClick = { onOpen(fav.packageName) },
                                        onLongClick = { fav.app?.let(onMenu) },
                                    )
                                }
                            }
                        }
                    }
                    items(shown.size, key = { shown[it].packageName }) { i ->
                        val app = shown[i]
                        AppTile(
                            app = app,
                            pinned = app.packageName in favoritePackages,
                            hidden = app.packageName in hidden,
                            modifier = if (!showFavorites && i == 0) Modifier.focusRequester(firstRequester) else Modifier,
                            onClick = { onOpen(app.packageName) },
                            onLongClick = { onMenu(app) },
                        )
                    }
                    if (hidden.isNotEmpty() && query.isBlank()) {
                        item(key = "hidden") {
                            HiddenToggle(showHidden, hidden.size) { showHidden = !showHidden }
                        }
                    }
                }
                Txt("Menu ≡ ou appui long sur une app : ★ épingler · masquer · infos", 14f, color = Nocturne.Neutral600)
            }
        }
    }
}

@Composable
private fun RailLetter(letter: String, star: Boolean = false, onFocus: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    Box(
        Modifier
            .onFocusChanged {
                focused = it.isFocused
                if (it.isFocused) onFocus()
            }
            .dpadClickable(onFocus)
            .size(26.dp, 20.dp)
            .background(if (focused) Nocturne.Neutral800 else Color.Transparent, RoundedCornerShape(Nocturne.RadiusSm)),
        contentAlignment = Alignment.Center,
    ) {
        Txt(
            letter,
            13f,
            color = when {
                star -> Nocturne.Accent300
                focused -> Nocturne.Text
                else -> Nocturne.Neutral600
            },
            lineHeight = 1.2f,
        )
    }
}

/** The search box only takes focus as a text field after OK, so D-pad moves never pop the keyboard. */
@Composable
private fun SearchField(query: String, onQuery: (String) -> Unit, modifier: Modifier = Modifier) {
    var focused by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf(false) }
    val field = remember { FocusRequester() }
    val border by animateColorAsState(if (focused || editing) Nocturne.Accent else Nocturne.Neutral800, tween(180), label = "border")
    LaunchedEffect(editing) { if (editing) runCatching { field.requestFocus() } }

    Row(
        modifier
            .onFocusChanged { focused = it.hasFocus; if (!it.hasFocus) editing = false }
            .dpadClickable({ editing = true })
            .height(36.dp)
            .border(1.dp, border, RoundedCornerShape(Nocturne.RadiusMd))
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        PhIcon(R.drawable.ph_magnifying_glass, 16.dp, Nocturne.Neutral500)
        Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
            if (query.isEmpty()) Txt("Rechercher", 15f, color = Nocturne.Neutral500, lineHeight = 1.2f)
            BasicTextField(
                value = query,
                onValueChange = onQuery,
                singleLine = true,
                textStyle = TextStyle(color = Nocturne.Text, fontSize = 15.sp, fontFamily = Inter),
                cursorBrush = SolidColor(Nocturne.Accent),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { editing = false }),
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(field)
                    .focusProperties { canFocus = editing },
            )
        }
    }
}

@Composable
private fun PinnedTile(fav: Favorite, modifier: Modifier, onClick: () -> Unit, onLongClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val p by animateFloatAsState(if (focused) 1f else 0f, tween(180, easing = CssEase), label = "pin")
    val shape = RoundedCornerShape(Nocturne.RadiusSm)
    Row(
        modifier
            .zIndex(if (focused) 1f else 0f)
            .onFocusChanged { focused = it.isFocused }
            .dpadClickable(onClick, onLongClick)
            .graphicsLayer { val s = 1f + 0.06f * p; scaleX = s; scaleY = s }
            .focusRing({ p }, Nocturne.RadiusSm, gap = Nocturne.Bg)
            .height(59.dp)
            .background(Nocturne.Neutral900, shape)
            .border(1.dp, Nocturne.Accent700, shape)
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
    ) {
        Txt("★", 15f, color = Nocturne.Accent300)
        Txt(fav.title, 15f, color = if (focused) Nocturne.Text else Nocturne.Neutral300)
    }
}

@Composable
private fun AppTile(
    app: AppEntry,
    pinned: Boolean,
    hidden: Boolean,
    modifier: Modifier,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    val p by animateFloatAsState(if (focused) 1f else 0f, tween(180, easing = CssEase), label = "app")
    Box(
        modifier
            .zIndex(if (focused) 1f else 0f)
            .onFocusChanged { focused = it.isFocused }
            .dpadClickable(onClick, onLongClick)
            .graphicsLayer {
                val s = 1f + 0.06f * p
                scaleX = s
                scaleY = s
                alpha = if (hidden) 0.45f else 1f
            }
            .focusRing({ p }, Nocturne.RadiusSm, gap = Nocturne.Bg)
            .fillMaxWidth()
            .aspectRatio(16f / 9f)
            .clip(RoundedCornerShape(Nocturne.RadiusSm))
            .background(Nocturne.Neutral900),
    ) {
        val banner = app.banner
        if (banner != null) {
            Image(banner, app.label, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        } else {
            Column(
                Modifier.fillMaxSize().padding(horizontal = 6.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterVertically),
            ) {
                app.icon?.let { Image(it, null, Modifier.size(30.dp)) }
                Txt(app.label, 12f, color = if (focused) Nocturne.Text else Nocturne.Neutral300)
            }
        }
        if (pinned) PhIcon(R.drawable.ph_star_fill, 12.dp, Nocturne.Accent300, Modifier.align(Alignment.TopEnd).padding(5.dp))
    }
}

@Composable
private fun HiddenToggle(showing: Boolean, count: Int, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(Nocturne.RadiusSm)
    Column(
        Modifier
            .onFocusChanged { focused = it.isFocused }
            .dpadClickable(onClick)
            .fillMaxWidth()
            .aspectRatio(16f / 9f)
            .border(1.dp, if (focused) Nocturne.Accent else Nocturne.Neutral800, shape),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterVertically),
    ) {
        val color = if (focused) Nocturne.Text else Nocturne.Neutral400
        PhIcon(if (showing) R.drawable.ph_eye_slash else R.drawable.ph_eye, 22.dp, color)
        Txt(if (showing) "Cacher les masquées" else "Masquées ($count)", 11f, color = color)
    }
}
