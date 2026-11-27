package app.novavpn.ui.glass

import org.junit.Assert.assertEquals
import org.junit.Test

class GlassTabBarMathTest {

    private val tabCount = 4
    private val padPx = 12f
    private val tabWidthPx = 90f
    private val pillWidthPx = padPx * 2f + tabWidthPx * tabCount // 384f

    @Test
    fun `tabIndexForPointerX maps LTR coordinates and clamps edges across 4 tabs`() {
        assertEquals(0, tabIndexForPointerX(xPx = -20f, padPx = padPx, tabWidthPx = tabWidthPx, tabCount = tabCount, isRtl = false))
        assertEquals(0, tabIndexForPointerX(xPx = 12f, padPx = padPx, tabWidthPx = tabWidthPx, tabCount = tabCount, isRtl = false))
        assertEquals(0, tabIndexForPointerX(xPx = 101.9f, padPx = padPx, tabWidthPx = tabWidthPx, tabCount = tabCount, isRtl = false))
        assertEquals(1, tabIndexForPointerX(xPx = 102f, padPx = padPx, tabWidthPx = tabWidthPx, tabCount = tabCount, isRtl = false))
        assertEquals(2, tabIndexForPointerX(xPx = 195f, padPx = padPx, tabWidthPx = tabWidthPx, tabCount = tabCount, isRtl = false))
        assertEquals(3, tabIndexForPointerX(xPx = 285f, padPx = padPx, tabWidthPx = tabWidthPx, tabCount = tabCount, isRtl = false))
        assertEquals(3, tabIndexForPointerX(xPx = 500f, padPx = padPx, tabWidthPx = tabWidthPx, tabCount = tabCount, isRtl = false))
    }

    @Test
    fun `tabIndexForPointerX mirrors slots in RTL across 4 tabs`() {
        assertEquals(3, tabIndexForPointerX(xPx = 0f, padPx = padPx, tabWidthPx = tabWidthPx, tabCount = tabCount, isRtl = true))
        assertEquals(3, tabIndexForPointerX(xPx = 57f, padPx = padPx, tabWidthPx = tabWidthPx, tabCount = tabCount, isRtl = true))
        assertEquals(2, tabIndexForPointerX(xPx = 147f, padPx = padPx, tabWidthPx = tabWidthPx, tabCount = tabCount, isRtl = true))
        assertEquals(1, tabIndexForPointerX(xPx = 237f, padPx = padPx, tabWidthPx = tabWidthPx, tabCount = tabCount, isRtl = true))
        assertEquals(0, tabIndexForPointerX(xPx = 327f, padPx = padPx, tabWidthPx = tabWidthPx, tabCount = tabCount, isRtl = true))
        assertEquals(0, tabIndexForPointerX(xPx = 450f, padPx = padPx, tabWidthPx = tabWidthPx, tabCount = tabCount, isRtl = true))
    }

    @Test
    fun `snapTabCenterXForIndex roundtrips cleanly with tabIndexForPointerX in LTR and RTL`() {
        for (isRtl in listOf(false, true)) {
            for (idx in 0 until tabCount) {
                val centerX = snapTabCenterXForIndex(
                    tabIndex = idx,
                    padPx = padPx,
                    tabWidthPx = tabWidthPx,
                    tabCount = tabCount,
                    isRtl = isRtl
                )
                val resolved = tabIndexForPointerX(
                    xPx = centerX,
                    padPx = padPx,
                    tabWidthPx = tabWidthPx,
                    tabCount = tabCount,
                    isRtl = isRtl
                )
                assertEquals("Failed roundtrip for idx=$idx isRtl=$isRtl", idx, resolved)
            }
        }
    }

    @Test
    fun `visualSlotForTabIndex flips order for RTL`() {
        assertEquals(listOf(0, 1, 2, 3), (0 until tabCount).map { visualSlotForTabIndex(it, tabCount, isRtl = false) })
        assertEquals(listOf(3, 2, 1, 0), (0 until tabCount).map { visualSlotForTabIndex(it, tabCount, isRtl = true) })
    }

    @Test
    fun `clampedBubbleLeftPx respects overshoot bounds`() {
        val bubbleWidth = 100f
        val overshoot = 8f
        assertEquals(-8f, clampedBubbleLeftPx(pointerXPx = -50f, bubbleWidthPx = bubbleWidth, pillWidthPx = pillWidthPx, overshootPx = overshoot), 0.001f)
        assertEquals(142f, clampedBubbleLeftPx(pointerXPx = 192f, bubbleWidthPx = bubbleWidth, pillWidthPx = pillWidthPx, overshootPx = overshoot), 0.001f)
        assertEquals(292f, clampedBubbleLeftPx(pointerXPx = 999f, bubbleWidthPx = bubbleWidth, pillWidthPx = pillWidthPx, overshootPx = overshoot), 0.001f)
    }
}
