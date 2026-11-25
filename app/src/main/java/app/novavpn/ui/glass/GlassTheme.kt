package app.novavpn.ui.glass

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import app.novavpn.domain.model.GlassMaterial
import app.novavpn.domain.model.GlassPrefs
import app.novavpn.domain.model.GlassTint
import app.novavpn.domain.model.GlassWallpaper

@Immutable
data class GlassLook(
    val isDark: Boolean,
    val prefs: GlassPrefs,
    val dynamicColor: Boolean = false,
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
    val chrome: Color,
    val chromeSolid: Color,
    val page: Color,
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
    val success: Color,
    val latin: Boolean = true
) {
    val reduced: Boolean get() = prefs.reduceTransparency
    val translucent: Boolean get() = prefs.glassLists && !prefs.reduceTransparency

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
        dynamicColor = false,
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
        chrome = Color(0x801C1D24),
        chromeSolid = Color(0xFF28282C),
        page = Color(0xFF000000),
        rimHi = Color(0x70FFFFFF),
        rimMid = Color(0x16FFFFFF),
        rimLo = Color(0x36FFFFFF),
        highlight = Color(0x30FFFFFF),
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
        success = Color(0xFF30D158),
        latin = true
    )
}

fun resolveGlassTintColor(tint: GlassTint, isDark: Boolean = true): Pair<Color, Color> = when (tint) {
    GlassTint.TEAL -> if (isDark) Color(0xFF00D2E0) to Color(0xFF6D7CFF) else Color(0xFF00A7B5) to Color(0xFF6155F5)
    GlassTint.VIOLET -> if (isDark) Color(0xFF9E7BFF) to Color(0xFF00D2E0) else Color(0xFF7C4DFF) to Color(0xFF00A7B5)
    GlassTint.BLUE -> if (isDark) Color(0xFF0091FF) to Color(0xFF3CD3FE) else Color(0xFF007AFF) to Color(0xFF00C0E8)
    GlassTint.CYAN -> if (isDark) Color(0xFF3CD3FE) to Color(0xFF6D7CFF) else Color(0xFF0099CC) to Color(0xFF6155F5)
    GlassTint.MINT -> if (isDark) Color(0xFF00DAC3) to Color(0xFF3CD3FE) else Color(0xFF00A896) to Color(0xFF0088FF)
    GlassTint.INDIGO -> if (isDark) Color(0xFF6D7CFF) to Color(0xFFDB34F2) else Color(0xFF5856D6) to Color(0xFFAF52DE)
    GlassTint.PURPLE -> if (isDark) Color(0xFFDB34F2) to Color(0xFF9E7BFF) else Color(0xFFCB30E0) to Color(0xFF7C4DFF)
    GlassTint.PINK -> if (isDark) Color(0xFFFF375F) to Color(0xFF9E7BFF) else Color(0xFFFF2D55) to Color(0xFF7C4DFF)
    GlassTint.RED -> if (isDark) Color(0xFFFF453A) to Color(0xFFFF9F0A) else Color(0xFFFF383C) to Color(0xFFFF8D28)
    GlassTint.ORANGE -> if (isDark) Color(0xFFFF9F0A) to Color(0xFFFF375F) else Color(0xFFFF8D28) to Color(0xFFFF2D55)
    GlassTint.GREEN -> if (isDark) Color(0xFF30D158) to Color(0xFF00D2E0) else Color(0xFF28A745) to Color(0xFF00A7B5)
    GlassTint.BROWN -> if (isDark) Color(0xFFB78A66) to Color(0xFFFF9F0A) else Color(0xFFAC7F5E) to Color(0xFFFF8D28)
    GlassTint.GRAPHITE -> if (isDark) Color(0xFF98989D) to Color(0xFF636366) else Color(0xFF636366) to Color(0xFF8E8E93)
}

fun wallpaperStops(
    wallpaper: GlassWallpaper,
    isDark: Boolean,
    dynamicColor: Boolean = false,
    colorScheme: ColorScheme? = null
): List<Color> {
    if (dynamicColor && colorScheme != null) {
        if (wallpaper == GlassWallpaper.PLAIN) {
            return if (isDark) {
                listOf(lerp(colorScheme.background, Color.Black, 0.35f), colorScheme.background)
            } else {
                listOf(colorScheme.background, colorScheme.surface)
            }
        }
        return if (isDark) {
            listOf(
                lerp(colorScheme.background, colorScheme.primaryContainer, 0.28f),
                lerp(colorScheme.surface, colorScheme.tertiaryContainer, 0.18f),
                lerp(colorScheme.background, Color.Black, 0.42f)
            )
        } else {
            listOf(
                lerp(colorScheme.background, colorScheme.primaryContainer, 0.36f),
                lerp(colorScheme.surface, colorScheme.tertiaryContainer, 0.24f),
                lerp(colorScheme.background, colorScheme.secondaryContainer, 0.20f)
            )
        }
    }

    return if (isDark) {
        when (wallpaper) {
            GlassWallpaper.LAGOON -> listOf(Color(0xFF031327), Color(0xFF051C36), Color(0xFF010812))
            GlassWallpaper.MIST -> listOf(Color(0xFF141820), Color(0xFF0B0D13), Color(0xFF040508))
            GlassWallpaper.DUSK -> listOf(Color(0xFF1A0712), Color(0xFF1A0D2E), Color(0xFF0A0410))
            GlassWallpaper.GRAPHITE -> listOf(Color(0xFF121317), Color(0xFF0B0C10), Color(0xFF050506))
            GlassWallpaper.PLAIN -> listOf(Color(0xFF000000), Color(0xFF050608))
        }
    } else {
        when (wallpaper) {
            GlassWallpaper.LAGOON -> listOf(Color(0xFFD5E9FF), Color(0xFFE1F0FC), Color(0xFFE8F3FA))
            GlassWallpaper.MIST -> listOf(Color(0xFFDCE3EE), Color(0xFFE5EAF2), Color(0xFFECEFF5))
            GlassWallpaper.DUSK -> listOf(Color(0xFFFBE6D8), Color(0xFFF5E0F0), Color(0xFFEFE4F8))
            GlassWallpaper.GRAPHITE -> listOf(Color(0xFFD8DEE8), Color(0xFFE0E5ED), Color(0xFFE7EBF1))
            GlassWallpaper.PLAIN -> listOf(Color(0xFFECEEF4), Color(0xFFF2F3F8))
        }
    }
}

fun computeGlassLook(
    isDark: Boolean,
    glassPrefs: GlassPrefs,
    dynamicColor: Boolean = false,
    colorScheme: ColorScheme? = null,
    latin: Boolean = true
): GlassLook {
    val dynamicScheme = if (dynamicColor) colorScheme else null
    val useDynamic = dynamicScheme != null
    val (accent, secondary) = if (dynamicScheme != null) {
        val primary = dynamicScheme.primary
        val sec = when {
            dynamicScheme.tertiary != primary -> dynamicScheme.tertiary
            dynamicScheme.secondary != primary -> dynamicScheme.secondary
            else -> lerp(primary, Color.White, 0.25f)
        }
        primary to sec
    } else {
        resolveGlassTintColor(glassPrefs.tint, isDark)
    }

    val tinted = glassPrefs.material == GlassMaterial.TINTED
    val reduced = glassPrefs.reduceTransparency

    val solidCell = when {
        dynamicScheme != null && isDark -> dynamicScheme.surfaceContainerHigh
        dynamicScheme != null && !isDark -> dynamicScheme.surfaceContainerLowest
        isDark -> Color(0xFF1C1C1E)
        else -> Color(0xFFF9FAFC)
    }

    val moduleSurface = when {
        reduced -> solidCell
        dynamicScheme != null && isDark && tinted ->
            lerp(dynamicScheme.surfaceContainerHigh, dynamicScheme.primaryContainer, 0.14f).copy(alpha = 0.86f)
        dynamicScheme != null && isDark ->
            lerp(dynamicScheme.surfaceContainer, dynamicScheme.primaryContainer, 0.10f).copy(alpha = 0.64f)
        dynamicScheme != null && !isDark && tinted ->
            lerp(dynamicScheme.surfaceContainerLow, dynamicScheme.primaryContainer, 0.14f).copy(alpha = 0.84f)
        dynamicScheme != null ->
            lerp(dynamicScheme.surfaceContainerLowest, dynamicScheme.primaryContainer, 0.10f).copy(alpha = 0.68f)
        isDark && tinted -> Color(0xD4262830)
        isDark -> Color(0x66282A32)
        tinted -> Color(0xBFEEF3FA)
        else -> Color(0x78F4F7FC)
    }

    val chromeSolid = when {
        dynamicScheme != null && isDark -> dynamicScheme.surfaceContainerHighest
        dynamicScheme != null && !isDark -> dynamicScheme.surfaceContainerLow
        isDark -> Color(0xFF28282C)
        else -> Color(0xFFF3F5FA)
    }

    val pillSurface = when {
        reduced -> chromeSolid
        dynamicScheme != null && isDark && tinted ->
            lerp(dynamicScheme.surfaceContainerHighest, dynamicScheme.primaryContainer, 0.16f).copy(alpha = 0.90f)
        dynamicScheme != null && isDark ->
            lerp(dynamicScheme.surfaceContainerHigh, dynamicScheme.primaryContainer, 0.12f).copy(alpha = 0.78f)
        dynamicScheme != null && !isDark && tinted ->
            lerp(dynamicScheme.surfaceContainer, dynamicScheme.primaryContainer, 0.16f).copy(alpha = 0.88f)
        dynamicScheme != null ->
            lerp(dynamicScheme.surfaceContainerLow, dynamicScheme.primaryContainer, 0.12f).copy(alpha = 0.74f)
        isDark && tinted -> Color(0xDC22232A)
        isDark -> Color(0x821C1D24)
        tinted -> Color(0xCCEAF0F8)
        else -> Color(0x8AF4F7FC)
    }

    val cardSurface = if (glassPrefs.glassLists && !reduced) moduleSurface else solidCell
    val pageBg = wallpaperStops(glassPrefs.wallpaper, isDark, useDynamic, dynamicScheme).first()

    val sheetColor = when {
        dynamicScheme != null && isDark ->
            lerp(dynamicScheme.surfaceContainer, dynamicScheme.background, 0.25f).copy(alpha = 0.96f)
        dynamicScheme != null ->
            dynamicScheme.surfaceContainerLowest.copy(alpha = 0.96f)
        isDark -> Color(0xF21B1C20)
        else -> Color(0xF5F4F6FA)
    }

    val textPrimary = when {
        dynamicScheme != null -> dynamicScheme.onSurface
        isDark -> Color.White
        else -> Color(0xFF0A0A0C)
    }
    val textSecondary = when {
        dynamicScheme != null -> dynamicScheme.onSurfaceVariant.copy(alpha = 0.88f)
        isDark -> Color(0xB8EBEBF5)
        else -> Color(0xA63C3C43)
    }
    val textMuted = when {
        dynamicScheme != null -> dynamicScheme.onSurfaceVariant.copy(alpha = 0.54f)
        isDark -> Color(0x6BEBEBF5)
        else -> Color(0x6B3C3C43)
    }
    val separatorColor = when {
        dynamicScheme != null -> dynamicScheme.outlineVariant.copy(alpha = if (isDark) 0.45f else 0.55f)
        isDark -> Color(0x24FFFFFF)
        else -> Color(0x223C3C43)
    }

    return GlassLook(
        isDark = isDark,
        prefs = glassPrefs,
        dynamicColor = useDynamic,
        accent = accent,
        secondaryAccent = secondary,
        textPrimary = textPrimary,
        textSecondary = textSecondary,
        textMuted = textMuted,
        cardSurface = cardSurface,
        solidSurface = solidCell,
        cardBorder = if (isDark) Color(0x3DFFFFFF) else Color(0x80FFFFFF),
        pillSurface = pillSurface,
        moduleSurface = moduleSurface,
        chrome = pillSurface,
        chromeSolid = chromeSolid,
        page = pageBg,
        rimHi = if (isDark) Color(0x70FFFFFF) else Color(0xCCFFFFFF),
        rimMid = if (isDark) Color(0x16FFFFFF) else Color(0x47FFFFFF),
        rimLo = if (isDark) Color(0x36FFFFFF) else Color(0x99FFFFFF),
        highlight = if (isDark) Color(0x30FFFFFF) else Color(0x88FFFFFF),
        platter = if (isDark) Color(0x22FFFFFF) else Color(0x12000000),
        thumb = if (isDark) Color(0x38FFFFFF) else Color(0xE8FFFFFF),
        dim = if (isDark) Color(0x78000000) else Color(0x36000000),
        sheet = sheetColor,
        separator = separatorColor,
        fill = if (isDark) Color(0x5C787880) else Color(0x33787880),
        fill2 = if (isDark) Color(0x3D767680) else Color(0x1A767680),
        shadow = if (isDark) Color(0x70000000) else Color(0x1A000000),
        danger = if (isDark) Color(0xFFFF453A) else Color(0xFFFF383C),
        warning = if (isDark) Color(0xFFFF9F0A) else Color(0xFFFF8D28),
        success = if (isDark) Color(0xFF30D158) else Color(0xFF34C759),
        latin = latin
    )
}

@Composable
fun GlassThemeProvider(
    isDark: Boolean,
    glassPrefs: GlassPrefs,
    dynamicColor: Boolean = false,
    colorScheme: ColorScheme = MaterialTheme.colorScheme,
    latin: Boolean = true,
    content: @Composable () -> Unit
) {
    val look = remember(isDark, glassPrefs, dynamicColor, colorScheme, latin) {
        computeGlassLook(
            isDark = isDark,
            glassPrefs = glassPrefs,
            dynamicColor = dynamicColor,
            colorScheme = colorScheme,
            latin = latin
        )
    }

    CompositionLocalProvider(LocalGlassLook provides look, content = content)
}
