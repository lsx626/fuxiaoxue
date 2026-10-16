package edu.fudan.elearning.sync.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.material3.Typography
import androidx.compose.material3.Shapes
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import androidx.compose.ui.graphics.Color

/** “复小学”主题色：靛蓝（Indigo）系，与桌面端 styles.py 保持一致。 */
val Indigo = Color(0xFF4F46E5)
val IndigoDark = Color(0xFF312E81)
val IndigoHover = Color(0xFF4338CA)
val IndigoContainer = Color(0xFFE0E3FF)
val AppBg = Color(0xFFF6F7FC)
val AppCard = Color(0xFFFFFFFF)

private val LightColors = lightColorScheme(
    primary = Indigo,
    onPrimary = Color.White,
    primaryContainer = IndigoContainer,
    onPrimaryContainer = Color(0xFF1E1B4B),
    secondary = IndigoHover,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFEBE8FF),
    onSecondaryContainer = Color(0xFF38305E),
    tertiary = Color(0xFF006C62),
    tertiaryContainer = Color(0xFFD7F4EC),
    onTertiaryContainer = Color(0xFF005047),
    background = Color(0xFFF7F8FC),
    onBackground = Color(0xFF202431),
    surface = AppCard,
    onSurface = Color(0xFF202431),
    onSurfaceVariant = Color(0xFF606779),
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color(0xFFF1F3F9),
    surfaceContainer = Color(0xFFECEEF6),
    surfaceContainerHigh = Color(0xFFE5E8F2),
    outline = Color(0xFF747B8C),
    outlineVariant = Color(0xFFE1E5EF)
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF9FA8FF),
    onPrimary = Color(0xFF1E1B4B),
    primaryContainer = Color(0xFF3730A3),
    secondary = Color(0xFFC7D2FE),
    onSecondary = Color(0xFF29233F),
    onPrimaryContainer = Color(0xFFE7E2FF),
    secondaryContainer = Color(0xFF302D46),
    onSecondaryContainer = Color(0xFFE8E1FF),
    tertiary = Color(0xFF86D6C5),
    tertiaryContainer = Color(0xFF123E36),
    onTertiaryContainer = Color(0xFFB4EFDF),
    background = Color(0xFF10131B),
    onBackground = Color(0xFFE7E9F3),
    surface = Color(0xFF191D28),
    onSurface = Color(0xFFE7E9F3),
    onSurfaceVariant = Color(0xFFB1B7C8),
    surfaceContainerLowest = Color(0xFF141720),
    surfaceContainerLow = Color(0xFF1C202C),
    surfaceContainer = Color(0xFF232837),
    surfaceContainerHigh = Color(0xFF2C3243),
    outline = Color(0xFF858DA2),
    outlineVariant = Color(0xFF343B4D)
)

private val AppTypography = Typography(
    headlineLarge = TextStyle(fontWeight = FontWeight.Bold, fontSize = 30.sp, lineHeight = 38.sp),
    headlineMedium = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 25.sp, lineHeight = 34.sp),
    headlineSmall = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 22.sp, lineHeight = 30.sp),
    titleLarge = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 20.sp, lineHeight = 28.sp),
    titleMedium = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 16.sp, lineHeight = 24.sp),
    titleSmall = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 14.sp, lineHeight = 22.sp),
    bodyLarge = TextStyle(fontSize = 16.sp, lineHeight = 26.sp),
    bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 22.sp),
    bodySmall = TextStyle(fontSize = 12.sp, lineHeight = 19.sp),
    labelLarge = TextStyle(fontWeight = FontWeight.Medium, fontSize = 14.sp, lineHeight = 20.sp),
    labelMedium = TextStyle(fontWeight = FontWeight.Medium, fontSize = 12.sp, lineHeight = 18.sp)
)

@Composable
fun FuXiaoXueTheme(mode: ThemeMode = ThemeMode.SYSTEM, content: @Composable () -> Unit) {
    val dark = when (mode) { ThemeMode.SYSTEM -> isSystemInDarkTheme(); ThemeMode.LIGHT -> false; ThemeMode.DARK -> true }
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
        shapes = Shapes(small = RoundedCornerShape(10.dp), medium = RoundedCornerShape(16.dp),
            large = RoundedCornerShape(24.dp), extraLarge = RoundedCornerShape(28.dp)),
        content = content
    )
}
