package io.github.drivevillage.core.village

/**
 * 마을 시설과 정비 항목의 짝(기획서 v2 5-5). 시설은 처음부터 보이고,
 * 짝이 된 정비 항목이 **도래해 응답을 기다리는 동안**만 흐리게 보여 '손봐 달라'는 신호를 준다.
 * 정비를 했다는 증빙이 아니라 '지금 확인할 일이 있다'는 표시다(응답 종류와 무관).
 */
enum class Facility(vararg val quests: QuestDef) {
    FORGE(QuestDef.ENGINE_OIL),
    MILL(QuestDef.COOLANT_LEVEL),
    WINDMILL(QuestDef.TIRE_PRESSURE, QuestDef.TIRE_ROTATION),
    LAMP(QuestDef.LIGHTS),
    GARDEN(QuestDef.CABIN_FILTER),
}

object Facilities {
    /** 지금 확인할 정비가 열려 있는 시설. 설정 전, 열린 퀘스트 없음, 마지막 정비를 '모름'으로 둔 기준 미확인 항목은 제외한다(처음부터 흐린 화면을 막는다). */
    fun needingCare(state: AppState, odoKm: Double?, today: Long): Set<Facility> {
        val open = Maintenance.openQuests(state, odoKm, today).filter { !it.baseUnconfirmed }.map { it.def }.toSet()
        return Facility.entries.filter { f -> f.quests.any { it in open } }.toSet()
    }
}
