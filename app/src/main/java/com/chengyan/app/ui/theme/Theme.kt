package com.chengyan.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// 澄言原创配色：暖纸 × 墨色 × 陶土橙
val Ink = Color(0xFF26221C)
val Ink2 = Color(0xFF5F574B)
val Ink3 = Color(0xFF9D937F)
val Ink4 = Color(0xFFC4BBA6)
val Paper = Color(0xFFF7F3EA)
val Paper2 = Color(0xFFEFE9DB)
val Paper3 = Color(0xFFE7DFCD)
val Card = Color(0xFFFFFEF9)
val Line = Color(0xFFE3DBC8)
val Line2 = Color(0xFFD5CBB2)
val Accent = Color(0xFFB25A29)
val Accent2 = Color(0xFFC96F3B)
val AccentSoft = Color(0xFFF3E2D2)
val AccentInk = Color(0xFF7E3D14)
val Ok = Color(0xFF4E7042)
val Err = Color(0xFFA3402E)
val CodeBg = Color(0xFF29241D)
val CodeHead = Color(0xFF221E17)
val CodeFg = Color(0xFFE8E0D0)

private val LightColors = lightColorScheme(
    primary = Accent,
    onPrimary = Color.White,
    primaryContainer = AccentSoft,
    onPrimaryContainer = AccentInk,
    secondary = Ink2,
    onSecondary = Color.White,
    background = Paper,
    onBackground = Ink,
    surface = Card,
    onSurface = Ink,
    surfaceVariant = Paper2,
    onSurfaceVariant = Ink2,
    outline = Line2,
    outlineVariant = Line,
    error = Err,
    surfaceContainer = Card,
    surfaceContainerLow = Paper,
    surfaceContainerHigh = Paper2,
)

private val DarkColors = darkColorScheme(
    primary = Accent2,
    onPrimary = Color.White,
    primaryContainer = Color(0xFF3A2A1E),
    onPrimaryContainer = AccentSoft,
    secondary = Color(0xFFC9BFAF),
    onSecondary = Ink,
    background = Color(0xFF1C1913),
    onBackground = Color(0xFFEDE6D8),
    surface = Color(0xFF25211A),
    onSurface = Color(0xFFEDE6D8),
    surfaceVariant = Color(0xFF2E2921),
    onSurfaceVariant = Color(0xFFB5AB99),
    outline = Color(0xFF4A4335),
    outlineVariant = Color(0xFF3A342A),
    error = Color(0xFFD07058),
    surfaceContainer = Color(0xFF25211A),
    surfaceContainerLow = Color(0xFF1C1913),
    surfaceContainerHigh = Color(0xFF2E2921),
)

@Composable
fun ChengYanTheme(darkTheme: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        content = content
    )
}
