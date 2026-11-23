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
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import app.novavpn.domain.model.GlassWallpaper

val LocalGlassTiltOffset = compositionLocalOf { Offset.Zero }

@Composable
fun GlassWallpaperLayer(
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
            animation = tween(durationMillis = 14_000, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "drift"
    )

    val blurModifier = if (!look.prefs.reduceTransparency && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        Modifier.graphicsLayer {
            renderEffect = RenderEffect.createBlurEffect(56f, 56f, Shader.TileMode.CLAMP).asComposeRenderEffect()
        }
    } else {
        Modifier
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(colors = stops))
    ) {
        if (look.prefs.wallpaper != GlassWallpaper.PLAIN) {
            Canvas(
                modifier = Modifier
                    .fillMaxSize()
                    .then(blurModifier)
            ) {
                val w = size.width
                val h = size.height
                val tiltX = tilt.x * w * 0.06f
                val tiltY = tilt.y * h * 0.04f

                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            look.accent.copy(alpha = if (look.isDark) 0.28f else 0.20f),
                            Color.Transparent
                        ),
                        center = Offset(w * (0.25f + drift * 0.12f) + tiltX, h * 0.22f + tiltY),
                        radius = w * 0.72f
                    ),
                    center = Offset(w * (0.25f + drift * 0.12f) + tiltX, h * 0.22f + tiltY),
                    radius = w * 0.72f
                )

                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            look.secondaryAccent.copy(alpha = if (look.isDark) 0.26f else 0.18f),
                            Color.Transparent
                        ),
                        center = Offset(w * (0.78f - drift * 0.14f) - tiltX, h * 0.68f - tiltY),
                        radius = w * 0.78f
                    ),
                    center = Offset(w * (0.78f - drift * 0.14f) - tiltX, h * 0.68f - tiltY),
                    radius = w * 0.78f
                )
            }
        }
    }
}

@Composable
fun GlassTopEdge(modifier: Modifier = Modifier) {
    val look = LocalGlassLook.current
    val base = wallpaperStops(look.prefs.wallpaper, look.isDark).first()
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(28.dp)
            .background(
                Brush.verticalGradient(
                    colors = listOf(base.copy(alpha = 0.75f), Color.Transparent)
                )
            )
    )
}
