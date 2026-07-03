/*
 * This file has been modified to support NDEF tag operations in NFC Reader Mode.
 * Modified by mnpezz.
 */
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
    primary = BitcoinOrange,
    onPrimary = BitcoinBlack,
    primaryContainer = BitcoinOrangeMuted,
    onPrimaryContainer = BitcoinText,
    secondary = BitcoinOrangeBright,
    onSecondary = BitcoinBlack,
    secondaryContainer = BitcoinSurfaceVariant,
    onSecondaryContainer = BitcoinText,
    tertiary = BitcoinGold,
    onTertiary = BitcoinBlack,
    tertiaryContainer = BitcoinSurfaceHigh,
    onTertiaryContainer = BitcoinText,
    background = BitcoinBackground,
    onBackground = BitcoinText,
    surface = BitcoinSurface,
    onSurface = BitcoinText,
    surfaceVariant = BitcoinSurfaceVariant,
    onSurfaceVariant = BitcoinTextMuted,
    outline = BitcoinOutline,
    error = BitcoinOrangeBright,
    onError = BitcoinBlack,
    errorContainer = BitcoinOrangeMuted,
    onErrorContainer = BitcoinText,
)

private val AppTypography = Typography(
    headlineSmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Bold,
        fontSize = 28.sp,
        lineHeight = 34.sp,
    ),
    titleMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
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
