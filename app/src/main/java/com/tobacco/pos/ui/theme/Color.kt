package com.tobacco.pos.ui.theme

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

val AmberPrimary = Color(0xFFFFB300)
val AmberOnPrimary = Color(0xFF261900)
val AmberContainer = Color(0xFF473100)
val AmberOnContainer = Color(0xFFFFDF9E)

val DarkSurface = Color(0xFF141416)
val DarkSurfaceVariant = Color(0xFF212124)
val DarkBackground = Color(0xFF0D0E10)
val DarkOnBackground = Color(0xFFE4E2E6)

val SuccessGreen = Color(0xFF4CAF50)
val DangerRed = Color(0xFFEF5350)
val InfoBlue = Color(0xFF29B6F6)

val DarkColorScheme = darkColorScheme(
    primary = AmberPrimary,
    onPrimary = AmberOnPrimary,
    primaryContainer = AmberContainer,
    onPrimaryContainer = AmberOnContainer,
    background = DarkBackground,
    onBackground = DarkOnBackground,
    surface = DarkSurface,
    onSurface = DarkOnBackground,
    surfaceVariant = DarkSurfaceVariant,
    error = DangerRed
)

val LightColorScheme = lightColorScheme(
    primary = Color(0xFF8A5100),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFFFDCBE),
    background = Color(0xFFFDF8F4),
    surface = Color.White,
    onSurface = Color(0xFF1E1B16)
)
