package app.novavpn.ui.glass

import android.graphics.RenderEffect
import android.graphics.Shader
import android.os.Build
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

fun isRenderEffectSupported(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

interface GlassBackdrop {
    val layers: List<LayerBackdrop>
}

class LayerBackdrop internal constructor() : GlassBackdrop {
    internal var recordedLayer: GraphicsLayer? by mutableStateOf(null)
    internal var rootPosition: Offset by mutableStateOf(Offset.Zero)
    override val layers: List<LayerBackdrop> get() = listOf(this)
}

class CombinedBackdrop(vararg val sources: LayerBackdrop) : GlassBackdrop {
    override val layers: List<LayerBackdrop> = sources.toList()
}

@Composable
fun rememberLayerBackdrop(): LayerBackdrop = remember { LayerBackdrop() }

@Composable
fun rememberCombinedBackdrop(vararg backdrops: LayerBackdrop): CombinedBackdrop =
    remember(*backdrops) { CombinedBackdrop(*backdrops) }

fun Modifier.layerBackdrop(backdrop: LayerBackdrop): Modifier = composed {
    val layer = rememberGraphicsLayer()
    backdrop.recordedLayer = layer
    this
        .onGloballyPositioned { coords ->
            backdrop.rootPosition = coords.positionInRoot()
        }
        .drawWithContent {
            val w = ceil(size.width).toInt()
            val h = ceil(size.height).toInt()
            if (w > 0 && h > 0) {
                layer.record(size = IntSize(w, h)) {
                    this@drawWithContent.drawContent()
                }
                drawLayer(layer)
            } else {
                drawContent()
            }
        }
}

sealed interface HighlightStyle {
    data class Default(val angle: Float = 45f) : HighlightStyle

    companion object {
        fun Default(angle: Float = 45f): HighlightStyle = Default(angle)
    }
}

data class GlassHighlightSpec(
    val width: Dp = 1.dp,
    val alpha: Float = 1f,
    val style: HighlightStyle = HighlightStyle.Default()
)

data class GlassShadowSpec(
    val radius: Dp = 24.dp,
    val offset: DpOffset = DpOffset.Zero,
    val color: Color = Color.Black.copy(alpha = 0.24f)
)

class BackdropEffectScope internal constructor(
    val size: androidx.compose.ui.geometry.Size,
    val layoutDirection: LayoutDirection,
    val density: Float
) {
    internal var useVibrancy: Boolean = false
    internal var blurRadiusPx: Float = 0f
    internal var lensHeightPx: Float = 0f
    internal var lensAmountPx: Float = 0f
    internal var lensAberration: Boolean = false

    fun vibrancy() {
        useVibrancy = true
    }

    fun blur(radiusPx: Float) {
        blurRadiusPx = maxOf(blurRadiusPx, radiusPx)
    }

    fun lens(refractionHeightPx: Float, refractionAmountPx: Float, chromaticAberration: Boolean = false) {
        lensHeightPx = refractionHeightPx
        lensAmountPx = refractionAmountPx
        lensAberration = chromaticAberration
    }
}

private data class CachedBackdropState(
    var lastSize: androidx.compose.ui.geometry.Size = androidx.compose.ui.geometry.Size.Unspecified,
    var lastLayoutDirection: LayoutDirection? = null,
    var outline: Outline? = null,
    var lastBlurPx: Float = -1f,
    var composeRenderEffect: androidx.compose.ui.graphics.RenderEffect? = null
)

fun Modifier.drawBackdrop(
    backdrop: GlassBackdrop?,
    shape: Shape,
    effects: BackdropEffectScope.() -> Unit = {},
    highlight: (() -> GlassHighlightSpec?)? = null,
    shadow: (() -> GlassShadowSpec?)? = null,
    exportedBackdrop: LayerBackdrop? = null,
    onDrawBackdrop: (DrawScope.(drawContent: () -> Unit) -> Unit)? = null,
    onDrawSurface: (DrawScope.() -> Unit)? = null,
    onDrawFront: (DrawScope.() -> Unit)? = null
): Modifier = composed {
    val blurLayer = rememberGraphicsLayer()
    val cache = remember { CachedBackdropState() }
    var selfPos by remember { mutableStateOf(Offset.Zero) }

    this
        .onGloballyPositioned { coords ->
            val pos = coords.positionInRoot()
            if (selfPos != pos) {
                selfPos = pos
            }
        }
        .drawWithContent {
            val intSize = IntSize(ceil(size.width).toInt(), ceil(size.height).toInt())
            if (intSize.width <= 0 || intSize.height <= 0) {
                drawContent()
                return@drawWithContent
            }

            if (cache.lastSize != size || cache.lastLayoutDirection != layoutDirection || cache.outline == null) {
                cache.outline = shape.createOutline(size, layoutDirection, this)
                cache.lastSize = size
                cache.lastLayoutDirection = layoutDirection
            }
            val outline = cache.outline!!

            shadow?.invoke()?.let { s ->
                if (s.color.alpha > 0f && s.radius > 0.dp) {
                    drawSoftShapeShadow(outline, s)
                }
            }

            if (backdrop != null && isRenderEffectSupported()) {
                val effectScope = BackdropEffectScope(size, layoutDirection, density).apply(effects)
                if (effectScope.blurRadiusPx != cache.lastBlurPx) {
                    cache.composeRenderEffect = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && effectScope.blurRadiusPx > 0.5f) {
                        RenderEffect.createBlurEffect(
                            effectScope.blurRadiusPx,
                            effectScope.blurRadiusPx,
                            Shader.TileMode.CLAMP
                        ).asComposeRenderEffect()
                    } else {
                        null
                    }
                    cache.lastBlurPx = effectScope.blurRadiusPx
                }

                val activeSources = backdrop.layers.filter { src ->
                    val l = src.recordedLayer
                    l != null && src !== exportedBackdrop && l.size.width > 0 && l.size.height > 0
                }
                if (activeSources.isNotEmpty()) {
                    blurLayer.clip = true
                    blurLayer.renderEffect = cache.composeRenderEffect
                    when (outline) {
                        is Outline.Rounded -> blurLayer.setRoundRectOutline(
                            cornerRadius = outline.roundRect.topLeftCornerRadius.x
                        )
                        is Outline.Rectangle -> blurLayer.setRectOutline()
                        is Outline.Generic -> blurLayer.setPathOutline(outline.path)
                    }
                    blurLayer.record(size = intSize) {
                        activeSources.forEach { src ->
                            val srcLayer = src.recordedLayer ?: return@forEach
                            val dx = src.rootPosition.x - selfPos.x
                            val dy = src.rootPosition.y - selfPos.y
                            translate(left = dx, top = dy) {
                                drawLayer(srcLayer)
                            }
                        }
                    }
                    if (onDrawBackdrop != null) {
                        onDrawBackdrop { drawLayer(blurLayer) }
                    } else {
                        drawLayer(blurLayer)
                    }
                }
            }

            onDrawSurface?.invoke(this)
            highlight?.invoke()?.let { h ->
                drawDirectionalHighlightOutline(outline, size, h)
            }

            this@drawWithContent.drawContent()
            onDrawFront?.invoke(this)
        }
}

private fun DrawScope.drawSoftShapeShadow(outline: Outline, spec: GlassShadowSpec) {
    val layers = 2
    val spread = spec.radius.toPx() * 0.4f
    val ox = spec.offset.x.toPx()
    val oy = spec.offset.y.toPx()
    for (i in layers downTo 1) {
        val frac = i / layers.toFloat()
        val strokeW = spread * frac
        val alpha = spec.color.alpha * (1f - frac * 0.55f) / layers
        translate(left = ox, top = oy + strokeW * 0.22f) {
            drawOutline(
                outline = outline,
                color = spec.color.copy(alpha = alpha.coerceIn(0f, 1f)),
                style = Stroke(width = strokeW)
            )
        }
    }
}

private fun DrawScope.drawDirectionalHighlightOutline(
    outline: Outline,
    size: androidx.compose.ui.geometry.Size,
    spec: GlassHighlightSpec
) {
    if (spec.alpha <= 0f || spec.width <= 0.dp) return
    val angleDeg = when (val st = spec.style) {
        is HighlightStyle.Default -> st.angle
    }
    val rad = angleDeg * (PI / 180.0)
    val dx = cos(rad).toFloat()
    val dy = sin(rad).toFloat()
    val cx = size.width * 0.5f
    val cy = size.height * 0.5f
    val halfDiag = hypot(cx, cy).coerceAtLeast(1f)
    val start = Offset(cx - dx * halfDiag, cy - dy * halfDiag)
    val end = Offset(cx + dx * halfDiag, cy + dy * halfDiag)

    val hi = Color.White.copy(alpha = (0.58f * spec.alpha).coerceIn(0f, 1f))
    val mid = Color.White.copy(alpha = (0.10f * spec.alpha).coerceIn(0f, 1f))
    val lo = Color.White.copy(alpha = (0.28f * spec.alpha).coerceIn(0f, 1f))

    drawOutline(
        outline = outline,
        brush = Brush.linearGradient(
            0f to hi,
            0.32f to mid,
            0.52f to Color.Transparent,
            0.70f to mid,
            1f to lo,
            start = start,
            end = end
        ),
        style = Stroke(width = spec.width.toPx() * 1.25f)
    )
}
