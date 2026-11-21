package com.codecapture.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val DarkColorScheme = darkColorScheme(
    primary = Color(0xFF4FC3F7),
    onPrimary = Color(0xFF002736),
    secondary = Color(0xFF81C784),
    onSecondary = Color(0xFF00290A),
    tertiary = Color(0xFFFFB74D),
    background = Color(0xFF0E141B),
    surface = Color(0xFF161F29),
    surfaceVariant = Color(0xFF222F3D),
    onBackground = Color(0xFFE6EDF3),
    onSurface = Color(0xFFE6EDF3),
    error = Color(0xFFFF6E6E)
)

@Composable
fun CodeCaptureTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = DarkColorScheme,
        content = content
    )
}
