package io.github.drivevillage.core.village

/**
 * 마을 시설과 정비 항목의 짝(기획서 v2 5-5). 시설은 처음부터 보이고,
 * 짝이 된 정비 항목이 **도래해 응답을 기다리는 동안**만 '쉬는 중'으로 보여 '손봐 달라'는 신호를 준다.
 * 정비를 했다는 증빙이 아니라 '지금 확인할 일이 있다'는 표시다(응답 종류와 무관).
 *
 * [label]은 화면의 시설 이름, [pair]는 짝 정비의 짧은 이름, [greeter]·[wakeLine]은 '했어요'로 다시 켜질 때의 한 줄 인사.
 */
enum class Facility(val label: String, val pair: String, val greeter: Resident, val wakeLine: String, vararg val quests: QuestDef) {
    FORGE("공방", "엔진오일", Resident.CRAFTSMAN, "고마워요, 공방 화로에 다시 불이 들어왔어요.", QuestDef.ENGINE_OIL),
    MILL("물레방아", "냉각수", Resident.CRAFTSMAN, "고마워요, 물레방아에 다시 물이 돌아요.", QuestDef.COOLANT_LEVEL),
    WINDMILL("풍차", "타이어", Resident.TRAVELER, "고마워요, 풍차에 다시 바람이 들어요.", QuestDef.TIRE_PRESSURE, QuestDef.TIRE_ROTATION),
    LAMP("가로등", "등화", Resident.TRAVELER, "고마워요, 가로등이 다시 환해졌어요.", QuestDef.LIGHTS),
    GARDEN("정원", "공조 필터", Resident.GARDENER, "고마워요, 정원 공기가 다시 맑아졌어요.", QuestDef.CABIN_FILTER),
}

object Facilities {
    /**
     * 정비 탭 '응답은 이렇게 기록돼요'의 설명. 시설은 짝이 된 정비 중 열린 것이 하나도 없을 때 평상으로 돌아온다
     * (풍차는 두 짝, 예약했어요·다음에로 미뤄도 열린 상태가 아니다). 그래서 '했어요 하나로 켜진다'고 쓰지 않는다.
     */
    const val RESPONSE_NOTE =
        "답은 직접 하는 기록이에요(앱은 정비 여부를 알 수 없어요). 어떤 답을 눌러도 재료는 드리지 않아요. " +
            "마을 시설은 짝이 된 정비에 모두 답하면 다시 켜져요. 정비한 날·거리를 남기면 공방이 커져요(선택)."

    /** 지금 확인할 정비가 열려 있는 시설. 설정 전, 열린 퀘스트 없음, 마지막 정비를 '모름'으로 둔 기준 미확인 항목은 제외한다(처음부터 흐린 화면을 막는다). */
    fun needingCare(state: AppState, odoKm: Double?, today: Long): Set<Facility> {
        val open = waitingQuests(state, odoKm, today)
        return Facility.entries.filter { f -> f.quests.any { it in open } }.toSet()
    }

    /** 시설을 쉬게 만드는 열린 정비(기준 미확인 제외). */
    private fun waitingQuests(state: AppState, odoKm: Double?, today: Long): Set<QuestDef> =
        Maintenance.openQuests(state, odoKm, today).filter { !it.baseUnconfirmed }.map { it.def }.toSet()

    /** [f]가 쉬고 있다면 기다리는 정비(정의 순서). 쉬지 않으면 빈 목록. */
    fun waitingFor(f: Facility, state: AppState, odoKm: Double?, today: Long): List<QuestDef> {
        val open = waitingQuests(state, odoKm, today)
        return f.quests.filter { it in open }
    }

    /** 응답 전후로 쉬는 중에서 평상으로 바뀐 시설(점등 연출 대상). */
    fun woke(before: AppState, after: AppState, odoKm: Double?, today: Long): Set<Facility> =
        needingCare(before, odoKm, today) - needingCare(after, odoKm, today)

    /**
     * 시설을 눌렀을 때 안내 시트의 1~2줄 설명. 쉬는 중이면 기다리는 정비, 평상이면 짝 소개(그 시설 하나만).
     * 응답은 받지 않는다(정비 탭의 흐름 하나만).
     */
    fun hint(f: Facility, waiting: List<QuestDef>): String =
        if (waiting.isNotEmpty()) "${waiting.joinToString("·") { it.title }} 때가 돼서 잠깐 쉬고 있어요."
        else "${f.label}${josa(f.label, "은", "는")} ${f.pair}${josa(f.pair, "과", "와")} 짝이에요. 그 정비 때가 되면 잠깐 쉬어요."
}
