package com.rhys.obd2.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color

/**
 * A dark-first theme.
 *
 * This app gets used in a car footwell and at night under a bonnet, so the dark scheme is
 * the one that's actually designed. The light scheme is no longer an afterthought though:
 * it previously reused the dark status colours, which put the accent at 1.6:1 against a
 * white card and made every chip, link and banner unreadable for anyone whose phone is set
 * to light — which on Android is most people. See [StatusColors].
 *
 * Dynamic colour is deliberately not used. The status hues carry meaning here — green is
 * healthy, red is a fault — and letting the wallpaper repaint them would make a passing
 * reading look like a failing one.
 */

private val DarkColors = darkColorScheme(
    primary = Color(0xFF37E5A0),
    onPrimary = Color(0xFF04231A),
    primaryContainer = Color(0xFF16332C),
    onPrimaryContainer = Color(0xFFA8F2D4),

    secondary = Color(0xFF54A0FF),
    onSecondary = Color(0xFF041A2E),
    secondaryContainer = Color(0xFF18293D),
    onSecondaryContainer = Color(0xFFBBD9FF),

    error = Color(0xFFFF5470),
    onError = Color(0xFF3A0010),
    errorContainer = Color(0xFF351F27),
    onErrorContainer = Color(0xFFFFC2CC),

    background = Color(0xFF0B0F14),
    onBackground = Color(0xFFE6EDF3),
    surface = Color(0xFF141A22),
    onSurface = Color(0xFFE6EDF3),
    surfaceVariant = Color(0xFF1D2530),
    onSurfaceVariant = Color(0xFF8C99A8),
    surfaceContainerHighest = Color(0xFF232C38),

    outline = Color(0xFF35404E),
    outlineVariant = Color(0xFF232C38),
    scrim = Color(0xCC000000),

    inverseSurface = Color(0xFFE6EDF3),
    inverseOnSurface = Color(0xFF11181F),
    inversePrimary = Color(0xFF0A6B45),
)

private val LightColors = lightColorScheme(
    primary = Color(0xFF0A6B45),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE1F4EC),
    onPrimaryContainer = Color(0xFF04301F),

    secondary = Color(0xFF0058C4),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFDEEAFB),
    onSecondaryContainer = Color(0xFF00264F),

    error = Color(0xFFC10025),
    onError = Color.White,
    errorContainer = Color(0xFFFCE0E5),
    onErrorContainer = Color(0xFF52000F),

    background = Color(0xFFF6F8FA),
    onBackground = Color(0xFF11181F),
    surface = Color.White,
    onSurface = Color(0xFF11181F),
    surfaceVariant = Color(0xFFEDF1F5),
    onSurfaceVariant = Color(0xFF4A5764),
    surfaceContainerHighest = Color(0xFFE3E9EF),

    outline = Color(0xFFC3CCD6),
    outlineVariant = Color(0xFFDFE5EB),
    scrim = Color(0x99000000),

    inverseSurface = Color(0xFF11181F),
    inverseOnSurface = Color(0xFFF6F8FA),
    inversePrimary = Color(0xFF37E5A0),
)

private val ObdShapes = Shapes(
    extraSmall = Radius.control,
    small = Radius.control,
    medium = Radius.card,
    large = Radius.card,
    extraLarge = Radius.sheet,
)

@Composable
fun OpenObdTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    CompositionLocalProvider(
        LocalStatusColors provides if (darkTheme) DarkStatusColors else LightStatusColors,
    ) {
        MaterialTheme(
            colorScheme = if (darkTheme) DarkColors else LightColors,
            typography = ObdTypography,
            shapes = ObdShapes,
            content = content,
        )
    }
}
