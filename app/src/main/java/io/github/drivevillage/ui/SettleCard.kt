package io.github.drivevillage.ui

import android.provider.Settings
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.drivevillage.core.village.MaterialBook
import io.github.drivevillage.core.village.SceneLedger
import io.github.drivevillage.core.village.SceneRecord
import io.github.drivevillage.core.village.SettleReveal
import kotlinx.coroutines.launch

/** 시스템 '애니메이션 끄기'(배율 0)인지. 화면이 처음 그려질 때 한 번 읽는다. */
@Composable
internal fun rememberReduceMotion(): Boolean {
    val ctx = LocalContext.current
    return remember {
        runCatching { Settings.Global.getFloat(ctx.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f }.getOrDefault(false)
    }
}

/** 마을 그래픽(재료 아이콘 포함). 마을 전경과 같은 캐시([VillageArt.load])를 쓴다. 준비 전에는 null. */
@Composable
internal fun rememberVillageArt(): VillageArt? {
    val ctx = LocalContext.current
    val art by produceState(VillageArt.peek()) { value = VillageArt.load(ctx) }
    return art
}

/**
 * 여정 정산의 첫 카드(+ 기억 태그 카드). 위에서 아래로 헤더 → 거리 → 재료 칩 → 도장 → 기억 태그.
 * 연출은 정산 id마다 한 번: 거리 카운트업(약 1초) → 칩 착지 → 도장(전체 3.5초 이내). 위치·투명도·크기와 숫자만 바뀌고
 * 글자는 0초부터 자리에 있다. 카드나 태그를 한 번 누르면 바로 최종 상태가 되고, 가만 두어도 카드는 닫히지 않는다.
 * 애니메이션 끄기면 처음부터 최종 상태.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun SceneSettleCards(vm: VillageViewModel, open: OpenResult.Scene, rec: SceneRecord) {
    val reduce = rememberReduceMotion()
    val id = rec.eventId
    val (chips, moreKinds) = remember(open.newMaterials) { MaterialBook.chips(open.newMaterials) }
    val stamp = open.missionDone
    val total = SettleReveal.totalMs(chips.size, stamp != null)
    val end = SettleReveal.MAX_MS.toFloat()
    val play = remember(id) { vm.sceneRevealPending(id, reduce) }
    val clock = remember(id) { Animatable(if (play) 0f else end) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(id) {
        vm.markSceneRevealed(id) // 탭을 옮겼다 와도, 회전해도 같은 정산은 다시 재생하지 않는다.
        if (play) clock.animateTo(total.toFloat(), tween(total.toInt(), easing = LinearEasing))
        clock.snapTo(end)
    }
    val skip: () -> Unit = { if (clock.value < end) scope.launch { clock.snapTo(end) } }
    val t = clock.value.toLong()
    val art = rememberVillageArt()

    Column(
        // 카드 안 어디를 눌러도(태그 버튼 포함) 연출을 건너뛴다. 누름을 소비하지 않아 버튼·스크롤은 그대로 동작한다.
        Modifier.fillMaxWidth().pointerInput(id) {
            awaitEachGesture { awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial); skip() }
        },
        verticalArrangement = Arrangement.spacedBy(Dimens.gap),
    ) {
        HeroCard {
            // ① 헤더
            CardTitle("지난번 이후의 여정")
            val days = vm.elapsedDays(rec)
            val since = rec.sinceMillis?.let { SceneLedger.dateLabel(SceneLedger.epochDay(it, java.time.ZoneId.systemDefault())) }
            // 날짜와 기간은 따로 한 줄씩(24sp에서 한 줄 약 15자)
            CardBody(SceneLedger.dateLabel(rec.epochDay), VillageTheme.inkSub)
            CardBody(
                when {
                    since == null || days == null -> "마을 시작 이후"
                    days == 0 -> "오늘 앞선 정산 이후"
                    else -> "지난번($since) 이후 ${days}일"
                },
                VillageTheme.inkSub,
            )
            // ② 거리(가장 큰 글자). 최종 숫자를 투명하게 깔아 자리를 고정하고, 그 위에 세는 숫자를 그린다.
            val km = kotlin.math.floor(rec.deltaKm).toLong()
            Row(Modifier.clearAndSetSemantics { contentDescription = "${km}km 달렸어요" }) {
                Box(Modifier.alignByBaseline()) {
                    Text("${km}km", color = VillageTheme.ink, style = VillageType.display, modifier = Modifier.graphicsLayer { alpha = 0f })
                    Text("${SettleReveal.distanceAt(km, t)}km", color = VillageTheme.ink, style = VillageType.display)
                }
                Text(" 달렸어요", color = VillageTheme.ink, style = VillageType.body, modifier = Modifier.alignByBaseline())
            }
            // ③ 재료 칩(같은 종류는 ×n으로 묶음, 최대 4개). 착지 전에도 흐리게 보여 0초부터 읽힌다.
            if (chips.isNotEmpty()) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(Dimens.inner), verticalArrangement = Arrangement.spacedBy(Dimens.inner)) {
                    chips.forEachIndexed { i, (kind, n) ->
                        val p = SettleReveal.chipAt(i, t)
                        Row(
                            Modifier.graphicsLayer {
                                val e = SettleReveal.easeOut(p)
                                alpha = SettleReveal.CHIP_ALPHA_FROM + (1f - SettleReveal.CHIP_ALPHA_FROM) * p
                                translationY = -(1f - e) * 18.dp.toPx()
                                val s = 1f + 0.08f * (1f - e)
                                scaleX = s; scaleY = s
                            }
                                .background(VillageTheme.tile, RoundedCornerShape(Dimens.buttonRadius))
                                .padding(horizontal = Dimens.inner, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            art?.materials?.get(kind)?.let { Image(it, contentDescription = null, modifier = Modifier.size(40.dp)) }
                            Text(kind.label + (MaterialBook.countChip(n)?.let { " $it" } ?: ""), color = VillageTheme.ink, style = VillageType.label)
                        }
                    }
                }
                if (moreKinds > 0) CardBody("그 밖에 ${moreKinds}가지를 더 받았어요.", VillageTheme.inkSub)
            }
            if (open.milestonesCrossed > 0) CardBody("1,000km 이정표 ${open.milestonesCrossed}개를 넘었어요.", VillageTheme.inkOk, VillageType.bodyStrong)
            if (open.monumentsGranted > 0) CardBody("이정표 기념물 ${open.monumentsGranted}개를 세웠어요.", VillageTheme.inkOk, VillageType.bodyStrong)
            if (open.monumentsPending > 0) {
                CardBody("기념물은 한 번에 ${SceneLedger.MAX_MILESTONES_PER_SETTLEMENT}개까지라 ${open.monumentsPending}개는 다음 정산 때 세워요(사라지지 않아요).", VillageTheme.inkSub)
            }
            if (open.alreadyGotToday) CardBody("재료는 하루 한 번이라 이번에는 이미 받았어요. 이 여정은 일기에 남겨요.", VillageTheme.inkSub)
            // ④ 도장: 문구는 처음부터 있고, 동그란 도장 자국만 마지막에 찍힌다.
            stamp?.let {
                val p = SettleReveal.stampAt(chips.size, t)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Dimens.inner)) {
                    Box(
                        Modifier.size(76.dp).graphicsLayer {
                            alpha = p
                            val s = 1.6f - 0.6f * SettleReveal.easeOut(p)
                            scaleX = s; scaleY = s
                            rotationZ = -12f
                        }
                            .border(3.dp, VillageTheme.inkOk, CircleShape)
                            .semantics { contentDescription = "도장" },
                        contentAlignment = Alignment.Center,
                    ) { Text("도장", color = VillageTheme.inkOk, style = VillageType.label) }
                    CardBody("미션 도장: ${it.title}", VillageTheme.inkOk, VillageType.bodyStrong)
                }
            }
        }
        // ⑤ 기억 태그
        PaperCard {
            CardTitle("이번 여정의 한 줄")
            CardBody("고르면 받은 것의 기억 태그가 돼요.", VillageTheme.inkSub)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(Dimens.gap), verticalArrangement = Arrangement.spacedBy(Dimens.gap)) {
                SceneLedger.TAG_PRESETS.forEach { tag ->
                    if (rec.tag == tag) InkButton(tag, selected = true, padH = Dimens.buttonPadCompact) {}
                    else InkButton(tag, padH = Dimens.buttonPadCompact) { vm.chooseTag(rec.eventId, tag) }
                }
            }
        }
    }
}
