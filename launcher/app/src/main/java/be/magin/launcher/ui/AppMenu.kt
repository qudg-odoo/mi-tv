package be.magin.launcher.ui

import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import be.magin.launcher.R

data class MenuTarget(val packageName: String, val label: String, val fromHome: Boolean)

/** ≡ on an app: ★ pin/unpin · move (dock) · hide/show (all apps) · app info. */
@Composable
fun AppMenu(
    target: MenuTarget,
    isFavorite: Boolean,
    isHidden: Boolean,
    onDismiss: () -> Unit,
    onTogglePin: () -> Unit,
    onMove: () -> Unit,
    onToggleHidden: () -> Unit,
    onInfo: () -> Unit,
) {
    // The dialog gets its own window; keep the 960-wide design density inside it.
    val density = LocalDensity.current
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        CompositionLocalProvider(LocalDensity provides density) {
            val first = remember { FocusRequester() }
            LaunchedEffect(Unit) { runCatching { first.requestFocus() } }
            val shape = RoundedCornerShape(Nocturne.RadiusLg)
            Column(
                Modifier
                    .width(300.dp)
                    .elevation(Elevation.Lg, Nocturne.RadiusLg)
                    .background(Nocturne.Surface, shape)
                    .border(1.dp, Nocturne.Neutral800, shape)
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Txt(target.label, 18f, Modifier.padding(start = 10.dp, top = 4.dp, bottom = 6.dp), weight = FontWeight.Medium)
                MenuItem(
                    if (isFavorite) R.drawable.ph_push_pin_slash else R.drawable.ph_push_pin,
                    if (isFavorite) "Retirer des favoris" else "Épingler aux favoris",
                    Modifier.focusRequester(first),
                ) { onTogglePin(); onDismiss() }
                if (target.fromHome && isFavorite) {
                    MenuItem(R.drawable.ph_arrows_left_right, "Déplacer") { onMove(); onDismiss() }
                }
                if (!target.fromHome) {
                    MenuItem(
                        if (isHidden) R.drawable.ph_eye else R.drawable.ph_eye_slash,
                        if (isHidden) "Afficher" else "Masquer",
                    ) { onToggleHidden(); onDismiss() }
                }
                MenuItem(R.drawable.ph_info, "Infos sur l'app") { onInfo(); onDismiss() }
            }
        }
    }
}

@Composable
private fun MenuItem(@DrawableRes icon: Int, label: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val color = if (focused) Nocturne.Text else Nocturne.Neutral300
    Row(
        modifier
            .onFocusChanged { focused = it.isFocused }
            .dpadClickable(onClick)
            .fillMaxWidth()
            .height(44.dp)
            .background(if (focused) Nocturne.Neutral800 else Color.Transparent, RoundedCornerShape(Nocturne.RadiusMd))
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        PhIcon(icon, 20.dp, if (focused) Nocturne.Accent300 else Nocturne.Neutral400)
        Txt(label, 15f, color = color)
    }
}
