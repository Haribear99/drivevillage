package io.github.drivevillage.ui

import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.drivevillage.R

/**
 * 오른쪽 패널의 크기 토큰. 기준은 주차 전용 앱 Tier 1(터치 64dp, 간격·가장자리 24dp, 글자 24sp)이고
 * 절대 하한은 터치 48dp, 글자 20sp다. 글자를 줄여 맞추지 말고 항목 수를 줄인다.
 */
object Dimens {
    // 터치
    val touch = 64.dp          // 탭·버튼·탭 가능한 행의 최소 높이
    val touchFloor = 48.dp     // 절대 하한(이 값 아래로는 쓰지 않는다)

    // 간격
    val edge = 24.dp           // 패널 가장자리 여백
    val gap = 24.dp            // 탭 가능한 요소 사이, 카드 사이
    val inner = 12.dp          // 같은 카드 안의 글줄 사이(탭 대상 사이에는 쓰지 않는다)
    val cardPad = 20.dp        // 카드 안쪽 여백(스티치 인셋 8dp보다 넉넉하게)

    // 모양
    val cardRadius = 20.dp
    val buttonRadius = 16.dp
    val buttonPadH = 20.dp
    val buttonPadV = 8.dp
    val buttonPadCompact = 12.dp // 증감 버튼처럼 짧은 숫자 라벨용
    val stitchInset = 8.dp
    val stitchWidth = 1.5.dp
    val stitchDash = 8.dp
    val stitchGapLen = 6.dp
    val highlightWidth = 1.5.dp
    val outlineWidth = 2.dp

    // 글자
    val display = 32.sp        // 강조 수치(달린 거리 등)
    val title = 28.sp          // 카드 제목
    val body = 24.sp           // 본문·라벨·보조 모두 기본값
    val label = 24.sp          // 탭·버튼 라벨
    val textFloor = 20.sp      // 절대 하한(현재 패널에서는 쓰지 않음)

    // 재질
    const val NOISE_ALPHA = 0.055f // 패널 노이즈 5~6%
    const val NOISE_TILE_PX = 128
}

/** 패널 글꼴. 제목·수치는 Gmarket Sans Bold, 본문은 Pretendard(모두 OFL, assets/licenses 참고). */
object VillageType {
    private val heading = FontFamily(Font(R.font.gmarket_sans_bold, FontWeight.Bold))
    private val text = FontFamily(
        Font(R.font.pretendard_regular, FontWeight.Normal),
        Font(R.font.pretendard_bold, FontWeight.Bold),
    )

    val display = TextStyle(fontFamily = heading, fontWeight = FontWeight.Bold, fontSize = Dimens.display, lineHeight = 40.sp)
    val title = TextStyle(fontFamily = heading, fontWeight = FontWeight.Bold, fontSize = Dimens.title, lineHeight = 36.sp)
    val body = TextStyle(fontFamily = text, fontWeight = FontWeight.Normal, fontSize = Dimens.body, lineHeight = 32.sp)
    val bodyStrong = body.copy(fontWeight = FontWeight.Bold)
    val label = TextStyle(fontFamily = text, fontWeight = FontWeight.Bold, fontSize = Dimens.label, lineHeight = 30.sp)
}
