package com.docscan.android.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val LightColors = lightColorScheme(
    primary = Color(0xFF137C70), onPrimary = Color.White,
    primaryContainer = Color(0xFFD9F1E9), onPrimaryContainer = Color(0xFF134C43),
    secondary = Color(0xFF526761), background = Color(0xFFF5F8F6),
    surface = Color(0xFFFFFFFF), surfaceVariant = Color(0xFFEAF0EC),
    onBackground = Color(0xFF182D2A), onSurface = Color(0xFF182D2A),
)
private val DarkColors = darkColorScheme(
    primary = Color(0xFF8AD5C5), onPrimary = Color(0xFF00382F),
    primaryContainer = Color(0xFF145145), onPrimaryContainer = Color(0xFFB9EFDF),
    background = Color(0xFF101C19), surface = Color(0xFF182520),
    surfaceVariant = Color(0xFF263831), onSurface = Color(0xFFE2EEE8),
)

@Composable
fun DocScanTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (isSystemInDarkTheme()) DarkColors else LightColors, content = content)
}
