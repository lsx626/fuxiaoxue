package edu.fudan.elearning.sync.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat

/** v1.3 后的“书页蓝”设计系统：清晰、克制，并用青绿色区分完成状态。 */
val Indigo = Color(0xFF2457D6)
val IndigoDark = Color(0xFF102A56)
val IndigoHover = Color(0xFF1948BD)
val IndigoContainer = Color(0xFFDCE7FF)
val AppBg = Color(0xFFF5F7FB)
val AppCard = Color(0xFFFFFFFF)

private val LightColors =
    lightColorScheme(
        primary = Color(0xFF2457D6),
        onPrimary = Color.White,
        primaryContainer = IndigoContainer,
        onPrimaryContainer = Color(0xFF102A56),
        secondary = Color(0xFF0A7C79),
        onSecondary = Color.White,
        secondaryContainer = Color(0xFFD8F2EF),
        onSecondaryContainer = Color(0xFF074B49),
        tertiary = Color(0xFFB85C00),
        tertiaryContainer = Color(0xFFFFE2C2),
        onTertiaryContainer = Color(0xFF673100),
        background = AppBg,
        onBackground = Color(0xFF171B24),
        surface = AppCard,
        onSurface = Color(0xFF171B24),
        onSurfaceVariant = Color(0xFF5E6878),
        surfaceContainerLowest = Color.White,
        surfaceContainerLow = Color(0xFFF0F3F9),
        surfaceContainer = Color(0xFFE8EDF6),
        surfaceContainerHigh = Color(0xFFDDE4F0),
        outline = Color(0xFF7A8494),
        outlineVariant = Color(0xFFDCE2EC),
    )

private val DarkColors =
    darkColorScheme(
        primary = Color(0xFFAEC6FF),
        onPrimary = Color(0xFF08224C),
        primaryContainer = Color(0xFF173E88),
        secondary = Color(0xFF7DD9D3),
        onSecondary = Color(0xFF003735),
        onPrimaryContainer = Color(0xFFDCE7FF),
        secondaryContainer = Color(0xFF124E4C),
        onSecondaryContainer = Color(0xFFB8F2ED),
        tertiary = Color(0xFFFFB86F),
        tertiaryContainer = Color(0xFF673A0E),
        onTertiaryContainer = Color(0xFFFFDFC1),
        background = Color(0xFF0D1118),
        onBackground = Color(0xFFE8ECF4),
        surface = Color(0xFF151A23),
        onSurface = Color(0xFFE8ECF4),
        onSurfaceVariant = Color(0xFFADB6C6),
        surfaceContainerLowest = Color(0xFF10141C),
        surfaceContainerLow = Color(0xFF1A202B),
        surfaceContainer = Color(0xFF212936),
        surfaceContainerHigh = Color(0xFF2A3443),
        outline = Color(0xFF7E899A),
        outlineVariant = Color(0xFF35404F),
    )

private val AppTypography =
    Typography(
        headlineLarge =
            TextStyle(fontWeight = FontWeight.Bold, fontSize = 32.sp, lineHeight = 40.sp),
        headlineMedium =
            TextStyle(fontWeight = FontWeight.Bold, fontSize = 26.sp, lineHeight = 34.sp),
        headlineSmall =
            TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 22.sp, lineHeight = 30.sp),
        titleLarge =
            TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 20.sp, lineHeight = 28.sp),
        titleMedium =
            TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 16.sp, lineHeight = 24.sp),
        titleSmall =
            TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 14.sp, lineHeight = 22.sp),
        bodyLarge = TextStyle(fontSize = 16.sp, lineHeight = 26.sp),
        bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 22.sp),
        bodySmall = TextStyle(fontSize = 12.sp, lineHeight = 19.sp),
        labelLarge =
            TextStyle(fontWeight = FontWeight.Medium, fontSize = 14.sp, lineHeight = 20.sp),
        labelMedium =
            TextStyle(fontWeight = FontWeight.Medium, fontSize = 12.sp, lineHeight = 18.sp),
    )

@Composable
fun FuXiaoXueTheme(mode: ThemeMode = ThemeMode.SYSTEM, content: @Composable () -> Unit) {
    val dark =
        when (mode) {
            ThemeMode.SYSTEM -> isSystemInDarkTheme()
            ThemeMode.LIGHT -> false
            ThemeMode.DARK -> true
        }
    val view = LocalView.current
    SideEffect {
        (view.context as? android.app.Activity)?.window?.let { window ->
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = !dark
                isAppearanceLightNavigationBars = !dark
            }
        }
    }
    MaterialTheme(
        colorScheme = if (dark) DarkColors else LightColors,
        typography = AppTypography,
        shapes =
            Shapes(
                small = RoundedCornerShape(8.dp),
                medium = RoundedCornerShape(14.dp),
                large = RoundedCornerShape(20.dp),
                extraLarge = RoundedCornerShape(26.dp),
            ),
        content = content,
    )
}
