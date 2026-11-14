package io.github.drivevillage.core.village

import io.github.drivevillage.core.gate.GearState
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** 앱을 열 때 읽은 누적 주행거리. */
sealed interface OdometerReading {
    data class Km(val value: Double) : OdometerReading
    /** 조회 실패(오류 콜백, 시간 초과, 권한 거부, 미지원 포함). */
    data class Failed(val reason: String) : OdometerReading
}

/** 열 때 정산 결과. */
sealed interface SceneResult {
    /** 최초: 기준값만 저장(마을 시작), 재료 없음. */
    data object FirstMeet : SceneResult
    /** 1km 미만 증가(0 포함): 기준값 유지 → 탑승 인사. */
    data object Boarding : SceneResult
    /** 읽기 실패: 보류. 기준값·저장 상태 변경 없음. */
    data class Held(val reason: String) : SceneResult
    /** 값 역전이 두 번 연속 확인됨: 기준값만 재설정, 재료 없음. 한 번만 읽힌 역전은 [Held]("reversed-unconfirmed"). */
    data object Reversed : SceneResult
    /** 오늘의 장면(하차). */
    data class Scene(
        val record: SceneRecord,
        /** 이번 장면으로 받은 재료. 오늘 이미 받았다면 비어 있다. */
        val newMaterials: List<Material>,
        /** 오늘 이미 재료를 받아 이번에는 지급하지 않았는지. */
        val alreadyGotToday: Boolean,
        /** 오늘의 미션을 이번 장면으로 완료했는지(이미 완료한 날이면 null). */
        val missionDone: MissionDef?,
    ) : SceneResult
}

/**
 * 오늘의 장면 정산(순수 함수). 호출자는 돌려받은 새 상태를 한 번에 저장한다.
 *
 * 규칙
 * - 마지막 정산 기준값 대비 주행거리 증가가 1km 이상이면 장면을 만든다. 기준값은 같은 상태 안에서 앞으로 옮긴다.
 *   그래서 같은 증가분으로 장면·재료가 두 번 생기지 않는다(재시작·중복 호출에도 멱등).
 * - 재료는 거리와 무관하게 하루 1회, [MATERIALS_PER_DAY]개. 재료마다 날짜·기억 태그·출처 이벤트 id를 붙인다.
 * - 미션은 normal 신호(기어 P, 주행거리)로만 판정하고 하루 최대 1개 도장. 실패해도 아무것도 깎지 않는다.
 */
object SceneLedger {
    const val MIN_SCENE_KM = 1.0
    const val MATERIALS_PER_DAY = 2
    const val HISTORY_LIMIT = 60

    val TAG_PRESETS = listOf("잠깐 쉬었어요", "친구를 만났어요", "그냥 출근길", "멀리 다녀왔어요", "장 보고 왔어요")

    fun settle(state: AppState, reading: OdometerReading, gear: GearState, nowMillis: Long, zone: ZoneId): Pair<AppState, SceneResult> {
        val today = epochDay(nowMillis, zone)
        val founded = foundIfNeeded(state, today)
        val ledger = founded.ledger
        val km = when (reading) {
            is OdometerReading.Failed -> return founded to SceneResult.Held(reading.reason)
            is OdometerReading.Km -> reading.value
        }
        // 0km는 실제 누적 주행거리로 보기 어렵다(초기화·오류 값). 기준값을 건드리지 않고 보류한다.
        if (km.isNaN() || km.isInfinite() || km <= 0) return founded to SceneResult.Held("invalid-value")
        val baseline = ledger.baselineKm
            ?: return founded.copy(ledger = ledger.copy(baselineKm = km, lastSettledAtMillis = nowMillis)) to SceneResult.FirstMeet
        if (km < baseline) {
            // 일시적으로 낮은 값 한 번으로 기준값을 내리면, 다음 정상 값에서 이미 센 거리로 장면이 다시 생긴다.
            // 다음 정산에서도 기준값보다 낮고 후보 이상(그 뒤로 달렸어도)인 값이 읽힐 때만 기준값을 바꾼다.
            val confirmed = ledger.reversalCandidateKm?.let { km >= it - 1e-6 } == true
            return if (confirmed) {
                founded.copy(ledger = ledger.copy(baselineKm = km, lastSettledAtMillis = nowMillis, reversalCandidateKm = null)) to SceneResult.Reversed
            } else {
                founded.copy(ledger = ledger.copy(reversalCandidateKm = km)) to SceneResult.Held("reversed-unconfirmed")
            }
        }
        val delta = km - baseline
        // 기준값을 바꾸지 않아 1km 미만 증가분이 다음 정산에 합쳐진다.
        if (delta < MIN_SCENE_KM) {
            val cleared = if (ledger.reversalCandidateKm != null) founded.copy(ledger = ledger.copy(reversalCandidateKm = null)) else founded
            return cleared to SceneResult.Boarding
        }

        val eventId = "scene-${ledger.sceneCount + 1}"
        // 시계가 하루 뒤로 간 경우(시간대 변경 등)는 '이미 받음'으로 본다(이중 지급 방지).
        // 지급일이 오늘보다 이틀 이상 앞서 있으면 시계가 앞섰던 기록으로 보고 무시한다(지급이 멈추지 않게).
        // 어떤 경우든 오늘 날짜로 이미 받은 재료가 있으면 다시 주지 않는다(하루 2개 상한).
        val last = ledger.lastMaterialEpochDay
        val lastIsFromFutureClock = last != null && last > today + 1
        val alreadyGot = founded.materials.any { it.epochDay == today } || (last != null && !lastIsFromFutureClock && today <= last)
        val newMaterials = if (alreadyGot) emptyList() else (0 until MATERIALS_PER_DAY).map { i ->
            Material(
                id = "m-$eventId-$i",
                kind = MaterialKind.entries[Math.floorMod(today + i, MaterialKind.entries.size.toLong()).toInt()],
                epochDay = today,
                eventId = eventId,
                tag = defaultTag(today),
            )
        }
        val record = SceneRecord(eventId, today, nowMillis, delta, newMaterials.map { it.id })

        val mission = Missions.today(today)
        val alreadyStamped = founded.missions.stamps.any { it.epochDay == today }
        val missionDone = if (!alreadyStamped && Missions.judge(mission, delta, gear)) mission else null
        val missions = if (missionDone != null) {
            founded.missions.copy(stamps = (founded.missions.stamps + MissionStamp(missionDone.id, today, eventId)).takeLast(HISTORY_LIMIT))
        } else founded.missions

        val next = founded.copy(
            ledger = ledger.copy(
                baselineKm = km,
                lastSettledAtMillis = nowMillis,
                sceneCount = ledger.sceneCount + 1,
                sceneKm = ledger.sceneKm + delta,
                lastMaterialEpochDay = if (alreadyGot) ledger.lastMaterialEpochDay else today,
                reversalCandidateKm = null,
                scenes = (ledger.scenes + record).takeLast(HISTORY_LIMIT),
            ),
            materials = founded.materials + newMaterials,
            missions = missions,
        )
        return next to SceneResult.Scene(record, newMaterials, alreadyGot, missionDone)
    }

    /** 장면에 한 줄 맥락(프리셋만)을 붙인다. 그 장면에서 받은 재료의 기억 태그도 함께 바뀐다. */
    fun setTag(state: AppState, eventId: String, tag: String): AppState? {
        if (tag !in TAG_PRESETS) return null
        val scene = state.ledger.scenes.firstOrNull { it.eventId == eventId } ?: return null
        val line = "${dateLabel(scene.epochDay)} · $tag"
        return state.copy(
            ledger = state.ledger.copy(scenes = state.ledger.scenes.map { if (it.eventId == eventId) it.copy(tag = tag) else it }),
            materials = state.materials.map { if (it.eventId == eventId) it.copy(tag = line) else it },
        )
    }

    fun defaultTag(epochDay: Long): String = "${dateLabel(epochDay)} · 마을로 돌아온 날"

    fun dateLabel(epochDay: Long): String = LocalDate.ofEpochDay(epochDay).let { "${it.monthValue}월 ${it.dayOfMonth}일" }

    /** 마을 경과일(시작한 날 = 1일째). 시작 전이면 null. */
    fun villageDay(state: AppState, today: Long): Int? =
        state.village.foundedEpochDay?.let { (today - it + 1).coerceAtLeast(1).toInt() }

    fun epochDay(millis: Long, zone: ZoneId): Long = Instant.ofEpochMilli(millis).atZone(zone).toLocalDate().toEpochDay()

    fun slotOf(millis: Long, zone: ZoneId): TimeSlot = when (Instant.ofEpochMilli(millis).atZone(zone).hour) {
        in 5..10 -> TimeSlot.MORNING
        in 11..16 -> TimeSlot.DAY
        in 17..21 -> TimeSlot.EVENING
        else -> TimeSlot.NIGHT
    }

    private fun foundIfNeeded(state: AppState, today: Long): AppState =
        if (state.village.foundedEpochDay != null) state else state.copy(village = state.village.copy(foundedEpochDay = today))
}

/** 조연 미션. normal 신호(기어·주행거리)로만 판정한다. 과속·점수·연속일수·페널티 없음. */
enum class MissionDef(val id: String, val title: String, val detail: String) {
    PARK_AFTER_DRIVE("park-after-drive", "주행 후 P단으로 정차하기", "달린 뒤 기어를 P에 두고 마을을 열면 완료(기어 신호)"),
    SHORT_TRIP("short-trip", "짧은 이동도 기록하기", "5km 미만의 짧은 이동도 마을에 남기면 완료(주행거리)"),
}

object Missions {
    const val SHORT_TRIP_MAX_KM = 5.0

    /** 날짜로 정해지는 오늘의 미션 1개(무작위 아님). */
    fun today(epochDay: Long): MissionDef = MissionDef.entries[Math.floorMod(epochDay, MissionDef.entries.size.toLong()).toInt()]

    fun judge(mission: MissionDef, deltaKm: Double, gear: GearState): Boolean = when (mission) {
        MissionDef.PARK_AFTER_DRIVE -> gear == GearState.PARK && deltaKm >= SceneLedger.MIN_SCENE_KM
        MissionDef.SHORT_TRIP -> gear == GearState.PARK && deltaKm >= SceneLedger.MIN_SCENE_KM && deltaKm < SHORT_TRIP_MAX_KM
    }

    fun byId(id: String): MissionDef? = MissionDef.entries.firstOrNull { it.id == id }
}

/** 마을 꾸미기: 재료 1개를 빈 슬롯에 놓는다(P단 화면에서만 호출). */
object Decoration {
    const val SLOT_COUNT = 6

    fun place(state: AppState, materialId: String, slot: Int, today: Long): AppState? {
        if (slot !in 0 until SLOT_COUNT) return null
        val m = state.materials.firstOrNull { it.id == materialId } ?: return null
        if (m.placedSlot != null) return null
        if (state.materials.any { it.placedSlot == slot }) return null
        return state.copy(materials = state.materials.map { if (it.id == materialId) it.copy(placedSlot = slot, placedEpochDay = today) else it })
    }

    fun placedToday(state: AppState, today: Long): Material? =
        state.materials.lastOrNull { it.placedEpochDay == today }
}

/** 탑승·하차 한 문장을 각각 한 번만 내기 위한 판정. */
object VoiceOnce {
    /** 하차(장면): 장면 이벤트마다 한 번. 말하기로 했으면 갱신된 상태를 돌려준다. */
    fun alight(state: AppState, eventId: String): AppState? =
        if (state.voice.lastAlightEventId == eventId) null else state.copy(voice = state.voice.copy(lastAlightEventId = eventId))

    /** 탑승: 같은 기준 주행거리(같은 운행 주기)에서 한 번. 기준값이 없으면 말하지 않는다. */
    fun boarding(state: AppState): AppState? {
        val key = state.ledger.baselineKm?.let { "b-$it" } ?: return null
        return if (state.voice.lastBoardingKey == key) null else state.copy(voice = state.voice.copy(lastBoardingKey = key))
    }
}
