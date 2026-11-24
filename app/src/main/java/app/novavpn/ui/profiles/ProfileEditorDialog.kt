package app.novavpn.ui.profiles

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.novavpn.domain.model.AetherProtocol
import app.novavpn.domain.model.DnsMode
import app.novavpn.domain.model.ObfuscationProfile
import app.novavpn.domain.model.RouteChain
import app.novavpn.domain.model.VpnProfile
import app.novavpn.domain.routing.RoutingRuleParser
import app.novavpn.domain.validation.InputValidators
import app.novavpn.domain.validation.ValidationResult
import app.novavpn.ui.theme.tr
import com.example.R
import java.util.UUID

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ProfileEditorDialog(
    initial: VpnProfile?,
    onSave: (VpnProfile) -> Unit,
    onDismiss: () -> Unit
) {
    val isNew = initial == null
    var name by remember { mutableStateOf(initial?.name ?: "") }
    var routeChain by remember { mutableStateOf(initial?.routeChain ?: RouteChain.SMART_AUTO) }
    var protocol by remember { mutableStateOf(initial?.aetherProtocol ?: AetherProtocol.MASQUE) }
    var masqueOverH2 by remember { mutableStateOf(initial?.masqueOverH2 ?: false) }
    var tlsFragment by remember { mutableStateOf(initial?.tlsFragment ?: false) }
    var obfuscation by remember { mutableStateOf(initial?.obfuscation ?: ObfuscationProfile.AUTO) }
    var endpoint by remember { mutableStateOf(initial?.customEndpoint ?: "") }
    var scanLevel by remember { mutableIntStateOf(initial?.scanLevel ?: 2) }
    var ipv4 by remember { mutableStateOf(initial?.ipv4Enabled ?: true) }
    var ipv6 by remember { mutableStateOf(initial?.ipv6Enabled ?: false) }
    var mtuText by remember { mutableStateOf((initial?.mtu ?: 1280).toString()) }
    var tcpBufText by remember { mutableStateOf((initial?.tcpBufferKb ?: 64).toString()) }
    var pingSecText by remember { mutableStateOf((initial?.pingIntervalSec ?: 15).toString()) }
    var pingLimitText by remember { mutableStateOf((initial?.pingFailureLimit ?: 3).toString()) }
    var extraArgs by remember { mutableStateOf(initial?.extraCoreArgs ?: "") }
    var psiphonRegion by remember { mutableStateOf(initial?.psiphonRegion ?: "AUTO") }
    var torUseBridges by remember { mutableStateOf(initial?.torUseBridges ?: false) }
    var torBridgesText by remember { mutableStateOf((initial?.torBridges ?: emptyList()).joinToString("\n")) }
    var dnsMode by remember { mutableStateOf(initial?.dnsMode ?: DnsMode.DOH) }
    var dnsPrimary by remember { mutableStateOf(initial?.customDnsPrimary ?: "https://cloudflare-dns.com/dns-query") }
    var dnsSecondary by remember { mutableStateOf(initial?.customDnsSecondary ?: "1.1.1.1") }
    var upstreamProxy by remember { mutableStateOf(initial?.upstreamProxyUri ?: "") }
    var directRules by remember { mutableStateOf(initial?.directRules ?: "10.0.0.0/8\n192.168.0.0/16\n*.ir") }
    var blockRules by remember { mutableStateOf(initial?.blockRules ?: "geosite:category-ads-all") }

    var validationError by remember { mutableStateOf<String?>(null) }

    val regions = listOf("AUTO", "DE", "NL", "US", "GB", "CA", "SG", "JP")

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (isNew) tr("New Profile", "پروفایل جدید") else tr("Edit Profile", "ویرایش پروفایل")) },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(tr("Profile Name", "نام پروفایل")) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().testTag("profile_name_input")
                )

                Text(tr("Route Chain", "زنجیره مسیر"), style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    RouteChain.entries.forEach { r ->
                        FilterChip(
                            selected = routeChain == r,
                            onClick = { routeChain = r },
                            label = { Text(r.name.replace("_", " ")) }
                        )
                    }
                }

                Text(tr("Aether Protocol", "پروتکل اتر"), style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    AetherProtocol.entries.forEach { p ->
                        FilterChip(
                            selected = protocol == p,
                            onClick = { protocol = p },
                            label = { Text(p.name.replace("_", "-")) }
                        )
                    }
                }

                val supportsMasqueCarrier = protocol == AetherProtocol.MASQUE ||
                    protocol == AetherProtocol.WARP_IN_MASQUE ||
                    protocol == AetherProtocol.MASQUE_IN_MASQUE
                if (supportsMasqueCarrier) {
                    Text(stringResource(R.string.profile_masque_carrier), style = MaterialTheme.typography.labelLarge)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(
                            selected = !masqueOverH2,
                            onClick = { masqueOverH2 = false },
                            label = { Text(stringResource(R.string.profile_masque_h3)) }
                        )
                        FilterChip(
                            selected = masqueOverH2,
                            onClick = { masqueOverH2 = true },
                            label = { Text(stringResource(R.string.profile_masque_h2)) }
                        )
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                        Text(stringResource(R.string.profile_tls_fragment), style = MaterialTheme.typography.bodyMedium)
                        Text(
                            stringResource(R.string.profile_tls_fragment_desc),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = tlsFragment,
                        onCheckedChange = { tlsFragment = it },
                        modifier = Modifier.testTag("profile_tls_fragment_switch")
                    )
                }

                Text(tr("Obfuscation", "مبهم‌سازی"), style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    ObfuscationProfile.entries.forEach { o ->
                        FilterChip(
                            selected = obfuscation == o,
                            onClick = { obfuscation = o },
                            label = { Text(o.name) }
                        )
                    }
                }

                OutlinedTextField(
                    value = endpoint,
                    onValueChange = { endpoint = it },
                    label = { Text(tr("Custom Endpoint (ip:port, optional)", "سرور دلخواه (ip:port اختیاری)")) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                Text(tr("Gateway Scan Level", "سطح اسکن"), style = MaterialTheme.typography.labelLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(1 to "Fast", 2 to "Balanced", 3 to "Deep").forEach { (lvl, lbl) ->
                        FilterChip(
                            selected = scanLevel == lvl,
                            onClick = { scanLevel = lvl },
                            label = { Text(lbl) }
                        )
                    }
                }

                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = mtuText,
                        onValueChange = { mtuText = it.filter(Char::isDigit) },
                        label = { Text("MTU (576-1500)") },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedTextField(
                        value = tcpBufText,
                        onValueChange = { tcpBufText = it.filter(Char::isDigit) },
                        label = { Text("TCP Buf KB") },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                }

                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = pingSecText,
                        onValueChange = { pingSecText = it.filter(Char::isDigit) },
                        label = { Text("Ping (s)") },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedTextField(
                        value = pingLimitText,
                        onValueChange = { pingLimitText = it.filter(Char::isDigit) },
                        label = { Text("Ping Limit") },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                }

                Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("IPv4")
                    Switch(checked = ipv4, onCheckedChange = { ipv4 = it })
                    Text("IPv6")
                    Switch(checked = ipv6, onCheckedChange = { ipv6 = it })
                }

                Text(tr("DNS Mode", "حالت DNS"), style = MaterialTheme.typography.labelLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    DnsMode.entries.forEach { d ->
                        FilterChip(selected = dnsMode == d, onClick = { dnsMode = d }, label = { Text(d.name) })
                    }
                }
                OutlinedTextField(
                    value = dnsPrimary,
                    onValueChange = { dnsPrimary = it },
                    label = { Text(tr("Primary DNS (DoH / DoT / IP)", "DNS اصلی")) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = dnsSecondary,
                    onValueChange = { dnsSecondary = it },
                    label = { Text(tr("Fallback DNS IP", "DNS دوم")) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                Text(tr("Psiphon Region", "منطقه سایفون"), style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    regions.forEach { reg ->
                        FilterChip(selected = psiphonRegion == reg, onClick = { psiphonRegion = reg }, label = { Text(reg) })
                    }
                }

                Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(tr("Use Custom Tor Bridges", "استفاده از پل‌های Tor"))
                    Switch(checked = torUseBridges, onCheckedChange = { torUseBridges = it })
                }
                if (torUseBridges) {
                    OutlinedTextField(
                        value = torBridgesText,
                        onValueChange = { torBridgesText = it },
                        label = { Text("obfs4 / webtunnel bridges (1 per line)") },
                        minLines = 2,
                        maxLines = 4,
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                OutlinedTextField(
                    value = upstreamProxy,
                    onValueChange = { upstreamProxy = it },
                    label = { Text(tr("Upstream Proxy URI (optional)", "پروکسی بالادستی (اختیاری)")) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                OutlinedTextField(
                    value = extraArgs,
                    onValueChange = { extraArgs = it },
                    label = { Text(tr("Extra Core Args (validated)", "پارامترهای اضافی هسته")) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                OutlinedTextField(
                    value = directRules,
                    onValueChange = { directRules = it },
                    label = { Text(tr("Direct Rules (IP/CIDR/Domain/Port)", "قوانین مستقیم (بدون تونل)")) },
                    minLines = 2,
                    maxLines = 4,
                    modifier = Modifier.fillMaxWidth()
                )

                OutlinedTextField(
                    value = blockRules,
                    onValueChange = { blockRules = it },
                    label = { Text(tr("Block Rules", "قوانین مسدودسازی")) },
                    minLines = 2,
                    maxLines = 4,
                    modifier = Modifier.fillMaxWidth()
                )

                if (validationError != null) {
                    Text(
                        text = validationError!!,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val cleanName = name.trim()
                    if (cleanName.isEmpty()) {
                        validationError = "Profile name cannot be empty"
                        return@Button
                    }
                    val epCheck = InputValidators.validateEndpoint(endpoint, allowEmpty = true)
                    if (epCheck is ValidationResult.Invalid) {
                        validationError = epCheck.reason
                        return@Button
                    }
                    val mtu = mtuText.toIntOrNull() ?: 0
                    val mtuCheck = InputValidators.validateMtu(mtu)
                    if (mtuCheck is ValidationResult.Invalid) {
                        validationError = mtuCheck.reason
                        return@Button
                    }
                    val tcpBuf = (tcpBufText.toIntOrNull() ?: 64).coerceIn(16, 512)
                    val pingSec = (pingSecText.toIntOrNull() ?: 15).coerceIn(5, 120)
                    val pingLimit = (pingLimitText.toIntOrNull() ?: 3).coerceIn(1, 20)
                    val dnsCheck = InputValidators.validateDnsServer(dnsPrimary)
                    if (dnsCheck is ValidationResult.Invalid) {
                        validationError = dnsCheck.reason
                        return@Button
                    }
                    val upstreamCheck = InputValidators.validateUpstreamProxy(upstreamProxy)
                    if (upstreamCheck is ValidationResult.Invalid) {
                        validationError = upstreamCheck.reason
                        return@Button
                    }
                    val extraRes = InputValidators.parseAndValidateExtraArgs(extraArgs)
                    if (extraRes.isFailure) {
                        validationError = extraRes.exceptionOrNull()?.message
                        return@Button
                    }
                    val bridges = torBridgesText.lines().map { it.trim() }.filter { it.isNotEmpty() }
                    if (torUseBridges) {
                        for (b in bridges) {
                            val bCheck = InputValidators.validateTorBridgeLine(b)
                            if (bCheck is ValidationResult.Invalid) {
                                validationError = bCheck.reason
                                return@Button
                            }
                        }
                    }
                    val directParse = RoutingRuleParser.parse(directRules)
                    if (directParse.invalidLines.isNotEmpty()) {
                        val (lineNum, raw) = directParse.invalidLines.first()
                        validationError = "Invalid direct rule on line $lineNum: $raw"
                        return@Button
                    }
                    val blockParse = RoutingRuleParser.parse(blockRules)
                    if (blockParse.invalidLines.isNotEmpty()) {
                        val (lineNum, raw) = blockParse.invalidLines.first()
                        validationError = "Invalid block rule on line $lineNum: $raw"
                        return@Button
                    }

                    onSave(
                        VpnProfile(
                            id = initial?.id ?: UUID.randomUUID().toString(),
                            name = cleanName,
                            routeChain = routeChain,
                            aetherProtocol = protocol,
                            masqueOverH2 = masqueOverH2,
                            tlsFragment = tlsFragment,
                            customEndpoint = endpoint.trim(),
                            scanLevel = scanLevel,
                            obfuscation = obfuscation,
                            ipv4Enabled = ipv4,
                            ipv6Enabled = ipv6,
                            mtu = mtu,
                            tcpBufferKb = tcpBuf,
                            pingIntervalSec = pingSec,
                            pingFailureLimit = pingLimit,
                            extraCoreArgs = extraArgs.trim(),
                            psiphonRegion = psiphonRegion,
                            torUseBridges = torUseBridges,
                            torBridges = bridges,
                            dnsMode = dnsMode,
                            customDnsPrimary = dnsPrimary.trim(),
                            customDnsSecondary = dnsSecondary.trim(),
                            upstreamProxyUri = upstreamProxy.trim(),
                            directRules = directRules,
                            blockRules = blockRules
                        )
                    )
                },
                modifier = Modifier.testTag("save_profile_button")
            ) {
                Text(tr("Save", "ذخیره"))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(tr("Cancel", "لغو"))
            }
        }
    )
}
