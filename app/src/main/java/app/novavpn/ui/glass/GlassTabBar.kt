package app.novavpn.ui.glass

import androidx.annotation.StringRes
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Analytics
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Layers
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.rounded.Analytics
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Layers
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.NavigationRailItemDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.R
import kotlinx.serialization.Serializable

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
    NovaTabSpec(NovaDestination.Home, "tab_home", R.string.nav_home, Icons.Rounded.Home, Icons.Outlined.Home),
    NovaTabSpec(NovaDestination.Profiles, "tab_profiles", R.string.nav_profiles, Icons.Rounded.Layers, Icons.Outlined.Layers),
    NovaTabSpec(NovaDestination.Stats, "tab_stats", R.string.nav_stats, Icons.Rounded.Analytics, Icons.Outlined.Analytics),
    NovaTabSpec(NovaDestination.Settings, "tab_settings", R.string.nav_settings, Icons.Rounded.Settings, Icons.Outlined.Settings)
)

@Composable
fun GlassTabBar(
    current: NovaDestination,
    onSelect: (NovaDestination) -> Unit,
    modifier: Modifier = Modifier
) {
    val look = LocalGlassLook.current
    val haptic = LocalHapticFeedback.current
    val currentIndex = novaTabs.indexOfFirst { it.dest == current }.coerceAtLeast(0)
    val capsule = RoundedCornerShape(32.dp)

    Box(
        modifier = modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        contentAlignment = Alignment.Center
    ) {
        BoxWithConstraints(
            modifier = Modifier
                .widthIn(max = 520.dp)
                .fillMaxWidth()
                .height(64.dp)
                .liquidGlass(shape = capsule, elevated = true)
                .padding(5.dp)
        ) {
            val slotWidth = maxWidth / novaTabs.size
            val platterOffset by animateDpAsState(
                targetValue = slotWidth * currentIndex,
                animationSpec = spring(dampingRatio = 0.72f, stiffness = Spring.StiffnessMediumLow),
                label = "tab_platter"
            )

            Box(
                modifier = Modifier
                    .offset(x = platterOffset)
                    .width(slotWidth)
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(26.dp))
                    .background(
                        Brush.horizontalGradient(
                            listOf(
                                look.accent.copy(alpha = 0.24f),
                                look.secondaryAccent.copy(alpha = 0.20f)
                            )
                        )
                    )
                    .glassHighlight(RoundedCornerShape(26.dp), look.highlight, 0.8.dp)
            )

            Row(
                modifier = Modifier.fillMaxSize(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                novaTabs.forEach { tab ->
                    val selected = current == tab.dest
                    val label = stringResource(tab.titleRes)
                    val source = remember { MutableInteractionSource() }
                    val itemColor by animateColorAsState(
                        targetValue = if (selected) look.accent else look.textSecondary,
                        animationSpec = tween(220),
                        label = "tab_color_${tab.tag}"
                    )

                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .defaultMinSize(minHeight = 48.dp)
                            .glassPress(source, scale = 0.94f)
                            .clip(RoundedCornerShape(26.dp))
                            .clickable(
                                interactionSource = source,
                                indication = null,
                                onClick = {
                                    if (!selected) {
                                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                        onSelect(tab.dest)
                                    }
                                }
                            )
                            .testTag(tab.tag),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Icon(
                            imageVector = if (selected) tab.iconSelected else tab.iconUnselected,
                            contentDescription = label,
                            tint = itemColor,
                            modifier = Modifier.size(22.dp)
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = label,
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                            color = if (selected) look.textPrimary else look.textSecondary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
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
                        contentDescription = label
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
