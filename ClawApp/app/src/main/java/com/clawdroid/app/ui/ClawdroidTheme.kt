package com.clawdroid.app.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val ClawdroidDarkColors = darkColorScheme(
    primary = Color(0xFF8CA8FF),
    onPrimary = Color(0xFF09152F),
    primaryContainer = Color(0xFF18305E),
    onPrimaryContainer = Color(0xFFDCE5FF),
    secondary = Color(0xFF84D8CF),
    onSecondary = Color(0xFF04211E),
    secondaryContainer = Color(0xFF123936),
    onSecondaryContainer = Color(0xFFD5F6F1),
    tertiary = Color(0xFFD7B6FF),
    onTertiary = Color(0xFF2D1645),
    tertiaryContainer = Color(0xFF43305F),
    onTertiaryContainer = Color(0xFFF0E1FF),
    background = Color(0xFF071019),
    onBackground = Color(0xFFE8EDF6),
    surface = Color(0xFF0F1824),
    onSurface = Color(0xFFE8EDF6),
    surfaceVariant = Color(0xFF1A2534),
    onSurfaceVariant = Color(0xFFB6C2D5),
    outline = Color(0xFF3A4A60),
    outlineVariant = Color(0xFF273447),
    error = Color(0xFFFFA6B1),
    onError = Color(0xFF3A0912),
    errorContainer = Color(0xFF5A1B28),
    onErrorContainer = Color(0xFFFFD9DE)
)

private val ClawdroidLightColors = lightColorScheme(
    primary = Color(0xFF355FCB),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFDDE6FF),
    onPrimaryContainer = Color(0xFF0C2259),
    secondary = Color(0xFF236D67),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFD6F1EC),
    onSecondaryContainer = Color(0xFF0C3835),
    tertiary = Color(0xFF7C5AA6),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFF0E2FF),
    onTertiaryContainer = Color(0xFF32184E),
    background = Color(0xFFF5F7FB),
    onBackground = Color(0xFF162133),
    surface = Color(0xFFFFFBFF),
    onSurface = Color(0xFF162133),
    surfaceVariant = Color(0xFFE8EDF5),
    onSurfaceVariant = Color(0xFF556176),
    outline = Color(0xFF8792A7),
    outlineVariant = Color(0xFFD1D7E2),
    error = Color(0xFFB32643),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFFD9E0),
    onErrorContainer = Color(0xFF430010)
)

@Composable
internal fun ClawdroidTheme(
    themeMode: ThemeMode = ThemeMode.FollowSystem,
    content: @Composable () -> Unit
) {
    val useDarkTheme = when (themeMode) {
        ThemeMode.FollowSystem -> isSystemInDarkTheme()
        ThemeMode.Dark -> true
        ThemeMode.Light -> false
    }
    val colors = if (useDarkTheme) {
        ClawdroidDarkColors
    } else {
        ClawdroidLightColors
    }

    MaterialTheme(
        colorScheme = colors,
        content = content
    )
}
