package app.novavpn.ui.glass

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Indication
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.clickable
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp

val LocalIsTv = compositionLocalOf { false }

val LocalLastTvFocusRequester = compositionLocalOf<MutableState<FocusRequester?>?> { null }

/**
 * Draws the Android TV focus scale, brighter translucent tint, and crisp accent + white rim,
 * registers the [FocusRequester] as the last focused TV node, and ensures the element scrolls
 * into view when focused.
 *
 * No-op on phone builds (`!LocalIsTv.current`) so touch layouts are completely unaffected.
 */
fun Modifier.tvFocusVisual(
    shape: Shape = RoundedCornerShape(16.dp),
    scaleOnFocus: Float = 1.05f,
    focusRequester: FocusRequester? = null,
    bringIntoViewRequester: BringIntoViewRequester? = null
): Modifier = composed {
    if (!LocalIsTv.current) return@composed this
    val look = LocalGlassLook.current
    val lastFocus = LocalLastTvFocusRequester.current
    val req = focusRequester ?: remember { FocusRequester() }
    val biv = bringIntoViewRequester ?: remember { BringIntoViewRequester() }
    var focused by remember { mutableStateOf(false) }

    LaunchedEffect(focused) {
        if (focused) {
            biv.bringIntoView()
        }
    }

    val scale by animateFloatAsState(
        targetValue = if (focused) scaleOnFocus else 1f,
        animationSpec = spring(dampingRatio = 0.72f, stiffness = Spring.StiffnessMediumLow),
        label = "tv_focus_scale"
    )

    this
        .bringIntoViewRequester(biv)
        .focusRequester(req)
        .onFocusChanged { state ->
            val nowFocused = state.isFocused || state.hasFocus
            if (focused != nowFocused) {
                focused = nowFocused
            }
            if (state.isFocused) {
                lastFocus?.value = req
            }
        }
        .graphicsLayer {
            scaleX = scale
            scaleY = scale
        }
        .drawWithContent {
            drawContent()
            if (focused) {
                val outline = shape.createOutline(size, layoutDirection, this)
                drawOutline(
                    outline = outline,
                    color = Color.White.copy(alpha = if (look.isDark) 0.14f else 0.22f)
                )
                drawOutline(
                    outline = outline,
                    color = look.accent,
                    style = Stroke(width = 2.5.dp.toPx())
                )
                drawOutline(
                    outline = outline,
                    color = Color.White.copy(alpha = 0.85f),
                    style = Stroke(width = 0.9.dp.toPx())
                )
            }
        }
}

/**
 * Reusable TV D-pad focus + click modifier for interactive Nova Glass components.
 * When `!LocalIsTv.current`, delegates strictly to standard [clickable].
 */
fun Modifier.tvFocusable(
    shape: Shape = RoundedCornerShape(16.dp),
    scaleOnFocus: Float = 1.05f,
    enabled: Boolean = true,
    role: Role? = Role.Button,
    interactionSource: MutableInteractionSource? = null,
    indication: Indication? = null,
    focusRequester: FocusRequester? = null,
    onClick: () -> Unit
): Modifier = composed {
    val src = interactionSource ?: remember { MutableInteractionSource() }
    val base = if (LocalIsTv.current) {
        this.tvFocusVisual(
            shape = shape,
            scaleOnFocus = scaleOnFocus,
            focusRequester = focusRequester
        )
    } else {
        this
    }
    base.clickable(
        interactionSource = src,
        indication = indication,
        enabled = enabled,
        role = role,
        onClick = onClick
    )
}

/**
 * Focusable modifier for read-only list items on TV (such as session records or log lines)
 * so D-pad UP/DOWN scrolls through LazyColumns smoothly without getting stuck.
 */
fun Modifier.tvReadOnlyFocusable(
    shape: Shape = RoundedCornerShape(16.dp)
): Modifier = composed {
    if (!LocalIsTv.current) return@composed this
    this
        .tvFocusVisual(shape = shape, scaleOnFocus = 1.02f)
        .focusable()
}
