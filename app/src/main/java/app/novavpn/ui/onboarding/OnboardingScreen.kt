package app.novavpn.ui.onboarding

import android.Manifest
import android.app.Activity
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.NotificationsActive
import androidx.compose.material.icons.rounded.Route
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material.icons.rounded.VpnKey
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.novavpn.ui.glass.GlassCard
import app.novavpn.ui.glass.GlassDivider
import app.novavpn.ui.glass.GlassRow
import app.novavpn.ui.glass.LocalGlassLook
import app.novavpn.ui.theme.tr

@Composable
fun OnboardingScreen(
    prepareVpnIntent: () -> android.content.Intent?,
    onOpenBatterySettings: () -> Unit,
    onOpenVpnSettings: () -> Unit,
    onFinish: () -> Unit
) {
    val look = LocalGlassLook.current
    var page by remember { mutableIntStateOf(0) }
    var vpnGranted by remember { mutableStateOf(prepareVpnIntent() == null) }
    var notifGranted by remember { mutableStateOf(Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) }

    val vpnLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { res ->
        vpnGranted = res.resultCode == Activity.RESULT_OK || prepareVpnIntent() == null
    }

    val notifLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        notifGranted = granted
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(20.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = 520.dp)
                .fillMaxSize(),
            verticalArrangement = Arrangement.SpaceBetween,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                TextButton(onClick = onFinish, modifier = Modifier.testTag("onboarding_skip")) {
                    Text(tr("Skip", "رد شدن"), color = look.textSecondary)
                }
            }

            GlassCard(
                modifier = Modifier.fillMaxWidth(),
                elevated = true
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    val icon = when (page) {
                        0 -> Icons.Rounded.AutoAwesome
                        1 -> Icons.Rounded.Route
                        else -> Icons.Rounded.Security
                    }
                    Box(
                        modifier = Modifier
                            .size(78.dp)
                            .clip(CircleShape)
                            .background(look.accent.copy(alpha = 0.16f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = icon,
                            contentDescription = null,
                            tint = look.accent,
                            modifier = Modifier.size(38.dp)
                        )
                    }

                    Spacer(modifier = Modifier.height(20.dp))

                    when (page) {
                        0 -> {
                            Text(
                                text = tr("Welcome to Nova VPN", "به نوا وی‌پی‌ان خوش آمدید"),
                                style = MaterialTheme.typography.headlineMedium,
                                color = look.textPrimary,
                                textAlign = TextAlign.Center
                            )
                            Spacer(modifier = Modifier.height(10.dp))
                            Text(
                                text = tr(
                                    "Multi-engine encrypted tunneling powered by Aether (MASQUE / WireGuard), Psiphon, and Tor — wrapped in adaptive Liquid Glass.",
                                    "تونل رمزنگاری‌شده چندموتوره با پشتیبانی از Aether، Psiphon و Tor در یک رابط کاربری مدرن."
                                ),
                                style = MaterialTheme.typography.bodyLarge,
                                color = look.textSecondary,
                                textAlign = TextAlign.Center
                            )
                        }
                        1 -> {
                            Text(
                                text = tr("Smart Adaptive Routing", "مسیریابی هوشمند و پایدار"),
                                style = MaterialTheme.typography.headlineMedium,
                                color = look.textPrimary,
                                textAlign = TextAlign.Center
                            )
                            Spacer(modifier = Modifier.height(10.dp))
                            Text(
                                text = tr(
                                    "Smart Auto probes your network with real HTTPS checks and remembers the fastest route per Wi-Fi or mobile carrier, with sing-box gVisor TUN and automatic fallback.",
                                    "حالت هوشمند مسیرهای مختلف را با تست واقعی HTTPS بررسی کرده و سریع‌ترین مسیر را برای هر شبکه ذخیره می‌کند."
                                ),
                                style = MaterialTheme.typography.bodyLarge,
                                color = look.textSecondary,
                                textAlign = TextAlign.Center
                            )
                        }
                        else -> {
                            Text(
                                text = tr("Permissions & Reliability", "دسترسی‌ها و پایداری"),
                                style = MaterialTheme.typography.headlineMedium,
                                color = look.textPrimary,
                                textAlign = TextAlign.Center
                            )
                            Spacer(modifier = Modifier.height(12.dp))
                            GlassRow(
                                title = tr("VPN Tunnel Permission", "مجوز تونل وی‌پی‌ان"),
                                subtitle = if (vpnGranted) tr("Granted", "تایید شده") else tr("Required to route device traffic", "برای هدایت ترافیک نیاز است"),
                                icon = Icons.Rounded.VpnKey,
                                onClick = {
                                    prepareVpnIntent()?.let { vpnLauncher.launch(it) }
                                }
                            ) {
                                if (vpnGranted) {
                                    Icon(Icons.Rounded.CheckCircle, contentDescription = null, tint = look.success)
                                } else {
                                    Text(tr("Grant", "تایید"), color = look.accent, fontWeight = FontWeight.Bold)
                                }
                            }
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                GlassDivider()
                                GlassRow(
                                    title = tr("Live Status Notification", "اعلان وضعیت اتصال"),
                                    subtitle = if (notifGranted) tr("Allowed", "فعال") else tr("Shows speed & pause controls", "نمایش سرعت و دکمه توقف"),
                                    icon = Icons.Rounded.NotificationsActive,
                                    onClick = { notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS) }
                                ) {
                                    if (notifGranted) {
                                        Icon(Icons.Rounded.CheckCircle, contentDescription = null, tint = look.success)
                                    } else {
                                        Text(tr("Allow", "اجازه"), color = look.accent, fontWeight = FontWeight.Bold)
                                    }
                                }
                            }
                            Spacer(modifier = Modifier.height(10.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                OutlinedButton(
                                    onClick = onOpenBatterySettings,
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Text(tr("Battery Opt.", "بهینه‌سازی باتری"), maxLines = 1)
                                }
                                OutlinedButton(
                                    onClick = onOpenVpnSettings,
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Text(tr("Always-on VPN", "اتصال همیشگی"), maxLines = 1)
                                }
                            }
                        }
                    }
                }
            }

            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    repeat(3) { idx ->
                        Box(
                            modifier = Modifier
                                .size(width = if (idx == page) 24.dp else 8.dp, height = 8.dp)
                                .clip(RoundedCornerShape(4.dp))
                                .background(if (idx == page) look.accent else look.cardBorder)
                        )
                    }
                }
                Spacer(modifier = Modifier.height(18.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    if (page > 0) {
                        OutlinedButton(
                            onClick = { page-- },
                            modifier = Modifier.weight(1f).height(52.dp)
                        ) {
                            Text(tr("Back", "قبلی"))
                        }
                    }
                    Button(
                        onClick = {
                            if (page < 2) page++ else onFinish()
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = look.accent,
                            contentColor = Color(0xFF031714)
                        ),
                        modifier = Modifier
                            .weight(1f)
                            .height(52.dp)
                            .testTag("onboarding_next")
                    ) {
                        Text(
                            text = if (page < 2) tr("Continue", "ادامه") else tr("Start Using Nova", "شروع استفاده"),
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }
    }
}
