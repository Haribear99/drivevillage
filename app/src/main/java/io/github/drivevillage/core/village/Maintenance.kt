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

/** 도래 근거. UNCONFIRMED는 마지막 정비 시점을 모르는(기준 미확인) 정비 항목: 지금 점검을 권한다. */
enum class DueBy { KM, DATE, BOTH, UNCONFIRMED }

data class QuestStatus(
    val def: QuestDef,
    /** 도래했고 미룬 상태가 아니어서 응답을 받을 수 있음. */
    val open: Boolean,
    /** 열렸다면 어느 쪽이 도래했는지. */
    val dueBy: DueBy?,
    /** 다음 도래까지 남은 km(km 트리거가 없거나 주행거리·기준을 모르면 null). 음수면 지남. */
    val kmLeft: Double?,
    /** 다음 도래까지 남은 일수. 음수면 지남. 기준 미확인이면 null. */
    val daysLeft: Long?,
    /** 다음 주기 계산 기준(정비 이력). 기준 미확인이면 둘 다 null. */
    val baseKm: Double?,
    val baseEpochDay: Long?,
    /** 주기상 도래했는지(미룸과 무관). */
    val due: Boolean = open,
    /** 도래했지만 '예약했어요'·'다음에'로 미룬 중. */
    val snoozed: Boolean = false,
    /** '예약했어요' 뒤 재확인 대상. */
    val reservedRecheck: Boolean = false,
    val snoozeDaysLeft: Long? = null,
    val snoozeKmLeft: Double? = null,
    /** 지금 증빙 '했어요'로 공방이 커질 수 있는지(마지막 증축 뒤 한 주기 경과). 정비 응답은 재료를 주지 않는다. */
    val growthAvailable: Boolean = true,
    /** 마지막 정비 시점을 모르는 상태(온보딩 '모름'). */
    val baseUnconfirmed: Boolean = false,
)

object Maintenance {
    const val MIN_OIL_KM = 1_000
    const val MAX_OIL_KM = 30_000
    const val MIN_MONTHS = 1
    const val MAX_MONTHS = 24

    /**
     * 온보딩 설정. [lastServiceUnknown]이면 마지막 정비 시점을 '기준 미확인'으로 두고,
     * [lastServiceKm]·[lastServiceEpochDay]는 쓰지 않는다(설치 시점을 정비 시점으로 간주하지 않음).
     */
    fun newSetup(
        preset: CarPreset, lastServiceKm: Double, lastServiceEpochDay: Long, oilKm: Int, oilMonths: Int, today: Long,
        lastServiceUnknown: Boolean = false,
    ): MaintenanceSetup? {
        if (oilKm !in MIN_OIL_KM..MAX_OIL_KM || oilMonths !in MIN_MONTHS..MAX_MONTHS) return null
        if (lastServiceUnknown) return MaintenanceSetup(preset.id, 0.0, today, oilKm, oilMonths, today, lastServiceUnknown = true)
        if (lastServiceKm.isNaN() || lastServiceKm.isInfinite() || lastServiceKm < 0) return null
        if (lastServiceEpochDay > today) return null
        return MaintenanceSetup(preset.id, lastServiceKm, lastServiceEpochDay, oilKm, oilMonths, today)
    }

    /** 설정 저장. 다시 저장할 때는 처음 설정한 날(체크인 기준일)을 유지한다(열린 체크인이 사라지지 않게). */
    fun saveSetup(state: AppState, setup: MaintenanceSetup): AppState {
        val keepDay = state.maintenance.setup?.setupEpochDay ?: setup.setupEpochDay
        return state.copy(maintenance = state.maintenance.copy(setup = setup.copy(setupEpochDay = keepDay)))
    }

    /** 정비 이력(다음 주기 계산 기준). null이면 기준 미확인. */
    private data class Base(val km: Double?, val day: Long)

    private fun base(def: QuestDef, rec: QuestRecord?, setup: MaintenanceSetup): Base? = when {
        rec != null && rec.completions > 0 -> Base(rec.baseKm, rec.baseEpochDay)
        def.kind == QuestKind.CHECK_IN -> Base(null, setup.setupEpochDay)
        setup.lastServiceUnknown -> null
        else -> Base(setup.lastServiceKm, setup.lastServiceEpochDay)
    }

    /**
     * 공방 증축 가능 여부(정비 이력과 분리). 마지막 증축 시점(신고 당시 주행거리·날짜, 저장 필드명은 이전의 memorialAnchor)에서
     * 한 주기가 지나야 다시. 이력에 과거 값을 넣어 곧바로 다시 도래시켜도 증축은 반복되지 않는다.
     */
    private fun memorialOk(def: QuestDef, rec: QuestRecord?, setup: MaintenanceSetup, odoKm: Double?, today: Long): Boolean =
        rec == null || anchorPassed(def, setup, rec.memorialAnchorKm, rec.memorialAnchorDay, odoKm, today)

    /** 앵커(마지막 보상 시점)에서 한 주기(km 또는 개월, 먼저 오는 쪽)가 지났는지. 앵커가 없으면 참. */
    private fun anchorPassed(def: QuestDef, setup: MaintenanceSetup, aKm: Double?, aDay: Long?, odoKm: Double?, today: Long): Boolean {
        if (aDay == null) return true
        val kmI = def.intervalKm(setup)
        val months = def.intervalMonths(setup)
        val byKm = kmI != null && aKm != null && odoKm != null && odoKm >= aKm + kmI
        val byDate = months != null && aDay <= today && today >= LocalDate.ofEpochDay(aDay).plusMonths(months.toLong()).toEpochDay()
        return byKm || byDate || (kmI == null && months == null)
    }

    /**
     * 도래 판정. [odoKm]이 null(주행거리 확인 불가)이면 km 쪽은 판정하지 않고 날짜만 본다.
     * km·날짜 중 하나라도 도래하면 도래(먼저 도래하는 쪽). 기준 미확인 정비 항목은 지금 점검을 권한다(날짜 기준만).
     * '예약했어요'·'다음에'로 미룬 동안은 열지 않는다.
     */
    fun status(def: QuestDef, state: AppState, odoKm: Double?, today: Long): QuestStatus? {
        val setup = state.maintenance.setup ?: return null
        val rec = state.maintenance.records[def.id]
        val b = base(def, rec, setup)
        val unconfirmed = b == null
        // 시계가 앞선 상태에서 저장된 기준일(오늘보다 미래)은 믿지 않고 '지금 도래'로 본다(다시 답하면 기준이 바로잡힌다).
        val futureBase = b != null && b.day > today + 1
        val kmInterval = def.intervalKm(setup)
        val months = def.intervalMonths(setup)
        val kmLeft = if (kmInterval != null && b?.km != null && odoKm != null) b.km + kmInterval - odoKm else null
        val dueDay = if (b == null) null else months?.let { if (futureBase) today else LocalDate.ofEpochDay(b.day).plusMonths(it.toLong()).toEpochDay() }
        val daysLeft = dueDay?.let { it - today }
        val byKm = kmLeft != null && kmLeft <= 0
        val byDate = daysLeft != null && daysLeft <= 0
        val dueBy = when {
            unconfirmed -> DueBy.UNCONFIRMED
            byKm && byDate -> DueBy.BOTH
            byKm -> DueBy.KM
            byDate -> DueBy.DATE
            else -> null
        }
        // 미룸 기한이 가능한 최대 미룸보다 멀면(앞선 시계) 미룸이 끝난 것으로 본다.
        val maxSnooze = maxOf(RESERVE_RECHECK_DAYS, LATER_DAYS)
        val snoozeDay = rec?.snoozeUntilDay?.takeIf { it <= today + maxSnooze + 1 }
        val snoozeKm = rec?.snoozeUntilKm
        val snoozeOver = (snoozeDay != null && today >= snoozeDay) || (snoozeKm != null && odoKm != null && odoKm >= snoozeKm)
        val snoozed = (snoozeDay != null || snoozeKm != null) && !snoozeOver
        val due = dueBy != null
        return QuestStatus(
            def = def, open = due && !snoozed, dueBy = dueBy, kmLeft = kmLeft, daysLeft = daysLeft, baseKm = b?.km, baseEpochDay = b?.day,
            due = due, snoozed = due && snoozed, reservedRecheck = due && (rec?.reservedRecheck == true),
            snoozeDaysLeft = snoozeDay?.let { it - today }, snoozeKmLeft = if (snoozeKm != null && odoKm != null) snoozeKm - odoKm else null,
            growthAvailable = memorialOk(def, rec, setup, odoKm, today), baseUnconfirmed = unconfirmed,
        )
    }

    fun all(state: AppState, odoKm: Double?, today: Long): List<QuestStatus> =
        QuestDef.entries.mapNotNull { status(it, state, odoKm, today) }

    fun openQuests(state: AppState, odoKm: Double?, today: Long): List<QuestStatus> = all(state, odoKm, today).filter { it.open }

    /**
     * 정비 퀘스트 응답(자기 신고). 열린 퀘스트만 받는다. 앱은 정비 여부를 알 수 없다. 기록은 사용자의 응답일 뿐이다.
     *
     * 정비 응답은 **재료를 주지 않는다**(0.1.4 P2). 반응은 시설이 다시 켜지는 연출과 주민의 한 줄 인사뿐이다(화면 쪽).
     * 이전 저장 데이터의 공방 톱니·정비 기념패는 그대로 둔다.
     * - 선택 증빙: '했어요'에서 정비 당시 날짜·주행거리를 넣으면 공방 증축 1단계. 마지막 증축 시점
     *   (신고 당시 주행거리·날짜)부터 한 주기가 지나야 다음 증축(과거 값으로 재도래시켜 반복해도 늘지 않음).
     * - [QuestResponse.DONE]: 다음 주기 기준을 [serviceKm]·[serviceDay](정비 당시, 선택)로 잡는다. 입력이 없으면 지금 값.
     *   입력은 현재 주행거리 이하·직전 기준 이상, 날짜는 미래가 아니고 직전 기준일 이상이어야 한다(아니면 거절).
     * - [QuestResponse.RESERVED]: [RESERVE_RECHECK_DAYS]일 뒤 재확인으로 다시 연다.
     * - [QuestResponse.LATER]: [LATER_KM]km 또는 [LATER_DAYS]일 중 먼저 오는 쪽(기준 미확인이면 날짜만).
     *
     * @return (새 상태, 공방이 커졌는지). 받을 수 없는 응답이면 null.
     */
    fun respond(
        state: AppState, def: QuestDef, response: QuestResponse, odoKm: Double?, today: Long,
        serviceKm: Double? = null, serviceDay: Long? = null,
    ): Pair<AppState, Boolean>? {
        val st = status(def, state, odoKm, today) ?: return null
        if (!st.open) return null
        val setup = state.maintenance.setup ?: return null
        val rec = state.maintenance.records[def.id]
        val prev = base(def, rec, setup)
        if (serviceKm != null || serviceDay != null) {
            if (response != QuestResponse.DONE) return null
        }
        if (serviceKm != null) {
            if (odoKm == null) return null
            if (serviceKm.isNaN() || serviceKm.isInfinite() || serviceKm < 0 || serviceKm > odoKm) return null
            if (prev?.km != null && serviceKm < prev.km) return null
        }
        if (serviceDay != null) {
            if (serviceDay > today) return null
            if (prev != null && serviceDay < prev.day && prev.day <= today + 1) return null
        }
        val rec0 = rec ?: QuestRecord(prev?.km, prev?.day ?: today, 0)
        // 공방 증축: 증빙이 있는 '했어요'에서만(예약했어요·다음에 뒤의 증빙 '했어요'도 포함). 재료는 주지 않는다.
        val grow = response == QuestResponse.DONE && (serviceKm != null || serviceDay != null) && memorialOk(def, rec, setup, odoKm, today)
        // 증축한 응답이면 증축 기준점을 지금(신고 당시)으로 옮긴다. 정비 이력과는 별개다.
        val anchored = if (grow) rec0.copy(memorialAnchorKm = odoKm, memorialAnchorDay = today) else rec0
        val newRec = when (response) {
            QuestResponse.DONE -> anchored.copy(
                baseKm = serviceKm ?: odoKm, baseEpochDay = serviceDay ?: today, completions = rec0.completions + 1,
                cycle = rec0.cycle + 1, snoozeUntilDay = null, snoozeUntilKm = null, reservedRecheck = false,
            )
            QuestResponse.RESERVED -> anchored.copy(
                snoozeUntilDay = today + RESERVE_RECHECK_DAYS, snoozeUntilKm = null, reservedRecheck = true,
            )
            QuestResponse.LATER -> anchored.copy(
                snoozeUntilDay = today + LATER_DAYS, snoozeUntilKm = if (st.baseUnconfirmed) null else odoKm?.plus(LATER_KM), reservedRecheck = false,
            )
        }
        val next = state.copy(
            maintenance = state.maintenance.copy(
                records = state.maintenance.records + (def.id to newRec),
                // 공방 증축은 증빙(정비 당시 날짜·주행거리 입력)이 있는 '했어요'에서, 증축 조건을 만족할 때만.
                workshopLevel = state.maintenance.workshopLevel + if (grow) 1 else 0,
            ),
        )
        return next to grow
    }

    /** '예약했어요' 뒤 재확인까지의 일수(팀 가정값). */
    const val RESERVE_RECHECK_DAYS = 14L
    /** '다음에' 재알림: 이 거리(km) 또는 [LATER_DAYS]일 중 먼저 오는 쪽(팀 가정값). */
    const val LATER_KM = 500.0
    const val LATER_DAYS = 14L
}

enum class QuestResponse(val label: String) { DONE("했어요"), RESERVED("예약했어요"), LATER("다음에") }
