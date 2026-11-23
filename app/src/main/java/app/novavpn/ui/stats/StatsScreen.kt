package app.novavpn.ui.stats

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Build
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.NetworkCheck
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.novavpn.domain.model.DataUsageDay
import app.novavpn.domain.model.DiagnosticReport
import app.novavpn.domain.model.SessionRecord
import app.novavpn.ui.glass.GlassBadge
import app.novavpn.ui.glass.GlassCard
import app.novavpn.ui.glass.GlassDivider
import app.novavpn.ui.glass.GlassRow
import app.novavpn.ui.glass.GlassSegmented
import app.novavpn.ui.glass.LocalGlassLook
import app.novavpn.ui.theme.JetBrainsMonoFamily
import app.novavpn.ui.theme.countryCodeToFlag
import app.novavpn.ui.theme.fmtDigits
import app.novavpn.ui.theme.formatBytes
import app.novavpn.ui.theme.formatDurationHms
import app.novavpn.ui.theme.tr
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private enum class StatsSubTab { USAGE, DIAGNOSTICS }
private enum class UsagePeriod { DAILY, WEEKLY, MONTHLY }

@Composable
fun StatsScreen(
    dailyUsage: List<DataUsageDay>,
    sessions: List<SessionRecord>,
    diagnosticReport: DiagnosticReport,
    onRunDiagnostics: () -> Unit,
    onApplySafeFix: () -> Unit,
    onClearStats: () -> Unit
) {
    val look = LocalGlassLook.current
    var subTab by remember { mutableStateOf(StatsSubTab.USAGE) }
    var period by remember { mutableStateOf(UsagePeriod.WEEKLY) }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 112.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Row(
                modifier = Modifier
                    .widthIn(max = 600.dp)
                    .fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = tr("Stats & Diagnostics", "آمار و عیب‌یابی"),
                    style = MaterialTheme.typography.headlineMedium,
                    color = look.textPrimary
                )
                if (subTab == StatsSubTab.USAGE && (dailyUsage.isNotEmpty() || sessions.isNotEmpty())) {
                    IconButton(onClick = onClearStats) {
                        Icon(Icons.Rounded.DeleteSweep, contentDescription = tr("Clear Stats", "پاک کردن آمار"), tint = look.textSecondary)
                    }
                }
            }
        }

        item {
            GlassSegmented(
                options = listOf(
                    StatsSubTab.USAGE to tr("Traffic & History", "مصرف و تاریخچه"),
                    StatsSubTab.DIAGNOSTICS to tr("Diagnostics", "عیب‌یابی")
                ),
                selected = subTab,
                onSelect = { subTab = it },
                modifier = Modifier
                    .widthIn(max = 600.dp)
                    .testTag("stats_subtab_selector")
            )
        }

        if (subTab == StatsSubTab.USAGE) {
            item {
                GlassCard(modifier = Modifier.widthIn(max = 600.dp).fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        GlassSegmented(
                            options = listOf(
                                UsagePeriod.DAILY to tr("Daily", "روزانه"),
                                UsagePeriod.WEEKLY to tr("7 Days", "۷ روز"),
                                UsagePeriod.MONTHLY to tr("30 Days", "۳۰ روز")
                            ),
                            selected = period,
                            onSelect = { period = it }
                        )
                        Spacer(modifier = Modifier.height(14.dp))

                        val slice = when (period) {
                            UsagePeriod.DAILY -> dailyUsage.takeLast(1)
                            UsagePeriod.WEEKLY -> dailyUsage.takeLast(7)
                            UsagePeriod.MONTHLY -> dailyUsage.takeLast(30)
                        }
                        val totalDown = slice.sumOf { it.downloadBytes }
                        val totalUp = slice.sumOf { it.uploadBytes }

                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Column {
                                Text(tr("Total Download", "کل دریافت"), style = MaterialTheme.typography.labelMedium, color = look.textSecondary)
                                Text(
                                    text = fmtDigits(formatBytes(totalDown)),
                                    style = MaterialTheme.typography.titleLarge,
                                    color = look.accent
                                )
                            }
                            Column(horizontalAlignment = Alignment.End) {
                                Text(tr("Total Upload", "کل ارسال"), style = MaterialTheme.typography.labelMedium, color = look.textSecondary)
                                Text(
                                    text = fmtDigits(formatBytes(totalUp)),
                                    style = MaterialTheme.typography.titleLarge,
                                    color = look.secondaryAccent
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(14.dp))
                        UsageBarChart(days = slice)
                    }
                }
            }

            item {
                Text(
                    text = tr("Recent Sessions", "نشست‌های اخیر"),
                    style = MaterialTheme.typography.titleMedium,
                    color = look.textPrimary,
                    modifier = Modifier.widthIn(max = 600.dp).fillMaxWidth().padding(top = 4.dp)
                )
            }

            if (sessions.isEmpty()) {
                item {
                    GlassCard(modifier = Modifier.widthIn(max = 600.dp).fillMaxWidth()) {
                        GlassRow(
                            title = tr("No completed sessions yet", "هنوز نشستی ثبت نشده است"),
                            subtitle = tr("Connect Nova VPN to track bandwidth and history", "برای ثبت ترافیک متصل شوید"),
                            icon = Icons.Rounded.History
                        )
                    }
                }
            } else {
                items(sessions, key = { it.id }) { session ->
                    val timeStr = remember(session.startedAtMs) {
                        DateTimeFormatter.ofPattern("MMM d, HH:mm")
                            .withZone(ZoneId.systemDefault())
                            .format(Instant.ofEpochMilli(session.startedAtMs))
                    }
                    GlassCard(modifier = Modifier.widthIn(max = 600.dp).fillMaxWidth()) {
                        GlassRow(
                            title = "${countryCodeToFlag(session.exitCountryCode)} ${session.profileName}",
                            subtitle = fmtDigits("$timeStr · ${formatDurationHms(session.durationSec)} · ↓ ${formatBytes(session.downloadBytes)} / ↑ ${formatBytes(session.uploadBytes)}"),
                            icon = Icons.Rounded.History
                        ) {
                            GlassBadge(text = session.routeChain.name, color = look.accent)
                        }
                    }
                }
            }
        } else {
            // DIAGNOSTICS TAB
            item {
                GlassCard(modifier = Modifier.widthIn(max = 600.dp).fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(tr("Network Health Check", "تست سلامت شبکه"), style = MaterialTheme.typography.titleMedium, color = look.textPrimary)
                                Text(
                                    tr("DNS leak, IPv6 leak, 3-endpoint latency & MTU probe", "بررسی نشت DNS/IPv6، تاخیر و MTU"),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = look.textSecondary
                                )
                            }
                            Button(
                                onClick = onRunDiagnostics,
                                enabled = !diagnosticReport.running,
                                colors = ButtonDefaults.buttonColors(containerColor = look.accent, contentColor = Color(0xFF041412)),
                                modifier = Modifier.testTag("run_diagnostics_button")
                            ) {
                                if (diagnosticReport.running) {
                                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp, color = Color(0xFF041412))
                                } else {
                                    Icon(Icons.Rounded.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                                }
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(tr("Run Test", "اجرای تست"))
                            }
                        }

                        if (diagnosticReport.timestampMs > 0L) {
                            GlassDivider()
                            val exitLabel = if (diagnosticReport.exitIp != null) {
                                "${countryCodeToFlag(diagnosticReport.exitCountry)} ${diagnosticReport.exitIp} ${diagnosticReport.exitCity?.let { "($it)" } ?: ""}"
                            } else {
                                tr("Unreachable", "در دسترس نیست")
                            }
                            GlassRow(
                                title = tr("Exit IP & Location", "آی‌پی خروجی"),
                                subtitle = exitLabel,
                                icon = Icons.Rounded.NetworkCheck
                            )
                            GlassDivider()
                            DiagnosticStatusRow(
                                title = tr("DNS Leak Check", "تست نشت DNS"),
                                summary = diagnosticReport.dnsSummary,
                                safe = diagnosticReport.dnsLeakSafe
                            )
                            GlassDivider()
                            DiagnosticStatusRow(
                                title = tr("IPv6 Leak Check", "تست نشت IPv6"),
                                summary = diagnosticReport.ipv6Summary,
                                safe = diagnosticReport.ipv6LeakSafe
                            )
                            GlassDivider()
                            Text(tr("Endpoint Latency", "تاخیر سرورها"), style = MaterialTheme.typography.labelLarge, color = look.textSecondary)
                            diagnosticReport.latencies.forEach { ep ->
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text("${ep.label} (${ep.host})", style = MaterialTheme.typography.bodyMedium, color = look.textPrimary)
                                    Text(
                                        text = if (ep.reachable && ep.latencyMs != null) fmtDigits("${ep.latencyMs} ms") else tr("Timeout", "تایم‌اوت"),
                                        style = MaterialTheme.typography.bodyMedium.copy(fontFamily = JetBrainsMonoFamily),
                                        color = if (ep.reachable) look.success else look.danger
                                    )
                                }
                            }
                            GlassDivider()
                            Text(
                                text = diagnosticReport.mtuSummary,
                                style = MaterialTheme.typography.bodySmall,
                                color = look.textSecondary
                            )

                            OutlinedButton(
                                onClick = onApplySafeFix,
                                modifier = Modifier.fillMaxWidth().testTag("fix_connection_button")
                            ) {
                                Icon(Icons.Rounded.Build, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(tr("Fix Connection (Apply Safe Profile)", "اصلاح خودکار اتصال (تنظیمات امن)"))
                            }
                            diagnosticReport.safeFixAppliedNote?.let { note ->
                                Text(note, style = MaterialTheme.typography.bodySmall, color = look.success)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DiagnosticStatusRow(title: String, summary: String, safe: Boolean?) {
    val look = LocalGlassLook.current
    GlassRow(
        title = title,
        subtitle = summary,
        icon = if (safe == true) Icons.Rounded.CheckCircle else Icons.Rounded.ErrorOutline,
        iconTint = if (safe == true) look.success else look.warning
    )
}

@Composable
private fun UsageBarChart(days: List<DataUsageDay>) {
    val look = LocalGlassLook.current
    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .height(120.dp)
    ) {
        if (days.isEmpty()) {
            drawLine(
                color = look.cardBorder.copy(alpha = 0.5f),
                start = Offset(0f, size.height - 6f),
                end = Offset(size.width, size.height - 6f),
                strokeWidth = 2f
            )
            return@Canvas
        }
        val maxBytes = days.maxOf { it.downloadBytes + it.uploadBytes }.coerceAtLeast(1024L).toFloat()
        val count = days.size.coerceAtLeast(1)
        val slotWidth = size.width / count
        val barWidth = (slotWidth * 0.52f).coerceIn(8f, 36f)

        days.forEachIndexed { idx, day ->
            val total = (day.downloadBytes + day.uploadBytes).toFloat()
            val h = (total / maxBytes) * (size.height - 16f)
            val x = idx * slotWidth + (slotWidth - barWidth) / 2f
            val y = size.height - h
            drawRoundRect(
                brush = Brush.verticalGradient(listOf(look.accent, look.secondaryAccent)),
                topLeft = Offset(x, y),
                size = Size(barWidth, h.coerceAtLeast(6f)),
                cornerRadius = CornerRadius(6.dp.toPx(), 6.dp.toPx())
            )
        }
    }
}
