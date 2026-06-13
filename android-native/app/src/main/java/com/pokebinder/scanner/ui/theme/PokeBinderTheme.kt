package com.pokebinder.scanner.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val PokeBinderDarkColors = darkColorScheme(
    primary = Color(0xFF5BE6B1),
    onPrimary = Color(0xFF06150F),
    secondary = Color(0xFFFFD45A),
    background = Color(0xFF080B0E),
    onBackground = Color(0xFFF4F6F8),
    surface = Color(0xFF11161C),
    onSurface = Color(0xFFF4F6F8),
    surfaceVariant = Color(0xFF1B222B),
    onSurfaceVariant = Color(0xFFB8C0CB),
    error = Color(0xFFFF7272),
)

private val PokeBinderLightColors = lightColorScheme(
    primary = Color(0xFF087C59),
    onPrimary = Color.White,
    secondary = Color(0xFF8A6500),
    background = Color(0xFFF5F7F8),
    onBackground = Color(0xFF11161C),
    surface = Color.White,
    onSurface = Color(0xFF11161C),
    surfaceVariant = Color(0xFFE8EDF0),
    onSurfaceVariant = Color(0xFF56616D),
    error = Color(0xFFBA1A1A),
)

@Composable
fun PokeBinderTheme(
    darkTheme: Boolean = true,
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) PokeBinderDarkColors else PokeBinderLightColors,
        content = content,
    )
}
