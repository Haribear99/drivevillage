package io.github.drivevillage

import io.github.drivevillage.core.gate.GearState
import io.github.drivevillage.core.village.SceneLedger
import io.github.drivevillage.core.village.OdometerReading
import io.github.drivevillage.core.village.AppState
import io.github.drivevillage.core.village.Decoration
import io.github.drivevillage.data.CommitUncertainException
import io.github.drivevillage.data.FileStateStore
import io.github.drivevillage.data.StateRepository
import io.github.drivevillage.data.StateStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.time.ZoneId
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class StateRepositoryTest {
    @get:Rule val tmp = TemporaryFolder()
    private val zone = ZoneId.of("Asia/Seoul")
    private val DAY = 24L * 60 * 60 * 1000
    /** 2026-11-15 09:00 KST(시계 하한 이후). */
    private val BASE = java.time.LocalDate.of(2026, 11, 15).atTime(9, 0).atZone(zone).toInstant().toEpochMilli()

    private fun seed(file: File): AppState {
        var s = SceneLedger.settle(AppState(), OdometerReading.Km(1.0), GearState.PARK, BASE, zone).first
        for (i in 1..50) s = SceneLedger.settle(s, OdometerReading.Km(1.0 + i * 2.0), GearState.PARK, BASE + i * DAY, zone).first
        FileStateStore(file).save(s)
        return s
    }

    @Test fun load_happensOffCaller_andMutationsWaitForIt() = runBlocking {
        val file = File(tmp.root, "s.json")
        val seeded = seed(file)
        val repo = StateRepository(FileStateStore(file))
        // 로드 전에 들어온 변경도 로드된 상태 위에서 처리된다.
        repo.update { it.copy(voice = it.voice.copy(lastBoardingKey = "먼저")) }
        assertEquals(seeded.ledger, repo.state.value.ledger)
        assertEquals("먼저", repo.state.value.voice.lastBoardingKey)
    }

    /** 여러 코루틴이 동시에 재료를 놓아도 슬롯 하나에 하나만, 잃는 변경 없이 직렬화된다(덮어쓰기 없음). */
    @Test fun concurrentMutations_areSerialized() = runBlocking {
        val file = File(tmp.root, "s.json")
        seed(file)
        val repo = StateRepository(FileStateStore(file)); repo.awaitLoaded()
        val ids = repo.state.value.materials.map { it.id }
        assertTrue(ids.size >= 20)
        (0 until 20).map { i -> async(Dispatchers.Default) { repo.update { Decoration.place(it, ids[i], i % Decoration.SLOT_COUNT, 1) ?: it } } }.awaitAll()
        val placed = repo.state.value.materials.filter { it.placedSlot != null }
        assertEquals(Decoration.SLOT_COUNT, placed.size)
        assertEquals(Decoration.SLOT_COUNT, placed.map { it.placedSlot }.toSet().size)
        assertEquals(repo.state.value, FileStateStore(file).load())
    }

    /** 한 스레드(메인 대역)에서 차례로 보낸 변경은 그 순서대로 반영된다(마지막 선택이 남는다). */
    @Test fun mutations_keepCallerOrder() = runBlocking {
        val repo = StateRepository(FileStateStore(File(tmp.root, "s.json"))); repo.awaitLoaded()
        val main = Executors.newSingleThreadExecutor()
        val colors = listOf("acorn", "cream", "sky", "mint", "peach")
        val jobs = mutableListOf<kotlinx.coroutines.Deferred<Boolean>>()
        val scope = CoroutineScope(main.asDispatcher())
        repeat(40) { i -> jobs += scope.async { repo.update { it.copy(voice = it.voice.copy(lastAlightEventId = colors[i % 5])) } } }
        jobs.awaitAll()
        assertEquals(colors[39 % 5], repo.state.value.voice.lastAlightEventId)
        main.shutdown()
    }

    /** 호출한 화면이 저장 도중 취소돼도, 시작된 저장은 메모리 반영까지 끝난다. */
    @Test fun callerCancellation_doesNotDropCommittedChange() = runBlocking {
        val inner = FileStateStore(File(tmp.root, "s.json"))
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val slow = object : StateStore {
            override fun load() = inner.load()
            override fun save(state: AppState) { entered.countDown(); release.await(5, TimeUnit.SECONDS); inner.save(state) }
        }
        val repo = StateRepository(slow); repo.awaitLoaded()
        val job = CoroutineScope(Dispatchers.Default).launch { repo.update { it.copy(voice = it.voice.copy(lastBoardingKey = "새이름")) } }
        entered.await(5, TimeUnit.SECONDS)
        job.cancel() // 화면 종료
        release.countDown()
        repeat(50) { if (repo.state.value.voice.lastBoardingKey != "새이름") delay(20) }
        assertEquals("새이름", repo.state.value.voice.lastBoardingKey)
        assertEquals("새이름", inner.load().voice.lastBoardingKey)
    }

    @Test fun saveFailure_keepsMemoryUnchanged() = runBlocking {
        val failing = object : StateStore {
            override fun load() = AppState()
            override fun save(state: AppState) { throw java.io.IOException("disk") }
        }
        val repo = StateRepository(failing)
        assertFalse(repo.update { it.copy(voice = it.voice.copy(lastBoardingKey = "x")) })
        assertEquals(AppState(), repo.state.value)
    }

    /** 교체 후 확정 불확실: 메모리를 디스크와 맞춘다(다음 변경이 디스크의 새 상태를 덮지 않게). */
    /** 확정 불확실이 끝내 풀리지 않으면: 메모리 불변(보상 비공개), 이후 저장 전부 차단(디스크 덮어쓰기 없음). */
    @Test fun commitUncertain_persistent_blocksWritesWithoutReloading() = runBlocking {
        val file = File(tmp.root, "s.json")
        val dirSyncFails = FileStateStore(file) { throw java.io.IOException("dir fsync") }
        val other = File(tmp.root, "other.json")
        assertEquals(true, runCatching { FileStateStore(other) { throw java.io.IOException("x") }.save(AppState()) }.exceptionOrNull() is CommitUncertainException)
        val repo = StateRepository(dirSyncFails); repo.awaitLoaded()
        assertFalse(repo.update { it.copy(voice = it.voice.copy(lastBoardingKey = "디스크")) })
        assertEquals(null, repo.state.value.voice.lastBoardingKey) // 공개하지 않음
        assertTrue(repo.writesBlocked)
        assertFalse(repo.update { it.copy(voice = it.voice.copy(lastBoardingKey = "나중")) })
        assertEquals("디스크", FileStateStore(file).load().voice.lastBoardingKey) // 디스크는 덮이지 않음
    }

    /** 디렉터리 동기화 재시도가 성공하면 정상 확정. */
    @Test fun commitUncertain_retrySucceeds_commitsNormally() = runBlocking {
        val file = File(tmp.root, "s.json")
        var calls = 0
        val flaky = FileStateStore(file) { if (calls++ == 0) throw java.io.IOException("once") }
        val repo = StateRepository(flaky); repo.awaitLoaded()
        assertTrue(repo.update { it.copy(voice = it.voice.copy(lastBoardingKey = "확정")) })
        assertEquals("확정", repo.state.value.voice.lastBoardingKey)
        assertFalse(repo.writesBlocked)
    }

    /** 시작 시 읽기 실패: 원본이 정상일 수 있으므로 쓰기를 차단하고 덮어쓰지 않는다. */
    @Test fun readFailureAtStart_blocksWrites_andKeepsOriginal() = runBlocking {
        val file = File(tmp.root, "s.json")
        val original = seed(file)
        val flaky = object : StateStore {
            override fun load() = AppState()
            override fun loadResult() = io.github.drivevillage.data.LoadResult.ReadFailed
            override fun save(state: AppState) = FileStateStore(file).save(state)
        }
        val repo = StateRepository(flaky); repo.awaitLoaded()
        assertTrue(repo.writesBlocked)
        assertFalse(repo.update { it.copy(voice = it.voice.copy(lastBoardingKey = "덮어쓰기")) })
        assertEquals(original, FileStateStore(file).load())
    }

    @Test fun loadResult_distinguishesMissingCorruptLoaded() {
        val file = File(tmp.root, "s.json")
        assertEquals(io.github.drivevillage.data.LoadResult.Missing, FileStateStore(file).loadResult())
        file.writeText("{bad")
        assertEquals(io.github.drivevillage.data.LoadResult.Corrupt(quarantined = true), FileStateStore(file).loadResult())
        FileStateStore(file).save(AppState())
        assertTrue(FileStateStore(file).loadResult() is io.github.drivevillage.data.LoadResult.Loaded)
    }

    /** 손상 + 보관 실패: 새로 시작하되 원본을 덮지 않도록 쓰기 차단. */
    @Test fun corruptWithoutQuarantine_blocksWrites() = runBlocking {
        val dir = File(tmp.root, "d"); dir.mkdirs()
        val file = File(dir, "s.json"); file.writeText("{bad")
        File(dir, "s.json.corrupt").mkdirs() // 보관 실패 유도(비어 있지 않은 같은 이름 디렉터리)
        File(dir, "s.json.corrupt/keep").writeText("x")
        val repo = StateRepository(FileStateStore(file)); repo.awaitLoaded()
        assertTrue(repo.writesBlocked)
        assertFalse(repo.update { it.copy(voice = it.voice.copy(lastBoardingKey = "x")) })
        assertEquals("{bad", file.readText())
    }

    /** 일시적 읽기 실패로 차단된 뒤, 새 화면에서 디스크를 다시 읽어 정상이면 복구된다. */
    @Test fun recoverIfBlocked_unblocksWhenDiskReadable() = runBlocking {
        val file = File(tmp.root, "s.json")
        val original = seed(file)
        var failFirst = true
        var dirSyncOk = true
        val flaky = object : StateStore {
            override fun load() = FileStateStore(file).load()
            override fun loadResult() = if (failFirst) { failFirst = false; io.github.drivevillage.data.LoadResult.ReadFailed } else FileStateStore(file).loadResult()
            override fun save(state: AppState) = FileStateStore(file).save(state)
            override fun retryDirSync() { if (!dirSyncOk) throw java.io.IOException("dir fsync") }
        }
        val repo = StateRepository(flaky); repo.awaitLoaded()
        assertTrue(repo.writesBlocked)
        repo.recoverIfBlocked()
        assertTrue(repo.update { it }) // 큐에서 복구 뒤에 처리됨
        assertFalse(repo.writesBlocked)
        assertEquals(original, repo.state.value)
        assertTrue(repo.update { it.copy(voice = it.voice.copy(lastBoardingKey = "복구")) })
        assertEquals("복구", FileStateStore(file).load().voice.lastBoardingKey)
    }

    /** 확정 불확실로 차단된 뒤 디렉터리 동기화가 계속 실패하면, 재열기 복구도 보상을 공개하지 않는다. */
    @Test fun recover_commitUncertain_requiresDirSync() = runBlocking {
        val file = File(tmp.root, "s.json")
        val dirSyncFails = FileStateStore(file) { throw java.io.IOException("dir fsync") }
        val repo = StateRepository(dirSyncFails); repo.awaitLoaded()
        assertFalse(repo.update { it.copy(voice = it.voice.copy(lastBoardingKey = "미확정")) })
        assertTrue(repo.writesBlocked)
        repo.recoverIfBlocked()
        assertFalse(repo.update { it }.let { false }) // 큐 처리 대기
        assertTrue(repo.writesBlocked)
        assertEquals(null, repo.state.value.voice.lastBoardingKey) // 미확정 상태 비공개
    }

    /** 이전 프로세스가 확정하지 못한 원장: 첫 로드에서도 디렉터리 동기화가 실패하면 공개하지 않고 차단. */
    @Test fun initialLoad_dirSyncFails_doesNotPublish() = runBlocking {
        val file = File(tmp.root, "s.json")
        seed(file)
        val repo = StateRepository(FileStateStore(file) { throw java.io.IOException("dir fsync") }); repo.awaitLoaded()
        assertTrue(repo.writesBlocked)
        assertEquals(AppState(), repo.state.value)
    }

    /** 손상 원장 보관본은 fsync·디렉터리 동기화까지 성공해야 '보관 완료'. */
    @Test fun quarantine_requiresDurableCopy() {
        val file = File(tmp.root, "s.json"); file.writeText("{bad")
        val r = FileStateStore(file) { throw java.io.IOException("dir fsync") }.loadResult()
        assertEquals(io.github.drivevillage.data.LoadResult.Corrupt(quarantined = false), r)
    }

    /** 읽기 실패로 차단된 뒤 디렉터리 동기화가 실패하면, 읽기가 성공해도 공개·해제하지 않는다. */
    @Test fun recover_readFailed_requiresDirSync() = runBlocking {
        val file = File(tmp.root, "s.json")
        seed(file)
        var first = true
        val store = object : StateStore {
            override fun load() = FileStateStore(file).load()
            override fun loadResult() = if (first) { first = false; io.github.drivevillage.data.LoadResult.ReadFailed } else FileStateStore(file).loadResult()
            override fun save(state: AppState) = FileStateStore(file).save(state)
            override fun retryDirSync() { throw java.io.IOException("dir fsync") }
        }
        val repo = StateRepository(store); repo.awaitLoaded()
        repo.recoverIfBlocked()
        repo.update { it } // 큐 처리 대기
        assertTrue(repo.writesBlocked)
        assertEquals(AppState(), repo.state.value)
    }

    private fun java.util.concurrent.ExecutorService.asDispatcher() = this.asCoroutineDispatcher()
}
