package com.firesms.app.ui.theme

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.material3.Typography
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat

private val LightColorScheme = lightColorScheme(
    primary = Color(0xFF1A73E8),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD8E7FF),
    onPrimaryContainer = Color(0xFF0B3D91),
    secondary = Color(0xFF4B607C),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFDCE6F7),
    onSecondaryContainer = Color(0xFF142840),
    tertiary = Color(0xFF0077B6),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFCFEFFF),
    onTertiaryContainer = Color(0xFF00344F),
    error = Color(0xFFB3261E),
    onError = Color.White,
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),
    background = Color(0xFFF6F9FF),
    onBackground = Color(0xFF121C2B),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF121C2B),
    surfaceVariant = Color(0xFFE9F1FF),
    onSurfaceVariant = Color(0xFF526174),
    outline = Color(0xFF8190A4),
    outlineVariant = Color(0xFFD2DEEF),
    inversePrimary = Color(0xFFA8C7FA),
    surfaceTint = Color(0xFF1A73E8),
    surfaceDim = Color(0xFFD6DCE6),
    surfaceBright = Color(0xFFF9FBFF),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF1F5FC),
    surfaceContainer = Color(0xFFEAF0F9),
    surfaceContainerHigh = Color(0xFFE3EAF4),
    surfaceContainerHighest = Color(0xFFDCE4EF),
    inverseSurface = Color(0xFF27313F),
    inverseOnSurface = Color(0xFFEDF1FA),
    scrim = Color(0xFF000000)
)

private val DarkColorScheme = darkColorScheme(
    primary = Color(0xFFA8C7FA),
    onPrimary = Color(0xFF003063),
    primaryContainer = Color(0xFF0F4FA8),
    onPrimaryContainer = Color(0xFFD8E7FF),
    secondary = Color(0xFFC0CBDD),
    onSecondary = Color(0xFF273244),
    secondaryContainer = Color(0xFF37445A),
    onSecondaryContainer = Color(0xFFDCE6F7),
    tertiary = Color(0xFF91D8FF),
    onTertiary = Color(0xFF00344F),
    tertiaryContainer = Color(0xFF00577F),
    onTertiaryContainer = Color(0xFFCFEFFF),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
    background = Color(0xFF0A101A),
    onBackground = Color(0xFFE7EEFB),
    surface = Color(0xFF101827),
    onSurface = Color(0xFFE7EEFB),
    surfaceVariant = Color(0xFF1A2638),
    onSurfaceVariant = Color(0xFFC2CAD8),
    outline = Color(0xFF8B96A8),
    outlineVariant = Color(0xFF37445A),
    inversePrimary = Color(0xFF1A73E8),
    surfaceTint = Color(0xFFA8C7FA),
    surfaceDim = Color(0xFF0A101A),
    surfaceBright = Color(0xFF2A3446),
    surfaceContainerLowest = Color(0xFF070C14),
    surfaceContainerLow = Color(0xFF121A28),
    surfaceContainer = Color(0xFF16202F),
    surfaceContainerHigh = Color(0xFF1F2A3B),
    surfaceContainerHighest = Color(0xFF2A3547),
    inverseSurface = Color(0xFFE7EEFB),
    inverseOnSurface = Color(0xFF27313F),
    scrim = Color(0xFF000000)
)

/** Status colours Material 3 has no role for: green for synced, amber for needs-attention. */
@Immutable
data class FireSmsExtendedColors(
    val success: Color,
    val onSuccess: Color,
    val successContainer: Color,
    val onSuccessContainer: Color,
    val warning: Color,
    val onWarning: Color,
    val warningContainer: Color,
    val onWarningContainer: Color
)

private val LightExtendedColors = FireSmsExtendedColors(
    success = Color(0xFF1E7D32),
    onSuccess = Color.White,
    successContainer = Color(0xFFD4F0D9),
    onSuccessContainer = Color(0xFF0A3814),
    warning = Color(0xFF8A5100),
    onWarning = Color.White,
    warningContainer = Color(0xFFFFE0B8),
    onWarningContainer = Color(0xFF2D1600)
)

private val DarkExtendedColors = FireSmsExtendedColors(
    success = Color(0xFF8BD89A),
    onSuccess = Color(0xFF00390F),
    successContainer = Color(0xFF1B5E2A),
    onSuccessContainer = Color(0xFFD4F0D9),
    warning = Color(0xFFFFB95C),
    onWarning = Color(0xFF4A2800),
    warningContainer = Color(0xFF6A3C00),
    onWarningContainer = Color(0xFFFFE0B8)
)

val LocalFireSmsColors = staticCompositionLocalOf { LightExtendedColors }

/** Access as `FireSmsTheme.colors.success` etc. */
object FireSmsTheme {
    val colors: FireSmsExtendedColors
        @Composable @ReadOnlyComposable get() = LocalFireSmsColors.current
}
private val FireSmsTypography = Typography(
    headlineMedium = TextStyle(
        fontWeight = FontWeight.SemiBold,
        fontSize = 28.sp,
        lineHeight = 36.sp,
        letterSpacing = 0.sp
    ),
    headlineSmall = TextStyle(
        fontWeight = FontWeight.SemiBold,
        fontSize = 24.sp,
        lineHeight = 32.sp,
        letterSpacing = 0.sp
    ),
    titleLarge = TextStyle(
        fontWeight = FontWeight.SemiBold,
        fontSize = 22.sp,
        lineHeight = 28.sp,
        letterSpacing = 0.sp
    ),
    titleMedium = TextStyle(
        fontWeight = FontWeight.SemiBold,
        fontSize = 16.sp,
        lineHeight = 24.sp,
        letterSpacing = 0.15.sp
    ),
    titleSmall = TextStyle(
        fontWeight = FontWeight.Medium,
        fontSize = 14.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.1.sp
    ),
    bodyLarge = TextStyle(
        fontWeight = FontWeight.Normal,
        fontSize = 16.sp,
        lineHeight = 24.sp,
        letterSpacing = 0.5.sp
    ),
    bodyMedium = TextStyle(
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.25.sp
    ),
    bodySmall = TextStyle(
        fontWeight = FontWeight.Normal,
        fontSize = 13.sp,
        lineHeight = 18.sp,
        letterSpacing = 0.4.sp
    ),
    labelLarge = TextStyle(
        fontWeight = FontWeight.Medium,
        fontSize = 14.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.1.sp
    ),
    labelMedium = TextStyle(
        fontWeight = FontWeight.Medium,
        fontSize = 12.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.5.sp
    ),
    labelSmall = TextStyle(
        fontWeight = FontWeight.Medium,
        fontSize = 11.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.5.sp
    )
)


private val FireSmsShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(20.dp),
    extraLarge = RoundedCornerShape(28.dp)
)

@Composable
fun FireSMSTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> DarkColorScheme
        else -> LightColorScheme
    }

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            // MainActivity.enableEdgeToEdge() handles system-bar backgrounds across API levels.
            val controller = WindowCompat.getInsetsController(window, view)
            controller.isAppearanceLightStatusBars = !darkTheme
            controller.isAppearanceLightNavigationBars = !darkTheme
        }
    }

    CompositionLocalProvider(
        LocalFireSmsColors provides if (darkTheme) DarkExtendedColors else LightExtendedColors
    ) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = FireSmsTypography,
            shapes = FireSmsShapes,
            content = content
        )
    }
}
