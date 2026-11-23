package app.novavpn.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import app.novavpn.domain.model.AppLanguage
import app.novavpn.domain.model.DigitMode
import java.util.Locale

val LocalAppLanguage = compositionLocalOf { AppLanguage.EN }
val LocalDigitMode = compositionLocalOf { DigitMode.LOCALE }

@Composable
fun isPersianUi(): Boolean {
    val lang = LocalAppLanguage.current
    return when (lang) {
        AppLanguage.FA -> true
        AppLanguage.EN -> false
        AppLanguage.SYSTEM -> Locale.getDefault().language.startsWith("fa", ignoreCase = true)
    }
}

@Composable
fun tr(en: String, fa: String): String {
    return if (isPersianUi()) fa else en
}

@Composable
fun fmtDigits(raw: String): String {
    val mode = LocalDigitMode.current
    val faUi = isPersianUi()
    val usePersian = when (mode) {
        DigitMode.PERSIAN -> true
        DigitMode.LATIN -> false
        DigitMode.LOCALE -> faUi
    }
    return if (usePersian) toPersianDigits(raw) else raw
}

fun toPersianDigits(input: String): String {
    val sb = StringBuilder(input.length)
    for (ch in input) {
        sb.append(
            when (ch) {
                '0' -> '۰'
                '1' -> '۱'
                '2' -> '۲'
                '3' -> '۳'
                '4' -> '۴'
                '5' -> '۵'
                '6' -> '۶'
                '7' -> '۷'
                '8' -> '۸'
                '9' -> '۹'
                else -> ch
            }
        )
    }
    return sb.toString()
}

fun formatDurationHms(totalSec: Long): String {
    val s = totalSec.coerceAtLeast(0L)
    val h = s / 3600L
    val m = (s % 3600L) / 60L
    val rem = s % 60L
    return if (h > 0) {
        String.format(Locale.US, "%02d:%02d:%02d", h, m, rem)
    } else {
        String.format(Locale.US, "%02d:%02d", m, rem)
    }
}

fun formatBytes(bytes: Long): String {
    val b = bytes.coerceAtLeast(0L)
    return when {
        b < 1024L -> "$b B"
        b < 1024L * 1024L -> String.format(Locale.US, "%.1f KB", b / 1024.0)
        b < 1024L * 1024L * 1024L -> String.format(Locale.US, "%.2f MB", b / (1024.0 * 1024.0))
        else -> String.format(Locale.US, "%.2f GB", b / (1024.0 * 1024.0 * 1024.0))
    }
}

fun formatSpeedRate(bps: Long): String {
    val b = bps.coerceAtLeast(0L)
    return when {
        b < 1024L -> "$b B/s"
        b < 1024L * 1024L -> String.format(Locale.US, "%.1f KB/s", b / 1024.0)
        else -> String.format(Locale.US, "%.2f MB/s", b / (1024.0 * 1024.0))
    }
}

fun countryCodeToFlag(code: String?): String {
    if (code == null || code.length != 2) return "🌐"
    val upper = code.uppercase(Locale.US)
    val first = Character.codePointAt(upper, 0) - 0x41 + 0x1F1E6
    val second = Character.codePointAt(upper, 1) - 0x41 + 0x1F1E6
    return String(Character.toChars(first)) + String(Character.toChars(second))
}
