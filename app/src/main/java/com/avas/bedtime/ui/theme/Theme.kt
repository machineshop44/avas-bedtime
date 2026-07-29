package com.avas.bedtime.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

private val NightInk = Color(0xFF1A2433)
private val MoonCream = Color(0xFFF2E8D5)
private val SoftTeal = Color(0xFF3D7A7A)
private val SoftTealDim = Color(0xFF2A5555)
private val WarmSand = Color(0xFFC4A574)

private val ColorScheme = darkColorScheme(
    primary = SoftTeal,
    onPrimary = MoonCream,
    secondary = WarmSand,
    onSecondary = NightInk,
    background = NightInk,
    onBackground = MoonCream,
    surface = SoftTealDim,
    onSurface = MoonCream,
    surfaceVariant = Color(0xFF243044),
    onSurfaceVariant = Color(0xFFD5CBB8)
)

@Composable
fun AvaBedtimeTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = ColorScheme,
        typography = MaterialTheme.typography.copy(
            headlineLarge = TextStyle(
                fontFamily = FontFamily.Serif,
                fontWeight = FontWeight.Medium,
                fontSize = 34.sp,
                lineHeight = 40.sp,
                color = MoonCream
            ),
            titleLarge = TextStyle(
                fontFamily = FontFamily.Serif,
                fontWeight = FontWeight.Medium,
                fontSize = 24.sp,
                color = MoonCream
            ),
            bodyLarge = TextStyle(
                fontFamily = FontFamily.SansSerif,
                fontSize = 16.sp,
                lineHeight = 24.sp,
                color = MoonCream
            ),
            bodyMedium = TextStyle(
                fontFamily = FontFamily.SansSerif,
                fontSize = 14.sp,
                lineHeight = 20.sp,
                color = Color(0xFFD5CBB8)
            ),
            labelLarge = TextStyle(
                fontFamily = FontFamily.SansSerif,
                fontWeight = FontWeight.SemiBold,
                fontSize = 14.sp,
                color = MoonCream
            )
        ),
        content = content
    )
}
