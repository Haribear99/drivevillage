package io.github.drivevillage

import io.github.drivevillage.core.village.DialogueValidator
import io.github.drivevillage.core.village.LineContext
import io.github.drivevillage.core.village.MaterialKind
import io.github.drivevillage.core.village.QuestDef
import io.github.drivevillage.core.village.Resident
import io.github.drivevillage.core.village.TemplateLineGenerator
import io.github.drivevillage.core.village.Templates
import io.github.drivevillage.core.village.TimeSlot
import io.github.drivevillage.core.village.TripSummary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 템플릿 렌더러: 슬롯 위반 0(남은 슬롯·입력에 없는 수치·금칙어), 사실은 입력값만. */
class DialogueTest {
    private val gen = TemplateLineGenerator()

    /** 슬롯 값이 있고 없는 경우를 고루 섞은 입력 전체. */
    private fun summaries(): List<TripSummary> {
        val out = mutableListOf<TripSummary>()
        for (km in listOf(null, 1, 12, 42, 300)) for (slot in TimeSlot.entries) for (day in listOf(null, 1, 37))
            for (quests in listOf(emptyList(), listOf(QuestDef.ENGINE_OIL.title), listOf(QuestDef.COOLANT_LEVEL.title, QuestDef.LIGHTS.title)))
                for (placed in listOf(null, MaterialKind.GLASS.label)) for (count in listOf(0, 2))
                    out += TripSummary(km, slot, day, quests, placed, count)
        return out
    }

    @Test fun everyRenderedLine_hasZeroSlotViolations() {
        var rendered = 0
        for (s in summaries()) for (r in Resident.entries) for (c in LineContext.entries) {
            val cands = gen.candidates(r, c, s)
            for (t in cands) {
                val line = gen.fill(t.text, r, s)
                assertFalse("남은 슬롯: $line", line.contains('{') || line.contains('}'))
                val allowed = DialogueValidator.allowedNumbers(s)
                DialogueValidator.numbersIn(line).forEach { n -> assertTrue("입력에 없는 수치 $n: $line", n in allowed) }
                DialogueValidator.FORBIDDEN.forEach { f -> assertFalse("금칙어 $f: $line", line.contains(f)) }
                assertTrue(DialogueValidator.isValid(line, s))
                rendered++
            }
            for (seed in 0..5) assertTrue(DialogueValidator.isValid(gen.line(r, c, s, seed), s))
        }
        assertTrue(rendered > 1000)
    }

    @Test fun factsComeFromSummary_only() {
        val s = TripSummary(42, TimeSlot.EVENING, 7, listOf(QuestDef.ENGINE_OIL.title), null, 2)
        val lines = Templates.all.filter { it.context == LineContext.SCENE }.flatMap { t -> Resident.entries.map { gen.fill(t.text, it, s) } }
        lines.filter { it.contains("km") }.forEach { assertTrue(it, it.contains("42km")) }
        lines.filter { it.contains("일째") }.forEach { assertTrue(it, it.contains("7일째")) }
    }

    @Test fun missingFacts_neverPickTemplatesNeedingThem() {
        val s = TripSummary(null, TimeSlot.DAY, null, emptyList(), null, 0)
        for (r in Resident.entries) for (c in LineContext.entries) for (t in gen.candidates(r, c, s)) {
            assertTrue(t.text, t.slots.none { it.token in listOf("{km}", "{day}", "{quest}", "{material}", "{count}") })
        }
    }

    @Test fun alreadyGotToday_sceneLine_doesNotClaimNewMaterials() {
        val s = TripSummary(12, TimeSlot.DAY, 3, emptyList(), null, 0)
        for (r in Resident.entries) for (seed in 0..10) {
            val line = gen.line(r, LineContext.SCENE, s, seed)
            assertFalse(line, line.contains("받은 재료") || line.contains("오늘의 재료를"))
        }
    }

    @Test fun validator_rejectsInventedNumbersAndForbiddenPhrases() {
        val s = TripSummary(12, TimeSlot.DAY, 3, emptyList(), null, 2)
        assertTrue(DialogueValidator.isValid("12km 수고했어요.", s))
        assertFalse(DialogueValidator.isValid("13km 수고했어요.", s))
        assertFalse(DialogueValidator.isValid("차가 정비를 확인했어요.", s))
        assertFalse(DialogueValidator.isValid("{km} 남았어요.", s))
        assertEquals(Templates.fallback(LineContext.CHAT), TemplateLineGenerator(emptyList()).line(Resident.GARDENER, LineContext.CHAT, s, 0))
    }

    @Test fun residentsAreThreeOriginalCharacters() {
        assertEquals(3, Resident.entries.size)
        assertEquals(setOf("공방 장인", "여행가", "정원사"), Resident.entries.map { it.role }.toSet())
    }
}
