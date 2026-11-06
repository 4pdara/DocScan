package com.docscan.rebuild.ui

import android.app.Activity
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.core.view.WindowCompat

val ScanTeal = Color(0xFF008F86)
val ScanMint = Color(0xFF28C7B5)
val ScanCharcoal = Color(0xFF1B1D1E)

@Composable
fun ScannerScreenTheme(dark: Boolean, content: @Composable () -> Unit) {
    val context = LocalContext.current
    DisposableEffect(dark, context) {
        val activity = context as? Activity
        val controller = activity?.window?.let { WindowCompat.getInsetsController(it, it.decorView) }
        val status = controller?.isAppearanceLightStatusBars
        val navigation = controller?.isAppearanceLightNavigationBars
        controller?.isAppearanceLightStatusBars = !dark
        controller?.isAppearanceLightNavigationBars = !dark
        onDispose {
            status?.let { controller?.isAppearanceLightStatusBars = it }
            navigation?.let { controller?.isAppearanceLightNavigationBars = it }
        }
    }
    val colors = if (dark) darkColorScheme(
        primary = ScanMint, onPrimary = Color(0xFF003F39), secondary = ScanMint,
        background = ScanCharcoal, surface = ScanCharcoal,
        surfaceVariant = Color(0xFF2B2E2F), onSurfaceVariant = Color(0xFFD4DEDC),
        outline = Color(0xFF57615F), outlineVariant = Color(0xFF3C4543)
    ) else lightColorScheme(
        primary = ScanTeal, onPrimary = Color.White, secondary = ScanTeal,
        background = Color(0xFFF5F7F7), surface = Color.White,
        primaryContainer = Color(0xFFDDF5F0), onPrimaryContainer = Color(0xFF004C45),
        surfaceVariant = Color(0xFFE9EEEE), onSurfaceVariant = Color(0xFF4C5C59),
        outline = Color(0xFF9AA9A6), outlineVariant = Color(0xFFD6DFDD)
    )
    MaterialTheme(colorScheme = colors, content = content)
}
