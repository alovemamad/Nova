package app.novavpn.ui.glass

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.statusBars
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.State
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.unit.dp
import app.novavpn.domain.model.GlassWallpaper
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur

@Immutable
class GlassChromeBackdrops(
    val wall: LayerBackdrop,
    val content: LayerBackdrop,
    val combined: CombinedBackdrop
)

val LocalGlassChrome = staticCompositionLocalOf<GlassChromeBackdrops?> { null }
val LocalGlassTiltState = staticCompositionLocalOf<State<Offset>?> { null }
val LocalGlassTiltOffset = compositionLocalOf { Offset.Zero }
val LocalGlassTiltAngle = compositionLocalOf { 45f }

@Composable
fun rememberGlassChromeBackdrops(): GlassChromeBackdrops {
    val wall = rememberLayerBackdrop()
    val content = rememberLayerBackdrop()
    val combined = rememberCombinedBackdrop(wall, content)
    return remember(wall, content, combined) {
        GlassChromeBackdrops(wall = wall, content = content, combined = combined)
    }
}

@Composable
fun GlassWallpaperLayer(
    backdrop: LayerBackdrop? = LocalGlassChrome.current?.wall,
    modifier: Modifier = Modifier
) {
    val look = LocalGlassLook.current
    val colorScheme = MaterialTheme.colorScheme
    val stops = remember(look.prefs.wallpaper, look.isDark, look.dynamicColor, colorScheme) {
        wallpaperStops(look.prefs.wallpaper, look.isDark, look.dynamicColor, colorScheme)
    }
    val glowPair = remember(
        look.prefs.wallpaper,
        look.isDark,
        look.dynamicColor,
        look.accent,
        look.secondaryAccent
    ) {
        wallpaperGlowPair(
            wallpaper = look.prefs.wallpaper,
            isDark = look.isDark,
            dynamicColor = look.dynamicColor,
            accent = look.accent,
            secondaryAccent = look.secondaryAccent
        )
    }
    val tiltState = LocalGlassTiltState.current
    val fallbackTilt = LocalGlassTiltOffset.current

    Box(
        modifier = modifier
            .fillMaxSize()
            .then(if (backdrop != null) Modifier.layerBackdrop(backdrop) else Modifier)
            .background(Brush.verticalGradient(colors = stops))
    ) {
        if (look.prefs.wallpaper != GlassWallpaper.PLAIN) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val tilt = tiltState?.value ?: fallbackTilt
                val tiltX = tilt.x * 0.04f
                val tiltY = tilt.y * 0.03f

                ellipseGlow(
                    cx = 0.74f + tiltX,
                    cy = 0.10f + tiltY,
                    rx = 0.86f,
                    ry = 0.44f,
                    color = glowPair.first
                )
                ellipseGlow(
                    cx = 0.18f - tiltX,
                    cy = 0.62f - tiltY,
                    rx = 0.82f,
                    ry = 0.42f,
                    color = glowPair.second
                )
            }
        }
    }
}

private fun DrawScope.ellipseGlow(cx: Float, cy: Float, rx: Float, ry: Float, color: Color) {
    if (color.alpha <= 0f) return
    val radiusX = size.width * rx
    val radiusY = size.height * ry
    if (radiusX <= 0f || radiusY <= 0f) return
    val center = Offset(size.width * cx, size.height * cy)
    scale(scaleX = radiusX / radiusY, scaleY = 1f, pivot = center) {
        drawCircle(
            brush = Brush.radialGradient(
                0f to color,
                0.55f to color.copy(alpha = color.alpha * 0.28f),
                0.85f to Color.Transparent,
                center = center,
                radius = radiusY
            ),
            radius = radiusY,
            center = center
        )
    }
}

@Composable
fun GlassTopEdge(
    backdrop: GlassBackdrop? = LocalGlassChrome.current?.combined,
    modifier: Modifier = Modifier
) {
    val look = LocalGlassLook.current
    val top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val height = if (top > 0.dp) top + 14.dp else 28.dp
    val base = wallpaperStops(
        look.prefs.wallpaper,
        look.isDark,
        look.dynamicColor,
        MaterialTheme.colorScheme
    ).first()

    if (backdrop == null || look.reduced || !isGlassRenderEffectSupported()) {
        Box(
            modifier = modifier
                .fillMaxWidth()
                .height(height)
                .drawBehind {
                    drawRect(
                        Brush.verticalGradient(
                            0f to base.copy(alpha = 0.88f),
                            0.55f to base.copy(alpha = 0.56f),
                            1f to Color.Transparent
                        )
                    )
                }
        )
        return
    }

    val fade = remember {
        Brush.verticalGradient(0f to Color.Black, 0.55f to Color.Black, 1f to Color.Transparent)
    }
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(height)
            .clip(RectangleShape)
            .drawBackdrop(
                backdrop = backdrop,
                shape = { RectangleShape },
                effects = { blur(10.dp.toPx()) },
                highlight = null,
                shadow = null,
                onDrawSurface = { drawRect(base.copy(alpha = 0.34f)) },
                onDrawFront = { drawRect(fade, blendMode = BlendMode.DstIn) }
            )
    )
}
