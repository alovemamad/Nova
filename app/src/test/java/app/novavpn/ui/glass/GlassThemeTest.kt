package app.novavpn.ui.glass

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import app.novavpn.domain.model.GlassPrefs
import app.novavpn.domain.model.GlassTint
import app.novavpn.domain.model.GlassWallpaper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GlassThemeTest {

    @Test
    fun `dynamic color overrides static GlassTint with ColorScheme palette`() {
        val dynamicPrimary = Color(0xFFE91E63)
        val dynamicTertiary = Color(0xFF4CAF50)
        val dynamicBg = Color(0xFF1A0F14)
        val scheme = darkColorScheme(
            primary = dynamicPrimary,
            tertiary = dynamicTertiary,
            primaryContainer = Color(0xFF5C0025),
            tertiaryContainer = Color(0xFF1B5E20),
            background = dynamicBg,
            surface = Color(0xFF201318),
            onSurface = Color(0xFFFCE4EC)
        )

        val look = computeGlassLook(
            isDark = true,
            glassPrefs = GlassPrefs(tint = GlassTint.TEAL, wallpaper = GlassWallpaper.LAGOON),
            dynamicColor = true,
            colorScheme = scheme
        )

        assertTrue(look.dynamicColor)
        assertEquals(dynamicPrimary, look.accent)
        assertEquals(dynamicTertiary, look.secondaryAccent)
        assertEquals(Color(0xFFFCE4EC), look.textPrimary)

        val staticStops = wallpaperStops(GlassWallpaper.LAGOON, isDark = true, dynamicColor = false)
        val dynamicStops = wallpaperStops(GlassWallpaper.LAGOON, isDark = true, dynamicColor = true, colorScheme = scheme)
        assertNotEquals(staticStops, dynamicStops)
    }

    @Test
    fun `static tint is used when dynamicColor is disabled`() {
        val scheme = lightColorScheme(
            primary = Color(0xFFE91E63),
            tertiary = Color(0xFF4CAF50)
        )

        val look = computeGlassLook(
            isDark = false,
            glassPrefs = GlassPrefs(tint = GlassTint.ORANGE),
            dynamicColor = false,
            colorScheme = scheme
        )

        assertFalse(look.dynamicColor)
        val (expectedAccent, expectedSecondary) = resolveGlassTintColor(GlassTint.ORANGE, isDark = false)
        assertEquals(expectedAccent, look.accent)
        assertEquals(expectedSecondary, look.secondaryAccent)
    }
}
