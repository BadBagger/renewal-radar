package com.renewalradar.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val RadarLightColors = lightColorScheme(
    primary = Color(0xFF2563EB),
    onPrimary = Color.White,
    secondary = Color(0xFF0F766E),
    tertiary = Color(0xFFB45309),
    error = Color(0xFFB91C1C),
    background = Color(0xFFF8FAFC),
    surface = Color.White,
    onSurface = Color(0xFF111827)
)

private val RadarDarkColors = darkColorScheme(
    primary = Color(0xFF93C5FD),
    onPrimary = Color(0xFF172554),
    secondary = Color(0xFF5EEAD4),
    tertiary = Color(0xFFFCD34D),
    error = Color(0xFFFCA5A5),
    background = Color(0xFF0F172A),
    surface = Color(0xFF111827),
    surfaceVariant = Color(0xFF1F2937),
    onSurface = Color(0xFFE5E7EB),
    onSurfaceVariant = Color(0xFFCBD5E1)
)

@Composable
fun RenewalRadarTheme(darkTheme: Boolean, content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (darkTheme) RadarDarkColors else RadarLightColors,
        typography = MaterialTheme.typography,
        content = content
    )
}
