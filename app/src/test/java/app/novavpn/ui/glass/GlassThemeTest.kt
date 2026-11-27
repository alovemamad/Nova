package app.novavpn.ui.glass

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import app.novavpn.domain.model.GlassMaterial
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

    @Test
    fun `textPrimary and textSecondary maintain WCAG AA 4_5 contrast across all wallpapers tints modes and materials`() {
        for (isDark in listOf(false, true)) {
            for (wallpaper in GlassWallpaper.entries) {
                for (tint in GlassTint.entries) {
                    for (material in GlassMaterial.entries) {
                        for (reduced in listOf(false, true)) {
                            val prefs = GlassPrefs(
                                tint = tint,
                                wallpaper = wallpaper,
                                material = material,
                                reduceTransparency = reduced
                            )
                            val look = computeGlassLook(
                                isDark = isDark,
                                glassPrefs = prefs,
                                dynamicColor = false
                            )
                            val stops = wallpaperStops(wallpaper, isDark = isDark)
                            val (glowA, glowB) = wallpaperGlowPair(
                                wallpaper = wallpaper,
                                isDark = isDark,
                                dynamicColor = false,
                                accent = look.accent,
                                secondaryAccent = look.secondaryAccent
                            )
                            val wallSamples = stops + listOf(
                                glowA.compositeOver(stops.first()),
                                glowB.compositeOver(stops.last())
                            )
                            val surfaces = listOf(look.cardSurface, look.pillSurface, look.solidSurface)

                            for (wallBg in wallSamples) {
                                for (surface in surfaces) {
                                    val compositedSurface = surface.compositeOver(wallBg)
                                    val primaryRatio = wcagContrastRatio(look.textPrimary, compositedSurface)
                                    val secondaryRatio = wcagContrastRatio(look.textSecondary, compositedSurface)
                                    assertTrue(
                                        "textPrimary contrast $primaryRatio < 4.5 for isDark=$isDark wallpaper=$wallpaper tint=$tint material=$material reduced=$reduced",
                                        primaryRatio >= 4.5f
                                    )
                                    assertTrue(
                                        "textSecondary contrast $secondaryRatio < 4.5 for isDark=$isDark wallpaper=$wallpaper tint=$tint material=$material reduced=$reduced",
                                        secondaryRatio >= 4.5f
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
