package com.docscan.android.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

private val LightColors = lightColorScheme(
    primary = if (DesignConfig.fixedTabs) Color(0xFFD92A1C) else Color(0xFF174BEA), onPrimary = Color.White,
    primaryContainer = if (DesignConfig.fixedTabs) Color(0xFFFFE6E1) else Color(0xFFE6EDFF), onPrimaryContainer = Color(0xFF14213C),
    secondary = Color(0xFF536078), background = if (DesignConfig.fixedTabs) Color.White else Color(0xFFF6F8FF),
    surface = Color.White, surfaceVariant = Color(0xFFF0F3F8),
    onBackground = Color(0xFF111827), onSurface = Color(0xFF111827),
    outline = Color(0xFF768093), outlineVariant = Color(0xFFDCE2ED),
)
private val DarkColors = darkColorScheme(
    primary = if (DesignConfig.fixedTabs) Color(0xFFFFA394) else Color(0xFFA9BEFF), onPrimary = Color(0xFF141B2A),
    primaryContainer = Color(0xFF28334D), onPrimaryContainer = Color(0xFFF1F4FA),
    background = Color(0xFF11141C), surface = Color(0xFF1A2030),
    surfaceVariant = Color(0xFF252E40), onSurface = Color(0xFFF1F4FA), onBackground = Color(0xFFF1F4FA),
)

val DesignCorner get() = RoundedCornerShape(if (DesignConfig.fixedTabs) 6.dp else 18.dp)

@Composable
fun DocScanTheme(content: @Composable () -> Unit) {
    val type = Typography()
    MaterialTheme(colorScheme = if (isSystemInDarkTheme()) DarkColors else LightColors,
        typography = type.copy(headlineMedium = type.headlineMedium.copy(fontWeight = FontWeight.Bold),
            headlineSmall = type.headlineSmall.copy(fontWeight = FontWeight.Bold),
            titleLarge = type.titleLarge.copy(fontWeight = FontWeight.SemiBold)),
        shapes = Shapes(small = DesignCorner, medium = DesignCorner, large = DesignCorner), content = content)
}
