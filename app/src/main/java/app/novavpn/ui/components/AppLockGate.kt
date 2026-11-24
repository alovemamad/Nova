package app.novavpn.ui.components

import android.graphics.Bitmap
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Backspace
import androidx.compose.material.icons.rounded.Fingerprint
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import app.novavpn.data.store.PinVerifyResult
import app.novavpn.ui.glass.GlassCard
import app.novavpn.ui.glass.LocalGlassLook
import app.novavpn.ui.theme.LocalHostActivity
import app.novavpn.ui.theme.fmtDigits
import com.example.R
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun AppLockGate(
    hasPin: Boolean,
    lockoutUntilMs: Long,
    onBiometricUnlocked: () -> Unit,
    onVerifyPin: suspend (String) -> PinVerifyResult
) {
    val context = LocalContext.current
    val hostActivity = LocalHostActivity.current
    val look = LocalGlassLook.current
    val scope = rememberCoroutineScope()
    var entered by remember { mutableStateOf("") }
    var errorMsg by remember { mutableStateOf<String?>(null) }
    var remainingLockSec by remember(lockoutUntilMs) {
        mutableLongStateOf(((lockoutUntilMs - System.currentTimeMillis()).coerceAtLeast(0L) + 999L) / 1000L)
    }

    LaunchedEffect(lockoutUntilMs) {
        while (true) {
            val remMs = lockoutUntilMs - System.currentTimeMillis()
            if (remMs <= 0L) {
                remainingLockSec = 0L
                break
            }
            remainingLockSec = (remMs + 999L) / 1000L
            delay(1000L)
        }
    }

    val canUseBiometric = remember(context) {
        runCatching {
            val mgr = BiometricManager.from(context)
            mgr.canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG or BiometricManager.Authenticators.DEVICE_CREDENTIAL) ==
                BiometricManager.BIOMETRIC_SUCCESS
        }.getOrDefault(false)
    }

    fun launchBiometric() {
        val activity = hostActivity ?: return
        runCatching {
            val executor = ContextCompat.getMainExecutor(activity)
            val prompt = BiometricPrompt(
                activity,
                executor,
                object : BiometricPrompt.AuthenticationCallback() {
                    override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                        onBiometricUnlocked()
                    }
                }
            )
            val info = BiometricPrompt.PromptInfo.Builder()
                .setTitle(context.getString(R.string.lock_title))
                .setSubtitle(context.getString(R.string.lock_subtitle))
                .setAllowedAuthenticators(
                    BiometricManager.Authenticators.BIOMETRIC_STRONG or BiometricManager.Authenticators.DEVICE_CREDENTIAL
                )
                .build()
            prompt.authenticate(info)
        }
    }

    LaunchedEffect(canUseBiometric, hostActivity) {
        if (canUseBiometric && hostActivity != null) {
            launchBiometric()
        }
    }

    val lockedOutText = if (remainingLockSec > 0L) {
        stringResource(R.string.lock_locked_out, remainingLockSec)
    } else null

    val wrongPinTemplate = context.getString(R.string.lock_incorrect_pin)
    val lockedOutTemplate = context.getString(R.string.lock_locked_out)

    fun submitPin() {
        if (remainingLockSec > 0L || entered.length < 4) return
        val attempt = entered
        scope.launch {
            when (val res = onVerifyPin(attempt)) {
                is PinVerifyResult.Success -> {
                    entered = ""
                    errorMsg = null
                }
                is PinVerifyResult.WrongPin -> {
                    entered = ""
                    errorMsg = String.format(wrongPinTemplate, res.remainingBeforeLockout)
                }
                is PinVerifyResult.LockedOut -> {
                    entered = ""
                    remainingLockSec = res.remainingSeconds
                    errorMsg = String.format(lockedOutTemplate, res.remainingSeconds)
                }
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        contentAlignment = Alignment.Center
    ) {
        GlassCard(
            modifier = Modifier
                .widthIn(max = 420.dp)
                .fillMaxWidth(),
            elevated = true
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Box(
                    modifier = Modifier
                        .size(64.dp)
                        .clip(CircleShape)
                        .background(look.accent.copy(alpha = 0.16f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Lock,
                        contentDescription = null,
                        tint = look.accent,
                        modifier = Modifier.size(32.dp)
                    )
                }
                Spacer(modifier = Modifier.height(14.dp))
                Text(
                    text = stringResource(R.string.lock_title),
                    style = MaterialTheme.typography.headlineSmall,
                    color = look.textPrimary
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = stringResource(R.string.lock_subtitle),
                    style = MaterialTheme.typography.bodySmall,
                    color = look.textSecondary
                )

                if (hasPin) {
                    Spacer(modifier = Modifier.height(18.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        repeat(8) { idx ->
                            val filled = idx < entered.length
                            Box(
                                modifier = Modifier
                                    .size(12.dp)
                                    .clip(CircleShape)
                                    .background(if (filled) look.accent else look.cardBorder.copy(alpha = 0.35f))
                            )
                        }
                    }

                    val activeError = lockedOutText ?: errorMsg
                    if (activeError != null) {
                        Spacer(modifier = Modifier.height(10.dp))
                        Text(activeError, color = look.danger, style = MaterialTheme.typography.bodySmall)
                    }

                    Spacer(modifier = Modifier.height(18.dp))
                    val rows = listOf(
                        listOf("1", "2", "3"),
                        listOf("4", "5", "6"),
                        listOf("7", "8", "9"),
                        listOf("BIO", "0", "DEL")
                    )
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        rows.forEach { row ->
                            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                                row.forEach { key ->
                                    Box(
                                        modifier = Modifier
                                            .size(64.dp)
                                            .clip(CircleShape)
                                            .background(look.cardSurface)
                                            .border(1.dp, look.cardBorder, CircleShape)
                                            .clickable(enabled = remainingLockSec == 0L || key == "BIO") {
                                                errorMsg = null
                                                when (key) {
                                                    "DEL" -> if (entered.isNotEmpty()) entered = entered.dropLast(1)
                                                    "BIO" -> if (canUseBiometric) launchBiometric()
                                                    else -> if (entered.length < 8) entered += key
                                                }
                                            },
                                        contentAlignment = Alignment.Center
                                    ) {
                                        when (key) {
                                            "DEL" -> Icon(Icons.AutoMirrored.Rounded.Backspace, contentDescription = stringResource(R.string.common_clear), tint = look.textPrimary)
                                            "BIO" -> Icon(Icons.Rounded.Fingerprint, contentDescription = stringResource(R.string.lock_authenticate), tint = if (canUseBiometric) look.accent else look.textMuted)
                                            else -> Text(fmtDigits(key), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = look.textPrimary)
                                        }
                                    }
                                }
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(12.dp))
                    if (entered.length >= 4) {
                        Button(
                            onClick = ::submitPin,
                            enabled = remainingLockSec == 0L,
                            modifier = Modifier.testTag("pin_unlock_button")
                        ) {
                            Text(stringResource(R.string.lock_unlock))
                        }
                    }
                } else if (canUseBiometric) {
                    Spacer(modifier = Modifier.height(20.dp))
                    Button(onClick = ::launchBiometric, modifier = Modifier.testTag("biometric_unlock_button")) {
                        Icon(Icons.Rounded.Fingerprint, contentDescription = null)
                        Spacer(modifier = Modifier.size(8.dp))
                        Text(stringResource(R.string.lock_authenticate))
                    }
                }
            }
        }
    }
}

@Composable
fun ProfileQrDialog(
    profileName: String,
    shareUri: String,
    qrBitmap: Bitmap?,
    onCopyUri: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(profileName) },
        text = {
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                if (qrBitmap != null) {
                    Image(
                        bitmap = qrBitmap.asImageBitmap(),
                        contentDescription = profileName,
                        modifier = Modifier
                            .size(220.dp)
                            .clip(RoundedCornerShape(16.dp))
                            .background(Color.White)
                            .padding(8.dp)
                    )
                }
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = shareUri.take(68) + if (shareUri.length > 68) "…" else "",
                    style = MaterialTheme.typography.bodySmall
                )
            }
        },
        confirmButton = {
            Button(onClick = onCopyUri) {
                Text(stringResource(R.string.profiles_copy_link))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.common_close))
            }
        }
    )
}
