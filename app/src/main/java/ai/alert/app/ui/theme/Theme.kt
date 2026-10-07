package ai.alert.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val AlertDarkColors = darkColorScheme(
    primary = Color(0xFFFF3B30),
    onPrimary = Color.White,
    secondary = Color(0xFFFF8A80),
    background = Color(0xFF08090B),
    surface = Color(0xFF12151A),
    onBackground = Color(0xFFE7E9EC),
    onSurface = Color(0xFFE7E9EC)
)

@Composable
fun AlertAiTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = AlertDarkColors,
        content = content
    )
}
