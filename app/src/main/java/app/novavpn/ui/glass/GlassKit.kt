package app.novavpn.ui.glass

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Indication
import androidx.compose.foundation.IndicationNodeFactory
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextFieldColors
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.node.DelegatableNode
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.node.invalidateDraw
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.R
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.effects.vibrancy
import com.kyant.backdrop.shadow.Shadow
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * Top inner specular bevel highlight clipped cleanly to [shape].
 */
fun Modifier.glassHighlight(shape: Shape, color: Color, thickness: Dp = 1.dp): Modifier =
    drawWithCache {
        val outline = shape.createOutline(size, layoutDirection, this)
        val strokePx = thickness.toPx().coerceAtLeast(1f)
        val bevelHeight = (size.height * 0.34f).coerceAtLeast(strokePx * 4f)
        val topRimBrush = Brush.verticalGradient(
            0f to color,
            0.45f to color.copy(alpha = color.alpha * 0.25f),
            1f to Color.Transparent,
            startY = 0f,
            endY = bevelHeight
        )
        onDrawWithContent {
            drawContent()
            drawOutline(
                outline = outline,
                brush = topRimBrush,
                style = Stroke(width = strokePx * 1.3f)
            )
        }
    }

fun glassRimBrush(look: GlassLook): Brush = Brush.linearGradient(
    0f to look.rimHi,
    0.28f to look.rimMid,
    0.48f to Color.Transparent,
    0.64f to Color.Transparent,
    1f to look.rimLo
)

fun Modifier.glassRim(shape: Shape, look: GlassLook, width: Dp = 1.dp): Modifier =
    border(width, glassRimBrush(look), shape)

private class GlassPressIndication(private val color: Color) : IndicationNodeFactory {
    override fun create(interactionSource: InteractionSource): DelegatableNode =
        GlassPressNode(interactionSource, color)

    override fun equals(other: Any?): Boolean = other is GlassPressIndication && other.color == color
    override fun hashCode(): Int = color.hashCode()
}

private class GlassPressNode(
    private val source: InteractionSource,
    private val color: Color
) : Modifier.Node(), DrawModifierNode {
    private var pressed = false

    override fun onAttach() {
        coroutineScope.launch {
            var count = 0
            source.interactions.collect { interaction ->
                when (interaction) {
                    is PressInteraction.Press -> count++
                    is PressInteraction.Release, is PressInteraction.Cancel -> count = (count - 1).coerceAtLeast(0)
                }
                val now = count > 0
                if (now != pressed) {
                    pressed = now
                    invalidateDraw()
                }
            }
        }
    }

    override fun ContentDrawScope.draw() {
        if (pressed) drawRect(color)
        drawContent()
    }
}

@Composable
fun glassRowIndication(): Indication {
    val look = LocalGlassLook.current
    return remember(look.fill2) { GlassPressIndication(look.fill2) }
}

@Composable
fun Modifier.glassPress(source: InteractionSource, scale: Float = 0.965f): Modifier {
    val pressed by source.collectIsPressedAsState()
    val s by animateFloatAsState(
        targetValue = if (pressed) scale else 1f,
        animationSpec = spring(dampingRatio = 0.55f, stiffness = Spring.StiffnessMedium),
        label = "glass_press"
    )
    return graphicsLayer {
        scaleX = s
        scaleY = s
    }
}

@Composable
fun Modifier.liquidGlass(
    shape: Shape = RoundedCornerShape(26.dp),
    elevated: Boolean = false,
    tintOverride: Color? = null,
    backdrop: GlassBackdrop? = null
): Modifier {
    val look = LocalGlassLook.current
    val tiltState = LocalGlassTiltState.current
    val fallbackTilt = LocalGlassTiltOffset.current
    val baseSurface = tintOverride ?: if (elevated) look.pillSurface else look.cardSurface

    if (look.reduced) {
        val opaqueSurface = if (tintOverride != null && tintOverride.alpha < 1f) {
            tintOverride.compositeOver(look.solidSurface)
        } else if (tintOverride != null) {
            tintOverride
        } else {
            look.solidSurface
        }
        return this
            .clip(shape)
            .background(opaqueSurface, shape)
            .border(0.75.dp, look.separator, shape)
    }

    if (!look.translucent && tintOverride == null && !elevated) {
        return this
            .clip(shape)
            .background(look.solidSurface, shape)
            .border(0.6.dp, look.separator, shape)
    }

    val renderLiveBlur = backdrop != null &&
        (elevated || look.translucent) &&
        isGlassRenderEffectSupported()

    if (renderLiveBlur && backdrop != null) {
        val tiltEnabled = look.prefs.motionHighlights
        return this
            .clip(shape)
            .drawBackdrop(
                backdrop = backdrop,
                shape = { shape },
                effects = {
                    vibrancy()
                    blur(14.dp.toPx())
                    if (elevated && isGlassLensSupported()) {
                        lens(12.dp.toPx(), 22.dp.toPx())
                    }
                },
                highlight = {
                    val angle = if (tiltEnabled) {
                        glassTiltAngleDegrees(tiltState?.value ?: fallbackTilt)
                    } else {
                        null
                    }
                    glassHighlightOf(angle)
                },
                shadow = if (elevated) {
                    { Shadow(radius = 18.dp, color = look.shadow) }
                } else {
                    null
                },
                onDrawSurface = {
                    drawRect(baseSurface)
                    drawRect(
                        Brush.verticalGradient(
                            0f to Color.White.copy(alpha = if (look.isDark) 0.11f else 0.22f),
                            0.45f to look.accent.copy(alpha = if (look.isDark) 0.04f else 0.03f),
                            1f to Color.Transparent
                        )
                    )
                }
            )
    }

    // No-backdrop or pre-API-31 path: avoid Modifier.shadow(clip = false) which causes
    // rectangular compositing artifacts underneath translucent surfaces.
    val specularAlpha = if (look.isDark) 0.10f else 0.20f
    val bottomAlpha = if (look.isDark) 0.04f else 0.06f
    val rimWidth = if (elevated) 1.05.dp else 0.85.dp

    return this
        .clip(shape)
        .drawWithCache {
            val outline = shape.createOutline(size, layoutDirection, this)
            val rimBrush = Brush.linearGradient(
                0f to look.rimHi,
                0.28f to look.rimMid,
                0.50f to Color.Transparent,
                0.68f to look.rimMid,
                1f to look.rimLo
            )
            val bevelHeight = (size.height * 0.28f).coerceAtLeast(6.dp.toPx())
            val topBevelBrush = Brush.verticalGradient(
                0f to look.highlight,
                0.5f to look.highlight.copy(alpha = look.highlight.alpha * 0.22f),
                1f to Color.Transparent,
                startY = 0f,
                endY = bevelHeight
            )
            val rimPx = rimWidth.toPx()

            onDrawBehind {
                val tilt = if (look.prefs.motionHighlights) {
                    tiltState?.value ?: fallbackTilt
                } else {
                    Offset.Zero
                }
                val shiftX = tilt.x * size.width * 0.16f
                val shiftY = tilt.y * size.height * 0.14f
                val sheenBrush = Brush.linearGradient(
                    colors = listOf(
                        Color.White.copy(alpha = specularAlpha),
                        Color.Transparent,
                        look.accent.copy(alpha = bottomAlpha)
                    ),
                    start = Offset(shiftX, shiftY),
                    end = Offset(size.width + shiftX, size.height + shiftY)
                )
                drawOutline(outline = outline, color = baseSurface)
                drawOutline(outline = outline, brush = sheenBrush)
                drawOutline(
                    outline = outline,
                    brush = topBevelBrush,
                    style = Stroke(width = 1.1.dp.toPx())
                )
                drawOutline(
                    outline = outline,
                    brush = rimBrush,
                    style = Stroke(width = rimPx)
                )
            }
        }
}

@Composable
fun GlassCard(
    modifier: Modifier = Modifier,
    cornerRadius: Dp = 26.dp,
    elevated: Boolean = false,
    tintOverride: Color? = null,
    backdrop: GlassBackdrop? = null,
    content: @Composable BoxScope.() -> Unit
) {
    Box(
        modifier = modifier.liquidGlass(
            shape = RoundedCornerShape(cornerRadius),
            elevated = elevated,
            tintOverride = tintOverride,
            backdrop = backdrop
        ),
        content = content
    )
}

@Composable
fun GlassSectionHeader(
    title: String,
    modifier: Modifier = Modifier,
    trailing: String? = null
) {
    val look = LocalGlassLook.current
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelLarge.copy(letterSpacing = 0.2.sp),
            color = look.textSecondary,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        if (!trailing.isNullOrBlank()) {
            Text(
                text = trailing,
                style = MaterialTheme.typography.labelSmall,
                color = look.textMuted,
                maxLines = 1
            )
        }
    }
}

@Composable
fun GlassGroup(
    title: String? = null,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    Column(modifier = modifier.fillMaxWidth()) {
        if (!title.isNullOrBlank()) {
            GlassSectionHeader(title = title)
        }
        GlassCard(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
                content = content
            )
        }
    }
}

@Composable
fun GlassRow(
    title: String,
    subtitle: String? = null,
    icon: ImageVector? = null,
    iconTint: Color? = null,
    onClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
    trailing: (@Composable () -> Unit)? = null
) {
    val look = LocalGlassLook.current
    val source = remember { MutableInteractionSource() }
    val pressIndication = glassRowIndication()
    val clickableMod = if (onClick != null) {
        modifier.clickable(
            interactionSource = source,
            indication = pressIndication,
            role = Role.Button,
            onClick = onClick
        )
    } else {
        modifier
    }

    val effectiveTint = iconTint ?: look.accent
    val iconShape = RoundedCornerShape(10.dp)
    val iconBrush = remember(effectiveTint) {
        Brush.verticalGradient(
            0f to lerp(effectiveTint, Color.White, 0.22f),
            0.65f to effectiveTint,
            1f to lerp(effectiveTint, Color.Black, 0.12f)
        )
    }

    Row(
        modifier = clickableMod
            .fillMaxWidth()
            .defaultMinSize(minHeight = 56.dp)
            .padding(horizontal = 16.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (icon != null) {
            Box(
                modifier = Modifier
                    .size(34.dp)
                    .clip(iconShape)
                    .background(iconBrush)
                    .glassHighlight(iconShape, Color.White.copy(alpha = 0.45f), 0.5.dp),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(19.dp)
                )
            }
            Spacer(modifier = Modifier.width(12.dp))
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                color = look.textPrimary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            if (!subtitle.isNullOrBlank()) {
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = look.textSecondary,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        if (trailing != null) {
            Spacer(modifier = Modifier.width(12.dp))
            trailing()
        }
    }
}

@Composable
fun GlassDivider(insetStart: Dp = 16.dp) {
    val look = LocalGlassLook.current
    HorizontalDivider(
        modifier = Modifier.padding(start = insetStart, end = 16.dp),
        thickness = 0.5.dp,
        color = look.separator
    )
}

@Composable
fun GlassSwitch(
    checked: Boolean,
    onCheckedChange: ((Boolean) -> Unit)?,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    interactionSource: MutableInteractionSource? = null
) {
    val look = LocalGlassLook.current
    val density = LocalDensity.current
    val source = interactionSource ?: remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val track by animateColorAsState(
        targetValue = if (checked) look.accent else look.fill,
        animationSpec = tween(220),
        label = "glass_switch_track"
    )
    val endPx = with(density) { 24.dp.toPx() }
    val startPx = with(density) { 2.dp.toPx() }
    val topPadPx = with(density) { 2.dp.toPx() }.roundToInt()
    val thumbX by animateFloatAsState(
        targetValue = if (checked) endPx else startPx,
        animationSpec = spring(dampingRatio = 0.62f, stiffness = Spring.StiffnessMediumLow),
        label = "glass_switch_knob"
    )
    val squash by animateFloatAsState(
        targetValue = if (pressed && enabled) 1f else 0f,
        animationSpec = spring(stiffness = Spring.StiffnessMedium),
        label = "glass_switch_press"
    )

    val capsule = CircleShape
    Box(
        modifier = modifier
            .size(width = 58.dp, height = 28.dp)
            .graphicsLayer { alpha = if (enabled) 1f else 0.4f }
            .clip(capsule)
            .background(track)
            .border(0.6.dp, if (checked) look.accent else look.separator, capsule)
            .then(
                if (onCheckedChange != null) {
                    Modifier.toggleable(
                        value = checked,
                        interactionSource = source,
                        indication = null,
                        enabled = enabled,
                        role = Role.Switch,
                        onValueChange = onCheckedChange
                    )
                } else {
                    Modifier
                }
            )
    ) {
        Box(
            modifier = Modifier
                .offset { IntOffset(thumbX.roundToInt(), topPadPx) }
                .size(width = 32.dp, height = 24.dp)
                .graphicsLayer {
                    scaleX = 1f + 0.18f * squash
                    scaleY = 1f + 0.10f * squash
                }
                .clip(capsule)
                .background(lerp(Color.White, Color.White.copy(alpha = 0.78f), squash))
                .border(0.5.dp, Color.Black.copy(alpha = 0.08f), capsule)
                .glassHighlight(capsule, Color.White, 0.8.dp)
        )
    }
}

@Composable
fun GlassSwitchRow(
    title: String,
    subtitle: String? = null,
    icon: ImageVector? = null,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    val haptic = LocalHapticFeedback.current
    val source = remember { MutableInteractionSource() }
    GlassRow(
        title = title,
        subtitle = subtitle,
        icon = icon,
        onClick = {
            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
            onCheckedChange(!checked)
        },
        modifier = modifier
    ) {
        GlassSwitch(
            checked = checked,
            onCheckedChange = {
                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                onCheckedChange(it)
            },
            interactionSource = source
        )
    }
}

@Composable
fun <T> GlassSegmented(
    options: List<Pair<T, String>>,
    selected: T,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier
) {
    if (options.isEmpty()) return
    val look = LocalGlassLook.current
    val haptic = LocalHapticFeedback.current
    val density = LocalDensity.current
    val isRtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val capsule = RoundedCornerShape(22.dp)
    val thumbShape = RoundedCornerShape(19.dp)
    val selectedIdx = options.indexOfFirst { it.first == selected }.coerceAtLeast(0)

    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .height(44.dp)
            .clip(capsule)
            .background(look.fill2)
            .border(0.8.dp, look.separator, capsule)
            .padding(3.dp)
    ) {
        val slotWidth = maxWidth / options.size
        val slotWidthPx = with(density) { slotWidth.toPx() }
        val sliderPx by animateFloatAsState(
            targetValue = slotWidthPx * selectedIdx,
            animationSpec = spring(dampingRatio = 0.72f, stiffness = Spring.StiffnessMediumLow),
            label = "glass_segment_x"
        )
        val thumbBrush = remember(look.isDark, look.accent, look.secondaryAccent, look.thumb) {
            if (look.isDark) {
                Brush.horizontalGradient(
                    listOf(look.accent.copy(alpha = 0.30f), look.secondaryAccent.copy(alpha = 0.24f))
                )
            } else {
                val base = lerp(look.thumb, look.accent, 0.14f)
                Brush.horizontalGradient(listOf(base, lerp(base, look.secondaryAccent, 0.08f)))
            }
        }

        Box(
            modifier = Modifier
                .width(slotWidth)
                .fillMaxHeight()
                .graphicsLayer {
                    translationX = if (isRtl) -sliderPx else sliderPx
                }
                .clip(thumbShape)
                .background(thumbBrush)
                .border(
                    width = 0.8.dp,
                    color = look.accent.copy(alpha = if (look.isDark) 0.62f else 0.44f),
                    shape = thumbShape
                )
                .glassHighlight(thumbShape, look.highlight, 0.75.dp)
        )

        Row(
            modifier = Modifier
                .fillMaxSize()
                .selectableGroup()
        ) {
            options.forEach { (value, label) ->
                val isSelected = value == selected
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .clip(thumbShape)
                        .selectable(
                            selected = isSelected,
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            role = Role.Tab,
                            onClick = {
                                if (!isSelected) {
                                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                    onSelect(value)
                                }
                            }
                        )
                        .padding(horizontal = 6.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = label,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                        color = if (isSelected) look.textPrimary else look.textSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        textAlign = TextAlign.Center
                    )
                }
            }
        }
    }
}

@Composable
fun GlassCircleButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 48.dp,
    tint: Color? = null,
    backdrop: GlassBackdrop? = null
) {
    val look = LocalGlassLook.current
    val source = remember { MutableInteractionSource() }
    val haptic = LocalHapticFeedback.current
    Box(
        modifier = modifier
            .glassPress(source, scale = 0.92f)
            .size(size)
            .liquidGlass(shape = CircleShape, elevated = true, backdrop = backdrop)
            .clickable(
                interactionSource = source,
                indication = ripple(bounded = true, color = look.accent),
                role = Role.Button,
                onClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    onClick()
                }
            ),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = tint ?: look.textPrimary,
            modifier = Modifier.size(21.dp)
        )
    }
}

@Composable
fun GlassCapsuleButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    prominent: Boolean = false,
    enabled: Boolean = true,
    tint: Color? = null,
    backdrop: GlassBackdrop? = null
) {
    val look = LocalGlassLook.current
    val source = remember { MutableInteractionSource() }
    val haptic = LocalHapticFeedback.current
    val capsule = CircleShape
    val accent = tint ?: look.accent
    val bgOverride = if (prominent) accent.copy(alpha = 0.24f) else null

    Row(
        modifier = modifier
            .glassPress(source, scale = 0.96f)
            .defaultMinSize(minHeight = 48.dp)
            .liquidGlass(shape = capsule, elevated = prominent, tintOverride = bgOverride, backdrop = backdrop)
            .clickable(
                interactionSource = source,
                indication = ripple(bounded = true, color = accent),
                enabled = enabled,
                role = Role.Button,
                onClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    onClick()
                }
            )
            .graphicsLayer { alpha = if (enabled) 1f else 0.45f }
            .padding(horizontal = 16.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (icon != null) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = if (prominent) accent else look.textPrimary,
                modifier = Modifier.size(18.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
        }
        Text(
            text = text,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
            color = look.textPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
fun GlassBadge(
    text: String,
    color: Color,
    modifier: Modifier = Modifier
) {
    val capsule = CircleShape
    Surface(
        modifier = modifier,
        shape = capsule,
        color = color.copy(alpha = 0.18f),
        border = BorderStroke(1.dp, color.copy(alpha = 0.48f))
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            color = color,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 5.dp),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
fun GlassModalDialog(
    onDismissRequest: () -> Unit,
    title: String,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    val look = LocalGlassLook.current
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { visible = true }

    Dialog(
        onDismissRequest = onDismissRequest,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        CompositionLocalProvider(LocalGlassChrome provides null) {
            AnimatedVisibility(
                visible = visible,
                enter = fadeIn(tween(200)) + scaleIn(
                    initialScale = 0.93f,
                    animationSpec = spring(dampingRatio = 0.78f, stiffness = Spring.StiffnessMedium)
                ),
                exit = fadeOut(tween(150)) + scaleOut(targetScale = 0.95f)
            ) {
                GlassCard(
                    cornerRadius = 28.dp,
                    elevated = true,
                    tintOverride = look.sheet,
                    backdrop = null,
                    modifier = modifier
                        .padding(horizontal = 20.dp, vertical = 24.dp)
                        .widthIn(max = 520.dp)
                        .fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(20.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.weight(1f)
                            ) {
                                if (icon != null) {
                                    Box(
                                        modifier = Modifier
                                            .size(34.dp)
                                            .clip(RoundedCornerShape(10.dp))
                                            .background(look.accent.copy(alpha = 0.16f)),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Icon(
                                            imageVector = icon,
                                            contentDescription = null,
                                            tint = look.accent,
                                            modifier = Modifier.size(19.dp)
                                        )
                                    }
                                    Spacer(modifier = Modifier.width(10.dp))
                                }
                                Text(
                                    text = title,
                                    style = MaterialTheme.typography.titleLarge,
                                    fontWeight = FontWeight.Bold,
                                    color = look.textPrimary,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            GlassCircleButton(
                                icon = GlassIcons.Close,
                                contentDescription = stringResource(R.string.common_close),
                                onClick = onDismissRequest,
                                size = 40.dp
                            )
                        }
                        Spacer(modifier = Modifier.height(14.dp))
                        content()
                    }
                }
            }
        }
    }
}

@Composable
fun GlassSheet(
    onDismissRequest: () -> Unit,
    title: String,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    val look = LocalGlassLook.current
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { visible = true }

    Dialog(
        onDismissRequest = onDismissRequest,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)
    ) {
        CompositionLocalProvider(LocalGlassChrome provides null) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(look.dim)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = onDismissRequest
                    ),
                contentAlignment = Alignment.BottomCenter
            ) {
                AnimatedVisibility(
                    visible = visible,
                    enter = slideInVertically(
                        initialOffsetY = { it / 2 },
                        animationSpec = spring(dampingRatio = 0.82f, stiffness = Spring.StiffnessMediumLow)
                    ) + fadeIn(tween(180)),
                    exit = slideOutVertically(targetOffsetY = { it / 2 }) + fadeOut(tween(140))
                ) {
                    val sheetShape = RoundedCornerShape(topStart = 30.dp, topEnd = 30.dp)
                    Box(
                        modifier = modifier
                            .widthIn(max = 600.dp)
                            .fillMaxWidth()
                            .heightIn(max = 680.dp)
                            .liquidGlass(shape = sheetShape, elevated = true, tintOverride = look.sheet, backdrop = null)
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                                onClick = {}
                            )
                            .navigationBarsPadding()
                            .padding(start = 20.dp, end = 20.dp, top = 10.dp, bottom = 20.dp)
                    ) {
                        Column(modifier = Modifier.fillMaxWidth()) {
                            Box(
                                modifier = Modifier
                                    .align(Alignment.CenterHorizontally)
                                    .size(width = 40.dp, height = 4.dp)
                                    .clip(CircleShape)
                                    .background(look.textMuted.copy(alpha = 0.55f))
                            )
                            Spacer(modifier = Modifier.height(12.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.weight(1f)
                                ) {
                                    if (icon != null) {
                                        Box(
                                            modifier = Modifier
                                                .size(34.dp)
                                                .clip(RoundedCornerShape(10.dp))
                                                .background(look.accent.copy(alpha = 0.16f)),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Icon(
                                                imageVector = icon,
                                                contentDescription = null,
                                                tint = look.accent,
                                                modifier = Modifier.size(19.dp)
                                            )
                                        }
                                        Spacer(modifier = Modifier.width(10.dp))
                                    }
                                    Text(
                                        text = title,
                                        style = MaterialTheme.typography.titleLarge,
                                        fontWeight = FontWeight.Bold,
                                        color = look.textPrimary,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                                GlassCircleButton(
                                    icon = GlassIcons.Close,
                                    contentDescription = stringResource(R.string.common_close),
                                    onClick = onDismissRequest,
                                    size = 40.dp
                                )
                            }
                            Spacer(modifier = Modifier.height(12.dp))
                            content()
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun novaOutlinedTextFieldColors(): TextFieldColors {
    val look = LocalGlassLook.current
    return OutlinedTextFieldDefaults.colors(
        focusedTextColor = look.textPrimary,
        unfocusedTextColor = look.textPrimary,
        disabledTextColor = look.textMuted,
        focusedContainerColor = look.fill2,
        unfocusedContainerColor = look.fill,
        disabledContainerColor = look.fill.copy(alpha = 0.5f),
        cursorColor = look.accent,
        focusedBorderColor = look.accent,
        unfocusedBorderColor = look.cardBorder,
        disabledBorderColor = look.separator,
        focusedLabelColor = look.accent,
        unfocusedLabelColor = look.textSecondary,
        disabledLabelColor = look.textMuted,
        focusedPlaceholderColor = look.textMuted,
        unfocusedPlaceholderColor = look.textMuted,
        focusedSupportingTextColor = look.textSecondary,
        unfocusedSupportingTextColor = look.textSecondary,
        focusedLeadingIconColor = look.accent,
        unfocusedLeadingIconColor = look.textSecondary,
        focusedTrailingIconColor = look.accent,
        unfocusedTrailingIconColor = look.textSecondary
    )
}
