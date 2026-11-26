package app.novavpn.ui.onboarding

import android.Manifest
import android.app.Activity
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
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
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.novavpn.ui.glass.GlassCapsuleButton
import app.novavpn.ui.glass.GlassCard
import app.novavpn.ui.glass.GlassDivider
import app.novavpn.ui.glass.GlassIcons
import app.novavpn.ui.glass.GlassRow
import app.novavpn.ui.glass.LocalGlassLook
import com.example.R

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
                    Text(stringResource(R.string.onb_skip), color = look.textSecondary)
                }
            }

            GlassCard(
                modifier = Modifier.fillMaxWidth(),
                elevated = true
            ) {
                AnimatedContent(
                    targetState = page,
                    transitionSpec = {
                        val forward = targetState >= initialState
                        (slideInHorizontally(tween(280)) { w -> if (forward) w / 3 else -w / 3 } + fadeIn(tween(240))) togetherWith
                            (slideOutHorizontally(tween(220)) { w -> if (forward) -w / 3 else w / 3 } + fadeOut(tween(180)))
                    },
                    label = "onboarding_page_anim"
                ) { activePage ->
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        val icon = when (activePage) {
                            0 -> GlassIcons.Sparkles
                            1 -> GlassIcons.Routing
                            else -> GlassIcons.Shield
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

                        when (activePage) {
                            0 -> {
                                Text(
                                    text = stringResource(R.string.onb_welcome_title),
                                    style = MaterialTheme.typography.headlineMedium,
                                    color = look.textPrimary,
                                    textAlign = TextAlign.Center
                                )
                                Spacer(modifier = Modifier.height(10.dp))
                                Text(
                                    text = stringResource(R.string.onb_welcome_body),
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = look.textSecondary,
                                    textAlign = TextAlign.Center
                                )
                            }
                            1 -> {
                                Text(
                                    text = stringResource(R.string.onb_routing_title),
                                    style = MaterialTheme.typography.headlineMedium,
                                    color = look.textPrimary,
                                    textAlign = TextAlign.Center
                                )
                                Spacer(modifier = Modifier.height(10.dp))
                                Text(
                                    text = stringResource(R.string.onb_routing_body),
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = look.textSecondary,
                                    textAlign = TextAlign.Center
                                )
                            }
                            else -> {
                                Text(
                                    text = stringResource(R.string.onb_perms_title),
                                    style = MaterialTheme.typography.headlineMedium,
                                    color = look.textPrimary,
                                    textAlign = TextAlign.Center
                                )
                                Spacer(modifier = Modifier.height(12.dp))
                                GlassRow(
                                    title = stringResource(R.string.onb_vpn_perm),
                                    subtitle = stringResource(if (vpnGranted) R.string.onb_vpn_granted else R.string.onb_vpn_required),
                                    icon = GlassIcons.Lock,
                                    onClick = {
                                        prepareVpnIntent()?.let { vpnLauncher.launch(it) }
                                    }
                                ) {
                                    if (vpnGranted) {
                                        Icon(GlassIcons.CheckCircle, contentDescription = null, tint = look.success)
                                    } else {
                                        Text(stringResource(R.string.onb_grant), color = look.accent, fontWeight = FontWeight.Bold)
                                    }
                                }
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                    GlassDivider()
                                    GlassRow(
                                        title = stringResource(R.string.onb_notif_perm),
                                        subtitle = stringResource(if (notifGranted) R.string.onb_notif_allowed else R.string.onb_notif_desc),
                                        icon = GlassIcons.Bell,
                                        onClick = { notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS) }
                                    ) {
                                        if (notifGranted) {
                                            Icon(GlassIcons.CheckCircle, contentDescription = null, tint = look.success)
                                        } else {
                                            Text(stringResource(R.string.onb_allow), color = look.accent, fontWeight = FontWeight.Bold)
                                        }
                                    }
                                }
                                Spacer(modifier = Modifier.height(12.dp))
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    GlassCapsuleButton(
                                        text = stringResource(R.string.onb_battery),
                                        onClick = onOpenBatterySettings,
                                        modifier = Modifier.weight(1f)
                                    )
                                    GlassCapsuleButton(
                                        text = stringResource(R.string.onb_always_on),
                                        onClick = onOpenVpnSettings,
                                        modifier = Modifier.weight(1f)
                                    )
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
                        val dotWidth by animateDpAsState(
                            targetValue = if (idx == page) 24.dp else 8.dp,
                            animationSpec = spring(),
                            label = "onb_dot_$idx"
                        )
                        Box(
                            modifier = Modifier
                                .size(width = dotWidth, height = 8.dp)
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
                        GlassCapsuleButton(
                            text = stringResource(R.string.onb_back),
                            onClick = { page-- },
                            modifier = Modifier.weight(1f).height(52.dp)
                        )
                    }
                    GlassCapsuleButton(
                        text = stringResource(if (page < 2) R.string.onb_continue else R.string.onb_finish),
                        prominent = true,
                        onClick = {
                            if (page < 2) page++ else onFinish()
                        },
                        modifier = Modifier
                            .weight(1f)
                            .height(52.dp)
                            .testTag("onboarding_next")
                    )
                }
            }
        }
    }
}
