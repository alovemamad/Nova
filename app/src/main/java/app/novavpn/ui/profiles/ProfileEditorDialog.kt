package app.novavpn.ui.profiles

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.novavpn.domain.model.AetherProtocol
import app.novavpn.domain.model.DnsMode
import app.novavpn.domain.model.ObfuscationProfile
import app.novavpn.domain.model.PsiphonRegions
import app.novavpn.domain.model.RouteChain
import app.novavpn.domain.model.VpnProfile
import app.novavpn.domain.routing.RoutingRuleParser
import app.novavpn.domain.validation.InputValidators
import app.novavpn.domain.validation.ValidationResult
import app.novavpn.ui.glass.GlassCapsuleButton
import app.novavpn.ui.glass.GlassCard
import app.novavpn.ui.glass.GlassDivider
import app.novavpn.ui.glass.GlassIcons
import app.novavpn.ui.glass.GlassSectionHeader
import app.novavpn.ui.glass.GlassSegmented
import app.novavpn.ui.glass.GlassSheet
import app.novavpn.ui.glass.GlassSwitch
import app.novavpn.ui.glass.LocalGlassLook
import app.novavpn.ui.glass.novaOutlinedTextFieldColors
import app.novavpn.ui.theme.countryCodeToFlag
import app.novavpn.ui.theme.isPersianUi
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
    val look = LocalGlassLook.current
    val faUi = isPersianUi()
    val isNew = initial == null

    var name by remember { mutableStateOf(initial?.name ?: "") }
    var routeChain by remember { mutableStateOf(initial?.routeChain ?: RouteChain.SMART_AUTO) }
    var protocol by remember { mutableStateOf(initial?.aetherProtocol ?: AetherProtocol.MASQUE) }
    // Default new profiles to HTTP/2 (TCP) for higher reliability on constrained networks
    var masqueOverH2 by remember { mutableStateOf(initial?.masqueOverH2 ?: true) }
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
    val isPresetAether = initial?.id == VpnProfile.PRESET_AETHER_ID
    var requiresCountry by remember {
        mutableStateOf(
            if (isPresetAether) false
            else initial?.requiresCountry ?: (routeChain == RouteChain.AETHER_PSIPHON || routeChain == RouteChain.PSIPHON_ONLY || routeChain == RouteChain.SMART_AUTO)
        )
    }
    var torUseBridges by remember { mutableStateOf(initial?.torUseBridges ?: false) }
    var torBridgesText by remember { mutableStateOf((initial?.torBridges ?: emptyList()).joinToString("\n")) }
    var dnsMode by remember { mutableStateOf(initial?.dnsMode ?: DnsMode.DOH) }
    var dnsPrimary by remember { mutableStateOf(initial?.customDnsPrimary ?: "https://cloudflare-dns.com/dns-query") }
    var dnsSecondary by remember { mutableStateOf(initial?.customDnsSecondary ?: "1.1.1.1") }
    var upstreamProxy by remember { mutableStateOf(initial?.upstreamProxyUri ?: "") }
    var directRules by remember { mutableStateOf(initial?.directRules ?: "10.0.0.0/8\n172.16.0.0/12\n192.168.0.0/16\n*.ir") }
    var blockRules by remember { mutableStateOf(initial?.blockRules ?: "geosite:category-ads-all") }

    var validationError by remember { mutableStateOf<String?>(null) }

    val validateAndSave = save@{
        val cleanName = name.trim()
        if (cleanName.isEmpty()) {
            validationError = "Profile name cannot be empty"
            return@save
        }
        val epCheck = InputValidators.validateEndpoint(endpoint, allowEmpty = true)
        if (epCheck is ValidationResult.Invalid) {
            validationError = epCheck.reason
            return@save
        }
        val mtu = mtuText.toIntOrNull() ?: 0
        val mtuCheck = InputValidators.validateMtu(mtu)
        if (mtuCheck is ValidationResult.Invalid) {
            validationError = mtuCheck.reason
            return@save
        }
        val tcpBuf = (tcpBufText.toIntOrNull() ?: 64).coerceIn(16, 512)
        val pingSec = (pingSecText.toIntOrNull() ?: 15).coerceIn(5, 120)
        val pingLimit = (pingLimitText.toIntOrNull() ?: 3).coerceIn(1, 20)
        val dnsCheck = InputValidators.validateDnsServer(dnsPrimary)
        if (dnsCheck is ValidationResult.Invalid) {
            validationError = dnsCheck.reason
            return@save
        }
        val upstreamCheck = InputValidators.validateUpstreamProxy(upstreamProxy)
        if (upstreamCheck is ValidationResult.Invalid) {
            validationError = upstreamCheck.reason
            return@save
        }
        val extraRes = InputValidators.parseAndValidateExtraArgs(extraArgs)
        if (extraRes.isFailure) {
            validationError = extraRes.exceptionOrNull()?.message
            return@save
        }
        val bridges = torBridgesText.lines().map { it.trim() }.filter { it.isNotEmpty() }
        if (torUseBridges) {
            for (b in bridges) {
                val bCheck = InputValidators.validateTorBridgeLine(b)
                if (bCheck is ValidationResult.Invalid) {
                    validationError = bCheck.reason
                    return@save
                }
            }
        }
        val directParse = RoutingRuleParser.parse(directRules)
        if (directParse.invalidLines.isNotEmpty()) {
            val (lineNum, raw) = directParse.invalidLines.first()
            validationError = "Invalid direct rule on line $lineNum: $raw"
            return@save
        }
        val blockParse = RoutingRuleParser.parse(blockRules)
        if (blockParse.invalidLines.isNotEmpty()) {
            val (lineNum, raw) = blockParse.invalidLines.first()
            validationError = "Invalid block rule on line $lineNum: $raw"
            return@save
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
                psiphonRegion = if (isPresetAether || !requiresCountry) "" else psiphonRegion.ifBlank { "AUTO" },
                requiresCountry = if (isPresetAether) false else requiresCountry,
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
    }

    GlassSheet(
        onDismissRequest = onDismiss,
        title = stringResource(if (isNew) R.string.profiles_new else R.string.profiles_edit),
        icon = GlassIcons.Pencil
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 520.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Section 1: Identity & Route
            GlassSectionHeader(title = stringResource(R.string.profiles_section_identity))
            GlassCard(cornerRadius = 22.dp, modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
                        label = { Text(tr("Profile Name", "نام پروفایل")) },
                        singleLine = true,
                        shape = RoundedCornerShape(14.dp),
                        colors = novaOutlinedTextFieldColors(),
                        modifier = Modifier.fillMaxWidth().testTag("profile_name_input")
                    )

                    Text(
                        tr("Route Chain", "زنجیره مسیر"),
                        style = MaterialTheme.typography.labelLarge,
                        color = look.textSecondary
                    )
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        RouteChain.entries.forEach { r ->
                            GlassOptionPill(
                                label = r.name.replace("_", " "),
                                selected = routeChain == r,
                                onClick = {
                                    routeChain = r
                                    if (!isPresetAether) {
                                        requiresCountry = when (r) {
                                            RouteChain.AETHER_PSIPHON, RouteChain.PSIPHON_ONLY -> true
                                            RouteChain.AETHER, RouteChain.AETHER_TOR, RouteChain.TOR_ONLY -> false
                                            RouteChain.SMART_AUTO -> requiresCountry
                                        }
                                    }
                                }
                            )
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f).padding(end = 10.dp)) {
                            Text(
                                stringResource(R.string.profile_requires_country),
                                style = MaterialTheme.typography.bodyMedium,
                                color = look.textPrimary
                            )
                            Text(
                                stringResource(R.string.profile_requires_country_desc),
                                style = MaterialTheme.typography.bodySmall,
                                color = look.textSecondary
                            )
                        }
                        GlassSwitch(
                            checked = !isPresetAether && requiresCountry,
                            onCheckedChange = { checked ->
                                if (!isPresetAether) {
                                    requiresCountry = checked
                                }
                            },
                            modifier = Modifier.testTag("profile_requires_country_switch")
                        )
                    }

                    val showPsiphonRegionPicker = !isPresetAether && requiresCountry &&
                        (routeChain == RouteChain.AETHER_PSIPHON || routeChain == RouteChain.PSIPHON_ONLY || routeChain == RouteChain.SMART_AUTO)
                    AnimatedVisibility(visible = showPsiphonRegionPicker) {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(
                                tr("Psiphon Exit Region", "کشور خروجی سایفون"),
                                style = MaterialTheme.typography.labelLarge,
                                color = look.textSecondary
                            )
                            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                items(PsiphonRegions.ALL, key = { it.code }) { reg ->
                                    val regLabel = if (reg.code == "AUTO") {
                                        if (faUi) "خودکار" else "Auto"
                                    } else {
                                        if (faUi) "${reg.nameFa} (${reg.code})" else "${reg.nameEn} (${reg.code})"
                                    }
                                    GlassOptionPill(
                                        label = "${countryCodeToFlag(reg.code)} $regLabel",
                                        selected = psiphonRegion.ifBlank { "AUTO" } == reg.code,
                                        onClick = { psiphonRegion = reg.code }
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // Section 2: Transport & Carrier
            GlassSectionHeader(title = stringResource(R.string.profiles_section_transport))
            GlassCard(cornerRadius = 22.dp, modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        tr("Aether Protocol", "پروتکل اتر"),
                        style = MaterialTheme.typography.labelLarge,
                        color = look.textSecondary
                    )
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        AetherProtocol.entries.forEach { p ->
                            GlassOptionPill(
                                label = p.name.replace("_", "-"),
                                selected = protocol == p,
                                onClick = { protocol = p }
                            )
                        }
                    }

                    val supportsMasqueCarrier = protocol == AetherProtocol.MASQUE ||
                        protocol == AetherProtocol.WARP_IN_MASQUE ||
                        protocol == AetherProtocol.MASQUE_IN_MASQUE
                    AnimatedVisibility(visible = supportsMasqueCarrier) {
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(
                                stringResource(R.string.profile_masque_carrier),
                                style = MaterialTheme.typography.labelLarge,
                                color = look.textSecondary
                            )
                            GlassSegmented(
                                options = listOf(
                                    true to stringResource(R.string.profile_masque_h2),
                                    false to stringResource(R.string.profile_masque_h3)
                                ),
                                selected = masqueOverH2,
                                onSelect = { masqueOverH2 = it }
                            )
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f).padding(end = 10.dp)) {
                            Text(
                                stringResource(R.string.profile_tls_fragment),
                                style = MaterialTheme.typography.bodyMedium,
                                color = look.textPrimary
                            )
                            Text(
                                stringResource(R.string.profile_tls_fragment_desc),
                                style = MaterialTheme.typography.bodySmall,
                                color = look.textSecondary
                            )
                        }
                        GlassSwitch(
                            checked = tlsFragment,
                            onCheckedChange = { tlsFragment = it },
                            modifier = Modifier.testTag("profile_tls_fragment_switch")
                        )
                    }

                    GlassDivider()

                    Text(
                        tr("Obfuscation", "مبهم‌سازی"),
                        style = MaterialTheme.typography.labelLarge,
                        color = look.textSecondary
                    )
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        ObfuscationProfile.entries.forEach { o ->
                            GlassOptionPill(
                                label = o.name.replace("_", " "),
                                selected = obfuscation == o,
                                onClick = { obfuscation = o }
                            )
                        }
                    }

                    OutlinedTextField(
                        value = endpoint,
                        onValueChange = { endpoint = it },
                        label = { Text(tr("Custom Endpoint (ip:port, optional)", "سرور دلخواه (ip:port اختیاری)")) },
                        singleLine = true,
                        shape = RoundedCornerShape(14.dp),
                        colors = novaOutlinedTextFieldColors(),
                        modifier = Modifier.fillMaxWidth()
                    )

                    Text(
                        tr("Gateway Scan Level", "سطح اسکن"),
                        style = MaterialTheme.typography.labelLarge,
                        color = look.textSecondary
                    )
                    GlassSegmented(
                        options = listOf(
                            1 to tr("Fast", "سریع"),
                            2 to tr("Balanced", "متعادل"),
                            3 to tr("Deep", "عمیق")
                        ),
                        selected = scanLevel,
                        onSelect = { scanLevel = it }
                    )
                }
            }

            // Section 3: Tunnel Tuning & Tor Bridges
            GlassSectionHeader(title = stringResource(R.string.profiles_section_tuning))
            GlassCard(cornerRadius = 22.dp, modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = mtuText,
                            onValueChange = { mtuText = it.filter(Char::isDigit) },
                            label = { Text("MTU (576-1500)") },
                            singleLine = true,
                            shape = RoundedCornerShape(14.dp),
                            colors = novaOutlinedTextFieldColors(),
                            modifier = Modifier.weight(1f)
                        )
                        OutlinedTextField(
                            value = tcpBufText,
                            onValueChange = { tcpBufText = it.filter(Char::isDigit) },
                            label = { Text("TCP Buf KB") },
                            singleLine = true,
                            shape = RoundedCornerShape(14.dp),
                            colors = novaOutlinedTextFieldColors(),
                            modifier = Modifier.weight(1f)
                        )
                    }

                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = pingSecText,
                            onValueChange = { pingSecText = it.filter(Char::isDigit) },
                            label = { Text("Ping (s)") },
                            singleLine = true,
                            shape = RoundedCornerShape(14.dp),
                            colors = novaOutlinedTextFieldColors(),
                            modifier = Modifier.weight(1f)
                        )
                        OutlinedTextField(
                            value = pingLimitText,
                            onValueChange = { pingLimitText = it.filter(Char::isDigit) },
                            label = { Text("Ping Limit") },
                            singleLine = true,
                            shape = RoundedCornerShape(14.dp),
                            colors = novaOutlinedTextFieldColors(),
                            modifier = Modifier.weight(1f)
                        )
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("IPv4", color = look.textPrimary)
                            GlassSwitch(checked = ipv4, onCheckedChange = { ipv4 = it })
                        }
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("IPv6", color = look.textPrimary)
                            GlassSwitch(checked = ipv6, onCheckedChange = { ipv6 = it })
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(tr("Use Custom Tor Bridges", "استفاده از پل‌های Tor"), color = look.textPrimary)
                        GlassSwitch(checked = torUseBridges, onCheckedChange = { torUseBridges = it })
                    }

                    AnimatedVisibility(visible = torUseBridges) {
                        OutlinedTextField(
                            value = torBridgesText,
                            onValueChange = { torBridgesText = it },
                            label = { Text("obfs4 / webtunnel bridges (1 per line)") },
                            minLines = 2,
                            maxLines = 4,
                            shape = RoundedCornerShape(14.dp),
                            colors = novaOutlinedTextFieldColors(),
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            }

            // Section 4: DNS & Upstream
            GlassSectionHeader(title = stringResource(R.string.profiles_section_dns))
            GlassCard(cornerRadius = 22.dp, modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text(tr("DNS Mode", "حالت DNS"), style = MaterialTheme.typography.labelLarge, color = look.textSecondary)
                    GlassSegmented(
                        options = DnsMode.entries.map { it to it.name },
                        selected = dnsMode,
                        onSelect = { dnsMode = it }
                    )
                    OutlinedTextField(
                        value = dnsPrimary,
                        onValueChange = { dnsPrimary = it },
                        label = { Text(tr("Primary DNS (DoH / DoT / IP)", "DNS اصلی")) },
                        singleLine = true,
                        shape = RoundedCornerShape(14.dp),
                        colors = novaOutlinedTextFieldColors(),
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = dnsSecondary,
                        onValueChange = { dnsSecondary = it },
                        label = { Text(tr("Fallback DNS IP", "DNS دوم")) },
                        singleLine = true,
                        shape = RoundedCornerShape(14.dp),
                        colors = novaOutlinedTextFieldColors(),
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = upstreamProxy,
                        onValueChange = { upstreamProxy = it },
                        label = { Text(tr("Upstream Proxy URI (optional)", "پروکسی بالادستی (اختیاری)")) },
                        singleLine = true,
                        shape = RoundedCornerShape(14.dp),
                        colors = novaOutlinedTextFieldColors(),
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = extraArgs,
                        onValueChange = { extraArgs = it },
                        label = { Text(tr("Extra Core Args (validated)", "پارامترهای اضافی هسته")) },
                        singleLine = true,
                        shape = RoundedCornerShape(14.dp),
                        colors = novaOutlinedTextFieldColors(),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }

            // Section 5: Split Rules
            GlassSectionHeader(title = stringResource(R.string.profiles_section_rules))
            GlassCard(cornerRadius = 22.dp, modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    OutlinedTextField(
                        value = directRules,
                        onValueChange = { directRules = it },
                        label = { Text(tr("Direct Rules (IP/CIDR/Domain/Port)", "قوانین مستقیم (بدون تونل)")) },
                        minLines = 2,
                        maxLines = 4,
                        shape = RoundedCornerShape(14.dp),
                        colors = novaOutlinedTextFieldColors(),
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = blockRules,
                        onValueChange = { blockRules = it },
                        label = { Text(tr("Block Rules", "قوانین مسدودسازی")) },
                        minLines = 2,
                        maxLines = 4,
                        shape = RoundedCornerShape(14.dp),
                        colors = novaOutlinedTextFieldColors(),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }

            if (validationError != null) {
                Text(
                    text = validationError!!,
                    color = look.danger,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(horizontal = 4.dp)
                )
            }
        }

        Spacer(modifier = Modifier.height(12.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End
        ) {
            GlassCapsuleButton(
                text = tr("Cancel", "لغو"),
                onClick = onDismiss
            )
            Spacer(modifier = Modifier.width(10.dp))
            GlassCapsuleButton(
                text = tr("Save", "ذخیره"),
                prominent = true,
                onClick = validateAndSave,
                modifier = Modifier.testTag("save_profile_button")
            )
        }
    }
}

@Composable
private fun GlassOptionPill(
    label: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    val look = LocalGlassLook.current
    val shape = RoundedCornerShape(50)
    Row(
        modifier = Modifier
            .defaultMinSize(minHeight = 36.dp)
            .clip(shape)
            .background(if (selected) look.accent.copy(alpha = 0.24f) else look.fill2)
            .border(
                width = 1.dp,
                color = if (selected) look.accent.copy(alpha = 0.65f) else look.separator,
                shape = shape
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            fontSize = 12.sp,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
            color = if (selected) look.textPrimary else look.textSecondary,
            maxLines = 1
        )
    }
}
