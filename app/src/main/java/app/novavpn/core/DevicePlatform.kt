package app.novavpn.core

import android.app.Activity
import android.app.UiModeManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import androidx.annotation.VisibleForTesting
import com.example.BuildConfig

object DevicePlatform {
    @Volatile
    private var cachedIsTv: Boolean? = null

    fun isTv(context: Context): Boolean {
        cachedIsTv?.let { return it }
        val appCtx = context.applicationContext ?: context
        val pm = runCatching { appCtx.packageManager }.getOrNull()
        val hasLeanback = pm?.hasSystemFeature(PackageManager.FEATURE_LEANBACK) == true
        val uiMode = runCatching { appCtx.getSystemService(UiModeManager::class.java) }.getOrNull()
        val uiModeType = uiMode?.currentModeType ?: Configuration.UI_MODE_TYPE_NORMAL
        val hasTouchscreen = pm?.hasSystemFeature(PackageManager.FEATURE_TOUCHSCREEN) != false
        val detected = computeIsTv(
            hasLeanback = hasLeanback,
            uiModeType = uiModeType,
            isTvBuild = BuildConfig.IS_TV,
            hasTouchscreen = hasTouchscreen
        )
        cachedIsTv = detected
        return detected
    }

    @VisibleForTesting
    internal fun computeIsTv(
        hasLeanback: Boolean,
        uiModeType: Int,
        isTvBuild: Boolean,
        hasTouchscreen: Boolean
    ): Boolean {
        if (hasLeanback) return true
        if (uiModeType == Configuration.UI_MODE_TYPE_TELEVISION) return true
        return isTvBuild && !hasTouchscreen
    }

    fun safeStartActivity(context: Context, intent: Intent): Boolean {
        return runCatching {
            val launchIntent = Intent(intent).apply {
                if (context !is Activity) {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            }
            val resolved = launchIntent.resolveActivity(context.packageManager) != null
            if (!resolved) return@runCatching false
            context.startActivity(launchIntent)
            true
        }.getOrDefault(false)
    }
}
