package com.example.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

private val DarkColorScheme = darkColorScheme(
    primary = NeonBluePrimary,
    secondary = NeonPurpleSecondary,
    tertiary = ElectricPinkAccent,
    background = SlateDarkBackground,
    surface = SlateDarkSurface,
    surfaceVariant = SlateDarkSurfaceVariant,
    onBackground = SlateOnBackground,
    onSurface = SlateOnSurface
)

private val LightColorScheme = DarkColorScheme // Keep music app in dark mode for premium vibe

@Composable
fun MyApplicationTheme(
  darkTheme: Boolean = true,
  dynamicColor: Boolean = false, // Use our gorgeous custom branding colors
  content: @Composable () -> Unit,
) {
  val colorScheme = if (darkTheme) DarkColorScheme else DarkColorScheme

  MaterialTheme(colorScheme = colorScheme, typography = Typography, content = content)
}
