package io.github.drivevillage

import io.github.drivevillage.core.gate.GearState
import io.github.drivevillage.core.village.AppState
import io.github.drivevillage.core.village.CarPreset
import io.github.drivevillage.core.village.Decoration
import io.github.drivevillage.core.village.DueBy
import io.github.drivevillage.core.village.Maintenance
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
        assertEquals(SceneLedger.MATERIALS_PER_DAY, end.materials.size)
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
        assertEquals(SceneLedger.MATERIALS_PER_DAY, n.materials.size)
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
            assertEquals("day $d", SceneLedger.MATERIALS_PER_DAY, (r as SceneResult.Scene).newMaterials.size)
            FileStateStore(file).save(n); s = n
        }
        odo += 12
        val (atFuture, rf) = settle(s, odo, at(400, 20)) // 앞서 받은 날에 실제로 도달
        assertTrue((rf as SceneResult.Scene).alreadyGotToday)
        assertTrue(StateValidator.isValid(atFuture))
        assertEquals(SceneLedger.MATERIALS_PER_DAY * 32, atFuture.materials.size)
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

    // ── 재료: 하루 1회, 거리와 무관한 동일량, 날짜·기억 태그 ──

    @Test fun materials_oncePerDay_sameAmountRegardlessOfDistance() {
        var s = started()
        val (a, ra) = settle(s, 1001.0, at(0)) // 1km
        assertEquals(SceneLedger.MATERIALS_PER_DAY, (ra as SceneResult.Scene).newMaterials.size)
        val (b, rb) = settle(a, 1300.0, at(0, 21)) // 같은 날 299km 더
        rb as SceneResult.Scene
        assertTrue(rb.alreadyGotToday)
        assertTrue(rb.newMaterials.isEmpty())
        assertEquals(SceneLedger.MATERIALS_PER_DAY, b.materials.size)
        val (c, rc) = settle(b, 1600.0, at(1)) // 다음 날 300km
        assertEquals(SceneLedger.MATERIALS_PER_DAY, (rc as SceneResult.Scene).newMaterials.size)
        assertEquals(SceneLedger.MATERIALS_PER_DAY * 2, c.materials.size)
        s = c
        // 모든 재료에 날짜·태그·출처 이벤트, id 중복 없음
        assertEquals(s.materials.size, s.materials.map { it.id }.toSet().size)
        s.materials.forEach { m ->
            assertTrue(m.tag.startsWith(SceneLedger.dateLabel(m.epochDay)))
            assertTrue(s.ledger.scenes.any { it.eventId == m.eventId })
        }
        assertTrue(StateValidator.isValid(s))
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

    @Test fun quest_completeOnlyWhenOpen_resetsCycle_growsWorkshop() {
        val d = day0.toEpochDay()
        val s = withSetup(10_000.0, d)
        assertNull(Maintenance.complete(s, QuestDef.ENGINE_OIL, 15_000.0, d + 1)) // 아직 안 열림
        val done = Maintenance.complete(s, QuestDef.ENGINE_OIL, 20_100.0, d + 40)!!
        assertEquals(1, done.maintenance.workshopLevel)
        assertNull(Maintenance.complete(done, QuestDef.ENGINE_OIL, 20_100.0, d + 40)) // 같은 주기 재신고 불가
        val st = Maintenance.status(QuestDef.ENGINE_OIL, done, 20_200.0, d + 41)!!
        assertFalse(st.open); assertEquals(9_900.0, st.kmLeft!!, 1e-9)
        assertTrue(StateValidator.isValid(done))
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
        assertTrue(Missions.judge(MissionDef.SHORT_TRIP, 3.0, GearState.PARK))
        assertFalse(Missions.judge(MissionDef.SHORT_TRIP, 12.0, GearState.PARK))
        // 하루 여러 장면이어도 도장은 최대 1개
        var s = started()
        var t = 0
        for (k in listOf(1002.0, 1004.0, 1006.0, 1008.0)) s = settle(s, k, at(0, 12 + t++)).first
        assertTrue(s.missions.stamps.count { it.epochDay == day0.toEpochDay() } <= 1)
        // 기어가 P가 아니면(판정 불가) 도장 없음, 재료·장면은 그대로(차감 없음)
        val (n, r) = settle(started(), 1003.0, at(0), GearState.NOT_PARK)
        assertNull((r as SceneResult.Scene).missionDone)
        assertTrue(n.missions.stamps.isEmpty())
        assertEquals(SceneLedger.MATERIALS_PER_DAY, n.materials.size)
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
