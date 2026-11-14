package io.github.drivevillage.core.village

import kotlinx.serialization.Serializable

/**
 * 앱이 저장하는 전체 상태. 한 파일에 원자적으로 저장한다.
 * 정산 기준값·장면 기록·재료 지급·미션 도장이 같은 쓰기에 들어가므로 중복 지급이 생기지 않는다.
 */
@Serializable
data class AppState(
    val version: Int = 1,
    val ledger: LedgerState = LedgerState(),
    val materials: List<Material> = emptyList(),
    val maintenance: MaintenanceState = MaintenanceState(),
    val missions: MissionState = MissionState(),
    val village: VillageState = VillageState(),
    val voice: VoiceMemo = VoiceMemo(),
)

@Serializable
data class LedgerState(
    /** 마지막 정산 때 저장한 누적 주행거리(km). null이면 아직 만나지 않음. */
    val baselineKm: Double? = null,
    val lastSettledAtMillis: Long? = null,
    /** 지금까지 보여 준 오늘의 장면 수(장면 이벤트 번호의 근거). */
    val sceneCount: Int = 0,
    /** 장면으로 정산된 거리의 합(km). */
    val sceneKm: Double = 0.0,
    /** 재료를 마지막으로 지급한 날(epochDay). 하루 1회 지급의 근거. */
    val lastMaterialEpochDay: Long? = null,
    /** 기준값보다 작은 값을 한 번 읽었을 때의 후보. 같은 값이 다음 정산에도 읽혀야 기준값을 바꾼다(일시 오류 방지). */
    val reversalCandidateKm: Double? = null,
    /** 최근 장면(최대 [SceneLedger.HISTORY_LIMIT]개). */
    val scenes: List<SceneRecord> = emptyList(),
)

@Serializable
data class SceneRecord(
    val eventId: String,
    val epochDay: Long,
    val atMillis: Long,
    val deltaKm: Double,
    /** 이 장면으로 지급한 재료 id. 같은 날 두 번째 장면부터는 비어 있다. */
    val materialIds: List<String> = emptyList(),
    /** 사용자가 고른 한 줄 맥락(프리셋). */
    val tag: String? = null,
)

@Serializable
enum class MaterialKind(val label: String) {
    WOOD("나무 조각"), STONE("둥근 돌"), SEED("꽃씨 주머니"), GLASS("유리 구슬"), CLOTH("천 조각"),
}

/** 재료 한 개. 날짜와 한 줄 기억 태그를 가진다. [placedSlot]이 있으면 마을 슬롯에 놓인 것. */
@Serializable
data class Material(
    val id: String,
    val kind: MaterialKind,
    val epochDay: Long,
    val eventId: String,
    val tag: String,
    val placedSlot: Int? = null,
    val placedEpochDay: Long? = null,
)

@Serializable
data class MaintenanceState(
    val setup: MaintenanceSetup? = null,
    /** 퀘스트별 마지막 완료(자기 신고) 기록. 없으면 설정값을 기준으로 삼는다. */
    val records: Map<String, QuestRecord> = emptyMap(),
    /** 공방 증축 단계 = 자기 신고 완료 누적 수. */
    val workshopLevel: Int = 0,
)

/** 최초 실행 때 사용자가 입력한 마지막 정비 시점과 차종 프리셋(사용자가 고친 값 포함). */
@Serializable
data class MaintenanceSetup(
    val presetId: String,
    val lastServiceKm: Double,
    val lastServiceEpochDay: Long,
    val oilKm: Int,
    val oilMonths: Int,
    /** 설정한 날. 월 1회 체크인류의 기준일. */
    val setupEpochDay: Long,
)

@Serializable
data class QuestRecord(
    /** 완료 신고 때의 주행거리. 읽지 못했으면 null(그 주기는 날짜만으로 연다). */
    val baseKm: Double?,
    val baseEpochDay: Long,
    val completions: Int,
)

@Serializable
data class MissionState(
    /** 미션을 완료한 날의 기록(하루 최대 1개). 점수·연속일수는 두지 않는다. */
    val stamps: List<MissionStamp> = emptyList(),
)

@Serializable
data class MissionStamp(val missionId: String, val epochDay: Long, val eventId: String)

@Serializable
data class VillageState(
    /** 마을을 처음 연 날. 마을 경과일의 기준. */
    val foundedEpochDay: Long? = null,
)

/** 탑승·하차 한 문장을 각각 한 번만 내기 위한 기록. */
@Serializable
data class VoiceMemo(
    val lastBoardingKey: String? = null,
    val lastAlightEventId: String? = null,
)

@Serializable
enum class TimeSlot(val word: String) { MORNING("아침"), DAY("낮"), EVENING("저녁"), NIGHT("밤") }
