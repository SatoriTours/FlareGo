package com.flarego.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

val Forest = Color(0xFF315B42)
val Lime = Color(0xFFD2EE98)
val Orange = Color(0xFFEA8B51)
private val Light =
    lightColorScheme(
        primary = Forest,
        onPrimary = Color.White,
        primaryContainer = Color(0xFFE7EFDF),
        onPrimaryContainer = Forest,
        background = Color(0xFFF5F6F2),
        surface = Color(0xFFFFFFFF),
        surfaceVariant = Color(0xFFEDF1E7),
        surfaceContainerLowest = Color.White,
        surfaceContainerLow = Color(0xFFF8FAF5),
        surfaceContainer = Color(0xFFF0F3EC),
        surfaceContainerHigh = Color.White,
        surfaceContainerHighest = Color(0xFFE7EDE1),
        surfaceTint = Forest,
        onSurface = Color(0xFF29352C),
        onSurfaceVariant = Color(0xFF7A8677),
        outlineVariant = Color(0xFFE5E9E0),
        secondary = Forest,
        secondaryContainer = Color(0xFFE7EFDF),
        onSecondaryContainer = Forest,
    )
private val Dark =
    darkColorScheme(
        primary = Lime,
        onPrimary = Forest,
        primaryContainer = Forest,
        onPrimaryContainer = Lime,
        background = Color(0xFF15201A),
        surface = Color(0xFF202D24),
        surfaceVariant = Color(0xFF2A382E),
        surfaceContainerLowest = Color(0xFF111A15),
        surfaceContainerLow = Color(0xFF1B2820),
        surfaceContainer = Color(0xFF202D24),
        surfaceContainerHigh = Color(0xFF2A382E),
        surfaceContainerHighest = Color(0xFF344639),
        surfaceTint = Lime,
        onSurface = Color(0xFFE8EEE4),
        onSurfaceVariant = Color(0xFFABB8A8),
        outlineVariant = Color(0xFF344639),
        secondary = Lime,
        secondaryContainer = Forest,
        onSecondaryContainer = Lime,
    )

@Composable
fun FlareGoTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) Dark else Light,
        typography =
            Typography(
                headlineLarge = TextStyle(fontSize = 34.sp, fontWeight = FontWeight.SemiBold),
                headlineSmall = TextStyle(fontSize = 24.sp, fontWeight = FontWeight.SemiBold),
                titleLarge = TextStyle(fontSize = 18.sp, fontWeight = FontWeight.SemiBold),
                titleMedium = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.SemiBold),
                titleSmall = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.SemiBold),
                bodyLarge = TextStyle(fontSize = 15.sp),
                bodyMedium = TextStyle(fontSize = 13.sp, lineHeight = 20.sp),
                bodySmall = TextStyle(fontSize = 11.sp, lineHeight = 16.sp),
                labelLarge = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.Medium),
                labelMedium = TextStyle(fontSize = 11.sp),
                labelSmall = TextStyle(fontSize = 10.sp),
            ),
        content = content,
    )
}
