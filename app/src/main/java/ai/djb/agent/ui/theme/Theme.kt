package ai.djb.agent.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val Ink = Color(0xFF0B1020)
private val Card = Color(0xFF141A32)
private val Violet = Color(0xFF7C5CFF)
private val Mint = Color(0xFF4DE1C1)
private val Sand = Color(0xFFF2E9DC)

private val DarkColors = darkColorScheme(
    primary = Violet,
    onPrimary = Color.White,
    secondary = Mint,
    onSecondary = Ink,
    background = Ink,
    onBackground = Sand,
    surface = Card,
    onSurface = Sand,
    surfaceVariant = Color(0xFF1D2544),
    onSurfaceVariant = Color(0xFFB9C0D8),
    error = Color(0xFFFF6B6B),
)

private val LightColors = lightColorScheme(
    primary = Violet,
    secondary = Color(0xFF12A594),
    background = Color(0xFFF7F6FB),
    surface = Color.White,
)

@Composable
fun DjbTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    MaterialTheme(
        colorScheme = if (dark) DarkColors else LightColors,
        typography = Typography(),
        content = content,
    )
}
