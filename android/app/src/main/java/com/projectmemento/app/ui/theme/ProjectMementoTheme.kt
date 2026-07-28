package com.projectmemento.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val MementoLightColors = lightColorScheme(
    primary = Color(0xFF5C5BD8),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE7E5FF),
    onPrimaryContainer = Color(0xFF1C1958),
    secondary = Color(0xFFD86F50),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFFFDBD0),
    onSecondaryContainer = Color(0xFF4F1608),
    tertiary = Color(0xFF247C67),
    background = Color(0xFFF8F6F1),
    onBackground = Color(0xFF1D1B20),
    surface = Color(0xFFFFFBF6),
    onSurface = Color(0xFF1D1B20),
    surfaceVariant = Color(0xFFF0EAE1),
    onSurfaceVariant = Color(0xFF625F66),
    outline = Color(0xFF817E86),
)

private val MementoDarkColors = darkColorScheme(
    primary = Color(0xFFBFC5FF),
    onPrimary = Color(0xFF242C78),
    primaryContainer = Color(0xFF303A8C),
    onPrimaryContainer = Color(0xFFE3E5FF),
    secondary = Color(0xFFFFB59F),
    onSecondary = Color(0xFF7B2D18),
    tertiary = Color(0xFF86DFE4),
    background = Color(0xFF0D1018),
    onBackground = Color(0xFFF0EDF6),
    surface = Color(0xFF171A24),
    onSurface = Color(0xFFF0EDF6),
    surfaceVariant = Color(0xFF252936),
    onSurfaceVariant = Color(0xFFC9C8D4),
    outline = Color(0xFF7E8494),
)

@Composable
fun ProjectMementoTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) MementoDarkColors else MementoLightColors,
        content = content,
    )
}
