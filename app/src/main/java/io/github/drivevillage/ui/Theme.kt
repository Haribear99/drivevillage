package io.github.drivevillage.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

object VillageTheme {
    val background = Color(0xFF1C2130)
    val panel = Color(0xFF272E42)
    val card = Color(0xFF323A52)
    val text = Color(0xFFF2EDE3)
    val subtext = Color(0xFFB9B3A6)
    val accent = Color(0xFFFFC46B)

    @Composable
    operator fun invoke(content: @Composable () -> Unit) {
        MaterialTheme(
            colorScheme = darkColorScheme(
                primary = accent,
                onPrimary = Color(0xFF2B1D14),
                secondaryContainer = card,
                onSecondaryContainer = text,
                surface = panel,
                onSurface = text,
                background = background,
            ),
            content = content,
        )
    }
}
