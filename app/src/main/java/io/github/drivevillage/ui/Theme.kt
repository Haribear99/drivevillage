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

    // 오른쪽 패널 재질(크림 종이 카드 + 네이비 글자). 대비는 모두 크림 #F5EFE0 기준 4.5:1 이상.
    // 크림 위에 앰버·세이지 글자는 쓰지 않는다(1.37:1, 2.13:1).
    val panelNavy = Color(0xFF1C2130)
    val paper = Color(0xFFF5EFE0)
    val ink = Color(0xFF1C2130)       // 13.98:1
    val inkSub = Color(0xFF575143)    // 6.87:1
    val inkOk = Color(0xFF2E6B3B)     // 5.58:1
    val inkWarn = Color(0xFF9A3324)   // 6.37:1
    val inkDue = Color(0xFF7A4B00)    // 6.46:1
    val stitch = Color(0xFFB9AE95)    // 장식선(정보 아님)

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
