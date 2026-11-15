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
    /** 정산 재료를 마지막으로 지급한 날(epochDay). 하루 1회 상한의 근거. */
    val lastMaterialEpochDay: Long? = null,
    /** 지금까지 넘은(지급 여부와 무관하게 셈한) 1,000km 이정표 수. 같은 이정표로 두 번 지급하지 않는 근거. */
    val milestonesCounted: Int = 0,
    /** 지금까지 지급한 이정표 기념물 수. 넘은 이정표 − 지급한 기념물 = 다음 정산으로 넘어간 기념물. */
    val monumentsGiven: Int = 0,
    /** 계절 장식을 마지막으로 준 계절 번호([SceneLedger.seasonIndex]). */
    val lastSeasonIndex: Long? = null,
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
    /** 이 정산으로 지급한 아이템 id(재료·기념물·계절 장식). */
    val materialIds: List<String> = emptyList(),
    /** 직전 정산(또는 마을 시작) 시각. 경과일 표시용. 없으면 null. */
    val sinceMillis: Long? = null,
    /** 사용자가 고른 한 줄 맥락(프리셋). */
    val tag: String? = null,
)

/** 아이템 출처. 마을 성장 점수의 가중치를 정한다. */
@Serializable
enum class ItemSource { JOURNEY, MILESTONE, SEASON, MAINTENANCE, MEMORIAL }

@Serializable
enum class MaterialKind(val label: String, val source: ItemSource) {
    // 정산 재료(정산 1회 = 1개, 하루 상한 1회)
    WOOD("나무 조각", ItemSource.JOURNEY), STONE("둥근 돌", ItemSource.JOURNEY), SEED("꽃씨 주머니", ItemSource.JOURNEY),
    GLASS("유리 구슬", ItemSource.JOURNEY), CLOTH("천 조각", ItemSource.JOURNEY),
    // 1,000km 이정표 기념물
    MONUMENT("이정표 기념비", ItemSource.MILESTONE),
    // 새 계절 첫 정산
    SPRING_GARLAND("봄 꽃줄", ItemSource.SEASON), SUMMER_LANTERN("여름 등불", ItemSource.SEASON),
    AUTUMN_WREATH("가을 잎 화환", ItemSource.SEASON), WINTER_STAR("겨울 별 장식", ItemSource.SEASON),
    // 0.1.3까지의 정비 응답 보상. 0.1.4부터 새로 주지 않지만 이미 저장된 아이템을 읽기 위해 남긴다.
    GEAR("공방 톱니", ItemSource.MAINTENANCE), MEMORIAL("정비 기념패", ItemSource.MEMORIAL),
}

/** 아이템(재료·기념물·장식) 한 개. 날짜와 한 줄 기억 태그를 가진다. [placedSlot]이 있으면 마을 슬롯에 놓인 것. */
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
    /** 온보딩에서 '모름'을 고름: 정비 항목의 기준을 '미확인'으로 둔다(설치 시점을 정비 시점으로 보지 않음). */
    val lastServiceUnknown: Boolean = false,
)

@Serializable
data class QuestRecord(
    /** 다음 주기 계산 기준 주행거리('했어요'에서 입력한 정비 당시 값, 없으면 신고 당시 값). 모르면 null(날짜로만). */
    val baseKm: Double?,
    val baseEpochDay: Long,
    /** '했어요' 누적 수(= 공방 증축에 반영). */
    val completions: Int,
    /** 퀘스트 주기 번호. '했어요'마다 1 증가. 응답 보상은 주기마다 1회. */
    val cycle: Int = 0,
    /** 응답 보상(톱니)을 준 마지막 주기. 0.1.3까지의 기록, 0.1.4부터 쓰지 않음(저장 호환용). */
    val rewardedCycle: Int? = null,
    /** '예약했어요'·'다음에'로 미룬 동안 숨김. 이 날짜(포함)부터 다시 보인다. */
    val snoozeUntilDay: Long? = null,
    /** '다음에'로 미룬 동안 숨김. 이 주행거리(포함)부터 다시 보인다(날짜와 먼저 오는 쪽). */
    val snoozeUntilKm: Double? = null,
    /** '예약했어요'로 미룬 경우 재확인 문구를 보인다. */
    val reservedRecheck: Boolean = false,
    /** 마지막으로 응답 보상(톱니)을 준 때의 주행거리·날짜. 0.1.3까지의 기록, 0.1.4부터 쓰지 않음(저장 호환용). */
    val rewardAnchorKm: Double? = null,
    val rewardAnchorDay: Long? = null,
    /** 마지막 공방 증축(증빙 '했어요') 때의 주행거리·날짜. 0.1.3까지는 기념패와 함께 기록했다(필드명 유지). */
    val memorialAnchorKm: Double? = null,
    val memorialAnchorDay: Long? = null,
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
