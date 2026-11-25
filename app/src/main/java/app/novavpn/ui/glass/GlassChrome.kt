package app.novavpn.ui.glass

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.unit.dp
import app.novavpn.domain.model.GlassWallpaper

@Immutable
class GlassChromeBackdrops(
    val wall: LayerBackdrop,
    val content: LayerBackdrop,
    val combined: CombinedBackdrop
)

val LocalGlassChrome = compositionLocalOf<GlassChromeBackdrops?> { null }
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
    backdrop: LayerBackdrop? = null,
    modifier: Modifier = Modifier
) {
    val look = LocalGlassLook.current
    val colorScheme = MaterialTheme.colorScheme
    val stops = remember(look.prefs.wallpaper, look.isDark, look.dynamicColor, colorScheme) {
        wallpaperStops(look.prefs.wallpaper, look.isDark, look.dynamicColor, colorScheme)
    }
    val tilt = LocalGlassTiltOffset.current

    Box(
        modifier = modifier
            .fillMaxSize()
            .then(if (backdrop != null) Modifier.layerBackdrop(backdrop) else Modifier)
            .background(Brush.verticalGradient(colors = stops))
    ) {
        if (look.prefs.wallpaper != GlassWallpaper.PLAIN) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val tiltX = tilt.x * 0.04f
                val tiltY = tilt.y * 0.03f

                val (topGlow, bottomGlow) = when {
                    look.dynamicColor -> {
                        val a = look.accent.copy(alpha = if (look.isDark) 0.28f else 0.24f)
                        val b = look.secondaryAccent.copy(alpha = if (look.isDark) 0.24f else 0.20f)
                        a to b
                    }
                    look.prefs.wallpaper == GlassWallpaper.LAGOON -> {
                        val a = if (look.isDark) Color(0x730091FF) else Color(0x520088FF)
                        val b = if (look.isDark) Color(0x4700D2E0) else Color(0x4200C3D0)
                        a to b
                    }
                    look.prefs.wallpaper == GlassWallpaper.DUSK -> {
                        val a = if (look.isDark) Color(0x61FF9230) else Color(0x52FF8D28)
                        val b = if (look.isDark) Color(0x52DB34F2) else Color(0x38CB30E0)
                        a to b
                    }
                    look.prefs.wallpaper == GlassWallpaper.GRAPHITE -> {
                        val a = if (look.isDark) Color(0x3D98989D) else Color(0x75FFFFFF)
                        val b = look.accent.copy(alpha = if (look.isDark) 0.16f else 0.12f)
                        a to b
                    }
                    look.prefs.wallpaper == GlassWallpaper.MIST -> {
                        val a = look.accent.copy(alpha = if (look.isDark) 0.24f else 0.18f)
                        val b = look.secondaryAccent.copy(alpha = if (look.isDark) 0.22f else 0.16f)
                        a to b
                    }
                    else -> Color.Transparent to Color.Transparent
                }

                ellipseGlow(
                    cx = 0.74f + tiltX,
                    cy = 0.10f + tiltY,
                    rx = 0.86f,
                    ry = 0.44f,
                    color = topGlow
                )
                ellipseGlow(
                    cx = 0.18f - tiltX,
                    cy = 0.62f - tiltY,
                    rx = 0.82f,
                    ry = 0.42f,
                    color = bottomGlow
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
    backdrop: GlassBackdrop? = null,
    modifier: Modifier = Modifier
) {
    val look = LocalGlassLook.current
    val base = wallpaperStops(
        look.prefs.wallpaper,
        look.isDark,
        look.dynamicColor,
        MaterialTheme.colorScheme
    ).first()

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(28.dp)
            .background(
                Brush.verticalGradient(
                    colors = listOf(base.copy(alpha = 0.80f), Color.Transparent)
                )
            )
    )
}
