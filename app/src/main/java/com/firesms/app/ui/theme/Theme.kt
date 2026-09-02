package com.firesms.app.ui.theme

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
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
    primary = Color(0xFF356B61),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD7ECE5),
    onPrimaryContainer = Color(0xFF143C34),
    secondary = Color(0xFF58645F),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFDDE5E1),
    onSecondaryContainer = Color(0xFF25332E),
    tertiary = Color(0xFF8A6417),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFFFE7B0),
    onTertiaryContainer = Color(0xFF2B1B00),
    error = Color(0xFFB44343),
    errorContainer = Color(0xFFFFDAD7),
    background = Color(0xFFF6F8F7),
    onBackground = Color(0xFF18211E),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF18211E),
    surfaceVariant = Color(0xFFEAF0ED),
    onSurfaceVariant = Color(0xFF53605B),
    outline = Color(0xFF87938E),
    outlineVariant = Color(0xFFD4DDD9)
)

private val DarkColorScheme = darkColorScheme(
    primary = Color(0xFF82CDB5),
    onPrimary = Color(0xFF00382D),
    primaryContainer = Color(0xFF29483F),
    onPrimaryContainer = Color(0xFFC0F1E1),
    secondary = Color(0xFFB8C7C0),
    onSecondary = Color(0xFF26332E),
    secondaryContainer = Color(0xFF34423C),
    onSecondaryContainer = Color(0xFFD4E4DC),
    tertiary = Color(0xFFE9C26C),
    onTertiary = Color(0xFF3E2E00),
    tertiaryContainer = Color(0xFF5B4400),
    onTertiaryContainer = Color(0xFFFFE3A3),
    error = Color(0xFFFFB3AD),
    errorContainer = Color(0xFF8C1D23),
    background = Color(0xFF101513),
    onBackground = Color(0xFFE5ECE8),
    surface = Color(0xFF171E1B),
    onSurface = Color(0xFFE5ECE8),
    surfaceVariant = Color(0xFF202A26),
    onSurfaceVariant = Color(0xFFAAB5B0),
    outline = Color(0xFF83908A),
    outlineVariant = Color(0xFF3E4A45)
)
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
        fontSize = 12.sp,
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
            window.statusBarColor = android.graphics.Color.TRANSPARENT
            window.navigationBarColor = android.graphics.Color.TRANSPARENT
            val controller = WindowCompat.getInsetsController(window, view)
            controller.isAppearanceLightStatusBars = !darkTheme
            controller.isAppearanceLightNavigationBars = !darkTheme
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = FireSmsTypography,
        shapes = FireSmsShapes,
        content = content
    )
}
