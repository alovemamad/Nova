package com.example

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.os.Bundle
import android.util.Log
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import app.novavpn.ServiceLocator
import app.novavpn.core.DevicePlatform
import app.novavpn.data.log.LogRepository
import app.novavpn.ui.NovaVpnRoot
import app.novavpn.ui.NovaVpnViewModel

class MainActivity : ComponentActivity() {
    private val vpnViewModel: NovaVpnViewModel by viewModels()
    private var exitMessageShown = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (DevicePlatform.isTv(this)) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        }
        enableEdgeToEdge()

        val initThrowable: Throwable? = try {
            ServiceLocator.init(applicationContext)
            ServiceLocator.initError
        } catch (t: Throwable) {
            Log.e("MainActivity", "Startup ServiceLocator.init failed", t)
            runCatching { LogRepository.e("MainActivity", Log.getStackTraceString(t)) }
            t
        }

        if (initThrowable != null) {
            setContent {
                StartupErrorFallbackScreen(error = initThrowable)
            }
            return
        }

        val viewModelOrError: Pair<NovaVpnViewModel?, Throwable?> = try {
            vpnViewModel to null
        } catch (t: Throwable) {
            Log.e("MainActivity", "NovaVpnViewModel creation failed", t)
            runCatching { LogRepository.e("MainActivity", Log.getStackTraceString(t)) }
            null to t
        }

        val vm = viewModelOrError.first
        val vmErr = viewModelOrError.second
        if (vm == null || vmErr != null) {
            setContent {
                StartupErrorFallbackScreen(error = vmErr ?: IllegalStateException("ViewModel unavailable"))
            }
            return
        }

        handleDeepLinkIntent(intent)

        setContent {
            NovaVpnRoot(viewModel = vm)
        }
    }

    override fun finish() {
        if (!exitMessageShown && !isChangingConfigurations) {
            exitMessageShown = true
            runCatching {
                Toast.makeText(
                    applicationContext,
                    getString(R.string.msg_disconnect_exit_taunt),
                    Toast.LENGTH_LONG
                ).show()
            }
        }
        super.finish()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleDeepLinkIntent(intent)
    }

    private fun handleDeepLinkIntent(intent: Intent?) {
        runCatching {
            val data = intent?.data ?: return
            if (data.scheme.equals("novavpn", ignoreCase = true) && data.host.equals("profile", ignoreCase = true)) {
                vpnViewModel.handleIncomingProfileUri(data.toString())
            }
        }.onFailure { t ->
            Log.w("MainActivity", "Malformed deep link intent ignored", t)
            runCatching { LogRepository.w("MainActivity", "Malformed deep link: ${t.message}") }
        }
    }
}

@Composable
private fun StartupErrorFallbackScreen(error: Throwable) {
    val context = LocalContext.current
    var copied by remember { mutableStateOf(false) }
    val title = "${error::class.java.simpleName}: ${error.message ?: "Unknown startup error"}"
    val stack = remember(error) { Log.getStackTraceString(error) }

    MaterialTheme {
        Surface(modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .safeDrawingPadding()
                    .padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = "Nova VPN failed to initialize",
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.error
                )
                SelectionContainer(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                ) {
                    Text(
                        text = "$title\n\n$stack",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                Spacer(modifier = Modifier.height(4.dp))
                Button(
                    onClick = {
                        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                        cm?.setPrimaryClip(ClipData.newPlainText("Nova VPN Startup Error", "$title\n\n$stack"))
                        copied = true
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(if (copied) "Copied error to clipboard" else "Copy error")
                }
            }
        }
    }
}
