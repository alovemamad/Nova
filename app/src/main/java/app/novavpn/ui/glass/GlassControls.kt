package app.novavpn.ui.glass

import android.graphics.RenderEffect
import android.graphics.Shader
import android.os.Build
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PowerSettingsNew
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import app.novavpn.domain.model.GlassConnectControl
import app.novavpn.domain.model.VpnState
import com.example.R
import kotlin.math.roundToInt

@Composable
fun NovaConnectControl(
    controlType: GlassConnectControl,
    vpnState: VpnState,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier
) {
    val haptic = LocalHapticFeedback.current
    val triggeredToggle = {
        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
        onToggle()
    }

    when (controlType) {
        GlassConnectControl.ORB -> NovaOrbControl(vpnState, triggeredToggle, modifier)
        GlassConnectControl.ZED_GLASS -> NovaZedGlassControl(vpnState, triggeredToggle, modifier)
        GlassConnectControl.BUTTON -> NovaPillButtonControl(vpnState, triggeredToggle, modifier)
        GlassConnectControl.SWITCH -> NovaSwitchControl(vpnState, triggeredToggle, modifier)
        GlassConnectControl.SLIDER -> NovaSlideControl(vpnState, triggeredToggle, modifier)
    }
}

@Composable
private fun NovaOrbControl(
    vpnState: VpnState,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier
) {
    val look = LocalGlassLook.current
    val isConnected = vpnState == VpnState.CONNECTED
    val isBusy = vpnState == VpnState.CONNECTING || vpnState == VpnState.RECONNECTING
    val isError = vpnState == VpnState.ERROR
    val isPaused = vpnState == VpnState.PAUSED

    val ringColor by animateColorAsState(
        targetValue = when {
            isConnected -> look.accent
            isBusy -> look.secondaryAccent
            isPaused -> look.warning
            isError -> look.danger
            else -> look.cardBorder
        },
        animationSpec = tween(420),
        label = "orb_color"
    )

    val inf = rememberInfiniteTransition(label = "orb_pulse")
    val pulse by inf.animateFloat(
        initialValue = 1f,
        targetValue = if (isConnected || isBusy) 1.12f else 1.03f,
        animationSpec = infiniteRepeatable(
            animation = tween(if (isBusy) 900 else 2200, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulse"
    )
    val sweep by inf.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(if (isBusy) 1500 else 5000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "sweep"
    )

    val btnScale by animateFloatAsState(
        targetValue = if (isConnected) 1.03f else 1f,
        animationSpec = spring(dampingRatio = 0.65f, stiffness = Spring.StiffnessMediumLow),
        label = "scale"
    )

    val source = remember { MutableInteractionSource() }
    val desc = stringResource(if (isConnected) R.string.ctrl_disconnect_desc else R.string.ctrl_connect_desc)

    Box(
        modifier = modifier
            .size(216.dp)
            .semantics { contentDescription = desc }
            .testTag("connect_orb_button"),
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.fillMaxSize().scale(pulse)) {
            val r = size.minDimension / 2f
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(ringColor.copy(alpha = 0.30f), Color.Transparent),
                    center = center,
                    radius = r
                ),
                radius = r
            )
            if (isBusy || isConnected) {
                drawArc(
                    brush = Brush.sweepGradient(
                        colors = listOf(
                            Color.Transparent,
                            look.accent.copy(alpha = 0.90f),
                            look.secondaryAccent.copy(alpha = 0.90f),
                            Color.Transparent
                        )
                    ),
                    startAngle = sweep,
                    sweepAngle = 220f,
                    useCenter = false,
                    style = Stroke(width = 3.5.dp.toPx(), cap = StrokeCap.Round)
                )
            }
        }

        Box(
            modifier = Modifier
                .size(154.dp)
                .glassPress(source, scale = 0.93f)
                .scale(btnScale)
                .liquidGlass(shape = CircleShape, elevated = true)
                .clickable(
                    interactionSource = source,
                    indication = null,
                    role = Role.Button,
                    onClick = onToggle
                ),
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(
                    imageVector = when {
                        isPaused -> Icons.Rounded.Pause
                        isConnected -> Icons.Rounded.Shield
                        else -> Icons.Rounded.PowerSettingsNew
                    },
                    contentDescription = null,
                    tint = when {
                        isConnected -> look.accent
                        isBusy -> look.secondaryAccent
                        isPaused -> look.warning
                        isError -> look.danger
                        else -> look.textPrimary
                    },
                    modifier = Modifier.size(46.dp)
                )
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = stringResource(
                        when {
                            isConnected -> R.string.ctrl_active
                            isBusy -> R.string.ctrl_linking
                            isPaused -> R.string.ctrl_paused
                            isError -> R.string.ctrl_retry
                            else -> R.string.ctrl_connect
                        }
                    ),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                    color = look.textPrimary
                )
            }
        }
    }
}

@Composable
private fun NovaZedGlassControl(
    vpnState: VpnState,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier
) {
    val look = LocalGlassLook.current
    val isConnected = vpnState == VpnState.CONNECTED
    val isBusy = vpnState == VpnState.CONNECTING || vpnState == VpnState.RECONNECTING
    val isPaused = vpnState == VpnState.PAUSED
    val isError = vpnState == VpnState.ERROR
    val shape = RoundedCornerShape(46.dp)

    val glowColor by animateColorAsState(
        targetValue = when {
            isConnected -> look.accent
            isBusy -> look.secondaryAccent
            isPaused -> look.warning
            isError -> look.danger
            else -> Color.Transparent
        },
        animationSpec = tween(420),
        label = "zed_glow"
    )

    val inf = rememberInfiniteTransition(label = "zed_motion")
    val shinePhase by inf.animateFloat(
        initialValue = -0.35f,
        targetValue = 1.35f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = if (isBusy) 1300 else 3200, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "zed_shine"
    )
    val breathe by inf.animateFloat(
        initialValue = 0.72f,
        targetValue = 1.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1900, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "zed_breathe"
    )

    val source = remember { MutableInteractionSource() }
    val glowModifier = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !look.prefs.reduceTransparency) {
        Modifier.graphicsLayer {
            renderEffect = RenderEffect.createBlurEffect(36f, 36f, Shader.TileMode.DECAL).asComposeRenderEffect()
        }
    } else {
        Modifier
    }

    Box(
        modifier = modifier
            .size(216.dp)
            .glassPress(source, scale = 0.94f)
            .testTag("connect_zed_button"),
        contentAlignment = Alignment.Center
    ) {
        if (glowColor.alpha > 0f) {
            Canvas(
                modifier = Modifier
                    .size(196.dp)
                    .then(glowModifier)
            ) {
                drawRoundRect(
                    brush = Brush.linearGradient(
                        listOf(glowColor.copy(alpha = 0.65f * breathe), look.secondaryAccent.copy(alpha = 0.45f * breathe))
                    ),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(46.dp.toPx())
                )
            }
        }

        Box(
            modifier = Modifier
                .size(182.dp)
                .liquidGlass(
                    shape = shape,
                    elevated = true,
                    tintOverride = if (isConnected) look.accent.copy(alpha = 0.18f) else null
                )
                .clickable(
                    interactionSource = source,
                    indication = null,
                    role = Role.Button,
                    onClick = onToggle
                ),
            contentAlignment = Alignment.Center
        ) {
            Canvas(
                modifier = Modifier
                    .fillMaxSize()
                    .clip(shape)
            ) {
                // Caustic sheen sweep across the glass crest
                val cx = size.width * shinePhase
                drawRect(
                    brush = Brush.linearGradient(
                        0f to Color.Transparent,
                        0.42f to Color.Transparent,
                        0.5f to Color.White.copy(alpha = if (isBusy) 0.34f else 0.16f),
                        0.58f to Color.Transparent,
                        1f to Color.Transparent,
                        start = Offset(cx - size.width * 0.4f, 0f),
                        end = Offset(cx + size.width * 0.4f, size.height)
                    )
                )
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(
                    imageVector = when {
                        isPaused -> Icons.Rounded.Pause
                        isConnected -> Icons.Rounded.Shield
                        else -> Icons.Rounded.AutoAwesome
                    },
                    contentDescription = stringResource(R.string.ctrl_toggle_vpn),
                    tint = when {
                        isConnected -> look.accent
                        isBusy -> look.secondaryAccent
                        isPaused -> look.warning
                        isError -> look.danger
                        else -> look.textPrimary
                    },
                    modifier = Modifier.size(48.dp)
                )
                Spacer(modifier = Modifier.height(10.dp))
                Text(
                    text = stringResource(
                        when {
                            isConnected -> R.string.ctrl_protected
                            isBusy -> R.string.ctrl_linking
                            isPaused -> R.string.ctrl_paused
                            isError -> R.string.ctrl_retry
                            else -> R.string.ctrl_tap_shield
                        }
                    ),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = look.textPrimary
                )
            }
        }
    }
}

@Composable
private fun NovaPillButtonControl(
    vpnState: VpnState,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier
) {
    val look = LocalGlassLook.current
    val isConnected = vpnState == VpnState.CONNECTED
    val source = remember { MutableInteractionSource() }
    val bg = if (isConnected) look.accent.copy(alpha = 0.24f) else look.secondaryAccent.copy(alpha = 0.22f)
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(64.dp)
            .glassPress(source, scale = 0.97f)
            .liquidGlass(shape = RoundedCornerShape(32.dp), elevated = true, tintOverride = bg)
            .clickable(interactionSource = source, indication = null, role = Role.Button, onClick = onToggle)
            .testTag("connect_pill_button"),
        contentAlignment = Alignment.Center
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Icon(
                imageVector = Icons.Rounded.Bolt,
                contentDescription = null,
                tint = if (isConnected) look.accent else look.textPrimary
            )
            Text(
                text = stringResource(if (isConnected) R.string.ctrl_disconnect_desc else R.string.ctrl_connect_desc),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = look.textPrimary
            )
        }
    }
}

@Composable
private fun NovaSwitchControl(
    vpnState: VpnState,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier
) {
    val look = LocalGlassLook.current
    val isConnected = vpnState == VpnState.CONNECTED || vpnState == VpnState.CONNECTING || vpnState == VpnState.RECONNECTING
    val source = remember { MutableInteractionSource() }
    val thumbOffset by animateDpAsState(
        targetValue = if (isConnected) 74.dp else 8.dp,
        animationSpec = spring(dampingRatio = 0.65f, stiffness = Spring.StiffnessMediumLow),
        label = "switch_thumb"
    )
    Box(
        modifier = modifier
            .width(148.dp)
            .height(74.dp)
            .glassPress(source, scale = 0.95f)
            .liquidGlass(
                shape = RoundedCornerShape(37.dp),
                elevated = true,
                tintOverride = if (isConnected) look.accent.copy(alpha = 0.25f) else null
            )
            .clickable(interactionSource = source, indication = null, role = Role.Switch, onClick = onToggle)
            .testTag("connect_switch_button"),
        contentAlignment = Alignment.CenterStart
    ) {
        Box(
            modifier = Modifier
                .offset(x = thumbOffset)
                .size(58.dp)
                .clip(CircleShape)
                .background(if (isConnected) look.accent else look.textSecondary.copy(alpha = 0.7f))
                .glassHighlight(CircleShape, Color.White.copy(alpha = 0.55f), 0.8.dp),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Rounded.PowerSettingsNew,
                contentDescription = stringResource(R.string.ctrl_toggle_vpn),
                tint = Color(0xFF041412)
            )
        }
    }
}

@Composable
private fun NovaSlideControl(
    vpnState: VpnState,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier
) {
    val look = LocalGlassLook.current
    val isConnected = vpnState == VpnState.CONNECTED
    val density = LocalDensity.current
    var dragX by remember { mutableFloatStateOf(0f) }

    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .height(64.dp)
            .liquidGlass(shape = RoundedCornerShape(32.dp), elevated = true)
            .testTag("connect_slide_button")
    ) {
        val maxOffsetPx = with(density) { (maxWidth - 64.dp).toPx().coerceAtLeast(1f) }
        val baseOffset = if (isConnected) maxOffsetPx else 0f
        val currentOffset = (baseOffset + dragX).coerceIn(0f, maxOffsetPx)

        Text(
            text = stringResource(if (isConnected) R.string.slide_to_disconnect else R.string.slide_to_connect),
            style = MaterialTheme.typography.titleMedium,
            color = look.textSecondary,
            modifier = Modifier.align(Alignment.Center)
        )

        Box(
            modifier = Modifier
                .offset { IntOffset(currentOffset.roundToInt(), 0) }
                .padding(4.dp)
                .size(56.dp)
                .clip(CircleShape)
                .background(if (isConnected) look.danger else look.accent)
                .glassHighlight(CircleShape, Color.White.copy(alpha = 0.55f), 0.8.dp)
                .pointerInput(isConnected, maxOffsetPx) {
                    detectHorizontalDragGestures(
                        onDragEnd = {
                            val threshold = maxOffsetPx * 0.45f
                            if ((!isConnected && dragX > threshold) || (isConnected && dragX < -threshold)) {
                                onToggle()
                            }
                            dragX = 0f
                        },
                        onDragCancel = { dragX = 0f },
                        onHorizontalDrag = { _, delta -> dragX += delta }
                    )
                },
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Rounded.PowerSettingsNew,
                contentDescription = stringResource(R.string.ctrl_slide_desc),
                tint = Color(0xFF041412)
            )
        }
    }
}

@Composable
fun SpeedSparkline(
    samples: List<Pair<Long, Long>>,
    modifier: Modifier = Modifier
) {
    val look = LocalGlassLook.current
    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(48.dp)
    ) {
        if (samples.size < 2) return@Canvas
        val maxVal = samples.maxOf { maxOf(it.first, it.second) }.coerceAtLeast(1024L).toFloat()
        val stepX = size.width / (samples.size - 1).coerceAtLeast(1)

        val downPath = Path()
        val upPath = Path()
        samples.forEachIndexed { idx, (down, up) ->
            val x = idx * stepX
            val yDown = size.height - (down / maxVal) * (size.height * 0.85f) - 4f
            val yUp = size.height - (up / maxVal) * (size.height * 0.85f) - 4f
            if (idx == 0) {
                downPath.moveTo(x, yDown)
                upPath.moveTo(x, yUp)
            } else {
                downPath.lineTo(x, yDown)
                upPath.lineTo(x, yUp)
            }
        }
        drawPath(
            path = upPath,
            color = look.secondaryAccent.copy(alpha = 0.75f),
            style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round)
        )
        drawPath(
            path = downPath,
            color = look.accent,
            style = Stroke(width = 2.5.dp.toPx(), cap = StrokeCap.Round)
        )
    }
}
