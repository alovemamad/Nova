package app.novavpn.ui.glass

import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.absoluteOffset
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.NavigationRailItemDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.AbsoluteAlignment
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.changedToUp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.R
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.effects.vibrancy
import com.kyant.backdrop.highlight.Highlight
import com.kyant.backdrop.shadow.Shadow
import kotlinx.serialization.Serializable
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.roundToInt

@Serializable
sealed interface NovaDestination {
    @Serializable data object Home : NovaDestination
    @Serializable data object Profiles : NovaDestination
    @Serializable data object Stats : NovaDestination
    @Serializable data object Settings : NovaDestination
}

data class NovaTabSpec(
    val dest: NovaDestination,
    val tag: String,
    @StringRes val titleRes: Int,
    val iconSelected: ImageVector,
    val iconUnselected: ImageVector
)

val novaTabs = listOf(
    NovaTabSpec(NovaDestination.Home, "tab_home", R.string.nav_home, GlassIcons.House, GlassIcons.House),
    NovaTabSpec(NovaDestination.Profiles, "tab_profiles", R.string.nav_profiles, GlassIcons.Layers, GlassIcons.Layers),
    NovaTabSpec(NovaDestination.Stats, "tab_stats", R.string.nav_stats, GlassIcons.Gauge, GlassIcons.Gauge),
    NovaTabSpec(NovaDestination.Settings, "tab_settings", R.string.nav_settings, GlassIcons.Gear, GlassIcons.Gear)
)

internal fun tabIndexForPointerX(
    xPx: Float,
    padPx: Float,
    tabWidthPx: Float,
    tabCount: Int,
    isRtl: Boolean
): Int {
    if (tabCount <= 1 || tabWidthPx <= 0f) return 0
    val visualSlot = floor(((xPx - padPx) / tabWidthPx).toDouble())
        .toInt()
        .coerceIn(0, tabCount - 1)
    return if (isRtl) (tabCount - 1 - visualSlot) else visualSlot
}

internal fun visualSlotForTabIndex(
    tabIndex: Int,
    tabCount: Int,
    isRtl: Boolean
): Int {
    if (tabCount <= 1) return 0
    val clamped = tabIndex.coerceIn(0, tabCount - 1)
    return if (isRtl) (tabCount - 1 - clamped) else clamped
}

internal fun clampedBubbleLeftPx(
    pointerXPx: Float,
    bubbleWidthPx: Float,
    pillWidthPx: Float,
    overshootPx: Float
): Float {
    if (pillWidthPx <= 0f) return 0f
    val minLeft = -overshootPx
    val maxLeft = (pillWidthPx - bubbleWidthPx + overshootPx).coerceAtLeast(minLeft)
    return (pointerXPx - bubbleWidthPx / 2f).coerceIn(minLeft, maxLeft)
}

internal fun snapTabCenterXForIndex(
    tabIndex: Int,
    padPx: Float,
    tabWidthPx: Float,
    tabCount: Int,
    isRtl: Boolean
): Float {
    val slot = visualSlotForTabIndex(tabIndex, tabCount, isRtl)
    return padPx + tabWidthPx * (slot + 0.5f)
}

private sealed interface TabGestureStart {
    data class Tap(val xPx: Float) : TabGestureStart
    data class Drag(val xPx: Float) : TabGestureStart
    data object Yield : TabGestureStart
}

private suspend fun AwaitPointerEventScope.awaitTapOrHorizontalDrag(
    down: PointerInputChange,
    touchSlop: Float,
    holdTimeoutMs: Long = 175L
): TabGestureStart {
    var result: TabGestureStart = TabGestureStart.Drag(down.position.x)
    val completedInTimeout = withTimeoutOrNull(holdTimeoutMs) {
        while (true) {
            val event = awaitPointerEvent()
            val change = event.changes.firstOrNull { it.id == down.id }
            if (change == null) {
                result = TabGestureStart.Yield
                return@withTimeoutOrNull true
            }
            if (change.changedToUp()) {
                if (change.isConsumed) {
                    result = TabGestureStart.Yield
                } else {
                    change.consume()
                    result = TabGestureStart.Tap(change.position.x)
                }
                return@withTimeoutOrNull true
            }
            if (!change.pressed) {
                result = TabGestureStart.Yield
                return@withTimeoutOrNull true
            }
            val dx = change.position.x - down.position.x
            val dy = change.position.y - down.position.y
            if (hypot(dx, dy) > touchSlop) {
                if (abs(dy) > abs(dx)) {
                    result = TabGestureStart.Yield
                } else {
                    change.consume()
                    result = TabGestureStart.Drag(change.position.x)
                }
                return@withTimeoutOrNull true
            }
        }
    }
    if (completedInTimeout == null) {
        return TabGestureStart.Drag(down.position.x)
    }
    return result
}

@Composable
fun GlassTabBar(
    current: NovaDestination,
    onSelect: (NovaDestination) -> Unit,
    modifier: Modifier = Modifier,
    shrunk: Boolean = false,
    backdrop: GlassBackdrop? = LocalGlassChrome.current?.combined
) {
    val horizontalPad by animateDpAsState(
        targetValue = if (shrunk) 44.dp else 16.dp,
        animationSpec = spring(dampingRatio = 0.78f, stiffness = Spring.StiffnessMediumLow),
        label = "tab_pad"
    )
    val barHeight by animateDpAsState(
        targetValue = if (shrunk) 50.dp else 64.dp,
        animationSpec = spring(dampingRatio = 0.78f, stiffness = Spring.StiffnessMediumLow),
        label = "tab_height"
    )

    Box(
        modifier = modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(horizontal = horizontalPad, vertical = 12.dp),
        contentAlignment = Alignment.Center
    ) {
        GlassBottomTabPill(
            tabs = novaTabs,
            current = current,
            onSelect = onSelect,
            shrunk = shrunk,
            backdrop = backdrop,
            modifier = Modifier
                .widthIn(max = 520.dp)
                .fillMaxWidth()
                .height(barHeight)
        )
    }
}

@Composable
private fun GlassBottomTabPill(
    tabs: List<NovaTabSpec>,
    current: NovaDestination,
    onSelect: (NovaDestination) -> Unit,
    shrunk: Boolean,
    backdrop: GlassBackdrop?,
    modifier: Modifier = Modifier
) {
    if (tabs.isEmpty()) return
    val look = LocalGlassLook.current
    val tiltState = LocalGlassTiltState.current
    val fallbackTilt = LocalGlassTiltOffset.current
    val density = LocalDensity.current
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val haptic = LocalHapticFeedback.current
    val exported = rememberLayerBackdrop()
    val navigate by rememberUpdatedState(onSelect)
    val activeCurrent by rememberUpdatedState(current)

    var holding by remember { mutableStateOf(false) }
    var holdX by remember { mutableFloatStateOf(0f) }
    val currentIndex = tabs.indexOfFirst { it.dest == current }.coerceAtLeast(0)

    BoxWithConstraints(modifier = modifier) {
        val pad = 4.dp
        val padPx = with(density) { pad.toPx() }
        val pillWidth = maxWidth
        val widthPx = with(density) { pillWidth.toPx() }.coerceAtLeast(1f)
        val pillHeight = maxHeight
        val tabWidth = (maxWidth - pad * 2) / tabs.size
        val tabPx = with(density) { tabWidth.toPx() }.coerceAtLeast(1f)

        val hoverIndex = if (holding) {
            tabIndexForPointerX(holdX, padPx, tabPx, tabs.size, rtl)
        } else {
            -1
        }
        val barScale by animateFloatAsState(
            targetValue = if (holding) 1.03f else 1f,
            animationSpec = spring(dampingRatio = 0.55f, stiffness = Spring.StiffnessMedium),
            label = "glass_bar_scale"
        )
        val platterSlot by animateFloatAsState(
            targetValue = currentIndex.toFloat(),
            animationSpec = spring(dampingRatio = 0.72f, stiffness = Spring.StiffnessMediumLow),
            label = "glass_bar_platter_slot"
        )
        val platterAlpha by animateFloatAsState(
            targetValue = if (holding) 0f else 1f,
            animationSpec = tween(140),
            label = "glass_bar_platter_alpha"
        )

        val shape = CircleShape
        val canRenderLive = backdrop != null && !look.reduced && isGlassRenderEffectSupported()
        val surface = if (canRenderLive) look.chrome else look.chromeSolid.copy(alpha = 0.94f)
        val tiltEnabled = look.prefs.motionHighlights && !look.reduced

        Box(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    scaleX = barScale
                    scaleY = barScale
                }
                .then(
                    if (canRenderLive && backdrop != null) {
                        Modifier
                            .clip(shape)
                            .drawBackdrop(
                                backdrop = backdrop,
                                shape = { shape },
                                effects = {
                                    vibrancy()
                                    blur(12.dp.toPx())
                                    if (isGlassLensSupported()) {
                                        lens(14.dp.toPx(), 26.dp.toPx())
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
                                shadow = { Shadow(radius = 20.dp, color = look.shadow) },
                                exportedBackdrop = exported,
                                onDrawSurface = { drawRect(surface) }
                            )
                    } else {
                        Modifier.liquidGlass(
                            shape = shape,
                            elevated = true,
                            tintOverride = surface,
                            backdrop = null
                        )
                    }
                )
                .pointerInput(tabs, rtl, tabPx, padPx) {
                    val slop = viewConfiguration.touchSlop
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        if (down.isConsumed) return@awaitEachGesture

                        when (val start = awaitTapOrHorizontalDrag(down, slop)) {
                            TabGestureStart.Yield -> return@awaitEachGesture
                            is TabGestureStart.Tap -> {
                                val targetIdx = tabIndexForPointerX(start.xPx, padPx, tabPx, tabs.size, rtl)
                                val dest = tabs[targetIdx].dest
                                if (dest != activeCurrent) {
                                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                    navigate(dest)
                                }
                                return@awaitEachGesture
                            }
                            is TabGestureStart.Drag -> {
                                holdX = start.xPx
                                holding = true
                                var lastHovered = tabIndexForPointerX(holdX, padPx, tabPx, tabs.size, rtl)
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                try {
                                    while (true) {
                                        val event = awaitPointerEvent()
                                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                                        if (change.changedToUp()) {
                                            if (!change.isConsumed && event.type != PointerEventType.Exit) {
                                                change.consume()
                                                val targetIdx = tabIndexForPointerX(
                                                    change.position.x,
                                                    padPx,
                                                    tabPx,
                                                    tabs.size,
                                                    rtl
                                                )
                                                val dest = tabs[targetIdx].dest
                                                if (dest != activeCurrent) {
                                                    navigate(dest)
                                                }
                                            }
                                            break
                                        }
                                        if (!change.pressed) break
                                        holdX = change.position.x
                                        val newHovered = tabIndexForPointerX(holdX, padPx, tabPx, tabs.size, rtl)
                                        if (newHovered != lastHovered) {
                                            lastHovered = newHovered
                                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                        }
                                        change.consume()
                                    }
                                } finally {
                                    holding = false
                                }
                            }
                        }
                    }
                }
        ) {
            Box(
                modifier = Modifier
                    .padding(pad)
                    .fillMaxSize()
            ) {
                Box(
                    modifier = Modifier
                        .offset { IntOffset((platterSlot * tabPx).roundToInt(), 0) }
                        .width(tabWidth)
                        .fillMaxHeight()
                        .graphicsLayer { alpha = platterAlpha }
                        .clip(shape)
                        .background(
                            lerp(
                                look.platter,
                                look.accent.copy(alpha = if (look.isDark) 0.24f else 0.14f),
                                0.45f
                            )
                        )
                        .glassHighlight(shape, look.highlight.copy(alpha = 0.65f), 0.75.dp)
                )
            }

            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(pad)
            ) {
                tabs.forEachIndexed { index, tab ->
                    val isSelected = tab.dest == current
                    val label = stringResource(tab.titleRes)
                    val color = when {
                        holding -> if (index == hoverIndex) look.accent else look.textPrimary
                        isSelected -> look.accent
                        else -> look.textSecondary
                    }
                    NovaTabCell(
                        tab = tab,
                        selected = isSelected,
                        shrunk = shrunk,
                        color = color,
                        label = label,
                        modifier = Modifier
                            .testTag(tab.tag)
                            .semantics(mergeDescendants = true) {
                                role = Role.Tab
                                selected = isSelected
                                contentDescription = label
                                onClick {
                                    if (!isSelected) {
                                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                        navigate(tab.dest)
                                    }
                                    true
                                }
                            }
                    )
                }
            }
        }

        if (holding) {
            val bubbleWidth = tabWidth + 14.dp
            val bubblePx = with(density) { bubbleWidth.toPx() }
            val liftPx = with(density) { 5.dp.toPx() }
            val overshootPx = with(density) { 8.dp.toPx() }
            val left = clampedBubbleLeftPx(holdX, bubblePx, widthPx, overshootPx)
            val bubbleShape = CircleShape
            val lensBackdrop = if (canRenderLive && backdrop != null) {
                rememberCombinedBackdrop(backdrop, exported)
            } else {
                null
            }
            val bubbleMod = Modifier
                .align(AbsoluteAlignment.TopLeft)
                .absoluteOffset { IntOffset(left.roundToInt(), -liftPx.roundToInt()) }
                .size(bubbleWidth, pillHeight + 10.dp)

            Box(
                bubbleMod.then(
                    if (lensBackdrop != null && isGlassLensSupported()) {
                        Modifier
                            .clip(bubbleShape)
                            .drawBackdrop(
                                backdrop = lensBackdrop,
                                shape = { bubbleShape },
                                effects = { lens(12.dp.toPx(), 24.dp.toPx(), chromaticAberration = true) },
                                highlight = { Highlight(width = 1.dp, alpha = 1f) },
                                shadow = {
                                    Shadow(
                                        radius = 22.dp,
                                        offset = DpOffset(0.dp, 8.dp),
                                        color = Color.Black.copy(alpha = 0.22f)
                                    )
                                },
                                onDrawBackdrop = { drawIt -> scale(1.18f, 1.18f, center) { drawIt() } },
                                onDrawSurface = { drawRect(Color.White.copy(alpha = if (look.isDark) 0.05f else 0.14f)) }
                            )
                    } else {
                        Modifier.liquidGlass(
                            shape = bubbleShape,
                            elevated = true,
                            tintOverride = look.thumb,
                            backdrop = null
                        )
                    }
                )
            )

            Box(bubbleMod.clip(bubbleShape)) {
                Row(
                    modifier = Modifier
                        .wrapContentSize(AbsoluteAlignment.TopLeft, unbounded = true)
                        .absoluteOffset { IntOffset((-left).roundToInt(), liftPx.roundToInt()) }
                        .size(pillWidth, pillHeight)
                        .graphicsLayer {
                            scaleX = 1.18f
                            scaleY = 1.18f
                            transformOrigin = TransformOrigin((holdX / widthPx).coerceIn(0f, 1f), 0.5f)
                        }
                        .padding(pad)
                ) {
                    tabs.forEachIndexed { index, tab ->
                        NovaTabCell(
                            tab = tab,
                            selected = index == hoverIndex,
                            shrunk = shrunk,
                            color = if (index == hoverIndex) look.accent else look.textPrimary,
                            label = stringResource(tab.titleRes)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun RowScope.NovaTabCell(
    tab: NovaTabSpec,
    selected: Boolean,
    shrunk: Boolean,
    color: Color,
    label: String,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .weight(1f)
            .fillMaxHeight()
            .defaultMinSize(minHeight = 40.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            imageVector = if (selected) tab.iconSelected else tab.iconUnselected,
            contentDescription = null,
            tint = color,
            modifier = Modifier.size(if (shrunk) 20.dp else 22.dp)
        )
        AnimatedVisibility(
            visible = !shrunk,
            enter = fadeIn(tween(150)) + expandVertically(),
            exit = fadeOut(tween(120)) + shrinkVertically()
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                    color = color,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
fun GlassNavigationRail(
    current: NovaDestination,
    onSelect: (NovaDestination) -> Unit,
    modifier: Modifier = Modifier
) {
    val look = LocalGlassLook.current
    NavigationRail(
        modifier = modifier,
        containerColor = look.pillSurface
    ) {
        Spacer(modifier = Modifier.height(16.dp))
        novaTabs.forEach { tab ->
            val selected = current == tab.dest
            val label = stringResource(tab.titleRes)
            NavigationRailItem(
                selected = selected,
                onClick = { onSelect(tab.dest) },
                icon = {
                    Icon(
                        imageVector = if (selected) tab.iconSelected else tab.iconUnselected,
                        contentDescription = label,
                        modifier = Modifier.size(22.dp)
                    )
                },
                label = { Text(label, maxLines = 1) },
                colors = NavigationRailItemDefaults.colors(
                    selectedIconColor = look.accent,
                    selectedTextColor = look.textPrimary,
                    indicatorColor = look.accent.copy(alpha = 0.2f),
                    unselectedIconColor = look.textSecondary,
                    unselectedTextColor = look.textSecondary
                ),
                modifier = Modifier.testTag(tab.tag)
            )
        }
    }
}

@Composable
fun ReportScrollShrink(
    listState: LazyListState,
    onShrinkChanged: (Boolean) -> Unit
) {
    val currentCallback by rememberUpdatedState(onShrinkChanged)
    LaunchedEffect(listState) {
        var anchorIndex = listState.firstVisibleItemIndex
        var anchorOffset = listState.firstVisibleItemScrollOffset
        var lastReported: Boolean? = null
        snapshotFlow { listState.firstVisibleItemIndex to listState.firstVisibleItemScrollOffset }
            .collect { (index, offset) ->
                val targetShrunk = when {
                    index == 0 && offset < 24 -> {
                        anchorIndex = index
                        anchorOffset = offset
                        false
                    }
                    else -> {
                        val delta = (index - anchorIndex) * 400 + (offset - anchorOffset)
                        when {
                            delta > 48 -> {
                                anchorIndex = index
                                anchorOffset = offset
                                true
                            }
                            delta < -48 -> {
                                anchorIndex = index
                                anchorOffset = offset
                                false
                            }
                            else -> lastReported ?: false
                        }
                    }
                }
                if (lastReported != targetShrunk) {
                    lastReported = targetShrunk
                    currentCallback(targetShrunk)
                }
            }
    }
}
