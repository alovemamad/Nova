package app.novavpn.ui.glass

import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.RenderEffect
import android.graphics.RuntimeShader
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
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.addOutline
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
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
import kotlin.math.min
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
    val size: Size,
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

private const val LENS_AGSL_SRC = """
    uniform shader content;
    uniform float2 resolution;
    uniform float cornerRadius;
    uniform float refractionHeight;
    uniform float refractionAmount;
    uniform float aberration;

    float sdRoundRect(float2 p, float2 b, float r) {
        float2 q = abs(p) - b + r;
        return min(max(q.x, q.y), 0.0) + length(max(q, 0.0)) - r;
    }

    half4 main(float2 fragCoord) {
        float2 halfSize = resolution * 0.5;
        float2 p = fragCoord - halfSize;
        float r = min(cornerRadius, min(halfSize.x, halfSize.y));
        float dist = sdRoundRect(p, halfSize, r);
        float band = clamp(1.0 + dist / max(refractionHeight, 1.0), 0.0, 1.0);
        float2 dir = length(p) > 0.001 ? normalize(p) : float2(0.0, 0.0);
        float shift = band * band * refractionAmount;
        float2 baseCoord = clamp(fragCoord - dir * shift, float2(0.0), resolution - 1.0);
        if (aberration > 0.5 && shift > 0.25) {
            float2 rCoord = clamp(fragCoord - dir * (shift * 1.18), float2(0.0), resolution - 1.0);
            float2 bCoord = clamp(fragCoord - dir * (shift * 0.82), float2(0.0), resolution - 1.0);
            half4 cr = content.eval(rCoord);
            half4 cg = content.eval(baseCoord);
            half4 cb = content.eval(bCoord);
            return half4(cr.r, cg.g, cb.b, cg.a);
        }
        return content.eval(baseCoord);
    }
"""

private fun buildBackdropRenderEffect(
    scope: BackdropEffectScope,
    cornerRadiusPx: Float
): RenderEffect? {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return null
    var effect: RenderEffect? = null

    if (scope.useVibrancy) {
        val matrix = ColorMatrix().apply { setSaturation(1.42f) }
        effect = RenderEffect.createColorFilterEffect(ColorMatrixColorFilter(matrix))
    }

    if (scope.blurRadiusPx > 0.5f) {
        val blur = RenderEffect.createBlurEffect(
            scope.blurRadiusPx,
            scope.blurRadiusPx,
            Shader.TileMode.CLAMP
        )
        effect = if (effect != null) RenderEffect.createChainEffect(blur, effect) else blur
    }

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
        scope.lensAmountPx > 0.5f &&
        scope.lensHeightPx > 0.5f &&
        scope.size.width > 4f &&
        scope.size.height > 4f
    ) {
        val lensEffect = runCatching {
            val shader = RuntimeShader(LENS_AGSL_SRC).apply {
                setFloatUniform("resolution", scope.size.width, scope.size.height)
                setFloatUniform("cornerRadius", cornerRadiusPx)
                setFloatUniform("refractionHeight", scope.lensHeightPx)
                setFloatUniform("refractionAmount", scope.lensAmountPx)
                setFloatUniform("aberration", if (scope.lensAberration) 1f else 0f)
            }
            RenderEffect.createRuntimeShaderEffect(shader, "content")
        }.getOrNull()
        if (lensEffect != null) {
            effect = if (effect != null) RenderEffect.createChainEffect(lensEffect, effect) else lensEffect
        }
    }

    return effect
}

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
    val compositeLayer = rememberGraphicsLayer()
    var selfPos by remember { mutableStateOf(Offset.Zero) }

    this
        .onGloballyPositioned { coords ->
            selfPos = coords.positionInRoot()
        }
        .drawWithContent {
            val intSize = IntSize(ceil(size.width).toInt(), ceil(size.height).toInt())
            if (intSize.width <= 0 || intSize.height <= 0) {
                drawContent()
                return@drawWithContent
            }

            val outline = shape.createOutline(size, layoutDirection, this)
            val shapePath = Path().apply { addOutline(outline) }
            val cornerRadiusPx = estimateCornerRadiusPx(outline, size)

            shadow?.invoke()?.let { s ->
                if (s.color.alpha > 0f && s.radius > 0.dp) {
                    drawSoftShapeShadow(outline, s)
                }
            }

            val effectScope = BackdropEffectScope(size, layoutDirection, density).apply(effects)
            val renderEffect = if (backdrop != null && isRenderEffectSupported()) {
                buildBackdropRenderEffect(effectScope, cornerRadiusPx)
            } else {
                null
            }

            val drawCapturedBackdrop: () -> Unit = {
                if (backdrop != null && isRenderEffectSupported()) {
                    val activeSources = backdrop.layers.filter { src ->
                        val l = src.recordedLayer
                        l != null && src !== exportedBackdrop && l.size.width > 0 && l.size.height > 0
                    }
                    if (activeSources.isNotEmpty()) {
                        blurLayer.renderEffect = renderEffect?.asComposeRenderEffect()
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
                        drawLayer(blurLayer)
                    }
                }
            }

            clipPath(shapePath) {
                if (onDrawBackdrop != null) {
                    compositeLayer.record(size = intSize) {
                        onDrawBackdrop(drawCapturedBackdrop)
                    }
                    drawLayer(compositeLayer)
                } else {
                    drawCapturedBackdrop()
                }
                onDrawSurface?.invoke(this)
                highlight?.invoke()?.let { h ->
                    drawDirectionalHighlight(shapePath, size, h)
                }
            }

            this@drawWithContent.drawContent()
            onDrawFront?.invoke(this)
        }
}

private fun estimateCornerRadiusPx(outline: Outline, size: Size): Float = when (outline) {
    is Outline.Rounded -> {
        val rr: RoundRect = outline.roundRect
        maxOf(rr.topLeftCornerRadius.x, rr.topRightCornerRadius.x)
    }
    is Outline.Rectangle -> 0f
    is Outline.Generic -> min(size.width, size.height) * 0.5f
}

private fun DrawScope.drawSoftShapeShadow(outline: Outline, spec: GlassShadowSpec) {
    val layers = 3
    val spread = spec.radius.toPx() * 0.45f
    val ox = spec.offset.x.toPx()
    val oy = spec.offset.y.toPx()
    for (i in layers downTo 1) {
        val frac = i / layers.toFloat()
        val strokeW = spread * frac
        val alpha = spec.color.alpha * (1f - frac * 0.65f) / layers
        translate(left = ox, top = oy + strokeW * 0.25f) {
            drawOutline(
                outline = outline,
                color = spec.color.copy(alpha = alpha.coerceIn(0f, 1f)),
                style = Stroke(width = strokeW)
            )
        }
    }
}

private fun DrawScope.drawDirectionalHighlight(
    shapePath: Path,
    size: Size,
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

    val hi = Color.White.copy(alpha = (0.72f * spec.alpha).coerceIn(0f, 1f))
    val mid = Color.White.copy(alpha = (0.08f * spec.alpha).coerceIn(0f, 1f))
    val lo = Color.White.copy(alpha = (0.34f * spec.alpha).coerceIn(0f, 1f))

    drawPath(
        path = shapePath,
        brush = Brush.linearGradient(
            0f to hi,
            0.32f to mid,
            0.52f to Color.Transparent,
            0.70f to mid,
            1f to lo,
            start = start,
            end = end
        ),
        style = Stroke(width = spec.width.toPx() * 1.4f)
    )
}
