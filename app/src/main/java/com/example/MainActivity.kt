package com.example

import android.app.ActivityManager
import android.content.Intent
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.novavpn.ServiceLocator
import app.novavpn.ui.NovaVpnRoot
import app.novavpn.ui.NovaVpnViewModel

class MainActivity : FragmentActivity() {
    private val vpnViewModel: NovaVpnViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ServiceLocator.init(applicationContext)
        enableEdgeToEdge()
        handleDeepLinkIntent(intent)

        setContent {
            val settings by ServiceLocator.settingsRepository.settingsFlow
                .collectAsStateWithLifecycle(initialValue = app.novavpn.domain.model.AppSettings())

            LaunchedEffect(settings.hideFromRecents) {
                if (settings.hideFromRecents) {
                    window.setFlags(
                        WindowManager.LayoutParams.FLAG_SECURE,
                        WindowManager.LayoutParams.FLAG_SECURE
                    )
                } else {
                    window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
                }
                val am = getSystemService(ActivityManager::class.java)
                am?.appTasks?.forEach { task ->
                    runCatching { task.setExcludeFromRecents(settings.hideFromRecents) }
                }
            }

            NovaVpnRoot(viewModel = vpnViewModel)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleDeepLinkIntent(intent)
    }

    private fun handleDeepLinkIntent(intent: Intent?) {
        val data = intent?.data ?: return
        if (data.scheme.equals("novavpn", ignoreCase = true) && data.host.equals("profile", ignoreCase = true)) {
            vpnViewModel.handleIncomingProfileUri(data.toString())
        }
    }
}
