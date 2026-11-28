package app.novavpn.core

import android.content.res.Configuration
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DevicePlatformTest {

    @Test
    fun `phone with touchscreen running TV APK is detected as phone not TV`() {
        assertFalse(
            DevicePlatform.computeIsTv(
                hasLeanback = false,
                uiModeType = Configuration.UI_MODE_TYPE_NORMAL,
                isTvBuild = true,
                hasTouchscreen = true
            )
        )
    }

    @Test
    fun `device with Leanback feature is detected as TV even on phone build`() {
        assertTrue(
            DevicePlatform.computeIsTv(
                hasLeanback = true,
                uiModeType = Configuration.UI_MODE_TYPE_NORMAL,
                isTvBuild = false,
                hasTouchscreen = false
            )
        )
    }

    @Test
    fun `device in television UI mode is detected as TV`() {
        assertTrue(
            DevicePlatform.computeIsTv(
                hasLeanback = false,
                uiModeType = Configuration.UI_MODE_TYPE_TELEVISION,
                isTvBuild = false,
                hasTouchscreen = true
            )
        )
    }

    @Test
    fun `touchless TV build fallback detects TV when Leanback flag absent`() {
        assertTrue(
            DevicePlatform.computeIsTv(
                hasLeanback = false,
                uiModeType = Configuration.UI_MODE_TYPE_NORMAL,
                isTvBuild = true,
                hasTouchscreen = false
            )
        )
    }
}
