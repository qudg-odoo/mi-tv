package be.magin.launcher.ui

import android.view.KeyEvent
import androidx.annotation.DrawableRes
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import be.magin.launcher.R

/** Nocturne design tokens (see _ds/nocturne-…/styles.css in the Claude Design handoff). */
object Nocturne {
    val Bg = Color(0xFF161826)
    val Surface = Color(0xFF232532)
    val Text = Color(0xFFE9E9ED)
    val Accent = Color(0xFF9184D9)
    val Accent200 = Color(0xFFE7E5FE)
    val Accent300 = Color(0xFFD2CEFD)
    val Accent700 = Color(0xFF5D5294)
    val Neutral100 = Color(0xFFF3F5FE)
    val Neutral200 = Color(0xFFE4E7F5)
    val Neutral300 = Color(0xFFCFD3E5)
    val Neutral400 = Color(0xFFB2B6CA)
    val Neutral500 = Color(0xFF9397AB)
    val Neutral600 = Color(0xFF75798C)
    val Neutral700 = Color(0xFF595D6C)
    val Neutral800 = Color(0xFF3F424D)
    val Neutral900 = Color(0xFF292B31)

    val RadiusSm = 4.dp
    val RadiusMd = 8.dp
    val RadiusLg = 14.dp

    /** `color-mix(in oklch, var(--color-surface) 70%, transparent)` — the glass cards. */
    val Glass = Surface.copy(alpha = 0.7f)
}

val Inter = FontFamily(
    Font(R.font.inter_light, FontWeight.Light),
    Font(R.font.inter_regular, FontWeight.Normal),
    Font(R.font.inter_medium, FontWeight.Medium),
    Font(R.font.inter_semibold, FontWeight.SemiBold),
)

/** CSS `ease`, `ease-in-out` and the launch curve `cubic-bezier(.3,0,.1,1)` from the prototype. */
val CssEase = CubicBezierEasing(0.25f, 0.1f, 0.25f, 1f)
val CssEaseInOut = CubicBezierEasing(0.42f, 0f, 0.58f, 1f)
val LaunchEasing = CubicBezierEasing(0.3f, 0f, 0.1f, 1f)

/** The prototype is drawn on a 960×540 canvas; everything is laid out in those units. */
const val DESIGN_WIDTH = 960f

/**
 * Rescales density so that 1.dp (and 1.sp) equals one prototype pixel, whatever the TV
 * reports (xhdpi 960×540 dp, tvdpi 1440×810 dp…). All sizes below are the prototype's numbers.
 */
@Composable
fun DesignCanvas(content: @Composable BoxScope.() -> Unit) {
    BoxWithConstraints(Modifier.fillMaxSize().background(Nocturne.Bg)) {
        val density = Density(constraints.maxWidth / DESIGN_WIDTH, fontScale = 1f)
        CompositionLocalProvider(LocalDensity provides density) {
            Box(Modifier.fillMaxSize(), content = content)
        }
    }
}

/** Body text: Inter, line-height 1.55 like the design system's `body`. */
@Composable
fun Txt(
    text: String,
    size: Float,
    modifier: Modifier = Modifier,
    color: Color = Nocturne.Text,
    weight: FontWeight = FontWeight.Normal,
    tracking: Float = 0f,
    lineHeight: Float = 1.55f,
) {
    BasicText(
        text = text,
        modifier = modifier,
        style = TextStyle(
            color = color,
            fontSize = size.sp,
            fontWeight = weight,
            fontFamily = Inter,
            letterSpacing = tracking.em,
            lineHeight = lineHeight.em,
            lineHeightStyle = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.None),
        ),
        overflow = TextOverflow.Ellipsis,
        softWrap = false,
        maxLines = 1,
    )
}

/** A Phosphor icon (vector drawables in res/drawable/ph_*.xml). */
@Composable
fun PhIcon(@DrawableRes id: Int, size: Dp, color: Color, modifier: Modifier = Modifier) {
    Image(painterResource(id), null, modifier.size(size), colorFilter = ColorFilter.tint(color))
}

/** `--shadow-md` / `--shadow-lg`: a 1px hairline ring plus ambient darkness. */
enum class Elevation(val ring: Color, val y: Dp, val blur: Dp, val shade: Float) {
    Md(Nocturne.Neutral700, 6.dp, 18.dp, 0.55f),
    Lg(Nocturne.Neutral500, 16.dp, 40.dp, 0.65f),
}

fun Modifier.elevation(e: Elevation, radius: Dp) = drawBehind {
    boxShadow(Color.Black.copy(alpha = e.shade), e.blur, radius, dy = e.y)
    val o = 1.dp.toPx()
    drawRoundRect(
        color = e.ring,
        topLeft = Offset(-o / 2, -o / 2),
        size = Size(size.width + o, size.height + o),
        cornerRadius = CornerRadius(radius.toPx() + o / 2),
        style = Stroke(o),
    )
}

/**
 * A CSS-like outer box-shadow. Like CSS, nothing is painted under the box itself, which
 * matters for the translucent glass cards.
 */
fun DrawScope.boxShadow(color: Color, blur: Dp, radius: Dp, dy: Dp = 0.dp) {
    val r = radius.toPx()
    val inside = Path().apply { addRoundRect(RoundRect(0f, 0f, size.width, size.height, CornerRadius(r))) }
    clipPath(inside, ClipOp.Difference) {
        drawIntoCanvas { canvas ->
            val paint = Paint().asFrameworkPaint().apply {
                isAntiAlias = true
                this.color = android.graphics.Color.TRANSPARENT
                setShadowLayer(blur.toPx() / 2f, 0f, dy.toPx(), color.toArgb())
            }
            canvas.nativeCanvas.drawRoundRect(0f, 0f, size.width, size.height, r, r, paint)
        }
    }
}

private fun DrawScope.outset(color: Color, by: Dp, radius: Dp) {
    val o = by.toPx()
    drawRoundRect(
        color = color,
        topLeft = Offset(-o, -o),
        size = Size(size.width + 2 * o, size.height + 2 * o),
        cornerRadius = CornerRadius(radius.toPx() + o),
    )
}

/**
 * The prototype's focus ring: `0 0 0 2px <gap>, 0 0 0 4px accent-200, 0 0 30px accent/40%`.
 * [progress] runs 0→1 with the 180 ms focus transition.
 */
fun Modifier.focusRing(
    progress: () -> Float,
    radius: Dp,
    gap: Color = Nocturne.Surface,
    ring: Color = Nocturne.Accent200,
) = drawBehind {
    val p = progress()
    if (p <= 0f) return@drawBehind
    boxShadow(Nocturne.Accent.copy(alpha = 0.4f * p), 30.dp, radius)
    outset(ring.copy(alpha = p), 4.dp, radius)
    outset(gap.copy(alpha = p), 2.dp, radius)
}

private const val LONG_PRESS_REPEATS = 10

/**
 * Focusable + OK/Enter to click, long OK or ≡ Menu for [onLongClick]. A click only fires if this
 * element also saw the key going down, so the key-up that follows a screen change can't click
 * whatever just got focus.
 */
fun Modifier.dpadClickable(onClick: () -> Unit, onLongClick: (() -> Unit)? = null): Modifier = composed {
    // [0] = OK went down here, [1] = long press already fired.
    val press = remember { BooleanArray(2) }
    this
        .onKeyEvent { e ->
            val ne = e.nativeKeyEvent
            when (ne.keyCode) {
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                    if (ne.action == KeyEvent.ACTION_DOWN) {
                        if (ne.repeatCount == 0) {
                            press[0] = true; press[1] = false
                        } else if (onLongClick != null && press[0] && !press[1] &&
                            (ne.isLongPress || ne.repeatCount >= LONG_PRESS_REPEATS)
                        ) {
                            press[1] = true
                            onLongClick()
                        }
                    } else if (ne.action == KeyEvent.ACTION_UP) {
                        if (press[0] && !press[1]) onClick()
                        press[0] = false
                    }
                    true
                }
                KeyEvent.KEYCODE_MENU -> {
                    if (onLongClick != null && ne.action == KeyEvent.ACTION_UP) onLongClick()
                    onLongClick != null
                }
                else -> false
            }
        }
        .focusable()
        .pointerInput(onClick, onLongClick) {
            detectTapGestures(
                onTap = { onClick() },
                onLongPress = onLongClick?.let { l -> { _: Offset -> l() } },
            )
        }
}
