package io.github.drivevillage.data

import android.content.Context
import io.github.drivevillage.core.village.AppState
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.io.File

/**
 * 프로세스에 하나뿐인 상태 저장소. 단일 소비자 큐로 모든 변경을 **요청 순서대로** 처리한다.
 *
 * - 첫 로드도 큐의 첫 작업으로 IO 스레드에서 한다(메인 스레드에서 파일을 읽지 않는다).
 *   로드 전에 들어온 변경은 로드가 끝난 뒤 순서대로 처리된다.
 * - 요청은 호출 즉시(호출 스레드에서) 큐에 넣는다. 그래서 사용자가 누른 순서가 뒤집히지 않는다.
 * - 처리는 저장소 자체 스코프에서 한다. 호출한 화면이 취소돼도 '저장 → 메모리 반영'은 끝난다.
 * - 저장 실패: 메모리를 바꾸지 않고 saved=false(보상 미확정).
 * - 교체 뒤 확정 불확실([CommitUncertainException]): 디렉터리 동기화를 몇 번 다시 시도하고,
 *   성공하면 정상 확정한다. 끝내 실패하면 디스크를 다시 읽지 않고(읽기 오류를 빈 원장으로 착각하지 않게)
 *   메모리를 바꾸지 않은 채 이 프로세스의 이후 저장을 모두 차단한다(보상 공개 없음, 디스크 덮어쓰기 없음).
 *   다음 실행에서 디스크를 새로 읽는다.
 */
class StateRepository(
    private val store: StateStore,
    dispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val _state = MutableStateFlow(AppState())
    val state: StateFlow<AppState> = _state
    private val loaded = CompletableDeferred<Unit>()
    private val _isLoaded = MutableStateFlow(false)
    /** 첫 로드 완료 여부. 완료 전에는 저장 데이터 기반 화면·편집을 보이지 않는다. */
    val isLoaded: StateFlow<Boolean> = _isLoaded
    private val _writesBlocked = MutableStateFlow(false)
    /** 쓰기 차단(이 프로세스 동안): 읽기 실패·보관 실패한 손상·확정 불확실 뒤. 화면에 안내한다. */
    val writesBlockedFlow: StateFlow<Boolean> = _writesBlocked
    val writesBlocked: Boolean get() = _writesBlocked.value

    data class Mutation<R>(val saved: Boolean, val value: R)

    private sealed interface Job
    private class Request(val f: (AppState) -> Pair<AppState, Any?>, val result: CompletableDeferred<Mutation<Any?>>) : Job
    /** 차단 상태 복구 시도. 같은 소비자에서 순서대로 실행되어 변경과 경합하지 않는다. */
    private object Recover : Job

    private val queue = Channel<Job>(Channel.UNLIMITED)

    /**
     * 저장이 차단된 상태면 디스크를 다시 확인해 복구를 시도한다. 디렉터리 동기화를 다시 하고,
     * 원장을 정상으로 다시 읽었을 때만 그 내용으로 메모리를 맞추고 차단을 푼다. 화면을 새로 열 때 부른다.
     */
    fun recoverIfBlocked() { if (_writesBlocked.value) queue.trySend(Recover) }

    /** 차단 원인. 확정 불확실은 디렉터리 동기화 성공까지 확인해야 풀린다. */
    private enum class BlockReason { READ_FAILED, CORRUPT_NOT_QUARANTINED, COMMIT_UNCERTAIN }
    @Volatile private var blockReason: BlockReason? = null

    private fun block(reason: BlockReason) { blockReason = reason; _writesBlocked.value = true }

    private fun recover() {
        if (!_writesBlocked.value) return
        when (blockReason) {
            BlockReason.COMMIT_UNCERTAIN -> {
                // 파일이 읽힌다는 것은 rename이 영구 저장됐다는 보장이 아니다. 동기화 성공이 먼저.
                if (runCatching { store.retryDirSync() }.isFailure) return
                val r = store.loadResult()
                if (r is LoadResult.Loaded) { _state.value = r.state; unblock() }
            }
            BlockReason.READ_FAILED -> {
                // 첫 읽기 실패는 이전 프로세스의 저장이 확정됐다는 근거가 아니다. 동기화 성공 + 정상 로드 둘 다 필요.
                if (runCatching { store.retryDirSync() }.isFailure) return
                val r = store.loadResult()
                if (r is LoadResult.Loaded) { _state.value = r.state; unblock() }
            }
            BlockReason.CORRUPT_NOT_QUARANTINED, null -> Unit // 사본 없는 손상 원장은 이 프로세스에서 복구하지 않음
        }
    }

    private fun unblock() { blockReason = null; _writesBlocked.value = false }

    init {
        scope.launch {
            when (val r = store.loadResult()) { // 예외를 던지지 않는다
                // 이전 프로세스가 rename 뒤 디렉터리 동기화를 확정하지 못했을 수 있다.
                // 디렉터리 동기화가 성공해야만 읽은 원장을 공개한다(실패하면 공개하지 않고 쓰기 차단).
                is LoadResult.Loaded ->
                    if (runCatching { store.retryDirSync() }.isSuccess) _state.value = r.state
                    else block(BlockReason.COMMIT_UNCERTAIN)
                LoadResult.Missing -> Unit // 처음 시작
                is LoadResult.Corrupt -> if (!r.quarantined) block(BlockReason.CORRUPT_NOT_QUARANTINED) // 사본이 없으면 원본 보호
                LoadResult.ReadFailed -> block(BlockReason.READ_FAILED) // 원본이 정상일 수 있다: 덮어쓰지 않는다
            }
            _isLoaded.value = true
            loaded.complete(Unit)
            for (job in queue) when (job) {
                Recover -> runCatching { recover() }
                is Request -> {
                    val outcome = try { process(job.f) } catch (e: Throwable) { null }
                    if (outcome != null) job.result.complete(outcome)
                    else job.result.completeExceptionally(IllegalStateException("mutation-failed"))
                }
            }
        }
    }

    suspend fun awaitLoaded() = loaded.await()

    private fun process(f: (AppState) -> Pair<AppState, Any?>): Mutation<Any?> {
        val current = _state.value
        val (next, value) = f(current)
        if (next == current) return Mutation(true, value)
        if (writesBlocked) return Mutation(false, value)
        return try {
            store.save(next)
            _state.value = next
            Mutation(true, value)
        } catch (e: CommitUncertainException) {
            val confirmed = (1..DIR_SYNC_RETRY).any {
                Thread.sleep(DIR_SYNC_BACKOFF_MS)
                runCatching { store.retryDirSync() }.isSuccess
            }
            if (confirmed) {
                _state.value = next
                Mutation(true, value)
            } else {
                block(BlockReason.COMMIT_UNCERTAIN)
                Mutation(false, value)
            }
        } catch (e: Exception) {
            Mutation(false, value)
        }
    }

    @Suppress("UNCHECKED_CAST")
    suspend fun <R> mutate(f: (AppState) -> Pair<AppState, R>): Mutation<R> {
        val result = CompletableDeferred<Mutation<Any?>>()
        queue.trySend(Request(f as (AppState) -> Pair<AppState, Any?>, result)) // UNLIMITED: 실패하지 않음
        return result.await() as Mutation<R>
    }

    suspend fun update(f: (AppState) -> AppState): Boolean = mutate { f(it) to Unit }.saved

    companion object {
        private const val DIR_SYNC_RETRY = 3
        private const val DIR_SYNC_BACKOFF_MS = 100L
        @Volatile private var instance: StateRepository? = null

        fun get(context: Context): StateRepository = instance ?: synchronized(this) {
            instance ?: StateRepository(
                FileStateStore(File(context.applicationContext.filesDir, "drivevillage-state.json"), AndroidDirSync::sync),
            ).also { instance = it }
        }
    }
}
