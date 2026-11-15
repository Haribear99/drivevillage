package io.github.drivevillage

import io.github.drivevillage.core.gate.GearState
import io.github.drivevillage.core.village.AppState
import io.github.drivevillage.core.village.ItemSource
import io.github.drivevillage.core.village.Material
import io.github.drivevillage.core.village.MaterialBook
import io.github.drivevillage.core.village.MaterialKind
import io.github.drivevillage.core.village.OdometerReading
import io.github.drivevillage.core.village.RevealOnce
import io.github.drivevillage.core.village.SceneLedger
import io.github.drivevillage.core.village.SceneResult
import io.github.drivevillage.core.village.SettleReveal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

/** 재료 도감 상태 판정, 획득 계기 문구, 정산 연출 타임라인·1회 판정. */
class MaterialBookTest {
    private val zone = ZoneId.of("Asia/Seoul")
    private val day0 = LocalDate.of(2026, 11, 15)
    private fun at(day: Long, hour: Int = 18): Long = day0.plusDays(day).atTime(hour, 0).atZone(zone).toInstant().toEpochMilli()
    private fun mat(id: String, kind: MaterialKind, day: Long = 0, slot: Int? = null) =
        Material(id, kind, day0.toEpochDay() + day, "e-$id", "tag-$id", placedSlot = slot)

    // ── 도감 상태 ──

    @Test fun entries_alwaysTwelve_inFixedDeclarationOrder() {
        val e = MaterialBook.entries(AppState())
        assertEquals(12, e.size)
        assertEquals(MaterialKind.entries.toList(), e.map { it.kind })
        assertTrue(e.none { it.met })
        // 순서는 보유 여부와 무관하게 고정
        val owned = AppState(materials = listOf(mat("a", MaterialKind.WINTER_STAR), mat("b", MaterialKind.WOOD)))
        assertEquals(MaterialKind.entries.toList(), MaterialBook.entries(owned).map { it.kind })
    }

    @Test fun entries_countOwnedPlacedUnplaced_newestUnplacedFirst() {
        val s = AppState(
            materials = listOf(
                mat("w3", MaterialKind.WOOD, day = 3), mat("w1", MaterialKind.WOOD, day = 1, slot = 0), mat("w2", MaterialKind.WOOD, day = 2),
            ),
        )
        val wood = MaterialBook.entries(s).first { it.kind == MaterialKind.WOOD }
        assertEquals(3, wood.owned)
        assertEquals(1, wood.placed)
        assertEquals(listOf("w3", "w2"), wood.unplaced.map { it.id })
        assertTrue(wood.met)
    }

    @Test fun progress_countsDistinctKinds_notItems() {
        val s = AppState(materials = listOf(mat("a", MaterialKind.WOOD), mat("b", MaterialKind.WOOD), mat("c", MaterialKind.MONUMENT, slot = 2)))
        assertEquals(2, MaterialBook.metCount(s))
        assertEquals("2/12 만났어요", MaterialBook.progressLabel(s))
        assertEquals("0/12 만났어요", MaterialBook.progressLabel(AppState()))
    }

    @Test fun countChip_hiddenForZeroOrOne() {
        assertNull(MaterialBook.countChip(0))
        assertNull(MaterialBook.countChip(1))
        assertEquals("×2", MaterialBook.countChip(2))
    }

    @Test fun columns_twelveDivisor_cellAtLeastTouchTarget() {
        // 가로 패널(카드 안 약 367dp): 4열(칸 약 74dp)
        assertEquals(4, MaterialBook.columns(367f, 24f, 64f))
        // 세로 패널(카드 안 약 680dp): 6열(칸 약 93dp)
        assertEquals(6, MaterialBook.columns(680f, 24f, 64f))
        // 좁으면 3열, 더 좁으면 2열
        assertEquals(3, MaterialBook.columns(300f, 24f, 64f))
        assertEquals(2, MaterialBook.columns(200f, 24f, 64f))
        for (w in listOf(250f, 300f, 367f, 455f, 680f)) {
            val c = MaterialBook.columns(w, 24f, 64f)
            assertEquals(0, 12 % c)
            assertTrue("w=$w c=$c", (w - 24f * (c - 1)) / c >= 64f)
        }
    }

    @Test fun defaultFocus_selectedThenNewThenFirstPlaceable() {
        val wood = mat("w", MaterialKind.WOOD, slot = 1)
        val stone = mat("s", MaterialKind.STONE)
        val star = mat("st", MaterialKind.WINTER_STAR)
        val e = MaterialBook.entries(AppState(materials = listOf(wood, stone, star)))
        assertEquals(MaterialKind.WINTER_STAR, MaterialBook.defaultFocus(e, "st", emptyList()))
        assertEquals(MaterialKind.WINTER_STAR, MaterialBook.defaultFocus(e, null, listOf(star)))
        assertEquals(MaterialKind.STONE, MaterialBook.defaultFocus(e, null, emptyList())) // 다 놓인 나무는 건너뜀
        assertNull(MaterialBook.defaultFocus(MaterialBook.entries(AppState()), null, emptyList()))
    }

    // ── 획득 계기 문구: 실제 지급 규칙과 같아야 한다 ──

    @Test fun hint_matchesRewardRules_noMaintenanceReward() {
        MaterialKind.entries.forEach { k ->
            val h = MaterialBook.hint(k)
            assertTrue("$k: $h", h.isNotBlank())
            // 정비 응답은 재료를 주지 않는다: 어떤 문구도 정비로 받는다고 말하지 않는다.
            assertFalse("$k: $h", h.contains("정비"))
            // 압박 문구 금지
            listOf("밀렸어요", "남았어요", "놓치지", "연속", "스트릭", "—").forEach { bad -> assertFalse("$k: $h", h.contains(bad)) }
        }
        assertEquals("주행 정산 때 와요.\n날마다 종류가 바뀌어요", MaterialBook.hint(MaterialKind.WOOD))
        assertTrue(MaterialBook.hint(MaterialKind.MONUMENT).contains("1,000km"))
        assertEquals("봄 첫 정산 때 와요", MaterialBook.hint(MaterialKind.SPRING_GARLAND))
        assertEquals("겨울 첫 정산 때 와요", MaterialBook.hint(MaterialKind.WINTER_STAR))
        assertTrue(MaterialBook.hint(MaterialKind.GEAR).contains("새로 생기지 않아요"))
        assertTrue(MaterialBook.hint(MaterialKind.MEMORIAL).contains("새로 생기지 않아요"))
    }

    @Test fun hint_seasonWordMatchesKindGivenBySettle() {
        // 가을(11월) 첫 정산은 가을 화환을 준다 → 도감 문구도 '가을'
        val start = SceneLedger.settle(AppState(), OdometerReading.Km(1000.0), GearState.PARK, at(0, 8), zone).first
        val (s1, r) = SceneLedger.settle(start, OdometerReading.Km(1012.0), GearState.PARK, at(0), zone)
        val seasonal = (r as SceneResult.Scene).seasonal!!
        assertEquals(MaterialKind.AUTUMN_WREATH, seasonal.kind)
        assertEquals("가을 첫 정산 때 와요", MaterialBook.hint(seasonal.kind))
        // 정산 재료는 JOURNEY 문구
        val journey = r.newMaterials.filter { it.kind.source == ItemSource.JOURNEY }
        assertEquals(1, journey.size) // 하루 하나
        journey.forEach { assertTrue(MaterialBook.hint(it.kind).startsWith("주행 정산 때")) }
        // 종류는 날짜로 바뀐다(다음 날 정산은 다른 종류)
        val (_, r2) = SceneLedger.settle(s1, OdometerReading.Km(1030.0), GearState.PARK, at(1), zone)
        val next = (r2 as SceneResult.Scene).newMaterials.first { it.kind.source == ItemSource.JOURNEY }
        assertTrue(next.kind != journey.first().kind)
    }

    // ── 신규 광택·칩 ──

    @Test fun shimmer_onlyNewNotYetShown_noneWhenReduceMotion() {
        val a = mat("a", MaterialKind.WOOD); val b = mat("b", MaterialKind.MONUMENT)
        assertEquals(setOf(MaterialKind.WOOD, MaterialKind.MONUMENT), MaterialBook.shimmerKinds(listOf(a, b), emptySet(), false))
        assertEquals(setOf(MaterialKind.MONUMENT), MaterialBook.shimmerKinds(listOf(a, b), setOf("a"), false))
        assertTrue(MaterialBook.shimmerKinds(listOf(a, b), setOf("a", "b"), false).isEmpty())
        assertTrue(MaterialBook.shimmerKinds(listOf(a, b), emptySet(), true).isEmpty())
    }

    @Test fun chips_groupSameKind_fixedOrder_capped() {
        val items = listOf(mat("s", MaterialKind.AUTUMN_WREATH), mat("m1", MaterialKind.MONUMENT), mat("m2", MaterialKind.MONUMENT), mat("w", MaterialKind.WOOD))
        val (chips, more) = MaterialBook.chips(items)
        assertEquals(listOf(MaterialKind.WOOD to 1, MaterialKind.MONUMENT to 2, MaterialKind.AUTUMN_WREATH to 1), chips)
        assertEquals(0, more)
        val (c2, more2) = MaterialBook.chips(items, max = 2)
        assertEquals(2, c2.size)
        assertEquals(1, more2)
    }

    // ── 정산 연출 ──

    @Test fun reveal_playsOncePerScene_andNeverWithReduceMotion() {
        assertTrue(RevealOnce.shouldPlay("scene-3", emptySet(), false))
        assertFalse(RevealOnce.shouldPlay("scene-3", setOf("scene-3"), false))
        assertTrue(RevealOnce.shouldPlay("scene-4", setOf("scene-3"), false))
        assertFalse(RevealOnce.shouldPlay("scene-4", emptySet(), true))
    }

    @Test fun timeline_endsWithinLimit_finalStateExact() {
        for (chips in 0..4) for (stamp in listOf(false, true)) {
            val total = SettleReveal.totalMs(chips, stamp)
            assertTrue("chips=$chips stamp=$stamp total=$total", total <= SettleReveal.MAX_MS)
            assertTrue(total >= SettleReveal.COUNT_MS)
            // 끝(=건너뛰기 시점 MAX_MS 포함)에서는 모두 최종 상태
            for (t in listOf(total, SettleReveal.MAX_MS)) {
                assertEquals(1234L, SettleReveal.distanceAt(1234L, t))
                repeat(chips) { i -> assertEquals(1f, SettleReveal.chipAt(i, t), 0f) }
                if (stamp) assertEquals(1f, SettleReveal.stampAt(chips, t), 0f)
            }
        }
    }

    @Test fun timeline_order_countThenChipsThenStamp() {
        // 거리 카운트업은 약 1초
        assertEquals(0L, SettleReveal.distanceAt(500L, 0))
        assertTrue(SettleReveal.distanceAt(500L, 500) in 1..499)
        assertEquals(500L, SettleReveal.distanceAt(500L, SettleReveal.COUNT_MS))
        // 칩은 차례로(앞 칩이 먼저), 도장은 마지막 칩이 착지한 뒤
        assertEquals(0f, SettleReveal.chipAt(0, 0), 0f)
        assertTrue(SettleReveal.chipAt(0, 1_000) > SettleReveal.chipAt(1, 1_000))
        val chips = 3
        val lastChipDone = (0L..SettleReveal.MAX_MS step 10).first { SettleReveal.chipAt(chips - 1, it) >= 1f }
        assertEquals(0f, SettleReveal.stampAt(chips, lastChipDone), 0f)
        assertTrue(SettleReveal.stampStartMs(chips) > lastChipDone)
        // 카운트업은 줄지 않는다
        var prev = -1L
        for (t in 0L..SettleReveal.COUNT_MS step 25) { val d = SettleReveal.distanceAt(987L, t); assertTrue(d >= prev); prev = d }
    }
}
