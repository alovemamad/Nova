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
    val solidSurface: Color,
    val cardBorder: Color,
    val pillSurface: Color,
    val moduleSurface: Color,
    val rimHi: Color,
    val rimMid: Color,
    val rimLo: Color,
    val highlight: Color,
    val platter: Color,
    val thumb: Color,
    val dim: Color,
    val sheet: Color,
    val separator: Color,
    val fill: Color,
    val fill2: Color,
    val shadow: Color,
    val danger: Color,
    val warning: Color,
    val success: Color
) {
    fun pingColor(ms: Int?): Color = when {
        ms == null -> textMuted
        ms <= 0 -> danger
        ms < 200 -> success
        ms < 400 -> warning
        else -> danger
    }
}

val LocalGlassLook = compositionLocalOf {
    GlassLook(
        isDark = true,
        prefs = GlassPrefs(),
        accent = Color(0xFF00D2E0),
        secondaryAccent = Color(0xFF6D7CFF),
        textPrimary = Color.White,
        textSecondary = Color(0x9EEBEBF5),
        textMuted = Color(0x4DEBEBF5),
        cardSurface = Color(0x612C2C30),
        solidSurface = Color(0xFF1C1C1E),
        cardBorder = Color(0x42FFFFFF),
        pillSurface = Color(0x661E1E20),
        moduleSurface = Color(0x612C2C30),
        rimHi = Color(0x94FFFFFF),
        rimMid = Color(0x12FFFFFF),
        rimLo = Color(0x42FFFFFF),
        highlight = Color(0x38FFFFFF),
        platter = Color(0x1CFFFFFF),
        thumb = Color(0x38FFFFFF),
        dim = Color(0x78000000),
        sheet = Color(0xF21C1C1E),
        separator = Color(0x1FFFFFFF),
        fill = Color(0x5C787880),
        fill2 = Color(0x3D767680),
        shadow = Color(0x80000000),
        danger = Color(0xFFFF453A),
        warning = Color(0xFFFF9F0A),
        success = Color(0xFF30D158)
    )
}

fun resolveGlassTintColor(tint: GlassTint, isDark: Boolean = true): Pair<Color, Color> = when (tint) {
    GlassTint.TEAL -> if (isDark) Color(0xFF00D2E0) to Color(0xFF6D7CFF) else Color(0xFF00A7B5) to Color(0xFF6155F5)
    GlassTint.VIOLET -> if (isDark) Color(0xFF9E7BFF) to Color(0xFF00D2E0) else Color(0xFF7C4DFF) to Color(0xFF00A7B5)
    GlassTint.BLUE -> if (isDark) Color(0xFF0091FF) to Color(0xFF3CD3FE) else Color(0xFF007AFF) to Color(0xFF00C0E8)
    GlassTint.CYAN -> if (isDark) Color(0xFF3CD3FE) to Color(0xFF6D7CFF) else Color(0xFF0099CC) to Color(0xFF6155F5)
    GlassTint.MINT -> if (isDark) Color(0xFF00DAC3) to Color(0xFF3CD3FE) else Color(0xFF00A896) to Color(0xFF0088FF)
    GlassTint.INDIGO -> if (isDark) Color(0xFF6D7CFF) to Color(0xFFDB34F2) else Color(0xFF5856D6) to Color(0xFFAF52DE)
    GlassTint.PINK -> if (isDark) Color(0xFFFF375F) to Color(0xFF9E7BFF) else Color(0xFFFF2D55) to Color(0xFF7C4DFF)
    GlassTint.ORANGE -> if (isDark) Color(0xFFFF9F0A) to Color(0xFFFF375F) else Color(0xFFFF8D28) to Color(0xFFFF2D55)
    GlassTint.GREEN -> if (isDark) Color(0xFF30D158) to Color(0xFF00D2E0) else Color(0xFF28A745) to Color(0xFF00A7B5)
    GlassTint.GRAPHITE -> if (isDark) Color(0xFF98989D) to Color(0xFF636366) else Color(0xFF636366) to Color(0xFF8E8E93)
}

fun wallpaperStops(wallpaper: GlassWallpaper, isDark: Boolean): List<Color> = if (isDark) {
    when (wallpaper) {
        GlassWallpaper.LAGOON -> listOf(Color(0xFF031327), Color(0xFF051C36), Color(0xFF010812))
        GlassWallpaper.MIST -> listOf(Color(0xFF141820), Color(0xFF0B0D13), Color(0xFF040508))
        GlassWallpaper.DUSK -> listOf(Color(0xFF1A0712), Color(0xFF1A0D2E), Color(0xFF0A0410))
        GlassWallpaper.GRAPHITE -> listOf(Color(0xFF121317), Color(0xFF0B0C10), Color(0xFF050506))
        GlassWallpaper.PLAIN -> listOf(Color(0xFF000000), Color(0xFF050608))
    }
} else {
    when (wallpaper) {
        GlassWallpaper.LAGOON -> listOf(Color(0xFFE2EFFF), Color(0xFFE8F4FA), Color(0xFFEEF5FA))
        GlassWallpaper.MIST -> listOf(Color(0xFFE4E8EF), Color(0xFFECEEF3), Color(0xFFF2F2F7))
        GlassWallpaper.DUSK -> listOf(Color(0xFFFFF0E6), Color(0xFFF9EAF5), Color(0xFFF6EDFB))
        GlassWallpaper.GRAPHITE -> listOf(Color(0xFFE1E5EC), Color(0xFFE7EAEF), Color(0xFFECEEF2))
        GlassWallpaper.PLAIN -> listOf(Color(0xFFF2F2F7), Color(0xFFF2F2F7))
    }
}

@Composable
fun GlassThemeProvider(
    isDark: Boolean,
    glassPrefs: GlassPrefs,
    content: @Composable () -> Unit
) {
    val (accent, secondary) = resolveGlassTintColor(glassPrefs.tint, isDark)
    val tinted = glassPrefs.material == GlassMaterial.TINTED
    val reduced = glassPrefs.reduceTransparency

    val solidCell = if (isDark) Color(0xFF1C1C1E) else Color.White
    val moduleSurface = when {
        reduced -> solidCell
        isDark && tinted -> Color(0xDB28282C)
        isDark -> Color(0x66282A32)
        tinted -> Color(0xD9FFFFFF)
        else -> Color(0x88FFFFFF)
    }

    val chromeSolid = if (isDark) Color(0xFF28282C) else Color(0xFFF9F9FB)
    val pillSurface = when {
        reduced -> chromeSolid
        isDark && tinted -> Color(0xE0222226)
        isDark -> Color(0x801C1D24)
        tinted -> Color(0xD8FFFFFF)
        else -> Color(0x8CFFFFFF)
    }

    val cardSurface = if (glassPrefs.glassLists && !reduced) moduleSurface else solidCell

    val look = GlassLook(
        isDark = isDark,
        prefs = glassPrefs,
        accent = accent,
        secondaryAccent = secondary,
        textPrimary = if (isDark) Color.White else Color(0xFF0A0A0C),
        textSecondary = if (isDark) Color(0xB8EBEBF5) else Color(0x9E3C3C43),
        textMuted = if (isDark) Color(0x6BEBEBF5) else Color(0x5E3C3C43),
        cardSurface = cardSurface,
        solidSurface = solidCell,
        cardBorder = if (isDark) Color(0x42FFFFFF) else Color(0x66FFFFFF),
        pillSurface = pillSurface,
        moduleSurface = moduleSurface,
        rimHi = if (isDark) Color(0x94FFFFFF) else Color.White,
        rimMid = if (isDark) Color(0x16FFFFFF) else Color(0x47FFFFFF),
        rimLo = if (isDark) Color(0x42FFFFFF) else Color(0xCCFFFFFF),
        highlight = if (isDark) Color(0x38FFFFFF) else Color(0xF2FFFFFF),
        platter = if (isDark) Color(0x22FFFFFF) else Color(0x12000000),
        thumb = if (isDark) Color(0x38FFFFFF) else Color(0xF5FFFFFF),
        dim = if (isDark) Color(0x78000000) else Color(0x36000000),
        sheet = if (isDark) Color(0xF21B1C20) else Color(0xF5F7F8FA),
        separator = if (isDark) Color(0x1FFFFFFF) else Color(0x243C3C43),
        fill = if (isDark) Color(0x5C787880) else Color(0x33787880),
        fill2 = if (isDark) Color(0x3D767680) else Color(0x1A767680),
        shadow = if (isDark) Color(0x80000000) else Color(0x1F000000),
        danger = if (isDark) Color(0xFFFF453A) else Color(0xFFFF383C),
        warning = if (isDark) Color(0xFFFF9F0A) else Color(0xFFFF8D28),
        success = if (isDark) Color(0xFF30D158) else Color(0xFF34C759)
    )

    CompositionLocalProvider(LocalGlassLook provides look, content = content)
}
