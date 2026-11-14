package io.github.drivevillage.core.village

import java.time.LocalDate

/**
 * 차종 프리셋(엔진오일·필터 주기). 모두 **가정값**이다: 매뉴얼 출처는 아래와 같고, 연식·조건마다 다르므로
 * 사용자가 반드시 자기 차 매뉴얼 값으로 고칠 수 있다(설정 화면).
 */
enum class CarPreset(val id: String, val label: String, val oilKm: Int, val oilMonths: Int, val source: String) {
    SONATA_HEV("sonata-dn8-hev", "쏘나타 DN8 HEV(같은 그룹 참고치)", 15_000, 12, "2026 매뉴얼 통상 조건"),
    SONATA_HEV_SEVERE("sonata-dn8-hev-severe", "쏘나타 DN8 HEV 가혹 조건", 7_500, 6, "2026 매뉴얼 가혹 조건"),
    GRANDEUR_HEV_SEVERE("grandeur-gn7-hev-severe", "그랜저 GN7 HEV 가혹 조건", 5_000, 6, "2023 매뉴얼 가혹 조건(통상 표 미확보)"),
    TUCSON_GAS("tucson-nx4-gas", "투싼 NX4 가솔린", 10_000, 12, "2025 매뉴얼 통상 조건"),
    CUSTOM("custom", "직접 입력", 10_000, 12, "사용자 입력"),
    ;

    companion object {
        fun byId(id: String): CarPreset? = entries.firstOrNull { it.id == id }
    }
}

enum class QuestKind { SERVICE, CHECK_IN }

/**
 * 정비 공방 퀘스트 정의(§5.2). 주행거리와 날짜 중 **먼저 도래하는 쪽**으로 연다.
 * 완료는 자기 신고뿐이다(어떤 차량 신호로도 정비 완료를 알 수 없다).
 */
enum class QuestDef(
    val id: String,
    val title: String,
    val kind: QuestKind,
    /** 고정 주기(km). null이면 km 트리거 없음. 엔진오일은 설정값을 쓴다. */
    private val fixedKm: Int?,
    /** 고정 주기(개월). 엔진오일은 설정값을 쓴다. */
    private val fixedMonths: Int?,
    val request: String,
    val basis: String,
) {
    ENGINE_OIL("engine-oil", "엔진오일·필터 교환", QuestKind.SERVICE, null, null,
        "공방 화로에 새 기름을 채울 때가 됐어요.", "차종 프리셋(가정값, 사용자 수정 가능)"),
    COOLANT_LEVEL("coolant-level", "냉각수 수위 점검", QuestKind.CHECK_IN, null, 1,
        "보닛을 열고 냉각수 눈금이 MIN~MAX 사이인지 봐 주세요.", "월 1회 체크인(교체가 아니라 수위 점검)"),
    TIRE_PRESSURE("tire-pressure", "타이어 공기압 점검", QuestKind.CHECK_IN, null, 1,
        "네 바퀴 공기압을 한 번 재 봐 주세요.", "월 1회 체크인"),
    TIRE_ROTATION("tire-rotation", "타이어 위치 교환", QuestKind.SERVICE, 10_000, 12,
        "바퀴 자리를 바꿔 고르게 닳게 해 주세요.", "10,000km/12개월(기아 MQ4 매뉴얼 참고치, 가정값)"),
    CABIN_FILTER("cabin-filter", "공조 필터 교체", QuestKind.SERVICE, null, 12,
        "실내 공기 거름망을 바꿀 때예요.", "12개월(매뉴얼 참고치, 가정값)"),
    LIGHTS("lights", "등화 점검(차 한 바퀴)", QuestKind.CHECK_IN, null, 3,
        "전조등·브레이크등·방향지시등이 다 켜지는지 한 바퀴 돌아봐 주세요.", "계절마다(3개월) 체크인"),
    ;

    fun intervalKm(setup: MaintenanceSetup): Int? = if (this == ENGINE_OIL) setup.oilKm else fixedKm
    fun intervalMonths(setup: MaintenanceSetup): Int? = if (this == ENGINE_OIL) setup.oilMonths else fixedMonths

    companion object {
        fun byId(id: String): QuestDef? = entries.firstOrNull { it.id == id }
    }
}

enum class DueBy { KM, DATE, BOTH }

data class QuestStatus(
    val def: QuestDef,
    /** 도래했고 미룬 상태가 아니어서 응답을 받을 수 있음. */
    val open: Boolean,
    /** 열렸다면 어느 쪽이 도래했는지. */
    val dueBy: DueBy?,
    /** 다음 도래까지 남은 km(km 트리거가 없거나 주행거리를 모르면 null). 음수면 지남. */
    val kmLeft: Double?,
    /** 다음 도래까지 남은 일수. 음수면 지남. */
    val daysLeft: Long?,
    val baseKm: Double?,
    val baseEpochDay: Long,
    /** 주기상 도래했는지(미룸과 무관). */
    val due: Boolean = open,
    /** 도래했지만 '예약했어요'·'다음에'로 미룬 중. */
    val snoozed: Boolean = false,
    /** '예약했어요' 뒤 재확인 대상. */
    val reservedRecheck: Boolean = false,
    val snoozeDaysLeft: Long? = null,
    val snoozeKmLeft: Double? = null,
    /** 이번 주기에 응답 보상을 아직 받지 않았는지. */
    val rewardAvailable: Boolean = true,
)

object Maintenance {
    const val MIN_OIL_KM = 1_000
    const val MAX_OIL_KM = 30_000
    const val MIN_MONTHS = 1
    const val MAX_MONTHS = 24

    fun newSetup(preset: CarPreset, lastServiceKm: Double, lastServiceEpochDay: Long, oilKm: Int, oilMonths: Int, today: Long): MaintenanceSetup? {
        if (lastServiceKm.isNaN() || lastServiceKm.isInfinite() || lastServiceKm < 0) return null
        if (lastServiceEpochDay > today) return null
        if (oilKm !in MIN_OIL_KM..MAX_OIL_KM || oilMonths !in MIN_MONTHS..MAX_MONTHS) return null
        return MaintenanceSetup(preset.id, lastServiceKm, lastServiceEpochDay, oilKm, oilMonths, today)
    }

    /** 설정 저장. 다시 저장할 때는 처음 설정한 날(체크인 기준일)을 유지한다(열린 체크인이 사라지지 않게). */
    fun saveSetup(state: AppState, setup: MaintenanceSetup): AppState {
        val keepDay = state.maintenance.setup?.setupEpochDay ?: setup.setupEpochDay
        return state.copy(maintenance = state.maintenance.copy(setup = setup.copy(setupEpochDay = keepDay)))
    }

    /** 퀘스트 기준점: 마지막 '했어요', 없으면 설정(정비 항목은 마지막 정비 시점, 체크인은 설정한 날). */
    private fun base(def: QuestDef, s: MaintenanceState, setup: MaintenanceSetup): QuestRecord {
        val rec = s.records[def.id]
        if (rec != null && rec.completions > 0) return rec
        val fromSetup = when (def.kind) {
            QuestKind.SERVICE -> QuestRecord(setup.lastServiceKm, setup.lastServiceEpochDay, 0)
            QuestKind.CHECK_IN -> QuestRecord(null, setup.setupEpochDay, 0)
        }
        // 아직 '했어요'가 없으면 기준은 설정에서, 미룸·보상 기록은 저장된 기록에서.
        return rec?.copy(baseKm = fromSetup.baseKm, baseEpochDay = fromSetup.baseEpochDay) ?: fromSetup
    }

    /**
     * 도래 판정. [odoKm]이 null(주행거리 확인 불가)이면 km 쪽은 판정하지 않고 날짜만 본다.
     * km·날짜 중 하나라도 도래하면 도래(먼저 도래하는 쪽). '예약했어요'·'다음에'로 미룬 동안은 열지 않는다.
     */
    fun status(def: QuestDef, state: AppState, odoKm: Double?, today: Long): QuestStatus? {
        val setup = state.maintenance.setup ?: return null
        val b = base(def, state.maintenance, setup)
        val kmInterval = def.intervalKm(setup)
        val months = def.intervalMonths(setup)
        val kmLeft = if (kmInterval != null && b.baseKm != null && odoKm != null) b.baseKm + kmInterval - odoKm else null
        val dueDay = months?.let { LocalDate.ofEpochDay(b.baseEpochDay).plusMonths(it.toLong()).toEpochDay() }
        val daysLeft = dueDay?.let { it - today }
        val byKm = kmLeft != null && kmLeft <= 0
        val byDate = daysLeft != null && daysLeft <= 0
        val dueBy = when {
            byKm && byDate -> DueBy.BOTH
            byKm -> DueBy.KM
            byDate -> DueBy.DATE
            else -> null
        }
        val snoozeDay = b.snoozeUntilDay
        val snoozeKm = b.snoozeUntilKm
        val snoozeOver = (snoozeDay != null && today >= snoozeDay) || (snoozeKm != null && odoKm != null && odoKm >= snoozeKm)
        val snoozed = (snoozeDay != null || snoozeKm != null) && !snoozeOver
        val due = dueBy != null
        return QuestStatus(
            def = def, open = due && !snoozed, dueBy = dueBy, kmLeft = kmLeft, daysLeft = daysLeft, baseKm = b.baseKm, baseEpochDay = b.baseEpochDay,
            due = due, snoozed = due && snoozed, reservedRecheck = due && b.reservedRecheck,
            snoozeDaysLeft = snoozeDay?.let { it - today }, snoozeKmLeft = if (snoozeKm != null && odoKm != null) snoozeKm - odoKm else null,
            rewardAvailable = b.rewardedCycle != b.cycle,
        )
    }

    fun all(state: AppState, odoKm: Double?, today: Long): List<QuestStatus> =
        QuestDef.entries.mapNotNull { status(it, state, odoKm, today) }

    fun openQuests(state: AppState, odoKm: Double?, today: Long): List<QuestStatus> = all(state, odoKm, today).filter { it.open }

    /**
     * 정비 퀘스트 응답(자기 신고). 열린 퀘스트만 받는다.
     * - 어떤 응답이든 같은 작은 보상(공방 톱니 1개). 단, 같은 퀘스트 주기 안에서는 1회만.
     * - [QuestResponse.DONE]: 기준점을 지금(주행거리·날짜)으로 옮기고 새 주기를 시작, 공방 1단계 증축.
     *   [serviceKm](정비 당시 주행거리, 선택)를 넣으면 그 값을 기준으로 쓰고 기념 장식 1개를 더 준다.
     *   주행거리를 모르고 입력도 없으면 그 주기의 km 기준은 비워 둔다(날짜로만 다시 열린다).
     * - [QuestResponse.RESERVED]: [RESERVE_RECHECK_DAYS]일 뒤 재확인으로 다시 연다.
     * - [QuestResponse.LATER]: [LATER_KM]km 또는 [LATER_DAYS]일 중 먼저 오는 쪽에 다시 연다.
     * 앱은 정비 여부를 알 수 없다. 기록은 사용자의 응답일 뿐이다.
     */
    fun respond(
        state: AppState, def: QuestDef, response: QuestResponse, odoKm: Double?, today: Long, serviceKm: Double? = null,
    ): Pair<AppState, List<Material>>? {
        val st = status(def, state, odoKm, today) ?: return null
        if (!st.open) return null
        if (serviceKm != null) {
            if (response != QuestResponse.DONE) return null
            if (serviceKm.isNaN() || serviceKm.isInfinite() || serviceKm < 0) return null
            if (odoKm != null && serviceKm > odoKm) return null
        }
        val setup = state.maintenance.setup ?: return null
        val rec0 = state.maintenance.records[def.id] ?: base(def, state.maintenance, setup)
        val existing = state.materials.mapTo(HashSet()) { it.id }
        val eventId = "quest-${def.id}-${rec0.cycle}"
        val tag = "${SceneLedger.dateLabel(today)} · ${def.title} 응답"
        val rewards = mutableListOf<Material>()
        if (rec0.rewardedCycle != rec0.cycle) {
            val id = "gear-${def.id}-${rec0.cycle}"
            if (id !in existing) rewards += Material(id, MaterialKind.GEAR, today, eventId, tag)
        }
        val rec = when (response) {
            QuestResponse.DONE -> {
                if (serviceKm != null) {
                    val id = "memo-${def.id}-${rec0.cycle}"
                    if (id !in existing) rewards += Material(id, MaterialKind.MEMORIAL, today, eventId, "${SceneLedger.dateLabel(today)} · ${def.title} 기록")
                }
                QuestRecord(
                    baseKm = serviceKm ?: odoKm, baseEpochDay = today, completions = rec0.completions + 1,
                    cycle = rec0.cycle + 1, rewardedCycle = rec0.cycle,
                )
            }
            QuestResponse.RESERVED -> rec0.copy(
                rewardedCycle = rec0.cycle, snoozeUntilDay = today + RESERVE_RECHECK_DAYS, snoozeUntilKm = null, reservedRecheck = true,
            )
            QuestResponse.LATER -> rec0.copy(
                rewardedCycle = rec0.cycle, snoozeUntilDay = today + LATER_DAYS, snoozeUntilKm = odoKm?.plus(LATER_KM), reservedRecheck = false,
            )
        }
        val next = state.copy(
            maintenance = state.maintenance.copy(
                records = state.maintenance.records + (def.id to rec),
                workshopLevel = state.maintenance.workshopLevel + if (response == QuestResponse.DONE) 1 else 0,
            ),
            materials = state.materials + rewards,
        )
        return next to rewards
    }

    /** '예약했어요' 뒤 재확인까지의 일수(팀 가정값). */
    const val RESERVE_RECHECK_DAYS = 14L
    /** '다음에' 재알림: 이 거리(km) 또는 [LATER_DAYS]일 중 먼저 오는 쪽(팀 가정값). */
    const val LATER_KM = 500.0
    const val LATER_DAYS = 14L
}

enum class QuestResponse(val label: String) { DONE("했어요"), RESERVED("예약했어요"), LATER("다음에") }
