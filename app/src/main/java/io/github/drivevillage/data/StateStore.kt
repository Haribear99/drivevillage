package io.github.drivevillage.data

import io.github.drivevillage.core.village.AppState
import io.github.drivevillage.core.village.Decoration
import io.github.drivevillage.core.village.ItemSource
import io.github.drivevillage.core.village.Maintenance
import io.github.drivevillage.core.village.SceneLedger
import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** 교체(rename)는 끝났지만 디렉터리 동기화를 확인하지 못함. 디스크에는 새 상태가 있을 수 있다. */
class CommitUncertainException(cause: Throwable) : java.io.IOException("commit-uncertain", cause)

/** 로드 결과. 빈 상태로 시작해도 되는 경우와 원본을 지켜야 하는 경우를 구분한다. */
sealed interface LoadResult {
    /** 파일이 없음이 확인됨: 처음부터 시작해도 된다. */
    data object Missing : LoadResult
    data class Loaded(val state: AppState) : LoadResult
    /** 파싱·검증 실패. [quarantined]면 원본 사본을 보관했으므로 새로 시작해도 원본은 남는다. */
    data class Corrupt(val quarantined: Boolean) : LoadResult
    /** 읽기 자체가 실패(I/O). 원본이 정상일 수 있으므로 덮어쓰면 안 된다. */
    data object ReadFailed : LoadResult
}

interface StateStore {
    /** 절대 예외를 던지지 않는다. */
    fun loadResult(): LoadResult = when (val r = runCatching { load() }.getOrNull()) {
        null -> LoadResult.ReadFailed
        else -> LoadResult.Loaded(r)
    }
    /** 편의용: 읽을 수 없거나 검증에 실패하면 빈 상태(기준값 없음). 원본 보호가 필요하면 [loadResult]. */
    fun load(): AppState
    /** 전체 상태를 한 번에 저장한다. 실패하면 예외(호출자는 보상을 확정하지 않는다). */
    fun save(state: AppState)
    /** [CommitUncertainException] 뒤 디렉터리 동기화만 다시 시도. 성공하면 직전 저장이 확정된다. */
    fun retryDirSync() = Unit
}

/**
 * 임시 파일에 쓰고 디스크에 동기화(fsync)한 뒤 원자적 이름 바꾸기로 교체한다.
 * 정산 기록·보상·새 기준값이 같은 파일·같은 교체에 들어가므로 일부만 저장되는 일이 없다.
 * 원자적 교체가 지원되지 않으면 저장하지 않고 실패한다(비원자 교체로 대신하지 않음).
 * 교체 뒤 [syncDir]로 부모 디렉터리까지 동기화해야 저장 성공을 반환한다(Android: android.system.Os.fsync).
 */
class FileStateStore(
    private val file: File,
    private val syncDir: (File) -> Unit = {},
) : StateStore {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    override fun load(): AppState = when (val r = loadResult()) {
        is LoadResult.Loaded -> r.state
        else -> AppState()
    }

    override fun loadResult(): LoadResult {
        // 존재 확인(exists)은 I/O 오류도 false로 돌려줄 수 있다. '파일 없음'은 읽기에서 난
        // NoSuchFileException(ENOENT)일 때만 인정하고, 그 밖의 오류는 원본 보호를 위해 ReadFailed.
        val text = try {
            String(Files.readAllBytes(file.toPath()), Charsets.UTF_8)
        } catch (e: java.nio.file.NoSuchFileException) {
            return LoadResult.Missing
        } catch (e: Exception) {
            return LoadResult.ReadFailed // 원본을 건드리지 않는다
        }
        val state = try {
            json.decodeFromString(AppState.serializer(), text)
        } catch (e: Exception) {
            null
        }
        if (state == null || !StateValidator.isValid(state)) {
            // 손상·불일치 원장은 부분 보정하지 않는다. 보관에 성공해야 새로 시작할 수 있다.
            return LoadResult.Corrupt(quarantined = quarantine())
        }
        return LoadResult.Loaded(state)
    }

    override fun save(state: AppState) {
        require(StateValidator.isValid(state)) { "invalid-state" }
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, file.name + ".tmp")
        FileOutputStream(tmp).use { out ->
            out.write(json.encodeToString(AppState.serializer(), state).toByteArray(Charsets.UTF_8))
            out.flush()
            out.fd.sync()
        }
        Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        // 여기부터는 파일이 이미 새 상태로 바뀌었다. 디렉터리 동기화 실패는 '미저장'이 아니라 '확정 불확실'.
        try {
            file.parentFile?.let(syncDir)
        } catch (e: Exception) {
            throw CommitUncertainException(e)
        }
    }

    override fun retryDirSync() { file.parentFile?.let(syncDir) }

    /** 손상 파일 보관. 보관 자체가 실패해도 앱 시작을 막지 않는다(성공 여부를 돌려준다). */
    private fun quarantine(): Boolean = runCatching {
        // 사본 내용과 디렉터리 항목을 모두 디스크에 남긴 경우에만 '보관 완료'로 본다.
        val bytes = Files.readAllBytes(file.toPath())
        val copy = File(file.parentFile, file.name + ".corrupt")
        FileOutputStream(copy).use { out -> out.write(bytes); out.flush(); out.fd.sync() }
        file.parentFile?.let(syncDir)
    }.isSuccess
}

/** 로드한 원장의 불변조건. 하나라도 어긋나면 손상으로 본다. */
object StateValidator {
    private const val EPS = 1e-6
    private const val MAX_TAG = 40
    private const val LEGACY_JOURNEY_PER_DAY = 2

    fun isValid(s: AppState): Boolean {
        val l = s.ledger
        fun finiteNonNeg(d: Double) = !d.isNaN() && !d.isInfinite() && d >= 0.0
        val journeyDays = s.materials.filter { it.kind.source == ItemSource.JOURNEY }.groupBy { it.epochDay }
        val slots = s.materials.mapNotNull { it.placedSlot }
        val setup = s.maintenance.setup
        return s.version == 1 &&
            (l.baselineKm == null || finiteNonNeg(l.baselineKm)) &&
            finiteNonNeg(l.sceneKm) &&
            l.sceneCount >= 0 &&
            (l.sceneCount == 0 || l.baselineKm != null) &&
            l.sceneKm + EPS >= l.sceneCount * SceneLedger.MIN_SCENE_KM &&     // 장면 1회당 1km 이상
            l.scenes.size <= l.sceneCount &&
            l.scenes.all { it.deltaKm + EPS >= SceneLedger.MIN_SCENE_KM && (it.tag == null || (it.tag.isNotBlank() && it.tag.length <= MAX_TAG)) } &&
            l.scenes.map { it.eventId }.toSet().size == l.scenes.size &&
            // 아이템: id 중복 없음. 정산 재료는 하루 2개 이하(0.1.0은 하루 2개, 0.1.1부터 1개 — 이전 저장을 손상으로 보지 않게),
            // 정산 재료가 있는 날 수 ≤ 정산 수, 기념물 수 ≤ 셈한 이정표 수.
            s.materials.map { it.id }.toSet().size == s.materials.size &&
            journeyDays.values.all { it.size <= LEGACY_JOURNEY_PER_DAY } &&
            journeyDays.size <= l.sceneCount &&
            l.milestonesCounted >= 0 &&
            l.monumentsGiven in 0..l.milestonesCounted &&
            s.materials.count { it.kind.source == ItemSource.MILESTONE } <= l.milestonesCounted &&
            s.materials.count { it.kind.source == ItemSource.SEASON } <= l.sceneCount &&
            (journeyDays.isEmpty() || l.lastMaterialEpochDay != null) &&
            // 슬롯: 범위 안, 한 슬롯에 하나.
            slots.all { it in 0 until Decoration.SLOT_COUNT } && slots.toSet().size == slots.size &&
            // 미션: 하루 최대 1개 도장, 아는 미션만.
            s.missions.stamps.map { it.epochDay }.toSet().size == s.missions.stamps.size &&
            // 정비: 설정값 범위, 아는 퀘스트만, 완료 수 합 = 공방 단계.
            (setup == null || (finiteNonNeg(setup.lastServiceKm) && setup.oilKm in Maintenance.MIN_OIL_KM..Maintenance.MAX_OIL_KM &&
                setup.oilMonths in Maintenance.MIN_MONTHS..Maintenance.MAX_MONTHS)) &&
            s.maintenance.records.values.all {
                it.completions >= 0 && it.cycle >= 0 && (it.rewardedCycle == null || it.rewardedCycle in 0..it.cycle) &&
                    (it.baseKm == null || finiteNonNeg(it.baseKm)) && (it.snoozeUntilKm == null || finiteNonNeg(it.snoozeUntilKm)) &&
                    (it.rewardAnchorKm == null || finiteNonNeg(it.rewardAnchorKm))
            } &&
            // 앞으로 퀘스트·미션·태그 문구가 바뀌어도 이전 이력을 손상으로 보지 않도록, 모르는 id·문구는 허용한다(표시만 건너뜀).
            // 공방 증축은 보상 조건을 만족한 '했어요'에서만 오르므로 완료 수와 같지 않을 수 있다.
            s.maintenance.workshopLevel >= 0 &&
            (s.maintenance.records.isEmpty() || setup != null)
    }
}
