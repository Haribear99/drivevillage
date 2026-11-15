package io.github.drivevillage.ui

import android.graphics.Bitmap
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageShader
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp

/*
 * 패널 재질 규칙(화면당 재질 효과 2개 이하):
 *  1) 패널 배경 = 네이비 + 5~6% 크림 노이즈(코드로 만든 타일, 에셋 없음)
 *  2) 주요 카드 1종(HeroCard)에만 점선 스티치 + 안쪽 하이라이트
 * 일반 카드는 평평한 크림 종이(그림자 없음). 앰버는 화면당 CTA 하나(PrimaryButton)에만 쓴다.
 */

/** 네이비 패널 배경 + 노이즈 오버레이. 타일은 한 번만 만들어 재사용한다. */
@Composable
fun Modifier.panelSurface(): Modifier {
    val noise = remember {
        val n = Dimens.NOISE_TILE_PX
        val rnd = java.util.Random(20261115L)
        val cream = VillageTheme.paper.toArgb() and 0x00FFFFFF
        val px = IntArray(n * n) { (rnd.nextInt(256) shl 24) or cream }
        val bmp = Bitmap.createBitmap(n, n, Bitmap.Config.ARGB_8888).apply { setPixels(px, 0, n, 0, 0, n, n) }
        ShaderBrush(ImageShader(bmp.asImageBitmap(), TileMode.Repeated, TileMode.Repeated))
    }
    return this.drawBehind {
        drawRect(VillageTheme.panelNavy)
        drawRect(noise, alpha = Dimens.NOISE_ALPHA)
    }
}

/** 일반 카드: 평평한 크림 종이. */
@Composable
fun PaperCard(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Column(
        modifier.fillMaxWidth().background(VillageTheme.paper, RoundedCornerShape(Dimens.cardRadius)).padding(Dimens.cardPad),
        verticalArrangement = Arrangement.spacedBy(Dimens.inner),
    ) { content() }
}

/** 주요 카드(탭마다 맨 위 1장): 크림 종이 + 점선 스티치 + 안쪽 하이라이트. */
@Composable
fun HeroCard(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Column(
        modifier.fillMaxWidth()
            .background(VillageTheme.paper, RoundedCornerShape(Dimens.cardRadius))
            .drawBehind {
                val hw = Dimens.highlightWidth.toPx()
                val r = Dimens.cardRadius.toPx()
                // 안쪽 하이라이트: 가장자리 바로 안쪽의 밝은 선
                drawRoundRect(
                    color = Color.White.copy(alpha = 0.75f),
                    topLeft = Offset(hw, hw), size = Size(size.width - 2 * hw, size.height - 2 * hw),
                    cornerRadius = CornerRadius(r - hw), style = Stroke(width = hw),
                )
                // 점선 스티치
                val inset = Dimens.stitchInset.toPx()
                drawRoundRect(
                    color = VillageTheme.stitch,
                    topLeft = Offset(inset, inset), size = Size(size.width - 2 * inset, size.height - 2 * inset),
                    cornerRadius = CornerRadius((r - inset).coerceAtLeast(0f)),
                    style = Stroke(
                        width = Dimens.stitchWidth.toPx(),
                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(Dimens.stitchDash.toPx(), Dimens.stitchGapLen.toPx())),
                    ),
                )
            }
            .padding(Dimens.cardPad),
        verticalArrangement = Arrangement.spacedBy(Dimens.inner),
    ) { content() }
}

@Composable
fun CardTitle(text: String, color: Color = VillageTheme.ink) = Text(text, color = color, style = VillageType.title)

@Composable
fun CardBody(text: String, color: Color = VillageTheme.ink, style: TextStyle = VillageType.body) = Text(text, color = color, style = style)

private val buttonShape = RoundedCornerShape(Dimens.buttonRadius)
private val buttonPad = PaddingValues(horizontal = Dimens.buttonPadH, vertical = Dimens.buttonPadV)

/** 화면당 하나만: 앰버 CTA. */
@Composable
fun PrimaryButton(text: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Button(
        onClick = onClick, modifier = modifier.heightIn(min = Dimens.touch), shape = buttonShape, contentPadding = buttonPad,
        colors = ButtonDefaults.buttonColors(containerColor = VillageTheme.accent, contentColor = VillageTheme.ink),
    ) { Text(text, style = VillageType.label, textAlign = TextAlign.Center) }
}

/** 크림 카드 위의 보조 버튼(네이비 테두리). selected면 네이비로 채운다(선택 칩). */
@Composable
fun InkButton(text: String, modifier: Modifier = Modifier, selected: Boolean = false, padH: Dp = Dimens.buttonPadH, onClick: () -> Unit) {
    val pad = PaddingValues(horizontal = padH, vertical = Dimens.buttonPadV)
    if (selected) {
        Button(
            onClick = onClick, modifier = modifier.heightIn(min = Dimens.touch), shape = buttonShape, contentPadding = pad,
            colors = ButtonDefaults.buttonColors(containerColor = VillageTheme.ink, contentColor = VillageTheme.paper),
        ) { Text(text, style = VillageType.label, textAlign = TextAlign.Center) }
    } else {
        OutlinedButton(
            onClick = onClick, modifier = modifier.heightIn(min = Dimens.touch), shape = buttonShape, contentPadding = pad,
            border = BorderStroke(Dimens.outlineWidth, VillageTheme.ink),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = VillageTheme.ink),
        ) { Text(text, style = VillageType.label, textAlign = TextAlign.Center) }
    }
}

/** 네이비 패널 위(카드 밖)의 보조 버튼: 크림 테두리 + 크림 글자. */
@Composable
fun GhostButton(text: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    OutlinedButton(
        onClick = onClick, modifier = modifier.heightIn(min = Dimens.touch), shape = buttonShape, contentPadding = buttonPad,
        border = BorderStroke(Dimens.outlineWidth, VillageTheme.text),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = VillageTheme.text),
    ) { Text(text, style = VillageType.label, textAlign = TextAlign.Center) }
}

/** 버튼 여러 개를 세로로 쌓을 때(사이 24dp). */
@Composable
fun ButtonColumn(content: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Dimens.gap)) { content() }
}

/** 버튼을 한 줄에 같은 폭으로 놓는다(사이 24dp). */
@Composable
fun ButtonRow(content: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Dimens.gap), verticalAlignment = Alignment.CenterVertically, content = content)
}

/**
 * 긴 설명은 접어 두고 누를 때만 펼친다(pull 방식). 행 전체가 64dp 이상 탭 영역이다.
 */
@Composable
fun MoreInfo(label: String, text: String) {
    var open by rememberSaveable(label, text) { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(Dimens.inner)) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = Dimens.touch)
                .border(Dimens.outlineWidth, VillageTheme.inkSub, buttonShape)
                .clickable(role = Role.Button) { open = !open }
                .padding(horizontal = Dimens.buttonPadH),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(label, color = VillageTheme.ink, style = VillageType.label, modifier = Modifier.weight(1f))
            Text(if (open) "접기" else "펼치기", color = VillageTheme.inkSub, style = VillageType.body)
        }
        if (open) CardBody(text, VillageTheme.inkSub)
    }
}
