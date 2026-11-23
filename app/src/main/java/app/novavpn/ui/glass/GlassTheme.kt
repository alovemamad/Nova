package app.novavpn.ui.glass

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.graphics.Color
import app.novavpn.domain.model.GlassMaterial
import app.novavpn.domain.model.GlassPrefs
import app.novavpn.domain.model.GlassTint
import app.novavpn.domain.model.GlassWallpaper

@Immutable
data class GlassLook(
    val isDark: Boolean,
    val prefs: GlassPrefs,
    val accent: Color,
    val secondaryAccent: Color,
    val textPrimary: Color,
    val textSecondary: Color,
    val textMuted: Color,
    val cardSurface: Color,
    val cardBorder: Color,
    val pillSurface: Color,
    val danger: Color,
    val warning: Color,
    val success: Color
)

val LocalGlassLook = compositionLocalOf {
    GlassLook(
        isDark = true,
        prefs = GlassPrefs(),
        accent = Color(0xFF00E5CC),
        secondaryAccent = Color(0xFF7C5CFF),
        textPrimary = Color(0xFFF3F7FD),
        textSecondary = Color(0xFFB5C2D8),
        textMuted = Color(0xFF7B89A4),
        cardSurface = Color(0xCC11192B),
        cardBorder = Color(0x38FFFFFF),
        pillSurface = Color(0xB80E1627),
        danger = Color(0xFFFF5C77),
        warning = Color(0xFFFFB547),
        success = Color(0xFF10E2A0)
    )
}

fun resolveGlassTintColor(tint: GlassTint): Pair<Color, Color> = when (tint) {
    GlassTint.TEAL -> Color(0xFF00E5CC) to Color(0xFF7C5CFF)
    GlassTint.VIOLET -> Color(0xFF8B6BFF) to Color(0xFF00E5CC)
    GlassTint.BLUE -> Color(0xFF3B82F6) to Color(0xFF06B6D4)
    GlassTint.CYAN -> Color(0xFF22D3EE) to Color(0xFF6366F1)
    GlassTint.MINT -> Color(0xFF2DD4BF) to Color(0xFF38BDF8)
    GlassTint.INDIGO -> Color(0xFF6366F1) to Color(0xFFA855F7)
    GlassTint.PINK -> Color(0xFFEC4899) to Color(0xFF8B5CF6)
    GlassTint.ORANGE -> Color(0xFFF97316) to Color(0xFFEC4899)
    GlassTint.GREEN -> Color(0xFF10B981) to Color(0xFF06B6D4)
    GlassTint.GRAPHITE -> Color(0xFF94A3B8) to Color(0xFF64748B)
}

fun wallpaperStops(wallpaper: GlassWallpaper, isDark: Boolean): List<Color> = if (isDark) {
    when (wallpaper) {
        GlassWallpaper.LAGOON -> listOf(Color(0xFF050914), Color(0xFF082032), Color(0xFF111433))
        GlassWallpaper.MIST -> listOf(Color(0xFF090D16), Color(0xFF141E30), Color(0xFF1D243B))
        GlassWallpaper.DUSK -> listOf(Color(0xFF0C0818), Color(0xFF22143A), Color(0xFF132238))
        GlassWallpaper.GRAPHITE -> listOf(Color(0xFF090B10), Color(0xFF141821), Color(0xFF0E1118))
        GlassWallpaper.PLAIN -> listOf(Color(0xFF070B14), Color(0xFF070B14))
    }
} else {
    when (wallpaper) {
        GlassWallpaper.LAGOON -> listOf(Color(0xFFE7F7F5), Color(0xFFE9EEFD), Color(0xFFF3F6FC))
        GlassWallpaper.MIST -> listOf(Color(0xFFEDF2FA), Color(0xFFE5ECF8), Color(0xFFF6F8FC))
        GlassWallpaper.DUSK -> listOf(Color(0xFFF2EBFA), Color(0xFFE6ECF9), Color(0xFFF8F4FC))
        GlassWallpaper.GRAPHITE -> listOf(Color(0xFFEBEFF5), Color(0xFFE2E6EE), Color(0xFFF4F6FA))
        GlassWallpaper.PLAIN -> listOf(Color(0xFFF3F6FC), Color(0xFFF3F6FC))
    }
}

@Composable
fun GlassThemeProvider(
    isDark: Boolean,
    glassPrefs: GlassPrefs,
    content: @Composable () -> Unit
) {
    val (accent, secondary) = resolveGlassTintColor(glassPrefs.tint)
    val tinted = glassPrefs.material == GlassMaterial.TINTED
    val solid = glassPrefs.reduceTransparency

    val cardSurface = when {
        solid && isDark -> Color(0xFF11192A)
        solid && !isDark -> Color(0xFFFFFFFF)
        tinted && isDark -> accent.copy(alpha = 0.14f)
        tinted && !isDark -> accent.copy(alpha = 0.12f)
        isDark -> Color(0x9E101829)
        else -> Color(0xC2FFFFFF)
    }

    val pillSurface = when {
        solid && isDark -> Color(0xFF151E33)
        solid && !isDark -> Color(0xFFF8FAFF)
        isDark -> Color(0xB80E1627)
        else -> Color(0xD8FFFFFF)
    }

    val look = GlassLook(
        isDark = isDark,
        prefs = glassPrefs,
        accent = accent,
        secondaryAccent = secondary,
        textPrimary = if (isDark) Color(0xFFF3F7FD) else Color(0xFF0E1626),
        textSecondary = if (isDark) Color(0xFFB5C2D8) else Color(0xFF475569),
        textMuted = if (isDark) Color(0xFF7D8BA6) else Color(0xFF64748B),
        cardSurface = cardSurface,
        cardBorder = if (isDark) Color(0x36FFFFFF) else Color(0x5594A3B8),
        pillSurface = pillSurface,
        danger = if (isDark) Color(0xFFFF5C77) else Color(0xFFD92D4E),
        warning = if (isDark) Color(0xFFFFB547) else Color(0xFFD97706),
        success = if (isDark) Color(0xFF10E2A0) else Color(0xFF059669)
    )

    CompositionLocalProvider(LocalGlassLook provides look, content = content)
}
