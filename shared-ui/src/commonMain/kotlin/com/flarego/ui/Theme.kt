package com.flarego.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// Kept for legacy demo illustrations. Production surfaces use semantic theme roles.
val Forest = Color(0xFF3A6C50)
val Lime = Color(0xFFC4DFAA)
val Orange = Color(0xFF906C31)
private val Light =
    lightColorScheme(
        primary = Forest, onPrimary = Color(0xFFF5F7F9),
        primaryContainer = Color(0xFFE8F1ED), onPrimaryContainer = Forest,
        background = Color(0xFFF5F7F9), onBackground = Color(0xFF22352C),
        surface = Color.White, onSurface = Color(0xFF22352C),
        surfaceVariant = Color(0xFFEDF1F4), onSurfaceVariant = Color(0xFF626F66),
        surfaceContainerLowest = Color.White, surfaceContainerLow = Color(0xFFF5F7F9),
        surfaceContainer = Color(0xFFEDF1F4), surfaceContainerHigh = Color.White,
        surfaceContainerHighest = Color(0xFFEDF1F4), surfaceTint = Forest,
        outline = Color(0xFF7B877D), outlineVariant = Color(0xFFE1E7EB),
        secondary = Forest, onSecondary = Color(0xFFF5F7F9),
        secondaryContainer = Color(0xFFE8F1ED), onSecondaryContainer = Forest,
        tertiary = Color(0xFF906C31), onTertiary = Color.White,
        tertiaryContainer = Color(0xFFF2EDDF), onTertiaryContainer = Color(0xFF906C31),
        error = Color(0xFFAC4338), onError = Color.White,
        errorContainer = Color(0xFFF8EBE7), onErrorContainer = Color(0xFFAC4338),
    )
private val Dark =
    darkColorScheme(
        primary = Lime, onPrimary = Color(0xFF131B18),
        primaryContainer = Color(0xFF29392B), onPrimaryContainer = Lime,
        background = Color(0xFF131B18), onBackground = Color(0xFFF0F3EB),
        surface = Color(0xFF1B2520), onSurface = Color(0xFFF0F3EB),
        surfaceVariant = Color(0xFF202B25), onSurfaceVariant = Color(0xFFA1AFA4),
        surfaceContainerLowest = Color(0xFF131B18), surfaceContainerLow = Color(0xFF131B18),
        surfaceContainer = Color(0xFF1B2520), surfaceContainerHigh = Color(0xFF202B25),
        surfaceContainerHighest = Color(0xFF2A362E), surfaceTint = Lime,
        outline = Color(0xFF8D9E91), outlineVariant = Color(0xFF2A362E),
        secondary = Lime, onSecondary = Color(0xFF131B18),
        secondaryContainer = Color(0xFF29392B), onSecondaryContainer = Lime,
        tertiary = Color(0xFFD9BF88), onTertiary = Color(0xFF131B18),
        tertiaryContainer = Color(0xFF2B2A21), onTertiaryContainer = Color(0xFFD9BF88),
        error = Color(0xFFF0A8A0), onError = Color(0xFF31221F),
        errorContainer = Color(0xFF31221F), onErrorContainer = Color(0xFFF0A8A0),
    )

@Composable
fun FlareGoTheme(darkTheme: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (darkTheme) Dark else Light,
        shapes = Shapes(
            extraSmall = RoundedCornerShape(5.dp), small = RoundedCornerShape(10.dp),
            medium = RoundedCornerShape(14.dp), large = RoundedCornerShape(16.dp),
            extraLarge = RoundedCornerShape(24.dp),
        ),
        typography = Typography(
            headlineLarge = TextStyle(fontSize = 28.sp, lineHeight = 36.sp, fontWeight = FontWeight.Medium),
            headlineSmall = TextStyle(fontSize = 24.sp, lineHeight = 32.sp, fontWeight = FontWeight.SemiBold),
            titleLarge = TextStyle(fontSize = 21.sp, lineHeight = 29.sp, fontWeight = FontWeight.Medium),
            titleMedium = TextStyle(fontSize = 16.sp, lineHeight = 24.sp, fontWeight = FontWeight.Medium),
            titleSmall = TextStyle(fontSize = 13.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium),
            bodyLarge = TextStyle(fontSize = 15.sp, lineHeight = 23.sp),
            bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 22.sp),
            bodySmall = TextStyle(fontSize = 12.sp, lineHeight = 20.sp),
            labelLarge = TextStyle(fontSize = 14.sp, lineHeight = 22.sp, fontWeight = FontWeight.Medium),
            labelMedium = TextStyle(fontSize = 11.sp, lineHeight = 17.sp),
            labelSmall = TextStyle(fontSize = 10.sp, lineHeight = 15.sp),
        ),
        content = content,
    )
}
