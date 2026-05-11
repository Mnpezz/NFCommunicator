package dev.alsatianconsulting.NFCommunicator.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

private val DarkColors = darkColorScheme(
    primary = EmberPrimary,
    onPrimary = EmberBlack,
    primaryContainer = EmberAccentDeep,
    onPrimaryContainer = EmberText,
    secondary = EmberPrimaryBright,
    onSecondary = EmberBlack,
    secondaryContainer = EmberSurfaceVariant,
    onSecondaryContainer = EmberText,
    tertiary = EmberAccent,
    onTertiary = EmberBlack,
    tertiaryContainer = EmberSurfaceHigh,
    onTertiaryContainer = EmberText,
    background = EmberBackground,
    onBackground = EmberText,
    surface = EmberSurface,
    onSurface = EmberText,
    surfaceVariant = EmberSurfaceVariant,
    onSurfaceVariant = EmberTextMuted,
    outline = EmberOutline,
    error = EmberPrimaryBright,
    onError = EmberBlack,
    errorContainer = EmberAccentDeep,
    onErrorContainer = EmberText,
)

private val AppTypography = Typography(
    headlineSmall = TextStyle(
        fontFamily = FontFamily.Serif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 28.sp,
        lineHeight = 32.sp,
    ),
    titleMedium = TextStyle(
        fontFamily = FontFamily.Serif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 20.sp,
        lineHeight = 24.sp,
    ),
    bodyLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontSize = 18.sp,
        lineHeight = 26.sp,
    ),
    bodyMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontSize = 16.sp,
        lineHeight = 24.sp,
    ),
    bodySmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontSize = 14.sp,
        lineHeight = 20.sp,
    ),
)

@Composable
fun NfcCommunicatorTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = DarkColors,
        typography = AppTypography,
        content = content,
    )
}
