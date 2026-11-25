package app.novavpn.ui.glass

import android.graphics.RenderEffect
import android.graphics.Shader
import android.os.Build
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.graphicsLayer
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
    val stops = wallpaperStops(look.prefs.wallpaper, look.isDark)
    val tilt = LocalGlassTiltOffset.current

    val inf = rememberInfiniteTransition(label = "wallpaper_drift")
    val drift by inf.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 16_000, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "drift"
    )

    val blurModifier = if (!look.prefs.reduceTransparency && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        Modifier.graphicsLayer {
            renderEffect = RenderEffect.createBlurEffect(48f, 48f, Shader.TileMode.CLAMP).asComposeRenderEffect()
        }
    } else {
        Modifier
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .then(if (backdrop != null) Modifier.layerBackdrop(backdrop) else Modifier)
            .background(Brush.verticalGradient(colors = stops))
    ) {
        if (look.prefs.wallpaper != GlassWallpaper.PLAIN) {
            Canvas(
                modifier = Modifier
                    .fillMaxSize()
                    .then(blurModifier)
            ) {
                val tiltX = tilt.x * 0.05f
                val tiltY = tilt.y * 0.04f

                val (topGlow, bottomGlow) = when (look.prefs.wallpaper) {
                    GlassWallpaper.LAGOON -> {
                        val a = if (look.isDark) Color(0x730091FF) else Color(0x5C0088FF)
                        val b = if (look.isDark) Color(0x4700D2E0) else Color(0x4700C3D0)
                        a to b
                    }
                    GlassWallpaper.DUSK -> {
                        val a = if (look.isDark) Color(0x61FF9230) else Color(0x5CFF8D28)
                        val b = if (look.isDark) Color(0x52DB34F2) else Color(0x3DCB30E0)
                        a to b
                    }
                    GlassWallpaper.GRAPHITE -> {
                        val a = if (look.isDark) Color(0x3D98989D) else Color(0xCCFFFFFF)
                        val b = look.accent.copy(alpha = if (look.isDark) 0.14f else 0.10f)
                        a to b
                    }
                    GlassWallpaper.MIST -> {
                        val a = look.accent.copy(alpha = if (look.isDark) 0.24f else 0.18f)
                        val b = look.secondaryAccent.copy(alpha = if (look.isDark) 0.22f else 0.16f)
                        a to b
                    }
                    GlassWallpaper.PLAIN -> Color.Transparent to Color.Transparent
                }

                ellipseGlow(
                    cx = 0.80f - drift * 0.14f + tiltX,
                    cy = 0.08f + drift * 0.04f + tiltY,
                    rx = 0.85f,
                    ry = 0.42f,
                    color = topGlow
                )
                ellipseGlow(
                    cx = 0.12f + drift * 0.14f - tiltX,
                    cy = 0.62f - drift * 0.05f - tiltY,
                    rx = 0.80f,
                    ry = 0.40f,
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
                0.72f to color.copy(alpha = 0f),
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
    val base = wallpaperStops(look.prefs.wallpaper, look.isDark).first()

    if (backdrop != null && !look.reduced && isRenderEffectSupported()) {
        Box(
            modifier = modifier
                .fillMaxWidth()
                .height(34.dp)
                .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                .drawWithContent {
                    drawContent()
                    drawRect(
                        brush = Brush.verticalGradient(
                            0f to Color.Black,
                            0.55f to Color.Black.copy(alpha = 0.6f),
                            1f to Color.Transparent
                        ),
                        blendMode = BlendMode.DstIn
                    )
                }
                .drawBackdrop(
                    backdrop = backdrop,
                    shape = RectangleShape,
                    effects = {
                        vibrancy()
                        blur(16f * density)
                    },
                    onDrawSurface = {
                        drawRect(
                            Brush.verticalGradient(
                                0f to base.copy(alpha = 0.62f),
                                1f to Color.Transparent
                            )
                        )
                    }
                )
        )
    } else {
        Box(
            modifier = modifier
                .fillMaxWidth()
                .height(28.dp)
                .background(
                    Brush.verticalGradient(
                        colors = listOf(base.copy(alpha = 0.72f), Color.Transparent)
                    )
                )
        )
    }
}
