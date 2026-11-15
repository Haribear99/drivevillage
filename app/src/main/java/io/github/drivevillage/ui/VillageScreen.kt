package io.github.drivevillage.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.drivevillage.core.village.AppState
import io.github.drivevillage.core.village.DueBy
import io.github.drivevillage.core.village.Facilities
import io.github.drivevillage.core.village.Facility
import io.github.drivevillage.core.village.Maintenance
import io.github.drivevillage.core.village.Missions
import io.github.drivevillage.core.village.Resident
import io.github.drivevillage.core.village.SceneLedger
import io.github.drivevillage.core.village.QuestResponse
import io.github.drivevillage.core.village.QuestStatus
import io.github.drivevillage.core.village.VillageGrowth
import kotlin.math.roundToLong

private enum class Tab(val label: String, val short: String) { SCENE("여정 정산", "정산"), WORKSHOP("정비 공방", "정비"), MISSION("미션", "미션"), DECORATE("꾸미기", "꾸미기") }

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun VillageScreen(vm: VillageViewModel) {
    val state by vm.state.collectAsState()
    val open by vm.open.collectAsState()
    val bubble by vm.bubble.collectAsState()
    val notice by vm.notice.collectAsState()
    val now by vm.now.collectAsState()
    val odo by vm.odoKm.collectAsState()
    val voiceOn by vm.voiceOn.collectAsState()
    val wake by vm.wake.collectAsState()
    val blocked by vm.writesBlocked.collectAsState()
    var tab by remember { mutableStateOf(if (state.maintenance.setup == null && open !is OpenResult.Scene) Tab.WORKSHOP else Tab.SCENE) }
    var selectedMaterial by remember { mutableStateOf<String?>(null) }
    // 여정 정산이 생기면 그 카드를 먼저 보여 준다.
    LaunchedEffect(open) { if (open is OpenResult.Scene) tab = Tab.SCENE }
    val today = vm.today()
    val timeSlot = SceneLedger.slotOf(now, java.time.ZoneId.systemDefault())

    val needingCare = remember(state, odo, today) { Facilities.needingCare(state, odo, today) }
    // 시설을 눌렀을 때 마을 아래쪽에 올라오는 안내 시트(응답은 받지 않는다).
    var sheetFacility by remember { mutableStateOf<Facility?>(null) }
    // ── 마을 전경 ──
    val villagePane: @Composable (Modifier) -> Unit = { paneModifier ->
        BoxWithConstraints(paneModifier) {
            VillageCanvas(
                modifier = Modifier.fillMaxSize(),
                timeSlot = timeSlot,
                workshopLevel = state.maintenance.workshopLevel,
                needingCare = needingCare,
                placed = state.materials.filter { it.placedSlot != null },
                highlightSlots = selectedMaterial != null,
                onResident = vm::tapResident,
                onSlot = { slot ->
                    val sel = selectedMaterial
                    val placed = state.materials.firstOrNull { it.placedSlot == slot }
                    when {
                        placed != null -> vm.showMaterialMemory(placed)
                        sel != null -> { vm.place(sel, slot); selectedMaterial = null }
                    }
                },
                onFacility = { sheetFacility = it },
                wake = wake,
            )
            // 주민 이름표(20sp): 발밑 가운데
            VillageLayout.residents.forEach { (r, o) ->
                Text(
                    r.displayName, color = Color.White, fontSize = 20.sp, lineHeight = 24.sp,
                    modifier = Modifier.offset(x = maxWidth * o.x - 27.dp, y = maxHeight * o.y + 2.dp)
                        .background(Color(0x99000000), RoundedCornerShape(8.dp)).padding(horizontal = 7.dp, vertical = 2.dp),
                )
            }
            Column(Modifier.align(Alignment.TopCenter).fillMaxWidth()) {
                // 상단 바: 제목 28sp, 상태 24sp, 버튼 64dp. 넓이가 모자라면 상태 항목이 줄바꿈된다.
                Column(
                    Modifier.fillMaxWidth().padding(10.dp).background(Color(0xB3141A26), RoundedCornerShape(16.dp))
                        .pointerInput(Unit) { detectTapGestures { } } // 바 빈 곳을 눌러도 뒤의 시설이 눌리지 않게
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text("DRIVE VILLAGE", color = VillageTheme.accent, style = VillageType.title, maxLines = 1, modifier = Modifier.weight(1f))
                        BarButton(if (voiceOn) "소리 켬" else "소리 끔", onClick = vm::toggleVoice)
                        BarButton("마을 닫기", onClick = vm::closeSilently)
                    }
                    val day = SceneLedger.villageDay(state, today)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                        listOfNotNull("마을 Lv.${VillageGrowth.level(state)}", day?.let { "${it}일째" }, "공방 ${state.maintenance.workshopLevel}단계", "정차 중에만 열려요").forEach {
                            Text(it, color = VillageTheme.text, style = VillageType.body, maxLines = 1)
                        }
                    }
                }
                // 주민 말풍선(본문 24sp). 안내 시트가 떠 있는 동안은 가린다(작은 마을 화면에서 겹치지 않게).
                bubble?.takeIf { sheetFacility == null }?.let { b ->
                    Column(
                        Modifier.align(Alignment.CenterHorizontally).widthIn(max = 560.dp).padding(horizontal = 16.dp)
                            .background(Color(0xF2FFF8EC), RoundedCornerShape(16.dp)).padding(16.dp),
                    ) {
                        Text("${b.resident.displayName} · ${b.resident.role}", color = Color(0xFF8A5A3A), fontSize = 20.sp, lineHeight = 26.sp, fontWeight = FontWeight.Bold)
                        Text(b.text, color = Color(0xFF2B2420), style = VillageType.body)
                    }
                }
            }
            sheetFacility?.let { f ->
                FacilitySheet(
                    f, Facilities.hint(f, Facilities.waitingFor(f, state, odo, today)),
                    modifier = Modifier.align(Alignment.BottomCenter),
                    onOpenWorkshop = { tab = Tab.WORKSHOP; sheetFacility = null },
                    onClose = { sheetFacility = null },
                )
            }
            if (blocked) {
                Text(
                    "저장 장치를 확인할 수 없어 이번에는 기록을 저장하지 않아요.", color = Color.White, fontSize = 13.sp,
                    modifier = Modifier.align(Alignment.BottomCenter).padding(8.dp).background(Color(0xCC9C2A2A), RoundedCornerShape(8.dp)).padding(8.dp),
                )
            }
        }
    }
    // ── 패널 ──
    val panelPane: @Composable (Modifier) -> Unit = { paneModifier ->
        Column(
            paneModifier.panelSurface().padding(start = Dimens.edge, end = Dimens.edge, top = Dimens.edge),
            verticalArrangement = Arrangement.spacedBy(Dimens.gap),
        ) {
            // 탭 바: 한 줄 4칸, 높이 64dp, 사이 24dp. 라벨은 2~3자로 줄여 24sp를 지킨다.
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Dimens.gap)) {
                Tab.entries.forEach { t -> PanelTab(t, t == tab, Modifier.weight(1f)) { tab = t } }
            }
            notice?.let {
                Row(
                    Modifier.fillMaxWidth().background(VillageTheme.card, RoundedCornerShape(Dimens.cardRadius)).padding(Dimens.cardPad),
                    horizontalArrangement = Arrangement.spacedBy(Dimens.gap),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(it, color = VillageTheme.text, style = VillageType.body, modifier = Modifier.weight(1f))
                    GhostButton("확인", onClick = vm::dismissNotice)
                }
            }
            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = Dimens.edge),
                verticalArrangement = Arrangement.spacedBy(Dimens.gap),
            ) {
                when (tab) {
                    Tab.SCENE -> ScenePanel(vm, state, open, today)
                    Tab.WORKSHOP -> WorkshopPanel(vm, state, odo, today)
                    Tab.MISSION -> MissionPanel(state, today)
                    Tab.DECORATE -> DecoratePanel(state, selectedMaterial) { selectedMaterial = it }
                }
            }
        }
    }
    // Pleos 1창(세로)에서는 위아래로, 2창(가로)에서는 좌우로 놓는다. 방향은 강제하지 않는다.
    BoxWithConstraints(Modifier.fillMaxSize()) {
        if (maxHeight > maxWidth) {
            Column(Modifier.fillMaxSize()) {
                villagePane(Modifier.weight(1f).fillMaxWidth())
                panelPane(Modifier.weight(1.3f).fillMaxWidth())
            }
        } else {
            Row(Modifier.fillMaxSize()) {
                villagePane(Modifier.weight(1.25f).fillMaxHeight())
                panelPane(Modifier.weight(1f).fillMaxHeight())
            }
        }
    }
}

/** 마을 상단 바 버튼: 높이 64dp 이상, 24sp. 짙은 바 위라 크림 테두리·글자. */
@Composable
private fun BarButton(text: String, onClick: () -> Unit) {
    OutlinedButton(
        onClick = onClick, modifier = Modifier.heightIn(min = Dimens.touch), shape = RoundedCornerShape(Dimens.buttonRadius),
        contentPadding = PaddingValues(horizontal = Dimens.buttonPadCompact, vertical = Dimens.buttonPadV),
        border = BorderStroke(Dimens.outlineWidth, VillageTheme.text),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = VillageTheme.text),
    ) { Text(text, style = VillageType.label, maxLines = 1) }
}

/**
 * 시설 안내 시트: 이름(Gmarket Sans) + 1~2줄 설명 + 버튼 2개(64dp). 쉬는 시설이면 기다리는 정비,
 * 평상이면 그 시설의 짝 하나만 소개한다(누를 때만 보이는 pull 방식). 정비 응답은 여기서 받지 않는다.
 */
@Composable
private fun FacilitySheet(f: Facility, hint: String, modifier: Modifier, onOpenWorkshop: () -> Unit, onClose: () -> Unit) {
    PaperCard(
        modifier.padding(16.dp)
            .border(Dimens.outlineWidth, VillageTheme.ink.copy(alpha = 0.35f), RoundedCornerShape(Dimens.cardRadius))
            .pointerInput(Unit) { detectTapGestures { } }, // 시트 빈 곳을 눌러도 뒤의 시설·슬롯이 눌리지 않게
    ) {
        CardTitle(f.label)
        CardBody(hint)
        Spacer(Modifier.height(Dimens.gap - Dimens.inner))
        ButtonRow {
            InkButton("정비 탭에서 보기", Modifier.weight(1.7f), selected = true, onClick = onOpenWorkshop)
            InkButton("닫기", Modifier.weight(1f), onClick = onClose)
        }
    }
}

/** 탭 한 칸: 높이 64dp. 선택 = 크림 면 + 네이비 글자, 비선택 = 짙은 면 + 크림 글자(둘 다 대비 9:1 이상). */
@Composable
private fun PanelTab(t: Tab, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val shape = RoundedCornerShape(Dimens.buttonRadius)
    Box(
        modifier.heightIn(min = Dimens.touch)
            .background(if (selected) VillageTheme.paper else VillageTheme.card, shape)
            .clickable(role = Role.Tab, onClickLabel = t.label) { onClick() }
            .semantics { this.selected = selected; contentDescription = t.label },
        contentAlignment = Alignment.Center,
    ) {
        Text(t.short, color = if (selected) VillageTheme.ink else VillageTheme.text, style = VillageType.label, maxLines = 1)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ScenePanel(vm: VillageViewModel, state: AppState, open: OpenResult, today: Long) {
    when (open) {
        OpenResult.Loading -> PaperCard { CardBody("주행거리를 읽는 중이에요…") }
        OpenResult.FirstMeet -> HeroCard {
            CardTitle("마을을 시작했어요")
            CardBody("지금 주행거리를 출발점으로 기억했어요. 1km 이상 달린 뒤 P단에서 다시 열면 지난번 이후의 여정을 정산해요.")
        }
        OpenResult.Boarding -> HeroCard {
            CardTitle("마을 입구")
            CardBody("마지막 기록 뒤로 1km 이상 달리지 않았어요. 다녀와서 P단에서 열면 지난번 이후의 여정을 정산해 드릴게요.")
        }
        OpenResult.Reversed -> HeroCard {
            CardTitle("주행거리 기준을 다시 잡았어요")
            CardBody("읽은 주행거리가 이전 기록보다 작게 두 번 확인돼 출발점만 새로 저장했어요(지급 없음).")
        }
        is OpenResult.Held -> HeroCard {
            CardTitle("이번 정산은 보류했어요")
            CardBody(
                when (open.reason) {
                    "save-failed" -> "기록을 저장하지 못했어요. 지급한 것은 없고, 다음에 열 때 다시 정산해요."
                    "reversed-unconfirmed" -> "주행거리가 이전 기록보다 작게 읽혔어요. 일시적인 값일 수 있어 기준은 그대로 두었어요."
                    "invalid-value" -> "주행거리 값이 올바르지 않아 이번 정산은 건너뛰었어요."
                    "clock-invalid" -> "기기 날짜를 믿기 어려워(앱 출시 이전 날짜) 이번 정산은 건너뛰었어요."
                    else -> "주행거리를 확인하지 못했어요. 기록은 그대로 두고, 다음에 열 때 합쳐서 정산해요."
                },
            )
        }
        is OpenResult.Scene -> {
            val rec = state.ledger.scenes.firstOrNull { it.eventId == open.record.eventId } ?: open.record
            HeroCard {
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
                Row {
                    Text("${kotlin.math.floor(rec.deltaKm).toLong()}km", color = VillageTheme.ink, style = VillageType.display, modifier = Modifier.alignByBaseline())
                    Text(" 달렸어요", color = VillageTheme.ink, style = VillageType.body, modifier = Modifier.alignByBaseline())
                }
                if (open.milestonesCrossed > 0) CardBody("1,000km 이정표 ${open.milestonesCrossed}개를 넘었어요.", VillageTheme.inkOk, VillageType.bodyStrong)
                if (open.monumentsGranted > 0) CardBody("이정표 기념물 ${open.monumentsGranted}개를 세웠어요.", VillageTheme.inkOk, VillageType.bodyStrong)
                if (open.monumentsPending > 0) {
                    CardBody("기념물은 한 번에 ${SceneLedger.MAX_MILESTONES_PER_SETTLEMENT}개까지라 ${open.monumentsPending}개는 다음 정산 때 세워요(사라지지 않아요).", VillageTheme.inkSub)
                }
                if (open.newMaterials.isNotEmpty()) {
                    CardBody("이번에 받은 것", style = VillageType.bodyStrong)
                    state.materials.filter { m -> open.newMaterials.any { it.id == m.id } }.forEach { CardBody("${it.kind.label} : ${it.tag}") }
                }
                if (open.alreadyGotToday) CardBody("재료는 하루 한 번이라 이번에는 이미 받았어요. 이 여정은 일기에 남겨요.", VillageTheme.inkSub)
                open.missionDone?.let { CardBody("미션 도장: ${it.title}", VillageTheme.inkOk, VillageType.bodyStrong) }
            }
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
            PaperCard {
                val total = state.ledger.sceneKm
                val toNext = SceneLedger.MILESTONE_KM - (total % SceneLedger.MILESTONE_KM)
                CardTitle("누적 ${kotlin.math.floor(total).toLong()}km")
                CardBody("이정표 ${state.ledger.milestonesCounted}개", VillageTheme.inkSub)
                CardBody("다음 이정표까지 약 ${kotlin.math.ceil(toNext).toLong()}km", VillageTheme.inkSub)
                MoreInfo(
                    "정산은 이렇게 해요",
                    "앱을 안 연 날이 있어도 달린 거리·넘은 이정표의 기념물·정비 부탁은 사라지지 않고 다음 정산에 합쳐져요. 재료만은 정산할 때 하루 하나예요.",
                )
            }
        }
    }
    val diary = state.ledger.scenes.takeLast(3).reversed()
    if (diary.isNotEmpty()) {
        PaperCard {
            CardTitle("마을 일기")
            diary.forEach { s ->
                CardBody("${SceneLedger.dateLabel(s.epochDay)} · ${kotlin.math.floor(s.deltaKm).toLong()}km${s.tag?.let { " · $it" } ?: ""}")
            }
        }
    }
}

@Composable
private fun WorkshopPanel(vm: VillageViewModel, state: AppState, odo: Double?, today: Long) {
    var editing by remember { mutableStateOf(false) }
    val setup = state.maintenance.setup
    if (setup == null || editing) {
        SetupForm(vm, state, odo, today) { editing = false }
        return
    }
    val currentOdo = odo ?: state.ledger.baselineKm
    HeroCard {
        CardTitle("정비 공방 · ${state.maintenance.workshopLevel}단계")
        CardBody("주행거리와 날짜 중 먼저 도래하는 쪽으로 부탁이 열려요.")
        if (currentOdo == null) CardBody("주행거리를 확인하지 못해 지금은 날짜 기준만 봐요.", VillageTheme.inkWarn)
        MoreInfo(
            "응답은 이렇게 기록돼요",
            "답은 직접 하는 기록이에요(앱은 정비 여부를 알 수 없어요). 어떤 답을 눌러도 재료는 드리지 않아요. 했어요를 누르면 짝이 된 마을 시설이 다시 켜져요. 정비한 날·거리를 남기면 공방이 커져요(선택).",
        )
    }
    val statuses = Maintenance.all(state, currentOdo, today).sortedBy { if (it.open) 0 else 1 }
    val firstOpen = statuses.firstOrNull { it.open }?.def
    statuses.forEach { st -> QuestCard(vm, st, currentOdo, primary = st.def == firstOpen) }
    GhostButton("마지막 정비 시점·주기(가정값) 고치기", Modifier.fillMaxWidth()) { editing = true }
}

/** primary = 이 화면의 앰버 CTA를 가진 카드(처음 열린 부탁 하나). */
@Composable
private fun QuestCard(vm: VillageViewModel, st: QuestStatus, currentOdo: Double?, primary: Boolean) {
    var doneOpen by remember(st.def, st.open) { mutableStateOf(false) }
    var serviceKm by remember(st.def, doneOpen) { mutableStateOf(currentOdo ?: 0.0) }
    val today = vm.today()
    var serviceDay by remember(st.def, doneOpen) { mutableStateOf(today) }
    val minKm = st.baseKm ?: 0.0
    val minDay = st.baseEpochDay?.takeIf { it <= today } ?: (today - 730)
    PaperCard {
        Text(st.def.title, color = VillageTheme.ink, style = if (st.open) VillageType.title else VillageType.bodyStrong)
        when {
            st.open -> {
                CardBody(
                    when {
                        st.reservedRecheck -> "도담: 예약한 정비는 다녀오셨나요?"
                        st.baseUnconfirmed -> "점검해 보세요(기준 미확인). 마지막 정비 시점을 모르니 지금 한 번 봐 주세요."
                        else -> "도담의 부탁: ${st.def.request}"
                    },
                )
                val due = when (st.dueBy) {
                    DueBy.KM -> "주행거리 쪽이 먼저 도래했어요"
                    DueBy.DATE -> "날짜 쪽이 먼저 도래했어요"
                    DueBy.BOTH -> "주행거리·날짜 모두 도래했어요"
                    DueBy.UNCONFIRMED -> "기준 미확인: 날짜 주기로만 안내해요"
                    null -> null
                }
                due?.let { CardBody(it, VillageTheme.inkDue) }
                if (!doneOpen) {
                    ButtonColumn {
                        if (primary) PrimaryButton(QuestResponse.DONE.label, Modifier.fillMaxWidth()) { doneOpen = true }
                        else InkButton(QuestResponse.DONE.label, Modifier.fillMaxWidth(), selected = true) { doneOpen = true }
                        ButtonRow {
                            InkButton(QuestResponse.RESERVED.label, Modifier.weight(1f)) { vm.respondQuest(st.def, QuestResponse.RESERVED) }
                            InkButton(QuestResponse.LATER.label, Modifier.weight(1f)) { vm.respondQuest(st.def, QuestResponse.LATER) }
                        }
                    }
                } else {
                    CardBody(
                        if (st.growthAvailable) "정비한 날을 넣으면 다음 안내가 정확해져요. 정비한 날·거리를 남기면 공방이 커져요(선택)."
                        else "정비한 날을 넣으면 다음 안내가 정확해져요. 공방은 지난 증축 뒤 한 주기가 지나야 다시 커져요.",
                        VillageTheme.inkOk,
                    )
                    // 증감 버튼 줄과 기록 버튼 사이도 24dp를 지키도록 한 묶음으로 쌓는다.
                    ButtonColumn {
                        Stepper("정비한 날", "${java.time.LocalDate.ofEpochDay(serviceDay)}", listOf("-1달" to -30.0, "-1일" to -1.0, "+1일" to 1.0)) {
                            serviceDay = (serviceDay + it.toLong()).coerceIn(minDay, today)
                        }
                        if (currentOdo != null) {
                            Stepper("정비 당시 주행거리", "${serviceKm.roundToLong()}km", listOf("-1000" to -1000.0, "-100" to -100.0, "+100" to 100.0)) {
                                serviceKm = (serviceKm + it).coerceIn(minOf(minKm, currentOdo), currentOdo)
                            }
                        }
                        ButtonColumn {
                            val record = { vm.respondQuest(st.def, QuestResponse.DONE, if (currentOdo != null) serviceKm else null, serviceDay) }
                            if (primary) PrimaryButton("이 정비 시점으로 기록", Modifier.fillMaxWidth(), onClick = record)
                            else InkButton("이 정비 시점으로 기록", Modifier.fillMaxWidth(), selected = true, onClick = record)
                            ButtonRow {
                                InkButton("지금으로 기록", Modifier.weight(1.6f)) { vm.respondQuest(st.def, QuestResponse.DONE) }
                                InkButton("취소", Modifier.weight(1f)) { doneOpen = false }
                            }
                        }
                    }
                }
            }
            st.snoozed -> {
                val parts = listOfNotNull(st.snoozeKmLeft?.let { "약 ${kotlin.math.ceil(it).toLong()}km" }, st.snoozeDaysLeft?.let { "${it}일" })
                CardBody(
                    (if (st.reservedRecheck) "예약했어요: " else "다음에: ") + "${parts.joinToString(" 또는 ")} 뒤에 다시 여쭐게요",
                    VillageTheme.inkSub,
                )
            }
            else -> {
                val parts = listOfNotNull(st.kmLeft?.let { "약 ${it.roundToLong()}km" }, st.daysLeft?.let { "${it}일" })
                CardBody("다음까지 ${parts.joinToString(" 또는 ")} 남음", VillageTheme.inkSub)
            }
        }
        CardBody("기준: ${st.def.basis}", VillageTheme.inkSub)
    }
}

@Composable
private fun SetupForm(vm: VillageViewModel, state: AppState, odo: Double?, today: Long, onDone: () -> Unit) {
    val prev = state.maintenance.setup
    var preset by remember { mutableStateOf(prev?.presetId?.let { io.github.drivevillage.core.village.CarPreset.byId(it) } ?: io.github.drivevillage.core.village.CarPreset.SONATA_HEV) }
    var lastKm by remember { mutableStateOf(prev?.lastServiceKm ?: (odo ?: state.ledger.baselineKm ?: 0.0)) }
    var lastDay by remember { mutableStateOf(prev?.lastServiceEpochDay ?: today) }
    var unknown by remember { mutableStateOf(prev?.lastServiceUnknown ?: false) }
    var oilKm by remember { mutableStateOf(prev?.oilKm ?: preset.oilKm) }
    var oilMonths by remember { mutableStateOf(prev?.oilMonths ?: preset.oilMonths) }
    HeroCard {
        CardTitle("정비 공방 설정")
        CardBody("마지막으로 정비(엔진오일 교환 등)한 시점을 알려 주세요. 처음 값은 지금 주행거리·오늘이에요. 퀘스트는 이 시점부터 계산해요.", VillageTheme.inkSub)
    }
    PaperCard {
        CardTitle("차종 프리셋")
        CardBody("가정값이에요. 내 차 매뉴얼로 확인해 고쳐 주세요.", VillageTheme.inkSub)
        ButtonColumn {
            io.github.drivevillage.core.village.CarPreset.entries.forEach { p ->
                InkButton(p.label, Modifier.fillMaxWidth(), selected = p == preset) { preset = p; oilKm = p.oilKm; oilMonths = p.oilMonths }
            }
        }
        CardBody("출처: ${preset.source}", VillageTheme.inkSub)
    }
    PaperCard {
        CardTitle("마지막 정비 시점")
        if (odo == null && prev == null) CardBody("주행거리를 읽지 못해 0km에서 시작해요. 계기판 값을 보고 맞춰 주세요.", VillageTheme.inkWarn)
        ButtonRow {
            InkButton("입력", Modifier.weight(1f), selected = !unknown) { unknown = false }
            InkButton("모름", Modifier.weight(1f), selected = unknown) { unknown = true }
        }
        if (unknown) {
            CardBody("모름으로 두면 정비 항목은 '기준 미확인'이 돼요. 설치한 날을 정비한 날로 치지 않고, 날짜 주기로만 '점검해 보세요'를 안내해요. 정비 후 '했어요'로 정비한 날을 넣으면 그때부터 정확해져요.", VillageTheme.inkSub)
        } else {
            Stepper("마지막 정비 주행거리", "${lastKm.roundToLong()}km", listOf("-10000" to -10_000.0, "-1000" to -1000.0, "-100" to -100.0, "+100" to 100.0, "+1000" to 1000.0, "+10000" to 10_000.0)) { lastKm = (lastKm + it).coerceAtLeast(0.0) }
            Stepper("마지막 정비 날짜", "${java.time.LocalDate.ofEpochDay(lastDay)}", listOf("-1달" to -30.0, "-1일" to -1.0, "+1일" to 1.0, "+1달" to 30.0)) { lastDay = (lastDay + it.toLong()).coerceAtMost(today) }
        }
    }
    PaperCard {
        CardTitle("엔진오일 주기(가정값)")
        Stepper("거리", "${oilKm}km", listOf("-500" to -500.0, "+500" to 500.0)) {
            oilKm = (oilKm + it.toInt()).coerceIn(Maintenance.MIN_OIL_KM, Maintenance.MAX_OIL_KM)
        }
        Stepper("기간", "${oilMonths}개월", listOf("-1" to -1.0, "+1" to 1.0)) {
            oilMonths = (oilMonths + it.toInt()).coerceIn(Maintenance.MIN_MONTHS, Maintenance.MAX_MONTHS)
        }
    }
    ButtonRow {
        PrimaryButton("저장", Modifier.weight(1f)) { vm.saveSetup(preset, lastKm, lastDay, oilKm, oilMonths, unknown); onDone() }
        if (prev != null) GhostButton("취소", Modifier.weight(1f), onClick = onDone)
    }
}

/** 값 한 줄 + 증감 버튼. 버튼 폭에 맞춰 한 줄에 놓을 개수를 정한다(사이 24dp, 높이 64dp). */
@Composable
private fun Stepper(label: String, value: String, steps: List<Pair<String, Double>>, onStep: (Double) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(Dimens.inner)) {
        CardBody("$label: $value", style = VillageType.bodyStrong)
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            // 24sp 굵은 글자 기준 대략 폭: 한글 24dp, 숫자·기호 14dp + 좌우 여백
            val padH = Dimens.buttonPadCompact
            val widest: Int = steps.maxOf { (l, _) -> l.fold(0) { acc, c -> acc + (if (c in '가'..'힣') 24 else 14) } } + 2 * padH.value.toInt()
            val gap = Dimens.gap.value.toInt()
            val maxCols = ((maxWidth.value.toInt() + gap) / (widest + gap)).coerceIn(1, steps.size)
            // 마지막 줄에 하나만 남지 않게 줄 수를 먼저 정하고 고르게 나눈다.
            val rows = (steps.size + maxCols - 1) / maxCols
            val cols = (steps.size + rows - 1) / rows
            ButtonColumn {
                steps.chunked(cols).forEach { row ->
                    ButtonRow {
                        row.forEach { (l, d) -> InkButton(l, Modifier.weight(1f), padH = padH) { onStep(d) } }
                        repeat(cols - row.size) { Spacer(Modifier.weight(1f)) }
                    }
                }
            }
        }
    }
}

@Composable
private fun MissionPanel(state: AppState, today: Long) {
    val mission = Missions.today(today)
    val done = state.missions.stamps.firstOrNull { it.epochDay == today }
    HeroCard {
        CardTitle("미션(조연)")
        CardBody(mission.title, style = VillageType.bodyStrong)
        CardBody(mission.detail, VillageTheme.inkSub)
        CardBody(if (done != null) "오늘의 도장을 받았어요." else "주행 후 P단에서 마을을 열면 한 번 판정해요.", if (done != null) VillageTheme.inkOk else VillageTheme.ink)
        CardBody("못 해도 아무것도 줄지 않아요. 점수·연속일수·벌점은 없어요.", VillageTheme.inkSub)
    }
    val stamps = state.missions.stamps.takeLast(4).reversed()
    if (stamps.isNotEmpty()) {
        PaperCard {
            CardTitle("모은 도장")
            stamps.forEach { s -> CardBody("${SceneLedger.dateLabel(s.epochDay)} · ${Missions.titleOf(s.missionId)}") }
        }
    }
}

@Composable
private fun DecoratePanel(state: AppState, selected: String?, onSelect: (String?) -> Unit) {
    val unplaced = state.materials.filter { it.placedSlot == null }
    val placedCount = state.materials.count { it.placedSlot != null }
    HeroCard {
        CardTitle("마을 꾸미기")
        CardBody("재료·기념물·장식을 하나 고른 뒤 마을의 빈 자리(점선 원)를 누르면 놓여요.")
        CardBody("놓인 것을 누르면 그날의 기억이 보여요.", VillageTheme.inkSub)
        CardBody("놓인 것 $placedCount / ${io.github.drivevillage.core.village.Decoration.SLOT_COUNT}자리", style = VillageType.bodyStrong)
    }
    if (unplaced.isEmpty()) {
        PaperCard { CardBody("놓을 것이 없어요. 여정 정산(하루 한 번 재료), 1,000km 이정표, 새 계절로 생겨요.", VillageTheme.inkSub) }
    }
    unplaced.forEach { m ->
        val isSel = m.id == selected
        PaperCard {
            Row(horizontalArrangement = Arrangement.spacedBy(Dimens.gap), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    CardBody(m.kind.label, style = VillageType.bodyStrong)
                    CardBody(m.tag, VillageTheme.inkSub)
                }
                InkButton(if (isSel) "취소" else "고르기", selected = isSel) { onSelect(if (isSel) null else m.id) }
            }
            // 고른 상태는 색이 아니라 문구로도 알린다.
            if (isSel) CardBody("골랐어요. 마을의 빈 자리를 눌러 주세요.", VillageTheme.inkOk, VillageType.bodyStrong)
        }
    }
}
