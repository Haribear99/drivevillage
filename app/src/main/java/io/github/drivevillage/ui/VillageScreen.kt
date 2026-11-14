package io.github.drivevillage.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.drivevillage.core.village.AppState
import io.github.drivevillage.core.village.DueBy
import io.github.drivevillage.core.village.Maintenance
import io.github.drivevillage.core.village.Missions
import io.github.drivevillage.core.village.Resident
import io.github.drivevillage.core.village.SceneLedger
import kotlin.math.roundToLong

private enum class Tab(val label: String) { SCENE("오늘의 장면"), WORKSHOP("정비 공방"), MISSION("미션"), DECORATE("꾸미기") }

@Composable
fun VillageScreen(vm: VillageViewModel) {
    val state by vm.state.collectAsState()
    val open by vm.open.collectAsState()
    val bubble by vm.bubble.collectAsState()
    val notice by vm.notice.collectAsState()
    val now by vm.now.collectAsState()
    val odo by vm.odoKm.collectAsState()
    val voiceOn by vm.voiceOn.collectAsState()
    val blocked by vm.writesBlocked.collectAsState()
    var tab by remember { mutableStateOf(if (state.maintenance.setup == null && open !is OpenResult.Scene) Tab.WORKSHOP else Tab.SCENE) }
    var selectedMaterial by remember { mutableStateOf<String?>(null) }
    // 오늘의 장면이 생기면 그 카드를 먼저 보여 준다.
    LaunchedEffect(open) { if (open is OpenResult.Scene) tab = Tab.SCENE }
    val today = vm.today()
    val timeSlot = SceneLedger.slotOf(now, java.time.ZoneId.systemDefault())

    Row(Modifier.fillMaxSize()) {
        // ── 왼쪽: 마을 전경 ──
        BoxWithConstraints(Modifier.weight(1.25f).fillMaxHeight()) {
            VillageCanvas(
                modifier = Modifier.fillMaxSize(),
                timeSlot = timeSlot,
                workshopLevel = state.maintenance.workshopLevel,
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
            )
            // 주민 이름표
            VillageLayout.residents.forEach { (r, o) ->
                Text(
                    r.displayName, color = Color.White, fontSize = 12.sp,
                    modifier = Modifier.offset(x = maxWidth * o.x - 14.dp, y = maxHeight * o.y + 2.dp)
                        .background(Color(0x99000000), RoundedCornerShape(6.dp)).padding(horizontal = 5.dp, vertical = 1.dp),
                )
            }
            // 상단 바
            Row(
                Modifier.fillMaxWidth().padding(10.dp).background(Color(0x99141A26), RoundedCornerShape(12.dp)).padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("DRIVE VILLAGE", color = VillageTheme.accent, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                    val day = SceneLedger.villageDay(state, today)
                    Text(
                        listOfNotNull(day?.let { "마을 ${it}일째" }, "공방 ${state.maintenance.workshopLevel}단계", "정차 중에만 열려요").joinToString(" · "),
                        color = VillageTheme.subtext, fontSize = 12.sp,
                    )
                }
                TextButton(onClick = vm::toggleVoice) { Text(if (voiceOn) "소리 켬" else "소리 끔", fontSize = 13.sp) }
                TextButton(onClick = vm::closeSilently) { Text("마을 닫기", fontSize = 13.sp) }
            }
            // 주민 말풍선
            bubble?.let { b ->
                Column(
                    Modifier.align(Alignment.TopCenter).offset(y = 70.dp).widthIn(max = 440.dp).padding(horizontal = 16.dp)
                        .background(Color(0xF2FFF8EC), RoundedCornerShape(16.dp)).padding(14.dp),
                ) {
                    Text("${b.resident.displayName} · ${b.resident.role}", color = Color(0xFF8A5A3A), fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    Text(b.text, color = Color(0xFF2B2420), fontSize = 16.sp)
                }
            }
            if (blocked) {
                Text(
                    "저장 장치를 확인할 수 없어 이번에는 기록을 저장하지 않아요.", color = Color.White, fontSize = 13.sp,
                    modifier = Modifier.align(Alignment.BottomCenter).padding(8.dp).background(Color(0xCC9C2A2A), RoundedCornerShape(8.dp)).padding(8.dp),
                )
            }
        }
        // ── 오른쪽: 패널 ──
        Column(Modifier.weight(1f).fillMaxHeight().background(VillageTheme.panel).padding(12.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Tab.entries.forEach { t ->
                    if (t == tab) Button(onClick = { tab = t }) { Text(t.label, fontSize = 13.sp) }
                    else OutlinedButton(onClick = { tab = t }) { Text(t.label, fontSize = 13.sp) }
                }
            }
            notice?.let {
                Row(
                    Modifier.fillMaxWidth().padding(top = 8.dp).background(VillageTheme.card, RoundedCornerShape(10.dp)).padding(horizontal = 10.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(it, color = VillageTheme.text, fontSize = 13.sp, modifier = Modifier.weight(1f))
                    TextButton(onClick = vm::dismissNotice) { Text("확인", fontSize = 12.sp) }
                }
            }
            Spacer(Modifier.height(8.dp))
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                when (tab) {
                    Tab.SCENE -> ScenePanel(vm, state, open, today)
                    Tab.WORKSHOP -> WorkshopPanel(vm, state, odo, today)
                    Tab.MISSION -> MissionPanel(state, today)
                    Tab.DECORATE -> DecoratePanel(state, selectedMaterial) { selectedMaterial = it }
                }
            }
        }
    }
}

@Composable
private fun Title(text: String) = Text(text, color = VillageTheme.accent, fontWeight = FontWeight.Bold, fontSize = 17.sp, modifier = Modifier.padding(bottom = 4.dp))

@Composable
private fun Body(text: String, color: Color = VillageTheme.text, size: Int = 14) = Text(text, color = color, fontSize = size.sp, modifier = Modifier.padding(vertical = 2.dp))

@Composable
private fun Card(content: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp).background(VillageTheme.card, RoundedCornerShape(12.dp)).padding(12.dp)) { content() }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ScenePanel(vm: VillageViewModel, state: AppState, open: OpenResult, today: Long) {
    when (open) {
        OpenResult.Loading -> Body("주행거리를 읽는 중이에요…")
        OpenResult.FirstMeet -> Card {
            Title("마을을 시작했어요")
            Body("지금 주행거리를 출발점으로 기억했어요. 1km 이상 달린 뒤 P단에서 다시 열면 오늘의 장면이 생겨요.")
        }
        OpenResult.Boarding -> Card {
            Title("마을 입구")
            Body("마지막 기록 뒤로 1km 이상 달리지 않았어요. 다녀와서 P단에서 열면 오늘의 장면을 보여 드릴게요.")
        }
        OpenResult.Reversed -> Card {
            Title("주행거리 기준을 다시 잡았어요")
            Body("읽은 주행거리가 이전 기록보다 작아 출발점만 새로 저장했어요(재료 지급 없음).")
        }
        is OpenResult.Held -> Card {
            Title("이번 장면은 보류했어요")
            Body(
                when (open.reason) {
                    "save-failed" -> "기록을 저장하지 못했어요. 지급한 것은 없고, 다음에 열 때 다시 정산해요."
                    "reversed-unconfirmed" -> "주행거리가 이전 기록보다 작게 읽혔어요. 일시적인 값일 수 있어 기준은 그대로 두었어요."
                    "invalid-value" -> "주행거리 값이 올바르지 않아 이번 정산은 건너뛰었어요."
                    "clock-invalid" -> "기기 날짜가 마을을 시작한 날보다 이전이라 이번 정산은 건너뛰었어요."
                    else -> "주행거리를 확인하지 못했어요. 기록은 그대로 두고, 다음에 열 때 합쳐서 정산해요."
                },
            )
        }
        is OpenResult.Scene -> Card {
            val rec = state.ledger.scenes.firstOrNull { it.eventId == open.record.eventId } ?: open.record
            Title("오늘의 장면 · ${SceneLedger.dateLabel(rec.epochDay)}")
            Body("마지막 기록 이후 ${kotlin.math.floor(rec.deltaKm).toLong()}km 이동", size = 18)
            if (open.alreadyGotToday) {
                Body("오늘 재료는 이미 받았어요(하루 1회, 거리와 무관). 이 장면은 일기에 남겨요.", VillageTheme.subtext)
            } else {
                Body("오늘의 재료 ${open.newMaterials.size}개를 받았어요(하루 1회, 거리와 무관):")
                state.materials.filter { m -> open.newMaterials.any { it.id == m.id } }.forEach { Body("· ${it.kind.label} — ${it.tag}", VillageTheme.subtext, 13) }
            }
            open.missionDone?.let { Body("미션 도장: ${it.title}", Color(0xFF9FE0A8)) }
            Spacer(Modifier.height(6.dp))
            Body("오늘의 한 줄(고르면 재료의 기억 태그가 돼요)", VillageTheme.subtext, 13)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                SceneLedger.TAG_PRESETS.forEach { tag ->
                    if (rec.tag == tag) Button(onClick = {}) { Text(tag, fontSize = 12.sp) }
                    else OutlinedButton(onClick = { vm.chooseTag(rec.eventId, tag) }) { Text(tag, fontSize = 12.sp) }
                }
            }
        }
    }
    val diary = state.ledger.scenes.takeLast(4).reversed()
    if (diary.isNotEmpty()) {
        Spacer(Modifier.height(8.dp))
        Title("마을 일기")
        diary.forEach { s ->
            Body("${SceneLedger.dateLabel(s.epochDay)} · ${kotlin.math.floor(s.deltaKm).toLong()}km${s.tag?.let { " · $it" } ?: ""}", VillageTheme.subtext, 13)
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
    Card {
        Title("정비 공방 · ${state.maintenance.workshopLevel}단계")
        Body("주행거리와 날짜 중 먼저 도래하는 쪽으로 부탁이 열려요. 완료는 직접 신고한 기록이에요(차량은 정비 여부를 알 수 없어요).", VillageTheme.subtext, 13)
        if (currentOdo == null) Body("주행거리를 확인하지 못해 지금은 날짜 기준만 봐요.", Color(0xFFFFB4A8), 13)
    }
    val statuses = Maintenance.all(state, currentOdo, today).sortedBy { if (it.open) 0 else 1 }
    statuses.forEach { st ->
        Card {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(st.def.title, color = if (st.open) VillageTheme.accent else VillageTheme.text, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                    if (st.open) {
                        Body("도담의 부탁: ${st.def.request}", size = 13)
                        Body(
                            when (st.dueBy) {
                                DueBy.KM -> "주행거리 쪽이 먼저 도래했어요"
                                DueBy.DATE -> "날짜 쪽이 먼저 도래했어요"
                                DueBy.BOTH -> "주행거리·날짜 모두 도래했어요"
                                null -> ""
                            }, Color(0xFFFFD58A), 12,
                        )
                    } else {
                        val parts = listOfNotNull(
                            st.kmLeft?.let { "약 ${it.roundToLong()}km" },
                            st.daysLeft?.let { "${it}일" },
                        )
                        Body("다음까지 ${parts.joinToString(" 또는 ")} 남음", VillageTheme.subtext, 12)
                    }
                    Body("기준: ${st.def.basis}", VillageTheme.subtext, 11)
                }
                if (st.open) Button(onClick = { vm.completeQuest(st.def) }) { Text("완료 신고", fontSize = 12.sp) }
            }
        }
    }
    TextButton(onClick = { editing = true }) { Text("마지막 정비 시점·주기(가정값) 고치기", fontSize = 13.sp) }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SetupForm(vm: VillageViewModel, state: AppState, odo: Double?, today: Long, onDone: () -> Unit) {
    val prev = state.maintenance.setup
    var preset by remember { mutableStateOf(prev?.presetId?.let { io.github.drivevillage.core.village.CarPreset.byId(it) } ?: io.github.drivevillage.core.village.CarPreset.SONATA_HEV) }
    var lastKm by remember { mutableStateOf(prev?.lastServiceKm ?: (odo ?: state.ledger.baselineKm ?: 0.0)) }
    var lastDay by remember { mutableStateOf(prev?.lastServiceEpochDay ?: today) }
    var oilKm by remember { mutableStateOf(prev?.oilKm ?: preset.oilKm) }
    var oilMonths by remember { mutableStateOf(prev?.oilMonths ?: preset.oilMonths) }
    Card {
        Title("정비 공방 설정")
        Body("마지막으로 정비(엔진오일 교환 등)한 시점을 알려 주세요. 처음 값은 지금 주행거리·오늘이에요. 퀘스트는 이 시점부터 계산해요.", VillageTheme.subtext, 13)
        Body("차종 프리셋(가정값 — 내 차 매뉴얼로 확인해 고쳐 주세요)", size = 13)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            io.github.drivevillage.core.village.CarPreset.entries.forEach { p ->
                val pick = { preset = p; oilKm = p.oilKm; oilMonths = p.oilMonths }
                if (p == preset) Button(onClick = pick) { Text(p.label, fontSize = 11.sp) } else OutlinedButton(onClick = pick) { Text(p.label, fontSize = 11.sp) }
            }
        }
        Body("출처: ${preset.source}", VillageTheme.subtext, 11)
        if (odo == null && prev == null) Body("주행거리를 읽지 못해 0km에서 시작해요. 계기판 값을 보고 맞춰 주세요.", Color(0xFFFFB4A8), 12)
        Stepper("마지막 정비 주행거리", "${lastKm.roundToLong()}km", listOf("-10000" to -10_000.0, "-1000" to -1000.0, "-100" to -100.0, "+100" to 100.0, "+1000" to 1000.0, "+10000" to 10_000.0)) { lastKm = (lastKm + it).coerceAtLeast(0.0) }
        Stepper("마지막 정비 날짜", "${java.time.LocalDate.ofEpochDay(lastDay)}", listOf("-1달" to -30.0, "-1일" to -1.0, "+1일" to 1.0, "+1달" to 30.0)) { lastDay = (lastDay + it.toLong()).coerceAtMost(today) }
        Stepper("엔진오일 주기(km, 가정값)", "${oilKm}km", listOf("-500" to -500.0, "+500" to 500.0)) {
            oilKm = (oilKm + it.toInt()).coerceIn(Maintenance.MIN_OIL_KM, Maintenance.MAX_OIL_KM)
        }
        Stepper("엔진오일 주기(개월, 가정값)", "${oilMonths}개월", listOf("-1" to -1.0, "+1" to 1.0)) {
            oilMonths = (oilMonths + it.toInt()).coerceIn(Maintenance.MIN_MONTHS, Maintenance.MAX_MONTHS)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { vm.saveSetup(preset, lastKm, lastDay, oilKm, oilMonths); onDone() }) { Text("저장", fontSize = 13.sp) }
            if (prev != null) OutlinedButton(onClick = onDone) { Text("취소", fontSize = 13.sp) }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Stepper(label: String, value: String, steps: List<Pair<String, Double>>, onStep: (Double) -> Unit) {
    Column(Modifier.padding(vertical = 4.dp)) {
        Text("$label: $value", color = VillageTheme.text, fontSize = 13.sp)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            steps.forEach { (l, d) -> OutlinedButton(onClick = { onStep(d) }) { Text(l, fontSize = 11.sp) } }
        }
    }
}

@Composable
private fun MissionPanel(state: AppState, today: Long) {
    val mission = Missions.today(today)
    val done = state.missions.stamps.firstOrNull { it.epochDay == today }
    Card {
        Title("오늘의 미션(조연)")
        Text(mission.title, color = VillageTheme.text, fontWeight = FontWeight.Bold, fontSize = 15.sp)
        Body(mission.detail, VillageTheme.subtext, 13)
        Body(if (done != null) "오늘의 도장을 받았어요." else "주행 후 P단에서 마을을 열면 한 번 판정해요.", if (done != null) Color(0xFF9FE0A8) else VillageTheme.subtext, 13)
        Body("못 해도 아무것도 줄지 않아요. 점수·연속일수·벌점은 없어요.", VillageTheme.subtext, 12)
    }
    val stamps = state.missions.stamps.takeLast(6).reversed()
    if (stamps.isNotEmpty()) {
        Title("모은 도장")
        stamps.forEach { s ->
            Body("${SceneLedger.dateLabel(s.epochDay)} · ${Missions.byId(s.missionId)?.title ?: s.missionId}", VillageTheme.subtext, 13)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DecoratePanel(state: AppState, selected: String?, onSelect: (String?) -> Unit) {
    val unplaced = state.materials.filter { it.placedSlot == null }
    Card {
        Title("마을 꾸미기")
        Body("재료를 하나 고른 뒤 마을의 빈 자리(점선 원)를 누르면 놓여요. 놓인 재료를 누르면 그날의 기억이 보여요.", VillageTheme.subtext, 13)
    }
    if (unplaced.isEmpty()) Body("놓을 재료가 없어요. 주행 후 P단에서 열면 하루 한 번 재료가 생겨요.", VillageTheme.subtext, 13)
    unplaced.forEach { m ->
        val isSel = m.id == selected
        Row(
            Modifier.fillMaxWidth().padding(vertical = 3.dp)
                .border(if (isSel) 2.dp else 0.dp, if (isSel) VillageTheme.accent else Color.Transparent, RoundedCornerShape(10.dp))
                .background(VillageTheme.card, RoundedCornerShape(10.dp)).padding(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(m.kind.label, color = VillageTheme.text, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                Text(m.tag, color = VillageTheme.subtext, fontSize = 12.sp)
            }
            if (isSel) OutlinedButton(onClick = { onSelect(null) }) { Text("취소", fontSize = 12.sp) }
            else FilledTonalButton(onClick = { onSelect(m.id) }) { Text("고르기", fontSize = 12.sp) }
        }
    }
    val placedCount = state.materials.count { it.placedSlot != null }
    Body("놓인 재료 $placedCount / ${io.github.drivevillage.core.village.Decoration.SLOT_COUNT}자리", VillageTheme.subtext, 12)
    Spacer(Modifier.width(1.dp))
}
