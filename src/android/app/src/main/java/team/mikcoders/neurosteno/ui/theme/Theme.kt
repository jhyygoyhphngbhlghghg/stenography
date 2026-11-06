package team.mikcoders.neurosteno.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val Green = Color(0xFF1B7F5A)
private val Ink = Color(0xFF122018)

private val DarkColors = darkColorScheme(
    primary = Green,
    background = Color(0xFF0E1411),
    surface = Color(0xFF16201A),
    onPrimary = Color.White,
    onBackground = Color(0xFFE6F0EA),
    onSurface = Color(0xFFE6F0EA),
)

private val LightColors = lightColorScheme(
    primary = Green,
    background = Color(0xFFF4F7F5),
    surface = Color.White,
    onPrimary = Color.White,
    onBackground = Ink,
    onSurface = Ink,
)

@Composable
fun NeuroStenoTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        content = content,
    )
}
