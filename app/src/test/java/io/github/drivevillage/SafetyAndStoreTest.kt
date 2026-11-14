package io.github.drivevillage

import io.github.drivevillage.core.gate.GearState
import io.github.drivevillage.core.gate.SafetyLatch
import io.github.drivevillage.core.gate.UxState
import io.github.drivevillage.core.village.AppState
import io.github.drivevillage.core.village.Maintenance
import io.github.drivevillage.core.village.CarPreset
import io.github.drivevillage.core.village.QuestDef
import io.github.drivevillage.core.village.Decoration
import io.github.drivevillage.core.village.OdometerReading
import io.github.drivevillage.core.village.SceneLedger
import io.github.drivevillage.data.StateValidator
import io.github.drivevillage.data.FileStateStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class SafetyAndStoreTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun armedLatch() = SafetyLatch().apply { onGear(GearState.PARK); onUx(UxState.UNRESTRICTED); arm() }

    @Test fun latch_beforeArm_onlyRestrictionTrips() {
        val l = SafetyLatch()
        l.onGear(GearState.NOT_PARK); l.onGear(GearState.UNKNOWN); l.onUx(UxState.UNKNOWN)
        assertFalse(l.isTripped)
        l.onUx(UxState.RESTRICTED)
        assertTrue(l.isTripped)
    }

    @Test fun latch_armWithUnsafeLatestValue_tripsImmediately() {
        val l = SafetyLatch(); l.onGear(GearState.NOT_PARK); l.onUx(UxState.UNRESTRICTED)
        l.arm()
        assertTrue(l.isTripped)
    }

    /** StateFlow라면 P→D→P가 P로 합쳐질 수 있는 상황: 래치는 중간의 D를 놓치지 않는다. */
    @Test fun latch_transientDriveAfterArm_staysTripped() {
        val l = armedLatch()
        assertTrue(l.allowsWrite())
        l.onGear(GearState.NOT_PARK); l.onGear(GearState.PARK)
        assertTrue(l.isTripped)
        assertFalse(l.allowsWrite())
    }

    @Test fun latch_unknownOrLostAfterArm_trips() {
        for (event in listOf<(SafetyLatch) -> Unit>({ it.onGear(GearState.UNKNOWN) }, { it.onUx(UxState.UNKNOWN) }, { it.onUx(UxState.RESTRICTED) })) {
            val l = armedLatch(); event(l)
            assertTrue(l.isTripped); assertFalse(l.allowsWrite())
        }
    }

    @Test fun latch_notArmed_noWrite() {
        val l = SafetyLatch(); l.onGear(GearState.PARK); l.onUx(UxState.UNRESTRICTED)
        assertFalse(l.allowsWrite())
    }


    @Test fun store_rejectsSemanticallyCorruptLedger() {
        val file = File(tmp.root, "state.json")
        val cases = listOf(
            """{"ledger":{"sceneKm":-1.0}}""",
            """{"ledger":{"sceneCount":-1}}""",
            """{"ledger":{"baselineKm":-5.0}}""",
            """{"maintenance":{"workshopLevel":-1}}""",
            """{"maintenance":{"records":{"engine-oil":{"baseKm":1.0,"baseEpochDay":1,"completions":3}},"workshopLevel":1}}""",
            """{"version":2}""",
        )
        for (c in cases) {
            file.writeText(c)
            val s = FileStateStore(file).load()
            assertNull(c, s.ledger.baselineKm)
            assertEquals(c, AppState(), s)
        }
        assertTrue(File(tmp.root, "state.json.corrupt").exists())
    }

    @Test fun store_loadNeverThrows_whenQuarantineFails() {
        // 상위 경로가 파일이라 .corrupt 복사가 실패하는 상황에서도 빈 상태를 돌려준다.
        val dir = File(tmp.root, "d"); dir.mkdirs()
        val file = File(dir, "state.json"); file.writeText("{bad")
        File(dir, "state.json.corrupt").mkdirs() // 같은 이름의 디렉터리 → copyTo 실패
        assertEquals(AppState(), FileStateStore(file).load())
    }

    @Test fun store_saveRejectsInvalidState() {
        val file = File(tmp.root, "state.json")
        val bad = AppState().let { it.copy(ledger = it.ledger.copy(sceneCount = 5)) }
        assertTrue(runCatching { FileStateStore(file).save(bad) }.isFailure)
        assertFalse(file.exists())
    }

    /** 개별 값은 정상이지만 서로 모순되는 원장(재료 과지급·슬롯 중복·하루 도장 2개·장면 없는 거리). */
    @Test fun store_rejectsCrossFieldContradictions() {
        val file = File(tmp.root, "state.json")
        val m = { id: String, day: Long, slot: Int? -> """{"id":"$id","kind":"WOOD","epochDay":$day,"eventId":"scene-1","tag":"t","placedSlot":${slot ?: "null"}}""" }
        val cases = listOf(
            // 하루 3개 지급
            """{"ledger":{"baselineKm":10.0,"sceneCount":1,"sceneKm":5.0,"lastMaterialEpochDay":1},"materials":[${m("a", 1, null)},${m("b", 1, null)},${m("c", 1, null)}]}""",
            // 한 슬롯에 두 개
            """{"ledger":{"baselineKm":10.0,"sceneCount":2,"sceneKm":5.0,"lastMaterialEpochDay":2},"materials":[${m("a", 1, 0)},${m("b", 2, 0)}]}""",
            // 장면 수보다 재료 지급일이 많음
            """{"ledger":{"baselineKm":10.0,"sceneCount":1,"sceneKm":5.0,"lastMaterialEpochDay":2},"materials":[${m("a", 1, null)},${m("b", 2, null)}]}""",
            // 장면 1회당 1km 미만
            """{"ledger":{"baselineKm":10.0,"sceneCount":5,"sceneKm":3.0}}""",
            // 같은 날 미션 도장 2개
            """{"missions":{"stamps":[{"missionId":"short-trip","epochDay":3,"eventId":"scene-1"},{"missionId":"park-after-drive","epochDay":3,"eventId":"scene-2"}]}}""",
        )
        for (c in cases) {
            file.writeText(c)
            assertEquals(c, AppState(), FileStateStore(file).load())
        }
    }

    /** 실제 정산을 많이 거친 상태(태그·꾸미기·정비 신고 포함)는 검증을 통과하고 그대로 저장·로드된다. */
    @Test fun store_acceptsRealHistory() {
        val zone = java.time.ZoneId.of("Asia/Seoul")
        var s = SceneLedger.settle(AppState(), OdometerReading.Km(0.0), GearState.PARK, 0, zone).first
        s = Maintenance.saveSetup(s, Maintenance.newSetup(CarPreset.TUCSON_GAS, 0.0, 0, 10_000, 12, 0)!!)
        var t = 0L
        for (i in 1..400) {
            t += 6L * 60 * 60 * 1000
            val day = SceneLedger.epochDay(t, zone)
            s = SceneLedger.settle(s, OdometerReading.Km(i * 3.0), GearState.PARK, t, zone).first
            s.ledger.scenes.lastOrNull()?.let { sc -> s = SceneLedger.setTag(s, sc.eventId, SceneLedger.TAG_PRESETS[i % SceneLedger.TAG_PRESETS.size]) ?: s }
            s.materials.firstOrNull { it.placedSlot == null }?.let { m -> s = Decoration.place(s, m.id, i % Decoration.SLOT_COUNT, day) ?: s }
            for (q in QuestDef.entries) s = Maintenance.complete(s, q, i * 3.0, day) ?: s
        }
        assertTrue(StateValidator.isValid(s))
        assertTrue(s.maintenance.workshopLevel > 0)
        val file = File(tmp.root, "state.json")
        FileStateStore(file).save(s)
        assertEquals(s, FileStateStore(file).load())
    }

    @Test fun store_dirSyncFailure_failsSave() {
        val file = File(tmp.root, "state.json")
        val store = FileStateStore(file) { throw java.io.IOException("fsync") }
        assertTrue(runCatching { store.save(AppState()) }.isFailure)
    }

    /** 쓰기의 '검사 → 전송' 구간 중에는 다른 스레드의 래치 작동이 끝날 때까지 기다리거나(최대 50ms) 그 뒤에 반영된다. */
    @Test fun latch_tripDuringWriteSection_doesNotHangCaller() {
        val l = armedLatch()
        val started = java.util.concurrent.CountDownLatch(1)
        val writer = Thread { l.withLock { started.countDown(); Thread.sleep(300) } }
        writer.start(); started.await()
        val t0 = System.nanoTime()
        l.onGear(GearState.NOT_PARK)
        val waitedMs = (System.nanoTime() - t0) / 1_000_000
        assertTrue("waited $waitedMs ms", waitedMs < 250)
        assertTrue(l.isTripped)
        writer.join()
    }
}
