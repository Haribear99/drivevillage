package io.github.drivevillage

import io.github.drivevillage.core.gate.GearState
import io.github.drivevillage.core.village.AppState
import io.github.drivevillage.core.village.CarPreset
import io.github.drivevillage.core.village.Decoration
import io.github.drivevillage.core.village.DueBy
import io.github.drivevillage.core.village.Maintenance
import io.github.drivevillage.core.village.ItemSource
import io.github.drivevillage.core.village.Material
import io.github.drivevillage.core.village.MaterialKind
import io.github.drivevillage.core.village.QuestResponse
import io.github.drivevillage.core.village.VillageGrowth
import io.github.drivevillage.core.village.MissionDef
import io.github.drivevillage.core.village.Missions
import io.github.drivevillage.core.village.OdometerReading
import io.github.drivevillage.core.village.QuestDef
import io.github.drivevillage.core.village.SceneLedger
import io.github.drivevillage.core.village.SceneResult
import io.github.drivevillage.core.village.VoiceOnce
import io.github.drivevillage.data.FileStateStore
import io.github.drivevillage.data.StateValidator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.time.LocalDate
import java.time.ZoneId

/** 오늘의 장면 정산(멱등성·재료 하루 1회), 정비 퀘스트 도래, 미션, 꾸미기, 한 문장 1회. */
class VillageCoreTest {
    @get:Rule val tmp = TemporaryFolder()
    private val zone = ZoneId.of("Asia/Seoul")
    private val day0 = LocalDate.of(2026, 11, 15)
    private fun at(day: Long, hour: Int = 18): Long = day0.plusDays(day).atTime(hour, 0).atZone(zone).toInstant().toEpochMilli()
    private fun km(v: Double) = OdometerReading.Km(v)
    private fun settle(s: AppState, v: Double, t: Long, gear: GearState = GearState.PARK) = SceneLedger.settle(s, km(v), gear, t, zone)
    private fun started(v: Double = 1000.0) = settle(AppState(), v, at(0, 8)).first
    /** 정산 재료(JOURNEY) 수. */
    private fun j(items: List<Material>) = items.count { it.kind.source == ItemSource.JOURNEY }

    // ── 정산 멱등성 ──

    @Test fun firstOpen_setsBaselineOnly_noMaterials() {
        val (s, r) = settle(AppState(), 1000.0, at(0))
        assertEquals(SceneResult.FirstMeet, r)
        assertEquals(1000.0, s.ledger.baselineKm!!, 0.0)
        assertTrue(s.materials.isEmpty())
        assertNotNull(s.village.foundedEpochDay)
    }

    @Test fun sameReadingTwice_secondIsBoarding_noDuplicateScene() {
        val (s1, r1) = settle(started(), 1012.0, at(0))
        assertTrue(r1 is SceneResult.Scene)
        val (s2, r2) = settle(s1, 1012.0, at(0, 19))
        assertEquals(SceneResult.Boarding, r2)
        assertEquals(s1, s2) // 상태 변화 없음
    }

    /** 저장 → 재시작(로드) → 같은 값으로 다시 정산을 반복해도 추가 지급 0. */
    @Test fun restartLoop_sameOdometer_noExtraRewards() {
        val file = File(tmp.root, "s.json")
        val (s1, _) = settle(started(), 1012.0, at(0))
        FileStateStore(file).save(s1)
        repeat(5) {
            val loaded = FileStateStore(file).load()
            val (next, r) = settle(loaded, 1012.0, at(0, 20))
            assertEquals(SceneResult.Boarding, r)
            FileStateStore(file).save(next)
        }
        val end = FileStateStore(file).load()
        assertEquals(1, end.ledger.sceneCount)
        assertEquals(1, j(end.materials))
    }

    @Test fun belowOneKm_accumulatesUntilThreshold() {
        val s0 = started()
        val (s1, r1) = settle(s0, 1000.6, at(0))
        assertEquals(SceneResult.Boarding, r1)
        assertEquals(1000.0, s1.ledger.baselineKm!!, 0.0) // 기준값 유지 → 증가분 누적
        val (_, r2) = settle(s1, 1001.1, at(0))
        assertTrue(r2 is SceneResult.Scene)
        assertEquals(1.1, (r2 as SceneResult.Scene).record.deltaKm, 1e-9)
    }

    @Test fun readFailure_heldWithoutChange_reversed_resetsBaselineOnly() {
        val s0 = settle(started(), 1012.0, at(0)).first
        val (h, rh) = SceneLedger.settle(s0, OdometerReading.Failed("timeout"), GearState.PARK, at(1), zone)
        assertTrue(rh is SceneResult.Held)
        assertEquals(s0, h)
        // 한 번 낮게 읽히면 보류(기준값 유지), 같은 값이 다음에도 읽혀야 재설정
        val (once, r1) = settle(s0, 500.0, at(1))
        assertEquals(SceneResult.Held("reversed-unconfirmed"), r1)
        assertEquals(1012.0, once.ledger.baselineKm!!, 0.0)
        val (rv, rr) = settle(once, 500.0, at(1, 19))
        assertEquals(SceneResult.Reversed, rr)
        assertEquals(500.0, rv.ledger.baselineKm!!, 0.0)
        assertEquals(s0.materials, rv.materials)
        for (bad in listOf(Double.NaN, Double.POSITIVE_INFINITY, -1.0, 0.0)) {
            assertTrue(settle(s0, bad, at(1)).second is SceneResult.Held)
        }
    }

    /** 일시적으로 낮은 값(0 포함) 뒤 정상 값: 이미 센 거리로 장면이 다시 생기지 않는다. */
    @Test fun transientLowReading_doesNotRecountDistance() {
        val s0 = settle(started(50_000.0), 50_010.0, at(0)).first
        for (glitch in listOf(0.0, 50_005.0)) {
            val (g, _) = settle(s0, glitch, at(1))
            val (n, r) = settle(g, 50_010.0, at(1, 19))
            assertEquals(SceneResult.Boarding, r)
            assertEquals(s0.ledger.sceneCount, n.ledger.sceneCount)
            assertEquals(s0.materials, n.materials)
            assertNull(n.ledger.reversalCandidateKm)
        }
    }

    /** 하루 뒤로 간 시계: 이중 지급 없음, 저장 정상. */
    @Test fun clockOneDayBack_noDoubleGrant() {
        val a = settle(started(), 1010.0, at(1)).first
        val (n, r) = settle(a, 1020.0, at(0, 20))
        assertTrue((r as SceneResult.Scene).alreadyGotToday)
        assertTrue(StateValidator.isValid(n))
        assertEquals(1, j(n.materials))
    }

    /** 시계가 400일 앞섰다가 돌아와도 저장이 막히지 않고, 정상 날짜에서 하루 1회 지급이 이어지며, 그 미래 날짜에 도달해도 하루 상한을 넘지 않는다. */
    @Test fun clockAheadThenNormal_dailyGrantContinues_neverExceedsDailyCap() {
        val file = File(tmp.root, "clock.json")
        var s = settle(started(), 1010.0, at(400)).first
        FileStateStore(file).save(s)
        var odo = 1010.0
        for (d in 0L..30L) {
            odo += 12
            val (n, r) = settle(FileStateStore(file).load(), odo, at(d, 18))
            assertEquals("day $d", 1, j((r as SceneResult.Scene).newMaterials))
            FileStateStore(file).save(n); s = n
        }
        odo += 12
        val (atFuture, rf) = settle(s, odo, at(400, 20)) // 앞서 받은 날에 실제로 도달
        assertTrue((rf as SceneResult.Scene).alreadyGotToday)
        assertTrue(StateValidator.isValid(atFuture))
        assertEquals(32, j(atFuture.materials))
    }

    /** 부팅 직후 RTC가 과거(마을 시작 전)로 잡히면 보류: 같은 실제 날에 재료가 늘지 않는다. */
    @Test fun clockBeforeFounding_isHeld_noExtraMaterials() {
        val a = settle(started(), 1010.0, at(5)).first
        val rtc1970 = 0L
        val (h, r) = settle(a, 1020.0, rtc1970)
        assertEquals(SceneResult.Held("clock-invalid"), r)
        assertEquals(a, h)
        val (h2, r2) = settle(a, 1030.0, at(-300))
        assertEquals(SceneResult.Held("clock-invalid"), r2)
        assertEquals(a.materials, h2.materials)
    }

    /** 첫 실행 때 시계가 앞서 있었어도, 정상 날짜로 돌아오면 정산·지급이 이어지고 시작일이 보정된다. */
    @Test fun firstRunWithClockAhead_recoversAfterClockFix() {
        var s = settle(AppState(), 1000.0, at(400)).first
        for (d in 1L..5L) {
            val (n, r) = settle(s, 1000.0 + d * 12, at(d))
            assertEquals("day $d", 1, j((r as SceneResult.Scene).newMaterials))
            s = n
        }
        assertEquals(day0.plusDays(1).toEpochDay(), s.village.foundedEpochDay)
        assertTrue(StateValidator.isValid(s))
    }

    /** 서로 다른 오류값 두 번(100 → 200)은 역전으로 확정하지 않는다. */
    @Test fun twoDifferentGlitchValues_doNotConfirmReversal() {
        val s0 = settle(started(50_000.0), 50_010.0, at(0)).first
        val g1 = settle(s0, 100.0, at(1)).first
        val (g2, r2) = settle(g1, 2_000.0, at(1, 19))
        assertTrue(r2 is SceneResult.Held)
        assertEquals(50_010.0, g2.ledger.baselineKm!!, 0.0)
        val (_, back) = settle(g2, 50_010.0, at(2))
        assertEquals(SceneResult.Boarding, back)
    }

    /** 실제 역전(계기 교체 등) 뒤 매 운행 달려도, 두 번째 낮은 값에서 기준값이 확정돼 장면이 이어진다. */
    @Test fun realReversal_withDrivingBetweenReads_confirms() {
        var s = settle(started(50_000.0), 50_010.0, at(0)).first
        val (h, rh) = settle(s, 30_000.0, at(1))
        assertEquals(SceneResult.Held("reversed-unconfirmed"), rh)
        val (c, rc) = settle(h, 30_012.0, at(1, 19))
        assertEquals(SceneResult.Reversed, rc)
        assertEquals(30_012.0, c.ledger.baselineKm!!, 0.0)
        s = c
        val (_, r) = settle(s, 30_024.0, at(2))
        assertEquals(12.0, (r as SceneResult.Scene).record.deltaKm, 1e-9)
    }

    // ── 성장 입력: 정산 재료(하루 1회), 이정표 기념물(최대 3), 계절 장식, 날짜·기억 태그 ──

    @Test fun journeyMaterial_onePerSettlement_dailyCapOne() {
        var s = started()
        val (a, ra) = settle(s, 1001.0, at(0)) // 1km
        assertEquals(1, j((ra as SceneResult.Scene).newMaterials))
        val (b, rb) = settle(a, 1300.0, at(0, 21)) // 같은 날 두 번째 정산(299km)
        rb as SceneResult.Scene
        assertTrue(rb.alreadyGotToday)
        assertEquals(0, j(rb.newMaterials))
        assertEquals(1, j(b.materials))
        val (c, rc) = settle(b, 1600.0, at(1)) // 다음 날
        assertEquals(1, j((rc as SceneResult.Scene).newMaterials))
        assertEquals(2, j(c.materials))
        s = c
        // 모든 아이템에 날짜·태그·출처 이벤트, id 중복 없음
        assertEquals(s.materials.size, s.materials.map { it.id }.toSet().size)
        s.materials.forEach { m ->
            assertTrue(m.tag.startsWith(SceneLedger.dateLabel(m.epochDay)))
            assertTrue(s.ledger.scenes.any { it.eventId == m.eventId })
        }
        assertTrue(StateValidator.isValid(s))
    }

    @Test fun milestone_everyThousandKm_once() {
        var s = started(10_000.0)
        val (a, ra) = settle(s, 10_999.0, at(0))
        assertEquals(0, (ra as SceneResult.Scene).monumentsGranted)
        val (b, rb) = settle(a, 11_000.0, at(1)) // 누적 1,000km 도달
        rb as SceneResult.Scene
        assertEquals(1, rb.milestonesCrossed); assertEquals(1, rb.monumentsGranted)
        assertEquals(1, b.ledger.milestonesCounted)
        assertEquals(listOf("mon-1"), b.materials.filter { it.kind == MaterialKind.MONUMENT }.map { it.id })
        // 같은 누적 거리로 다시 열거나, 재시작해도 추가 지급 없음
        val file = File(tmp.root, "mon.json"); FileStateStore(file).save(b)
        val (c, rc) = settle(FileStateStore(file).load(), 11_000.0, at(1, 20))
        assertEquals(SceneResult.Boarding, rc)
        assertEquals(1, c.materials.count { it.kind == MaterialKind.MONUMENT })
        val (d, rd) = settle(c, 11_500.0, at(2))
        assertEquals(0, (rd as SceneResult.Scene).monumentsGranted)
        s = d
        assertTrue(StateValidator.isValid(s))
    }

    /** 한 번에 이정표 여러 개를 넘어도 정산 1회 기념물은 최대 3개. 남은 몫은 다음 정산으로 넘어가고 두 번 주지 않는다. */
    @Test fun milestone_capThreePerSettlement_remainderCarriesOver() {
        val (a, ra) = settle(started(10_000.0), 15_200.0, at(0)) // 5,200km → 이정표 5개
        ra as SceneResult.Scene
        assertEquals(5, ra.milestonesCrossed)
        assertEquals(SceneLedger.MAX_MILESTONES_PER_SETTLEMENT, ra.monumentsGranted)
        assertEquals(2, ra.monumentsPending)
        assertEquals(5, a.ledger.milestonesCounted)
        assertEquals(3, a.materials.count { it.kind == MaterialKind.MONUMENT })
        // 같은 날 다시 정산해도(재료 없음) 넘어온 기념물 2개는 받는다
        val (b, rb) = settle(a, 15_900.0, at(0, 21))
        rb as SceneResult.Scene
        assertEquals(0, rb.milestonesCrossed); assertEquals(2, rb.monumentsGranted); assertEquals(0, rb.monumentsPending)
        val (c, rc) = settle(b, 16_000.0, at(2)) // 6,000km: 6번째 이정표 1개
        assertEquals(1, (rc as SceneResult.Scene).monumentsGranted)
        val ids = c.materials.filter { it.kind == MaterialKind.MONUMENT }.map { it.id }
        assertEquals((1..6).map { "mon-$it" }, ids)
        val (d, rd) = settle(c, 16_100.0, at(3))
        assertEquals(0, (rd as SceneResult.Scene).monumentsGranted)
        assertEquals(c.materials.size + 1, d.materials.size) // 재료 1개만
        assertTrue(StateValidator.isValid(d))
    }

    /** 정산 1회 상한을 넘는 거리(예: 10,000km)라도 한 번에 3개를 넘게 주지 않는다. */
    @Test fun milestone_neverMoreThanThreeInOneSettlement() {
        var s = started(1_000.0)
        val (a, ra) = settle(s, 11_000.0, at(0))
        assertEquals(10, (ra as SceneResult.Scene).milestonesCrossed)
        s = a
        var t = 1L
        while (s.ledger.monumentsGiven < 10) {
            val (n, r) = settle(s, s.ledger.baselineKm!! + 2, at(t++))
            assertTrue((r as SceneResult.Scene).monumentsGranted <= SceneLedger.MAX_MILESTONES_PER_SETTLEMENT)
            s = n
        }
        assertEquals(10, s.materials.count { it.kind == MaterialKind.MONUMENT })
    }

    @Test fun season_firstSettlementOfNewSeason_once() {
        val (a, ra) = settle(started(), 1010.0, at(0)) // 2026-11-15 가을
        assertEquals(MaterialKind.AUTUMN_WREATH, (ra as SceneResult.Scene).seasonal!!.kind)
        val (b, rb) = settle(a, 1020.0, at(5)) // 같은 계절
        assertNull((rb as SceneResult.Scene).seasonal)
        val (c, rc) = settle(b, 1030.0, at(20)) // 12월: 겨울
        assertEquals(MaterialKind.WINTER_STAR, (rc as SceneResult.Scene).seasonal!!.kind)
        val (d, rd) = settle(c, 1040.0, at(50)) // 1월: 같은 겨울
        assertNull((rd as SceneResult.Scene).seasonal)
        val (e, re) = settle(d, 1050.0, at(14)) // 시계가 가을로 되돌아가도 다시 주지 않음
        assertNull((re as SceneResult.Scene).seasonal)
        assertEquals(2, e.materials.count { it.kind.source == ItemSource.SEASON })
        assertTrue(StateValidator.isValid(e))
    }

    @Test fun growthLevel_accumulatesFromAllInputs() {
        val s0 = started()
        assertEquals(1, VillageGrowth.level(s0))
        var s = s0
        var odo = 1000.0
        for (d in 0L..20L) { odo += 60; s = settle(s, odo, at(d)).first }
        val pts = VillageGrowth.points(s)
        assertEquals(21 * 1 + 2 * 2 + 1 * 3, pts) // 재료 21 + 계절 2(가을·겨울) + 이정표 1(1,260km)
        assertEquals(1 + pts / VillageGrowth.POINTS_PER_LEVEL, VillageGrowth.level(s))
    }

    @Test fun tag_onlyPresets_updatesMaterialsOfThatEventOnly() {
        val a = settle(started(), 1010.0, at(0)).first
        val b = settle(a, 1030.0, at(1)).first
        val ev0 = a.ledger.scenes.last().eventId
        val tagged = SceneLedger.setTag(b, ev0, "친구를 만났어요")!!
        assertTrue(tagged.materials.filter { it.eventId == ev0 }.all { it.tag == "${SceneLedger.dateLabel(it.epochDay)} · 친구를 만났어요" })
        assertTrue(tagged.materials.filter { it.eventId != ev0 }.all { it.tag.endsWith("마을로 돌아온 날") })
        assertNull(SceneLedger.setTag(b, ev0, "자유 입력"))
        assertNull(SceneLedger.setTag(b, "scene-999", "친구를 만났어요"))
    }

    // ── 정비 퀘스트: km·날짜 중 먼저 도래 ──

    private fun withSetup(lastKm: Double, lastDay: Long, oilKm: Int = 10_000, months: Int = 12, today: Long = lastDay): AppState =
        Maintenance.saveSetup(started(), Maintenance.newSetup(CarPreset.TUCSON_GAS, lastKm, lastDay, oilKm, months, today)!!)

    @Test fun quest_kmComesFirst() {
        val d = day0.toEpochDay()
        val s = withSetup(10_000.0, d)
        val before = Maintenance.status(QuestDef.ENGINE_OIL, s, 19_999.0, d + 30)!!
        assertFalse(before.open); assertEquals(1.0, before.kmLeft!!, 1e-9)
        val after = Maintenance.status(QuestDef.ENGINE_OIL, s, 20_000.0, d + 30)!!
        assertTrue(after.open); assertEquals(DueBy.KM, after.dueBy)
    }

    @Test fun quest_dateComesFirst() {
        val d = day0.toEpochDay()
        val s = withSetup(10_000.0, d)
        val due = LocalDate.ofEpochDay(d).plusMonths(12).toEpochDay()
        assertFalse(Maintenance.status(QuestDef.ENGINE_OIL, s, 15_000.0, due - 1)!!.open)
        val st = Maintenance.status(QuestDef.ENGINE_OIL, s, 15_000.0, due)!!
        assertTrue(st.open); assertEquals(DueBy.DATE, st.dueBy)
        assertEquals(DueBy.BOTH, Maintenance.status(QuestDef.ENGINE_OIL, s, 20_500.0, due)!!.dueBy)
    }

    @Test fun quest_unknownOdometer_judgesByDateOnly() {
        val d = day0.toEpochDay()
        val s = withSetup(10_000.0, d)
        val st = Maintenance.status(QuestDef.ENGINE_OIL, s, null, d + 10)!!
        assertNull(st.kmLeft); assertFalse(st.open)
    }

    @Test fun quest_userEditedPreset_isUsed_andInvalidSetupRejected() {
        val d = day0.toEpochDay()
        val s = withSetup(0.0, d, oilKm = 5_000, months = 6)
        assertTrue(Maintenance.status(QuestDef.ENGINE_OIL, s, 5_000.0, d + 1)!!.open)
        assertNull(Maintenance.newSetup(CarPreset.CUSTOM, 0.0, d + 1, 10_000, 12, d)) // 미래 날짜
        assertNull(Maintenance.newSetup(CarPreset.CUSTOM, -1.0, d, 10_000, 12, d))
        assertNull(Maintenance.newSetup(CarPreset.CUSTOM, 0.0, d, 100, 12, d))
        assertNull(Maintenance.newSetup(CarPreset.CUSTOM, 0.0, d, 10_000, 0, d))
    }

    @Test fun coolant_isMonthlyLevelCheckIn_fromSetupDay() {
        val d = day0.toEpochDay()
        val s = withSetup(0.0, d - 400, today = d) // 정비는 오래전이어도 체크인은 설정한 날 기준
        assertFalse(Maintenance.status(QuestDef.COOLANT_LEVEL, s, 50_000.0, d + 29)!!.open)
        val st = Maintenance.status(QuestDef.COOLANT_LEVEL, s, 50_000.0, LocalDate.ofEpochDay(d).plusMonths(1).toEpochDay())!!
        assertTrue(st.open); assertEquals(DueBy.DATE, st.dueBy)
        assertTrue(QuestDef.COOLANT_LEVEL.title.contains("수위 점검"))
    }

    private fun gears(s: AppState) = s.materials.count { it.kind == MaterialKind.GEAR }

    @Test fun quest_done_onlyWhenOpen_resetsBase_newCycle_growsWorkshop() {
        val d = day0.toEpochDay()
        val s = withSetup(10_000.0, d)
        assertNull(Maintenance.respond(s, QuestDef.ENGINE_OIL, QuestResponse.DONE, 15_000.0, d + 1)) // 아직 안 열림
        val (done, rw) = Maintenance.respond(s, QuestDef.ENGINE_OIL, QuestResponse.DONE, 20_100.0, d + 40)!!
        assertEquals(listOf(MaterialKind.GEAR), rw.map { it.kind })
        assertEquals(0, done.maintenance.workshopLevel) // 증빙 없는 '했어요'는 톱니만, 증축 없음
        assertNull(Maintenance.respond(done, QuestDef.ENGINE_OIL, QuestResponse.DONE, 20_100.0, d + 40)) // 닫힘
        val st = Maintenance.status(QuestDef.ENGINE_OIL, done, 20_200.0, d + 41)!!
        assertFalse(st.open); assertEquals(9_900.0, st.kmLeft!!, 1e-9)
        assertFalse(st.rewardAvailable) // 마지막 보상(20,100km)에서 한 주기가 지나야 다음 보상
        // 다음 주기 도래 → 응답 보상 1개 더
        val (next, rw2) = Maintenance.respond(done, QuestDef.ENGINE_OIL, QuestResponse.LATER, 30_100.0, d + 60)!!
        assertEquals(1, rw2.size)
        assertEquals(2, gears(next))
        assertTrue(StateValidator.isValid(next))
    }

    /** 세 응답 모두 같은 작은 보상, 같은 주기 안에서는 1회만. */
    @Test fun quest_anyResponse_sameSmallReward_oncePerCycle() {
        val d = day0.toEpochDay()
        for (first in QuestResponse.entries) {
            val s = withSetup(10_000.0, d)
            val (a, r1) = Maintenance.respond(s, QuestDef.ENGINE_OIL, first, 20_000.0, d + 1)!!
            assertEquals(first.name, listOf(MaterialKind.GEAR), r1.map { it.kind })
            if (first == QuestResponse.DONE) continue
            // 미룸이 끝나 다시 열려도 같은 주기 → 보상 없음
            val later = d + 1 + Maintenance.RESERVE_RECHECK_DAYS
            val (b, r2) = Maintenance.respond(a, QuestDef.ENGINE_OIL, QuestResponse.LATER, 20_010.0, later)!!
            assertTrue(first.name, r2.isEmpty())
            val (c, r3) = Maintenance.respond(b, QuestDef.ENGINE_OIL, QuestResponse.DONE, 20_020.0, later + Maintenance.LATER_DAYS)!!
            assertTrue(first.name, r3.isEmpty())
            assertEquals(1, gears(c))
            assertTrue(StateValidator.isValid(c))
        }
    }

    @Test fun quest_reserved_reopensAfter14Days_asRecheck() {
        val d = day0.toEpochDay()
        val s = withSetup(10_000.0, d)
        val (a, _) = Maintenance.respond(s, QuestDef.ENGINE_OIL, QuestResponse.RESERVED, 20_000.0, d + 1)!!
        val hidden = Maintenance.status(QuestDef.ENGINE_OIL, a, 25_000.0, d + 14)!! // km가 늘어도 날짜 전엔 숨김
        assertFalse(hidden.open); assertTrue(hidden.snoozed)
        val back = Maintenance.status(QuestDef.ENGINE_OIL, a, 20_000.0, d + 1 + Maintenance.RESERVE_RECHECK_DAYS)!!
        assertTrue(back.open); assertTrue(back.reservedRecheck)
        assertEquals(10_000.0, back.baseKm!!, 0.0) // 기준점은 그대로
    }

    @Test fun quest_later_reopensAt500kmOr14Days_whicheverFirst() {
        val d = day0.toEpochDay()
        val s = withSetup(10_000.0, d)
        val (a, _) = Maintenance.respond(s, QuestDef.ENGINE_OIL, QuestResponse.LATER, 20_000.0, d + 1)!!
        assertFalse(Maintenance.status(QuestDef.ENGINE_OIL, a, 20_499.0, d + 2)!!.open)
        assertTrue(Maintenance.status(QuestDef.ENGINE_OIL, a, 20_500.0, d + 2)!!.open) // km 먼저
        assertTrue(Maintenance.status(QuestDef.ENGINE_OIL, a, 20_010.0, d + 1 + Maintenance.LATER_DAYS)!!.open) // 날짜 먼저
        assertFalse(Maintenance.status(QuestDef.ENGINE_OIL, a, 20_010.0, d + Maintenance.LATER_DAYS)!!.open)
        // 주행거리를 모를 때는 날짜로만
        assertFalse(Maintenance.status(QuestDef.ENGINE_OIL, a, null, d + 2)!!.open)
    }

    @Test fun quest_done_withServiceKm_givesMemorial_andBecomesBase() {
        val d = day0.toEpochDay()
        val s = withSetup(10_000.0, d)
        val (a, rw) = Maintenance.respond(s, QuestDef.ENGINE_OIL, QuestResponse.DONE, 20_300.0, d + 3, serviceKm = 20_050.0)!!
        assertEquals(setOf(MaterialKind.GEAR, MaterialKind.MEMORIAL), rw.map { it.kind }.toSet())
        assertEquals(20_050.0, Maintenance.status(QuestDef.ENGINE_OIL, a, 20_300.0, d + 3)!!.baseKm!!, 0.0) // 입력한 정비 당시 값이 기준
        assertTrue(rw.first { it.kind == MaterialKind.MEMORIAL }.tag.contains("20050km"))
        // 현재 주행거리보다 큰 값·음수·비정상 값, '했어요' 외 응답의 입력은 거부
        for (bad in listOf(20_301.0, -1.0, Double.NaN)) assertNull(Maintenance.respond(s, QuestDef.ENGINE_OIL, QuestResponse.DONE, 20_300.0, d + 3, bad))
        assertNull(Maintenance.respond(s, QuestDef.ENGINE_OIL, QuestResponse.DONE, null, d + 3, 20_000.0)) // 주행거리를 모르면 입력 불가
        assertNull(Maintenance.respond(s, QuestDef.ENGINE_OIL, QuestResponse.LATER, 20_300.0, d + 3, 20_000.0))
        assertTrue(rw.none { it.tag.contains("확인") })
        assertTrue(StateValidator.isValid(a))
    }

    /** 과거 정비 값을 넣어 곧바로 다시 도래시키며 '했어요'를 반복해도 보상·공방 증축은 처음 한 번뿐(r2 회귀). */
    @Test fun quest_done_oldServiceValues_noRewardLoop() {
        val d = day0.toEpochDay()
        var s = withSetup(10_000.0, d)
        var got = 0
        var odo = 50_000.0
        repeat(20) {
            for (km in listOf(0.0, 30_000.0, odo - 9_999.0, odo)) {
                Maintenance.respond(s, QuestDef.ENGINE_OIL, QuestResponse.DONE, odo, d + 1, km)?.let { (n, rw) -> s = n; got += rw.size }
            }
            odo += 2
        }
        assertEquals(2, got) // 처음 한 번(톱니 + 기념패)뿐
        assertEquals(1, s.maintenance.workshopLevel)
        val st = Maintenance.status(QuestDef.ENGINE_OIL, s, 50_000.0, d + 1)!!
        assertTrue(st.baseKm!! >= 40_001.0) // 기준은 입력값을 따라가지만 보상은 늘지 않는다
    }

    /** 시계가 앞선 상태에서 응답해도, 정상 날짜로 돌아오면 체크인이 다시 열린다. */
    @Test fun quest_respondWithClockAhead_reopensAfterClockFix() {
        val d = day0.toEpochDay()
        val s = withSetup(0.0, d, today = d)
        val ahead = d + 400
        val (a, _) = Maintenance.respond(s, QuestDef.COOLANT_LEVEL, QuestResponse.DONE, 100.0, ahead)!!
        val back = LocalDate.ofEpochDay(d + 10).plusMonths(1).toEpochDay()
        assertTrue(Maintenance.status(QuestDef.COOLANT_LEVEL, a, 100.0, back)!!.open)
        val (r, _) = Maintenance.respond(s, QuestDef.TIRE_PRESSURE, QuestResponse.RESERVED, 100.0, ahead)!!
        assertTrue(Maintenance.status(QuestDef.TIRE_PRESSURE, r, 100.0, back)!!.open)
    }

    /** 늦게 신고해도 정비 당시 값을 넣으면 다음 기준은 실제 정비 시점(10,000 + 5,000 = 15,000). */
    @Test fun quest_lateReport_usesEnteredServicePoint() {
        val d = day0.toEpochDay()
        val s = withSetup(5_000.0, d - 200, oilKm = 5_000, months = 12, today = d)
        val (a, _) = Maintenance.respond(s, QuestDef.ENGINE_OIL, QuestResponse.DONE, 12_000.0, d, serviceKm = 10_000.0, serviceDay = d - 30)!!
        val st = Maintenance.status(QuestDef.ENGINE_OIL, a, 12_000.0, d)!!
        assertEquals(10_000.0, st.baseKm!!, 0.0); assertEquals(d - 30, st.baseEpochDay)
        assertEquals(3_000.0, st.kmLeft!!, 1e-9) // 15,000까지
        assertTrue(Maintenance.status(QuestDef.ENGINE_OIL, a, 15_000.0, d + 1)!!.open)
        val dueDay = LocalDate.ofEpochDay(d - 30).plusMonths(12).toEpochDay()
        assertTrue(Maintenance.status(QuestDef.ENGINE_OIL, a, 12_100.0, dueDay)!!.open) // 날짜도 정비한 날 기준
        // 입력이 없으면 지금처럼 현재 값(17,000까지)
        val (b, _) = Maintenance.respond(s, QuestDef.ENGINE_OIL, QuestResponse.DONE, 12_000.0, d)!!
        assertEquals(5_000.0, Maintenance.status(QuestDef.ENGINE_OIL, b, 12_000.0, d)!!.kmLeft!!, 1e-9)
    }

    @Test fun quest_done_rejectsInvalidServicePoint() {
        val d = day0.toEpochDay()
        val s = withSetup(10_000.0, d - 100, oilKm = 5_000, months = 12, today = d)
        fun done(km: Double?, day: Long?, odo: Double? = 16_000.0) = Maintenance.respond(s, QuestDef.ENGINE_OIL, QuestResponse.DONE, odo, d, km, day)
        assertNull(done(16_001.0, null))           // 현재 주행거리 초과
        assertNull(done(9_999.0, null))            // 직전 기준(10,000) 미만
        assertNull(done(null, d + 1))              // 미래 날짜
        assertNull(done(null, d - 101))            // 직전 기준일 이전
        assertNull(done(Double.NaN, null)); assertNull(done(-1.0, null))
        assertNull(done(12_000.0, null, odo = null)) // 주행거리를 모르면 km 입력 불가
        assertNull(Maintenance.respond(s, QuestDef.ENGINE_OIL, QuestResponse.LATER, 16_000.0, d, 12_000.0, null)) // 했어요 외 입력 불가
        assertNotNull(done(10_000.0, d - 100))     // 경계값은 허용
        assertNotNull(done(null, d - 10)) // 날짜만 입력
    }

    /** 온보딩 '모름': 기준 미확인. 설치 시점을 정비 시점으로 보지 않고, 날짜 주기로만 '점검해 보세요'를 연다. */
    @Test fun quest_unconfirmedBase_flow() {
        val d = day0.toEpochDay()
        val setup = Maintenance.newSetup(CarPreset.TUCSON_GAS, 0.0, d, 10_000, 12, d, lastServiceUnknown = true)!!
        val s = Maintenance.saveSetup(started(), setup)
        val st = Maintenance.status(QuestDef.ENGINE_OIL, s, 30_000.0, d)!!
        assertTrue(st.open); assertTrue(st.baseUnconfirmed); assertEquals(DueBy.UNCONFIRMED, st.dueBy)
        assertNull(st.baseKm); assertNull(st.baseEpochDay); assertNull(st.kmLeft)
        // 체크인(설정한 날 기준)은 영향 없음
        assertFalse(Maintenance.status(QuestDef.COOLANT_LEVEL, s, 30_000.0, d)!!.open)
        // '다음에'는 날짜로만 다시 연다(기준 미확인이라 km 재알림 없음)
        val (later, rw) = Maintenance.respond(s, QuestDef.ENGINE_OIL, QuestResponse.LATER, 30_000.0, d)!!
        assertEquals(1, rw.size)
        assertFalse(Maintenance.status(QuestDef.ENGINE_OIL, later, 99_000.0, d + 1)!!.open)
        assertTrue(Maintenance.status(QuestDef.ENGINE_OIL, later, 30_000.0, d + Maintenance.LATER_DAYS)!!.open)
        // '했어요'로 정비한 날을 넣으면 그때부터 기준이 생긴다(직전 기준이 없으니 하한 없음)
        val (done, _) = Maintenance.respond(later, QuestDef.ENGINE_OIL, QuestResponse.DONE, 30_000.0, d + Maintenance.LATER_DAYS, 27_000.0, d - 5)!!
        val after = Maintenance.status(QuestDef.ENGINE_OIL, done, 30_000.0, d + Maintenance.LATER_DAYS)!!
        assertFalse(after.baseUnconfirmed); assertEquals(27_000.0, after.baseKm!!, 0.0); assertEquals(7_000.0, after.kmLeft!!, 1e-9)
        assertTrue(StateValidator.isValid(done))
    }

    /** 세 응답 모두 같은 톱니 1개. 공방 증축은 '했어요' + 증빙(날짜·주행거리 입력)일 때만. */
    @Test fun quest_sameGearForAllResponses_workshopOnlyWithEvidence() {
        val d = day0.toEpochDay()
        val s = withSetup(10_000.0, d - 10, oilKm = 5_000, months = 12, today = d)
        for (resp in QuestResponse.entries) {
            val (n, rw) = Maintenance.respond(s, QuestDef.ENGINE_OIL, resp, 16_000.0, d)!!
            assertEquals(resp.name, listOf(MaterialKind.GEAR), rw.map { it.kind })
            assertEquals(resp.name, 0, n.maintenance.workshopLevel)
        }
        for ((km, day) in listOf(15_000.0 to null, null to d - 3, 15_000.0 to d - 3)) {
            val (n, rw) = Maintenance.respond(s, QuestDef.ENGINE_OIL, QuestResponse.DONE, 16_000.0, d, km, day)!!
            assertEquals(setOf(MaterialKind.GEAR, MaterialKind.MEMORIAL), rw.map { it.kind }.toSet())
            assertEquals(1, n.maintenance.workshopLevel)
        }
    }

    /** 예약했어요·다음에(톱니) 뒤 증빙 '했어요'도 기념패·증축을 받는다. 같은 주기에 반복하면 0. */
    @Test fun quest_evidenceAfterSnooze_stillGrowsWorkshop_once() {
        val d = day0.toEpochDay()
        for (first in listOf(QuestResponse.LATER, QuestResponse.RESERVED)) {
            val s = withSetup(10_000.0, d - 10, oilKm = 5_000, months = 12, today = d)
            val (a, r1) = Maintenance.respond(s, QuestDef.ENGINE_OIL, first, 16_000.0, d)!!
            assertEquals(listOf(MaterialKind.GEAR), r1.map { it.kind })
            val back = d + Maintenance.RESERVE_RECHECK_DAYS
            val (b, r2) = Maintenance.respond(a, QuestDef.ENGINE_OIL, QuestResponse.DONE, 16_100.0, back, 15_000.0, d - 3)!!
            assertEquals(first.name, listOf(MaterialKind.MEMORIAL), r2.map { it.kind }) // 톱니는 이미 받음
            assertEquals(first.name, 1, b.maintenance.workshopLevel)
            // 같은 날 과거 값으로 다시 재도래시켜 증빙 '했어요'를 반복해도 추가 없음
            var c = b
            var more = 0
            for (km in listOf(15_001.0, 15_002.0, 15_003.0)) {
                Maintenance.respond(c, QuestDef.ENGINE_OIL, QuestResponse.DONE, 16_100.0, back, km, d - 3)?.let { (n, rw) -> c = n; more += rw.size }
            }
            assertEquals(first.name, 0, more)
            assertEquals(first.name, 1, c.maintenance.workshopLevel)
            assertTrue(StateValidator.isValid(c))
        }
    }

    /** 보상 중복 방지 회귀: 다음에 → 과거 값으로 했어요 → 곧바로 재도래 → 다음에 … 를 반복해도 보상은 한 번. */
    @Test fun quest_rewardDedup_survivesHistoryRewrites() {
        val d = day0.toEpochDay()
        var s = withSetup(10_000.0, d - 10, oilKm = 5_000, months = 12, today = d)
        var gearsGot = 0
        var responded = 0
        var day = d
        repeat(20) { i ->
            val resp = listOf(QuestResponse.LATER, QuestResponse.DONE, QuestResponse.RESERVED)[i % 3]
            val km = if (resp == QuestResponse.DONE) 10_000.0 + i else null
            val sd = if (resp == QuestResponse.DONE) d - 9 else null
            Maintenance.respond(s, QuestDef.ENGINE_OIL, resp, 40_000.0, day, km, sd)?.let { (n, rw) -> s = n; gearsGot += rw.size; responded++ }
            day += 14
        }
        assertEquals(20, responded) // 과거 값 때문에 매번 다시 열려 응답 자체는 계속 받는다
        assertEquals(2, gearsGot) // 톱니 1(첫 다음에) + 기념패 1(첫 증빙 했어요), 이후 반복은 0
        assertEquals(1, s.maintenance.workshopLevel) // 증빙 보상 1회만큼만 증축
        // 마지막 보상(40,000km)에서 한 주기(5,000km)가 지나면 다시 보상
        val open = Maintenance.status(QuestDef.ENGINE_OIL, s, 45_000.0, d + 15)!!
        assertTrue(open.rewardAvailable)
        assertTrue(StateValidator.isValid(s))
    }

    @Test fun setup_resave_keepsCheckInBaseDay() {
        val d = day0.toEpochDay()
        val s = withSetup(0.0, d - 400, today = d)
        val later = LocalDate.ofEpochDay(d).plusMonths(1).toEpochDay()
        assertTrue(Maintenance.status(QuestDef.COOLANT_LEVEL, s, 1.0, later)!!.open)
        val resaved = Maintenance.saveSetup(s, Maintenance.newSetup(CarPreset.CUSTOM, 0.0, d - 400, 8_000, 12, later)!!)
        assertTrue(Maintenance.status(QuestDef.COOLANT_LEVEL, resaved, 1.0, later)!!.open) // 열린 체크인 유지
        assertEquals(8_000, resaved.maintenance.setup!!.oilKm)
    }

    // ── 미션(조연) ──

    @Test fun mission_normalSignalsOnly_oncePerDay_noPenalty() {
        assertTrue(Missions.judge(MissionDef.PARK_AFTER_DRIVE, 12.0, GearState.PARK))
        assertFalse(Missions.judge(MissionDef.PARK_AFTER_DRIVE, 12.0, GearState.UNKNOWN))
        assertFalse(Missions.judge(MissionDef.PARK_AFTER_DRIVE, 0.5, GearState.PARK))
        assertEquals(listOf(MissionDef.PARK_AFTER_DRIVE), MissionDef.entries) // 짧은 이동 미션 제거(0.1.2)
        assertEquals("짧은 이동도 기록하기(이전 미션)", Missions.titleOf("short-trip")) // 이전 저장 도장 표시
        // 하루 여러 장면이어도 도장은 최대 1개
        var s = started()
        var t = 0
        for (k in listOf(1002.0, 1004.0, 1006.0, 1008.0)) s = settle(s, k, at(0, 12 + t++)).first
        assertTrue(s.missions.stamps.count { it.epochDay == day0.toEpochDay() } <= 1)
        // 기어가 P가 아니면(판정 불가) 도장 없음, 재료·장면은 그대로(차감 없음)
        val (n, r) = settle(started(), 1003.0, at(0), GearState.NOT_PARK)
        assertNull((r as SceneResult.Scene).missionDone)
        assertTrue(n.missions.stamps.isEmpty())
        assertEquals(1, j(n.materials))
    }

    // ── 꾸미기 ──

    @Test fun decoration_oneMaterialPerEmptySlot() {
        val s = settle(started(), 1010.0, at(0)).first
        val (m1, m2) = s.materials
        val today = day0.toEpochDay()
        val p1 = Decoration.place(s, m1.id, 0, today)!!
        assertNull(Decoration.place(p1, m2.id, 0, today)) // 이미 찬 자리
        assertNull(Decoration.place(p1, m1.id, 1, today)) // 이미 놓인 재료
        assertNull(Decoration.place(p1, m2.id, Decoration.SLOT_COUNT, today)) // 범위 밖
        assertNull(Decoration.place(p1, "nope", 1, today))
        val p2 = Decoration.place(p1, m2.id, 1, today)!!
        assertEquals(m2.kind, Decoration.placedToday(p2, today)!!.kind)
        assertTrue(StateValidator.isValid(p2))
    }

    /** 0.1.0 저장 파일(하루 재료 2개, 새 필드 없음)을 손상으로 보지 않고 이어서 정산한다. */
    @Test fun legacy_0_1_0_state_loadsAndContinues() {
        val d = day0.toEpochDay()
        val file = File(tmp.root, "legacy.json")
        val m = { id: String -> """{"id":"$id","kind":"WOOD","epochDay":$d,"eventId":"scene-1","tag":"t"}""" }
        file.writeText(
            """{"version":1,"ledger":{"baselineKm":1010.0,"lastSettledAtMillis":${at(0)},"sceneCount":1,"sceneKm":10.0,"lastMaterialEpochDay":$d,""" +
                """"scenes":[{"eventId":"scene-1","epochDay":$d,"atMillis":${at(0)},"deltaKm":10.0,"materialIds":["m-scene-1-0","m-scene-1-1"]}]},""" +
                """"materials":[${m("m-scene-1-0")},${m("m-scene-1-1")}],"village":{"foundedEpochDay":$d},""" +
                """"maintenance":{"records":{"engine-oil":{"baseKm":1000.0,"baseEpochDay":$d,"completions":1}},"workshopLevel":1,""" +
                """"setup":{"presetId":"custom","lastServiceKm":1000.0,"lastServiceEpochDay":$d,"oilKm":10000,"oilMonths":12,"setupEpochDay":$d}}}""",
        )
        val loaded = FileStateStore(file).load()
        assertEquals(2, loaded.materials.size)
        val (n, r) = settle(loaded, 1030.0, at(1))
        assertEquals(1, j((r as SceneResult.Scene).newMaterials))
        FileStateStore(file).save(n)
        assertEquals(n, FileStateStore(file).load())
    }

    // ── 탑승·하차 한 문장 각 1회 ──

    @Test fun voice_alightOncePerEvent_boardingOncePerBaseline() {
        val (s, r) = settle(started(), 1010.0, at(0))
        val ev = (r as SceneResult.Scene).record.eventId
        val spoken = VoiceOnce.alight(s, ev)!!
        assertNull(VoiceOnce.alight(spoken, ev))
        val b = VoiceOnce.boarding(spoken)!!
        assertNull(VoiceOnce.boarding(b))
        val moved = settle(b, 1020.0, at(1)).first
        assertNotNull(VoiceOnce.boarding(moved)) // 다음 운행 주기에는 다시 한 번
        assertNull(VoiceOnce.boarding(AppState())) // 기준값이 없으면 말하지 않음
    }
}
