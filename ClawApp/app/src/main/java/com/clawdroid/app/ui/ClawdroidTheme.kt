package com.clawdroid.app.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.clawdroid.app.data.model.ThemeMode

private val ClawdroidDarkColors = darkColorScheme(
    primary = Color(0xFF4EA1FF),
    onPrimary = Color(0xFF071A2B),
    primaryContainer = Color(0xFF17324D),
    onPrimaryContainer = Color(0xFFD9ECFF),
    secondary = Color(0xFF6FCF97),
    onSecondary = Color(0xFF052016),
    secondaryContainer = Color(0xFF173B2A),
    onSecondaryContainer = Color(0xFFD8F7E5),
    tertiary = Color(0xFFE5B567),
    onTertiary = Color(0xFF2A1B05),
    tertiaryContainer = Color(0xFF4A3514),
    onTertiaryContainer = Color(0xFFFFE7B5),
    background = Color(0xFF0B0F14),
    onBackground = Color(0xFFE6EBF2),
    surface = Color(0xFF11171F),
    onSurface = Color(0xFFE6EBF2),
    surfaceVariant = Color(0xFF18212B),
    onSurfaceVariant = Color(0xFF9AA7B5),
    outline = Color(0xFF2A3542),
    outlineVariant = Color(0xFF1D2733),
    error = Color(0xFFFF6B6B),
    onError = Color(0xFF2A0808),
    errorContainer = Color(0xFF4A1818),
    onErrorContainer = Color(0xFFFFDADA)
)

private val ClawdroidLightColors = lightColorScheme(
    primary = Color(0xFF1F6FEB),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFDCEAFF),
    onPrimaryContainer = Color(0xFF0A2A5E),
    secondary = Color(0xFF15803D),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFDCFCE7),
    onSecondaryContainer = Color(0xFF052E16),
    tertiary = Color(0xFFB45309),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFFEF3C7),
    onTertiaryContainer = Color(0xFF451A03),
    background = Color(0xFFF4F6F8),
    onBackground = Color(0xFF111827),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF111827),
    surfaceVariant = Color(0xFFE9EEF3),
    onSurfaceVariant = Color(0xFF52606D),
    outline = Color(0xFFCBD5E1),
    outlineVariant = Color(0xFFE2E8F0),
    error = Color(0xFFB91C1C),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFEE2E2),
    onErrorContainer = Color(0xFF450A0A)
)

private val ClawdroidTypography = Typography(
    displaySmall = TextStyle(fontSize = 28.sp, lineHeight = 34.sp, fontWeight = FontWeight.SemiBold),
    headlineSmall = TextStyle(fontSize = 22.sp, lineHeight = 28.sp, fontWeight = FontWeight.SemiBold),
    titleLarge = TextStyle(fontSize = 18.sp, lineHeight = 24.sp, fontWeight = FontWeight.SemiBold),
    titleMedium = TextStyle(fontSize = 16.sp, lineHeight = 22.sp, fontWeight = FontWeight.SemiBold),
    bodyLarge = TextStyle(fontSize = 15.sp, lineHeight = 22.sp),
    bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 20.sp),
    labelLarge = TextStyle(fontSize = 13.sp, lineHeight = 18.sp, fontWeight = FontWeight.Medium),
    labelMedium = TextStyle(fontSize = 12.sp, lineHeight = 16.sp, fontWeight = FontWeight.Medium)
)

private val ClawdroidShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(20.dp),
    extraLarge = RoundedCornerShape(24.dp)
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
    MaterialTheme(
        colorScheme = if (useDarkTheme) ClawdroidDarkColors else ClawdroidLightColors,
        typography = ClawdroidTypography,
        shapes = ClawdroidShapes,
        content = content
    )
}
