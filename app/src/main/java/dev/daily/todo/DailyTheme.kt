package dev.daily.todo

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val day = lightColorScheme(
    primary = Color(0xFF155B52), onPrimary = Color.White,
    primaryContainer = Color(0xFFCBEADF), onPrimaryContainer = Color(0xFF103F37),
    secondary = Color(0xFF52665D), surface = Color(0xFFF8FAF5),
    surfaceContainer = Color(0xFFEDEFE9), onSurface = Color(0xFF19231F)
)
private val night = darkColorScheme(
    primary = Color(0xFF9ED1C0), onPrimary = Color(0xFF103A32),
    primaryContainer = Color(0xFF265347), onPrimaryContainer = Color(0xFFD1EEE2),
    secondary = Color(0xFFB7CABE), surface = Color(0xFF111814),
    surfaceContainer = Color(0xFF1D2621), onSurface = Color(0xFFE3EBE4)
)

@Composable
fun DailyTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (isSystemInDarkTheme()) night else day, content = content)
}
