package com.rhys.obd2.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * A dark-first palette.
 *
 * This app gets used in a car footwell and at night under a bonnet, so the dark scheme
 * is the one that's actually designed; the light scheme exists so the app doesn't look
 * broken in daylight. Status colours are picked to stay distinguishable for the most
 * common forms of colour blindness — green/amber/red carry a shape or label too, never
 * colour alone.
 */

val Accent = Color(0xFF37E5A0)
val AccentDim = Color(0xFF1F9E6E)
val Warning = Color(0xFFFFB020)
val Danger = Color(0xFFFF5470)
val Info = Color(0xFF54A0FF)
val Surface0 = Color(0xFF0B0F14)
val Surface1 = Color(0xFF141A22)
val Surface2 = Color(0xFF1D2530)
val OnSurfaceDim = Color(0xFF8C99A8)

private val DarkColors = darkColorScheme(
    primary = Accent,
    onPrimary = Color(0xFF04231A),
    primaryContainer = AccentDim,
    onPrimaryContainer = Color(0xFFDDFFF1),
    secondary = Info,
    onSecondary = Color(0xFF041A2E),
    error = Danger,
    onError = Color(0xFF3A0010),
    background = Surface0,
    onBackground = Color(0xFFE6EDF3),
    surface = Surface1,
    onSurface = Color(0xFFE6EDF3),
    surfaceVariant = Surface2,
    onSurfaceVariant = OnSurfaceDim,
    outline = Color(0xFF35404E),
)

private val LightColors = lightColorScheme(
    primary = Color(0xFF11785A),
    onPrimary = Color.White,
    secondary = Color(0xFF1F5FA8),
    error = Color(0xFFB3123A),
    background = Color(0xFFF6F8FA),
    onBackground = Color(0xFF11181F),
    surface = Color.White,
    onSurface = Color(0xFF11181F),
    surfaceVariant = Color(0xFFE8ECF1),
    onSurfaceVariant = Color(0xFF4A5764),
    outline = Color(0xFFC3CCD6),
)

/** Numbers use a monospaced face so digits don't jitter as values change. */
val NumericStyle = TextStyle(
    fontFamily = FontFamily.Monospace,
    fontWeight = FontWeight.Bold,
    fontSize = 34.sp,
)

val NumericSmallStyle = TextStyle(
    fontFamily = FontFamily.Monospace,
    fontWeight = FontWeight.SemiBold,
    fontSize = 18.sp,
)

@Composable
fun OpenObdTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        typography = Typography(),
        content = content,
    )
}
