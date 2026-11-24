package app.novavpn.ui.glass

import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
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
    Box(
        modifier = modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        contentAlignment = Alignment.Center
    ) {
        Row(
            modifier = Modifier
                .widthIn(max = 520.dp)
                .fillMaxWidth()
                .liquidGlass(shape = RoundedCornerShape(30.dp), elevated = true)
                .padding(horizontal = 6.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically
        ) {
            novaTabs.forEach { tab ->
                val selected = current == tab.dest
                val label = stringResource(tab.titleRes)
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .defaultMinSize(minHeight = 52.dp)
                        .clip(RoundedCornerShape(24.dp))
                        .background(
                            if (selected) {
                                Brush.horizontalGradient(
                                    listOf(look.accent.copy(alpha = 0.22f), look.secondaryAccent.copy(alpha = 0.20f))
                                )
                            } else {
                                Brush.horizontalGradient(listOf(Color.Transparent, Color.Transparent))
                            }
                        )
                        .clickable { onSelect(tab.dest) }
                        .padding(vertical = 6.dp)
                        .testTag(tab.tag),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Icon(
                        imageVector = if (selected) tab.iconSelected else tab.iconUnselected,
                        contentDescription = label,
                        tint = if (selected) look.accent else look.textSecondary,
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
