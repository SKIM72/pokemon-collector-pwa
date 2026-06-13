package com.pokebinder.scanner.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val PokeBinderColors = darkColorScheme(
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

@Composable
fun PokeBinderTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = PokeBinderColors,
        content = content,
    )
}
