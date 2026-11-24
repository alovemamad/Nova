package app.novavpn.ui.theme

import android.content.Context
import android.content.ContextWrapper
import android.content.res.Configuration
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.fragment.app.FragmentActivity
import app.novavpn.domain.model.AppLanguage
import app.novavpn.domain.model.AppSettings
import app.novavpn.domain.model.AppThemeMode
import app.novavpn.ui.glass.GlassThemeProvider
import com.example.R
import java.util.Locale

val LocalHostActivity = compositionLocalOf<FragmentActivity?> { null }

val NovaTeal = Color(0xFF00E5CC)
val NovaViolet = Color(0xFF7C5CFF)
val NovaNavyDeep = Color(0xFF070B14)
val NovaSurfaceDark = Color(0xFF0E1525)
val NovaSurfaceVariantDark = Color(0xFF172035)
val NovaLightBg = Color(0xFFF3F6FC)
val NovaLightSurface = Color(0xFFFFFFFF)

val PlusJakartaFamily = FontFamily(
    Font(R.font.plus_jakarta_sans, FontWeight.Normal),
    Font(R.font.plus_jakarta_sans, FontWeight.Medium),
    Font(R.font.plus_jakarta_sans, FontWeight.SemiBold),
    Font(R.font.plus_jakarta_sans, FontWeight.Bold)
)

val VazirmatnFamily = FontFamily(
    Font(R.font.vazirmatn, FontWeight.Normal),
    Font(R.font.vazirmatn, FontWeight.Medium),
    Font(R.font.vazirmatn, FontWeight.SemiBold),
    Font(R.font.vazirmatn, FontWeight.Bold)
)

val JetBrainsMonoFamily = FontFamily(
    Font(R.font.jetbrains_mono, FontWeight.Normal),
    Font(R.font.jetbrains_mono, FontWeight.Medium)
)

private val NovaDarkColorScheme = darkColorScheme(
    primary = NovaTeal,
    onPrimary = Color(0xFF00201C),
    primaryContainer = Color(0xFF004F45),
    onPrimaryContainer = Color(0xFFA7FFF1),
    secondary = NovaViolet,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFF2D235C),
    onSecondaryContainer = Color(0xFFE3DCFF),
    tertiary = Color(0xFF38BDF8),
    background = NovaNavyDeep,
    onBackground = Color(0xFFF1F5FB),
    surface = NovaSurfaceDark,
    onSurface = Color(0xFFF1F5FB),
    surfaceVariant = NovaSurfaceVariantDark,
    onSurfaceVariant = Color(0xFFB3BFD4),
    outline = Color(0xFF2B3854),
    error = Color(0xFFFF5C77),
    onError = Color.White
)

private val NovaLightColorScheme = lightColorScheme(
    primary = Color(0xFF008777),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFB5F7EA),
    onPrimaryContainer = Color(0xFF00201B),
    secondary = Color(0xFF5D3FD3),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFE8E2FF),
    onSecondaryContainer = Color(0xFF1A0E4D),
    tertiary = Color(0xFF0284C7),
    background = NovaLightBg,
    onBackground = Color(0xFF0D1422),
    surface = NovaLightSurface,
    onSurface = Color(0xFF0D1422),
    surfaceVariant = Color(0xFFE4EAF5),
    onSurfaceVariant = Color(0xFF4B5873),
    outline = Color(0xFFC5D0E4),
    error = Color(0xFFD92D4E),
    onError = Color.White
)

private val NovaShapes = Shapes(
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(20.dp),
    large = RoundedCornerShape(26.dp),
    extraLarge = RoundedCornerShape(32.dp)
)

private fun buildTypography(isFa: Boolean): Typography {
    val bodyFamily = if (isFa) VazirmatnFamily else PlusJakartaFamily
    return Typography(
        displaySmall = TextStyle(fontFamily = bodyFamily, fontWeight = FontWeight.Bold, fontSize = 32.sp, lineHeight = 38.sp),
        headlineMedium = TextStyle(fontFamily = bodyFamily, fontWeight = FontWeight.Bold, fontSize = 24.sp, lineHeight = 30.sp),
        headlineSmall = TextStyle(fontFamily = bodyFamily, fontWeight = FontWeight.SemiBold, fontSize = 20.sp, lineHeight = 26.sp),
        titleLarge = TextStyle(fontFamily = bodyFamily, fontWeight = FontWeight.SemiBold, fontSize = 18.sp, lineHeight = 24.sp),
        titleMedium = TextStyle(fontFamily = bodyFamily, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, lineHeight = 20.sp),
        bodyLarge = TextStyle(fontFamily = bodyFamily, fontWeight = FontWeight.Normal, fontSize = 15.sp, lineHeight = 22.sp),
        bodyMedium = TextStyle(fontFamily = bodyFamily, fontWeight = FontWeight.Normal, fontSize = 13.5.sp, lineHeight = 19.sp),
        bodySmall = TextStyle(fontFamily = bodyFamily, fontWeight = FontWeight.Normal, fontSize = 12.sp, lineHeight = 16.sp),
        labelLarge = TextStyle(fontFamily = bodyFamily, fontWeight = FontWeight.SemiBold, fontSize = 13.5.sp, lineHeight = 18.sp),
        labelMedium = TextStyle(fontFamily = bodyFamily, fontWeight = FontWeight.Medium, fontSize = 12.sp, lineHeight = 16.sp),
        labelSmall = TextStyle(fontFamily = bodyFamily, fontWeight = FontWeight.Medium, fontSize = 11.sp, lineHeight = 14.sp)
    )
}

private tailrec fun Context.findFragmentActivity(): FragmentActivity? = when (this) {
    is FragmentActivity -> this
    is ContextWrapper -> baseContext.findFragmentActivity()
    else -> null
}

@Composable
fun NovaVpnTheme(
    settings: AppSettings,
    content: @Composable () -> Unit
) {
    val isDark = when (settings.themeMode) {
        AppThemeMode.DARK -> true
        AppThemeMode.LIGHT -> false
        AppThemeMode.SYSTEM -> isSystemInDarkTheme()
    }
    val baseContext = LocalContext.current
    val hostActivity = remember(baseContext) { baseContext.findFragmentActivity() }

    val colorScheme = when {
        settings.dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            if (isDark) dynamicDarkColorScheme(baseContext) else dynamicLightColorScheme(baseContext)
        }
        isDark -> NovaDarkColorScheme
        else -> NovaLightColorScheme
    }

    val targetLocale = when (settings.language) {
        AppLanguage.FA -> Locale("fa")
        AppLanguage.EN -> Locale.ENGLISH
        AppLanguage.SYSTEM -> Locale.getDefault()
    }
    val isFa = targetLocale.language.startsWith("fa", ignoreCase = true)
    val direction = if (isFa) LayoutDirection.Rtl else LayoutDirection.Ltr

    val localizedContext = remember(baseContext, targetLocale) {
        val config = Configuration(baseContext.resources.configuration).apply {
            setLocale(targetLocale)
            setLayoutDirection(targetLocale)
        }
        baseContext.createConfigurationContext(config)
    }

    CompositionLocalProvider(
        LocalHostActivity provides hostActivity,
        LocalContext provides localizedContext,
        LocalConfiguration provides localizedContext.resources.configuration,
        LocalAppLanguage provides settings.language,
        LocalDigitMode provides settings.digitMode,
        LocalLayoutDirection provides direction
    ) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = buildTypography(isFa),
            shapes = NovaShapes
        ) {
            GlassThemeProvider(
                isDark = isDark,
                glassPrefs = settings.glass,
                content = content
            )
        }
    }
}
