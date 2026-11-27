package app.novavpn.ui.glass

import android.os.Build
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.backdrops.LayerBackdrop as KyantLayerBackdrop
import com.kyant.backdrop.backdrops.layerBackdrop as kyantLayerBackdrop
import com.kyant.backdrop.backdrops.rememberCombinedBackdrop as kyantRememberCombinedBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop as kyantRememberLayerBackdrop
import com.kyant.backdrop.highlight.Highlight
import com.kyant.backdrop.highlight.HighlightStyle

typealias GlassBackdrop = Backdrop
typealias LayerBackdrop = KyantLayerBackdrop
typealias CombinedBackdrop = Backdrop

fun isGlassRenderEffectSupported(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

fun isGlassLensSupported(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU

fun isRenderEffectSupported(): Boolean = isGlassRenderEffectSupported()

@Composable
fun rememberLayerBackdrop(): LayerBackdrop = kyantRememberLayerBackdrop()

@Composable
fun rememberCombinedBackdrop(vararg backdrops: Backdrop): CombinedBackdrop =
    kyantRememberCombinedBackdrop(*backdrops)

fun Modifier.layerBackdrop(backdrop: LayerBackdrop): Modifier =
    this.kyantLayerBackdrop(backdrop)

fun glassHighlightOf(tiltAngle: Float?): Highlight =
    if (tiltAngle == null) {
        Highlight.Default
    } else {
        Highlight(style = HighlightStyle.Default(angle = tiltAngle))
    }
