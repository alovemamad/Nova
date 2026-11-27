package app.novavpn.core

import android.app.Activity
import android.app.UiModeManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import com.example.BuildConfig

object DevicePlatform {
    fun isTv(context: Context): Boolean {
        if (BuildConfig.IS_TV) return true
        val pm = runCatching { context.packageManager }.getOrNull()
        if (pm?.hasSystemFeature(PackageManager.FEATURE_LEANBACK) == true) return true
        val uiMode = runCatching { context.getSystemService(UiModeManager::class.java) }.getOrNull()
        return uiMode?.currentModeType == Configuration.UI_MODE_TYPE_TELEVISION
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
