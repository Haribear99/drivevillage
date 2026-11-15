package io.github.drivevillage.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.runtime.withFrameNanos
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.drivevillage.core.village.AppState
import io.github.drivevillage.core.village.Decoration
import io.github.drivevillage.core.village.MaterialBook
import io.github.drivevillage.core.voice.Sfx

private val silhouetteFilter = ColorFilter.tint(VillageTheme.silhouette, BlendMode.SrcIn)

/**
 * 꾸미기 탭 = 재료 도감. 10종(예전 기념품은 가진 종류만 더해 최대 12종)을 고정 순서 격자로 보여 주고(칸 64dp 이상, 사이 24dp, 글자 24sp),
 * 칸을 누르면 아래 카드에 이름과 얻는 법(못 만남) 또는 그날의 기억과 [마을에 놓기](가짐)를 보인다.
 * 칸이 좁아 이름·계기 문구는 칸 안에 넣지 않는다(글자를 줄이지 않고 아래 카드로 보낸다).
 * 앰버는 [마을에 놓기] 하나뿐이다. 고른 재료는 칸을 네이비로 채우고 문구로도 알린다.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun DecoratePanel(vm: VillageViewModel, state: AppState, open: OpenResult, selected: String?, onSelect: (String?) -> Unit) {
    val entries = remember(state.materials) { MaterialBook.entries(state) }
    val newMaterials = (open as? OpenResult.Scene)?.newMaterials.orEmpty()
    val reduce = rememberReduceMotion()
    // 이번 정산으로 새로 받은 종류만, 처음 볼 때 한 번 테두리 광택.
    val shimmer = remember(newMaterials) { vm.shimmerPending(newMaterials, reduce) }
    LaunchedEffect(newMaterials) { vm.markShimmered(newMaterials) }
    var focus by remember { mutableStateOf(MaterialBook.defaultFocus(entries, selected, newMaterials)) }
    var pick by remember(focus) { mutableIntStateOf(0) }
    val art = rememberVillageArt()
    val placedCount = state.materials.count { it.placedSlot != null }
    val slotsFull = placedCount >= Decoration.SLOT_COUNT
    val sfx = LocalSfx.current

    // 칸을 누르면 아래 카드가 보이도록 패널을 필요한 만큼만 올린다(가로 패널은 격자와 카드가 한 화면에 다 들어가지 않는다).
    val detailView = remember { BringIntoViewRequester() }
    var taps by remember { mutableIntStateOf(0) }
    LaunchedEffect(taps) {
        if (taps == 0) return@LaunchedEffect
        withFrameNanos { } // 바뀐 카드 내용이 배치된 뒤에
        detailView.bringIntoView()
    }

    HeroCard {
        CardTitle(MaterialBook.progressLabel(state))
        if (state.materials.isEmpty()) CardBody("아직 마을에 놓을 게 없어요. 드라이브를 다녀오면 하나둘 모여요.", VillageTheme.inkSub)
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val gap = Dimens.gap
            val cols = MaterialBook.columns(maxWidth.value, gap.value, Dimens.touch.value, entries.size)
            val cell = (maxWidth - gap * (cols - 1)) / cols
            Column(verticalArrangement = Arrangement.spacedBy(gap)) {
                entries.chunked(cols).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(gap)) {
                        row.forEach { e ->
                            val chosen = selected != null && e.unplaced.any { it.id == selected }
                            BookCell(e, art?.materials?.get(e.kind), cell, focused = e.kind == focus, chosen = chosen, shimmer = e.kind in shimmer) {
                                // 다른 칸을 보면 고르던 것은 내려놓는다(마을 자리 표시가 엉뚱한 재료를 가리키지 않게).
                                if (!chosen) onSelect(null)
                                focus = e.kind
                                taps++
                                sfx(Sfx.SELECT)
                            }
                        }
                    }
                }
            }
        }
    }
    val e = entries.firstOrNull { it.kind == focus }
    PaperCard(Modifier.bringIntoViewRequester(detailView)) {
        when {
            e == null -> CardBody("칸을 누르면 이름과 얻는 법이 보여요.", VillageTheme.inkSub)
            !e.met -> {
                CardTitle(e.kind.label)
                CardBody(MaterialBook.hint(e.kind), VillageTheme.inkSub)
            }
            e.unplaced.isEmpty() -> {
                CardTitle(e.kind.label)
                CardBody("모두 마을에 놓였어요. 마을에서 누르면 그날의 기억이 보여요.", VillageTheme.inkSub)
            }
            else -> {
                val chosen = e.unplaced.firstOrNull { it.id == selected }
                val m = chosen ?: e.unplaced[pick % e.unplaced.size]
                CardTitle(e.kind.label)
                CardBody(m.tag, VillageTheme.inkSub)
                when {
                    // 고른 상태는 색이 아니라 문구로도 알린다.
                    chosen != null -> {
                        CardBody("골랐어요. 마을의 빈 자리를 눌러 주세요.", VillageTheme.inkOk, VillageType.bodyStrong)
                        InkButton("고르기 취소", Modifier.fillMaxWidth(), sound = Sfx.CANCEL) { onSelect(null) }
                    }
                    slotsFull -> CardBody("마을의 ${Decoration.SLOT_COUNT}자리가 모두 찼어요.", VillageTheme.inkSub)
                    else -> ButtonRow {
                        PrimaryButton("마을에 놓기", Modifier.weight(1f), sound = Sfx.SELECT) { onSelect(m.id); taps++ }
                        if (e.unplaced.size > 1) InkButton("다른 기억", Modifier.weight(1f)) { pick++ }
                    }
                }
            }
        }
        if (state.materials.isNotEmpty()) CardBody("마을에 놓인 것 $placedCount / ${Decoration.SLOT_COUNT}자리", VillageTheme.inkSub)
    }
}

/** 도감 한 칸: 아이콘(못 만남은 단색 실루엣) + 우하단 보유 수 칩(2개 이상). 칸 전체가 탭 대상. */
@Composable
private fun BookCell(
    e: MaterialBook.Entry, icon: androidx.compose.ui.graphics.ImageBitmap?, size: Dp,
    focused: Boolean, chosen: Boolean, shimmer: Boolean, onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(Dimens.buttonRadius)
    val glow = remember { Animatable(0f) }
    LaunchedEffect(shimmer) {
        if (!shimmer) return@LaunchedEffect
        glow.animateTo(1f, tween(600))
        glow.animateTo(0f, tween(1200))
    }
    val state = when {
        !e.met -> "아직 못 만났어요"
        chosen -> "골랐어요"
        else -> "${e.owned}개"
    }
    Box(
        Modifier.size(size)
            .background(if (chosen) VillageTheme.ink else VillageTheme.tile, shape)
            .then(if (focused && !chosen) Modifier.border(3.dp, VillageTheme.ink, shape) else Modifier)
            .clickable(role = Role.Button, onClickLabel = e.kind.label) { onClick() }
            .semantics { contentDescription = "${e.kind.label}, $state"; this.selected = focused },
        contentAlignment = Alignment.Center,
    ) {
        icon?.let {
            Image(it, contentDescription = null, modifier = Modifier.fillMaxSize(0.68f), colorFilter = if (e.met) null else silhouetteFilter)
        }
        MaterialBook.countChip(e.owned)?.let { chip ->
            Text(
                chip, color = if (chosen) VillageTheme.ink else VillageTheme.paper, style = VillageType.label,
                modifier = Modifier.align(Alignment.BottomEnd).padding(3.dp)
                    .background(if (chosen) VillageTheme.paper else VillageTheme.ink, RoundedCornerShape(10.dp))
                    .padding(horizontal = 6.dp),
            )
        }
        // 새로 받은 종류: 숫자 배지 대신 테두리에 은은한 광택 1회(투명도만 바뀐다).
        if (shimmer) {
            Box(
                Modifier.matchParentSize().graphicsLayer { alpha = glow.value }
                    .border(4.dp, Brush.linearGradient(listOf(Color(0xFFFFFDF5), Color(0xFFE2B85C), Color(0xFFFFFDF5))), shape),
            )
        }
    }
}
