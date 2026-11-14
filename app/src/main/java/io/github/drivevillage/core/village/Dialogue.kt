package io.github.drivevillage.core.village

/** 마을 주민 3명(오리지널). */
enum class Resident(val displayName: String, val role: String) {
    CRAFTSMAN("도담", "공방 장인"),
    TRAVELER("나들", "여행가"),
    GARDENER("소이", "정원사"),
}

/**
 * 주민 대사의 입력. **사실·수치는 모두 코드가 채운다**(주행거리 증가, 시간대, 마을 경과일, 열린 퀘스트, 오늘 놓은 재료).
 * 대사 생성기는 이 값만 쓸 수 있고 새 수치를 만들 수 없다.
 */
data class TripSummary(
    /** 이번 장면의 주행거리 증가(정수 km, 내림). 장면이 아니면 null. */
    val deltaKm: Int?,
    val timeSlot: TimeSlot,
    /** 마을 경과일(1일째부터). 모르면 null. */
    val villageDay: Int?,
    /** 열린 정비 퀘스트 제목(첫 번째만 대사에 쓴다). */
    val openQuests: List<String>,
    /** 오늘 놓은 재료 이름. */
    val placedToday: String?,
    /** 이번에 받은 재료 수(0이면 오늘 이미 받음). */
    val materialsReceived: Int,
)

enum class LineContext { BOARDING, SCENE, CHAT }

/**
 * 대사 생성기 인터페이스. MVP는 [TemplateLineGenerator]만 쓴다.
 *
 * LLM 연동은 2차 PT 범위다. 그때도 같은 [TripSummary]만 입력으로 받고, 결과는 [DialogueValidator]를 통과해야 하며,
 * 실패·오프라인이면 템플릿으로 돌아간다(사실·보상·판정은 코드, 문장 표현만 생성기).
 */
interface LineGenerator {
    fun line(resident: Resident, context: LineContext, summary: TripSummary, seed: Int): String
}

/** 템플릿 슬롯. 값이 없으면 그 슬롯을 쓰는 템플릿은 고르지 않는다. */
enum class Slot(val token: String) {
    KM("{km}"), TIME("{time}"), DAY("{day}"), QUEST("{quest}"), MATERIAL("{material}"), COUNT("{count}"), NAME("{name}");
}

data class LineTemplate(val resident: Resident?, val context: LineContext, val text: String) {
    val slots: Set<Slot> = Slot.entries.filter { text.contains(it.token) }.toSet()
}

object Templates {
    private fun t(r: Resident?, c: LineContext, s: String) = LineTemplate(r, c, s)
    private val C = Resident.CRAFTSMAN
    private val T = Resident.TRAVELER
    private val G = Resident.GARDENER

    val all: List<LineTemplate> = listOf(
        // 하차(오늘의 장면)
        t(null, LineContext.SCENE, "{time}에 {km}km를 달려 돌아왔네요, 오늘의 재료를 마을에 들여놓을게요."),
        t(null, LineContext.SCENE, "오늘은 {km}km, 마을 {day}일째의 한 장면으로 남겨 둘게요."),
        t(T, LineContext.SCENE, "{km}km 길 이야기를 들려줘서 고마워요, 지도에 한 줄 그어 둘게요."),
        t(T, LineContext.SCENE, "{time} 길은 어땠어요? 오늘의 장면을 엽서에 적어 둘게요."),
        t(G, LineContext.SCENE, "돌아오셨네요, 오늘 받은 재료 {count}개는 정원 옆에 잘 놓아 둘게요."),
        t(G, LineContext.SCENE, "마을 {day}일째, {time} 공기가 좋네요."),
        t(C, LineContext.SCENE, "{km}km 수고했어요, 공방 불은 켜 두었어요."),
        t(C, LineContext.SCENE, "어서 와요, 공방에 「{quest}」 부탁이 하나 걸려 있어요."),
        t(G, LineContext.SCENE, "오늘 재료는 이미 받았지만, 이 장면도 일기에 적어 둘게요."),
        // 탑승
        t(null, LineContext.BOARDING, "다녀오세요, 마을은 여기서 기다릴게요."),
        t(T, LineContext.BOARDING, "{time} 길 잘 다녀와요, 돌아오면 이야기 들려줘요."),
        t(G, LineContext.BOARDING, "다녀오는 동안 정원에 물을 줘 둘게요."),
        t(C, LineContext.BOARDING, "공방은 제가 지킬게요, 천천히 다녀와요."),
        // 마을에서 주민을 눌렀을 때
        t(C, LineContext.CHAT, "공방에 「{quest}」 부탁이 와 있어요, 시간 날 때 봐 주세요."),
        t(C, LineContext.CHAT, "마을 {day}일째, 공방 연장도 손에 익었어요."),
        t(C, LineContext.CHAT, "급한 부탁은 없어요, 오늘은 연장을 닦는 날이에요."),
        t(T, LineContext.CHAT, "오늘 {km}km 길은 제 지도에도 적어 뒀어요."),
        t(T, LineContext.CHAT, "{time}에는 길 냄새가 달라요, 다음 장면이 기대돼요."),
        t(G, LineContext.CHAT, "오늘 놓은 {material}, 정원이랑 잘 어울려요."),
        t(G, LineContext.CHAT, "꽃밭 자리가 아직 남아 있어요, 재료가 생기면 하나 놓아 봐요."),
        t(G, LineContext.CHAT, "마을 {day}일째, 새싹이 조금 자랐어요."),
    )

    /** 어떤 슬롯도 없는 안전한 대체 문장. */
    fun fallback(context: LineContext): String = when (context) {
        LineContext.BOARDING -> "다녀오세요, 마을은 여기서 기다릴게요."
        LineContext.SCENE -> "돌아오셨네요, 오늘의 장면을 남겨 둘게요."
        LineContext.CHAT -> "오늘도 마을은 평온해요."
    }
}

/** 렌더 결과 검증: 남은 슬롯 0, 입력에 없는 수치 0, 금칙어 0. */
object DialogueValidator {
    /** 정비 완료를 차가 확인했다는 식의 표현, 점수·연속일수·접속 압박 표현은 쓰지 않는다. */
    val FORBIDDEN = listOf("확인했", "확인됐", "점수", "연속", "보고 싶었", "왜 안 왔", "벌점", "펫", "반려")

    fun allowedNumbers(s: TripSummary): Set<String> = setOfNotNull(
        s.deltaKm?.toString(), s.villageDay?.toString(), s.materialsReceived.toString(),
    )

    fun isValid(line: String, s: TripSummary): Boolean {
        if (line.isBlank() || line.contains('{') || line.contains('}')) return false
        if (FORBIDDEN.any { line.contains(it) }) return false
        val allowed = allowedNumbers(s) + s.openQuests.flatMap { numbersIn(it) } + listOfNotNull(s.placedToday).flatMap { numbersIn(it) }
        return numbersIn(line).all { it in allowed }
    }

    fun numbersIn(text: String): List<String> = Regex("\\d+").findAll(text).map { it.value }.toList()
}

/** 템플릿 렌더러: 사실 슬롯은 [TripSummary]에서만 채우고, 문장 변주만 템플릿 풀에서 고른다. */
class TemplateLineGenerator(private val pool: List<LineTemplate> = Templates.all) : LineGenerator {

    fun candidates(resident: Resident, context: LineContext, s: TripSummary): List<LineTemplate> =
        pool.filter { (it.resident == null || it.resident == resident) && it.context == context && it.slots.all { slot -> available(slot, s) } }
            .filter { context != LineContext.SCENE || (it.text.contains("이미 받았") == (s.materialsReceived == 0)) }
            .filter { !(it.slots.contains(Slot.COUNT) && s.materialsReceived == 0) }

    override fun line(resident: Resident, context: LineContext, summary: TripSummary, seed: Int): String {
        val list = candidates(resident, context, summary)
        if (list.isEmpty()) return Templates.fallback(context)
        val picked = list[Math.floorMod(seed, list.size)]
        val out = fill(picked.text, resident, summary)
        return if (DialogueValidator.isValid(out, summary)) out else Templates.fallback(context)
    }

    fun fill(text: String, resident: Resident, s: TripSummary): String = text
        .replace(Slot.KM.token, s.deltaKm?.toString() ?: "")
        .replace(Slot.TIME.token, s.timeSlot.word)
        .replace(Slot.DAY.token, s.villageDay?.toString() ?: "")
        .replace(Slot.QUEST.token, s.openQuests.firstOrNull() ?: "")
        .replace(Slot.MATERIAL.token, s.placedToday ?: "")
        .replace(Slot.COUNT.token, s.materialsReceived.toString())
        .replace(Slot.NAME.token, resident.displayName)

    private fun available(slot: Slot, s: TripSummary): Boolean = when (slot) {
        Slot.KM -> s.deltaKm != null
        Slot.TIME, Slot.NAME -> true
        Slot.DAY -> s.villageDay != null
        Slot.QUEST -> s.openQuests.isNotEmpty()
        Slot.MATERIAL -> s.placedToday != null
        Slot.COUNT -> s.materialsReceived > 0
    }
}

/** 마지막 글자의 받침 유무로 조사를 고른다. */
fun josa(word: String, withFinal: String, withoutFinal: String): String {
    val c = word.lastOrNull() ?: return withoutFinal
    if (c !in '가'..'힣') return withoutFinal
    return if ((c - '가') % 28 != 0) withFinal else withoutFinal
}
