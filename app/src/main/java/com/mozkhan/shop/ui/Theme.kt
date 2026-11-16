package com.mozkhan.shop.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalLayoutDirection
import com.mozkhan.shop.R

val Vazir = FontFamily(
    Font(R.font.vazir_regular, FontWeight.Normal),
    Font(R.font.vazir_medium, FontWeight.Medium),
    Font(R.font.vazir_bold, FontWeight.Bold),
)

/** Semantic colours that Material3 does not define. */
data class Extra(
    val profit: Color, val loss: Color, val warn: Color, val cardAlt: Color,
    val gradA: Color, val gradB: Color,
)

val LocalExtra = staticCompositionLocalOf {
    Extra(Color.Unspecified, Color.Unspecified, Color.Unspecified, Color.Unspecified, Color.Unspecified, Color.Unspecified)
}

private val Indigo = Color(0xFF4F46E5)
private val LightScheme: ColorScheme = lightColorScheme(
    primary = Indigo, onPrimary = Color.White,
    primaryContainer = Color(0xFFE0E7FF), onPrimaryContainer = Color(0xFF1E1B4B),
    secondary = Color(0xFF0EA5E9), onSecondary = Color.White,
    secondaryContainer = Color(0xFFE0F2FE), onSecondaryContainer = Color(0xFF082F49),
    tertiary = Color(0xFFF59E0B),
    background = Color(0xFFF6F7FB), onBackground = Color(0xFF0F172A),
    surface = Color.White, onSurface = Color(0xFF0F172A),
    surfaceVariant = Color(0xFFEEF0F7), onSurfaceVariant = Color(0xFF5B6478),
    surfaceContainer = Color(0xFFF1F3F9), surfaceContainerHigh = Color(0xFFE9ECF5),
    outline = Color(0xFFCBD2E1), outlineVariant = Color(0xFFE2E6F0),
    error = Color(0xFFDC2626),
)
private val DarkScheme: ColorScheme = darkColorScheme(
    primary = Color(0xFF8B8CFF), onPrimary = Color(0xFF14123D),
    primaryContainer = Color(0xFF2B2A6B), onPrimaryContainer = Color(0xFFE0E7FF),
    secondary = Color(0xFF38BDF8), onSecondary = Color(0xFF04202F),
    secondaryContainer = Color(0xFF0C3550), onSecondaryContainer = Color(0xFFE0F2FE),
    tertiary = Color(0xFFFBBF24),
    background = Color(0xFF0B1020), onBackground = Color(0xFFE7EAF3),
    surface = Color(0xFF141A2E), onSurface = Color(0xFFE7EAF3),
    surfaceVariant = Color(0xFF1C2440), onSurfaceVariant = Color(0xFF9AA4BD),
    surfaceContainer = Color(0xFF182038), surfaceContainerHigh = Color(0xFF1F2847),
    outline = Color(0xFF3A4568), outlineVariant = Color(0xFF263050),
    error = Color(0xFFF87171),
)

private fun t(size: Int, w: FontWeight = FontWeight.Normal, lh: Int = (size * 1.55).toInt()) =
    TextStyle(fontFamily = Vazir, fontWeight = w, fontSize = size.sp, lineHeight = lh.sp, textDirection = TextDirection.Content)

private val AppTypography = Typography(
    displayLarge = t(40, FontWeight.Bold), displayMedium = t(34, FontWeight.Bold), displaySmall = t(28, FontWeight.Bold),
    headlineLarge = t(26, FontWeight.Bold), headlineMedium = t(22, FontWeight.Bold), headlineSmall = t(20, FontWeight.Bold),
    titleLarge = t(19, FontWeight.Bold), titleMedium = t(16, FontWeight.Medium), titleSmall = t(14, FontWeight.Medium),
    bodyLarge = t(16), bodyMedium = t(14), bodySmall = t(12),
    labelLarge = t(14, FontWeight.Medium), labelMedium = t(12, FontWeight.Medium), labelSmall = t(11, FontWeight.Medium),
)

private val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp), small = RoundedCornerShape(12.dp), medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(22.dp), extraLarge = RoundedCornerShape(28.dp),
)

@Composable
fun ShopTheme(dark: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    val extra = if (dark) Extra(Color(0xFF34D399), Color(0xFFF87171), Color(0xFFFBBF24), Color(0xFF1B2342), Color(0xFF5B5BF0), Color(0xFF22A6E8))
    else Extra(Color(0xFF059669), Color(0xFFDC2626), Color(0xFFD97706), Color(0xFFF1F3F9), Color(0xFF4F46E5), Color(0xFF0EA5E9))
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl, LocalExtra provides extra) {
        MaterialTheme(colorScheme = if (dark) DarkScheme else LightScheme, typography = AppTypography, shapes = AppShapes, content = content)
    }
}

val MaterialTheme.extra: Extra @Composable get() = LocalExtra.current
