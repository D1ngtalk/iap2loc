package io.github.d1ngtalk.iap2loc.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// The web console's palette (Web/index.html :root), so the native screens and the page match.
val Accent = Color(0xFF185FA5)
val Ok = Color(0xFF0F6E56)
val Warn = Color(0xFFBA7517)
val Bad = Color(0xFFA32D2D)

private val Light = lightColorScheme(
    primary = Accent,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFEAF2FB),
    onPrimaryContainer = Color(0xFF0B3A66),
    secondary = Color(0xFF5A6B7D),
    background = Color(0xFFF6F6F4),
    surface = Color(0xFFFFFFFF),
    surfaceVariant = Color(0xFFF0EFEA),
    onSurfaceVariant = Color(0xFF6F6D66),
    outline = Color(0xFFD5D4CE),
    outlineVariant = Color(0xFFE3E2DD),
    error = Bad,
    errorContainer = Color(0xFFFBECEB),
)

private val Dark = darkColorScheme(
    primary = Color(0xFF8CB8E6),
    onPrimary = Color(0xFF00315A),
    primaryContainer = Color(0xFF13487E),
    onPrimaryContainer = Color(0xFFD3E4F8),
    error = Color(0xFFF0A3A3),
)

@Composable
fun Iap2LocTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (isSystemInDarkTheme()) Dark else Light, content = content)
}
