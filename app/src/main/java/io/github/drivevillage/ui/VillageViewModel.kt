package io.github.drivevillage.ui

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.drivevillage.Flavor
import io.github.drivevillage.core.gate.GateSession
import io.github.drivevillage.core.gate.Screen
import io.github.drivevillage.core.village.AppState
import io.github.drivevillage.core.village.CarPreset
import io.github.drivevillage.core.village.Decoration
import io.github.drivevillage.core.village.Facilities
import io.github.drivevillage.core.village.Facility
import io.github.drivevillage.core.village.LineContext
import io.github.drivevillage.core.village.LineGenerator
import io.github.drivevillage.core.village.Maintenance
import io.github.drivevillage.core.village.Material
import io.github.drivevillage.core.village.MaterialBook
import io.github.drivevillage.core.village.MissionDef
import io.github.drivevillage.core.village.OdometerReading
import io.github.drivevillage.core.village.QuestDef
import io.github.drivevillage.core.village.QuestResponse
import io.github.drivevillage.core.village.Resident
import io.github.drivevillage.core.village.RevealOnce
import io.github.drivevillage.core.village.SceneLedger
import io.github.drivevillage.core.village.SceneRecord
import io.github.drivevillage.core.village.SceneResult
import io.github.drivevillage.core.village.TemplateLineGenerator
import io.github.drivevillage.core.village.TripSummary
import io.github.drivevillage.core.village.VoiceOnce
import io.github.drivevillage.core.village.josa
import io.github.drivevillage.core.voice.Sfx
import io.github.drivevillage.core.voice.VoiceGate
import io.github.drivevillage.data.StateRepository
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.time.ZoneId
import kotlin.math.floor

/** 이번에 연 화면의 정산 결과(화면 표시용). */
sealed interface OpenResult {
    data object Loading : OpenResult
    data object FirstMeet : OpenResult
    data object Boarding : OpenResult
    data object Reversed : OpenResult
    data class Held(val reason: String) : OpenResult
    data class Scene(
        val record: SceneRecord,
        val newMaterials: List<Material>,
        val alreadyGotToday: Boolean,
        val milestonesCrossed: Int,
        val monumentsGranted: Int,
        val monumentsPending: Int,
        val missionDone: MissionDef?,
    ) : OpenResult
}

/** 주민 말풍선 한 줄. */
data class Bubble(val resident: Resident, val text: String)

/** '했어요'로 쉬던 시설이 다시 켜진 순간(점등 연출 1회). [atMs]는 SystemClock.uptimeMillis 기준. */
data class FacilityWake(val facility: Facility, val atMs: Long)

class VillageViewModel(app: Application) : AndroidViewModel(app) {

    val signals = Flavor.createSignals(app)
    /** 프로세스 단일 저장소: 화면이 바뀌어도 같은 상태·같은 직렬화 장치를 쓴다. */
    private val repo = StateRepository.get(app)
    private val zone: ZoneId = ZoneId.systemDefault()
    private val gate = GateSession()
    /** 대사 생성기. MVP는 템플릿 렌더러. LLM 연동은 2차 PT 범위(같은 인터페이스, 템플릿 폴백 유지). */
    private val lines: LineGenerator = TemplateLineGenerator()

    private val _screen = MutableStateFlow(Screen.PENDING)
    val screen: StateFlow<Screen> = _screen
    val state: StateFlow<AppState> = repo.state
    val stateLoaded: StateFlow<Boolean> = repo.isLoaded
    /** 저장 차단 상태(디스크 문제). 화면에 늘 안내한다. */
    val writesBlocked: StateFlow<Boolean> = repo.writesBlockedFlow

    init { repo.recoverIfBlocked() }

    private val _open = MutableStateFlow<OpenResult>(OpenResult.Loading)
    val open: StateFlow<OpenResult> = _open
    /** 이번 화면에서 읽은 누적 주행거리. 읽지 못했으면 null. */
    private val _odoKm = MutableStateFlow<Double?>(null)
    val odoKm: StateFlow<Double?> = _odoKm
    private val _bubble = MutableStateFlow<Bubble?>(null)
    val bubble: StateFlow<Bubble?> = _bubble
    private val _wake = MutableStateFlow<FacilityWake?>(null)
    val wake: StateFlow<FacilityWake?> = _wake
    private val _notice = MutableStateFlow<String?>(null)
    val notice: StateFlow<String?> = _notice
    /** 화면이 쓰는 '지금'(demo의 날짜 이동 반영). */
    private val _now = MutableStateFlow(signals.nowMillis())
    val now: StateFlow<Long> = _now

    private var settled = false
    private var chatSeed = 0

    // ── 목소리(정차 중, 탑승·하차 한 문장씩) ──
    @Volatile private var voicePrefs: SharedPreferences? = null
    private val voicePrefLoaded = CompletableDeferred<Unit>()
    /** 상단 바 '소리 켬/끔' 하나가 주민 음성과 효과음을 함께 켜고 끈다(저장 키는 예전 이름 그대로). */
    private val _voiceOn = MutableStateFlow(false) // 설정을 읽기 전에는 말하지 않는다
    val voiceOn: StateFlow<Boolean> = _voiceOn
    /** 음성·효과음이 함께 쓰는 허용 조건(같은 fail-closed 규칙). */
    private val soundAllowed: () -> Boolean = {
        foreground && !VoiceGate.mustStop(_screen.value, signals.safety.isTripped, _voiceOn.value)
    }
    /** 앱 공용 오디오 포커스. 잃으면 모든 소리를 즉시 멈춘다. */
    private val audioFocus = AppAudioFocus(app) { change -> stopAllSound("focus-lost-$change") }
    private val voice = VillageVoice(app, audioFocus.shared, soundAllowed)
    private val sfx = VillageSfx(app, audioFocus, soundAllowed)

    init {
        viewModelScope.launch(Dispatchers.IO) {
            val prefs = app.getSharedPreferences(VOICE_PREFS, Context.MODE_PRIVATE)
            voicePrefs = prefs
            _voiceOn.value = prefs.getBoolean(VOICE_ON_KEY, true)
            voicePrefLoaded.complete(Unit)
        }
        // 대기 시간 제한을 신호 시작보다 먼저 건다(초기화가 늦어져도 3초 안에 판정).
        viewModelScope.launch {
            delay(PENDING_TIMEOUT_MS)
            onGate(gate.onPendingTimeout())
        }
        signals.start()
        // 래치가 걸리는 순간 그 스레드에서 바로 소리를 끊는다(메인 수집자를 기다리지 않음).
        signals.safety.onTrip = {
            val t0 = SystemClock.elapsedRealtimeNanos()
            voice.stop("latch")
            sfx.stopAll("latch")
            Log.i(TAG, "latch audio stop calls took ${(SystemClock.elapsedRealtimeNanos() - t0) / 1000}us")
        }
        viewModelScope.launch { signals.gear.collect { onGate(gate.onGear(it)) } }
        viewModelScope.launch { signals.ux.collect { onGate(gate.onUx(it)) } }
        viewModelScope.launch { signals.safety.tripped.collect { if (it) onGate(gate.close()) } }
        viewModelScope.launch { signals.dayShift.collect { _now.value = signals.nowMillis() } }
        // 날짜가 바뀌는 순간을 화면에 반영(1분 간격).
        viewModelScope.launch { while (true) { delay(60_000); _now.value = signals.nowMillis() } }
    }

    private fun onGate(next: Screen) {
        if (_screen.value == next) return
        _screen.value = next
        if (next != Screen.VILLAGE) stopAllSound("screen-$next")
        Log.i(TAG, "screen=$next gear=${gate.gear} ux=${gate.ux} latch=${signals.safety.reason ?: "-"}")
        when (next) {
            Screen.VILLAGE -> {
                signals.safety.arm() // 이 순간 이후의 비P·제한·확인 불가는 되돌릴 수 없이 종료
                if (!settled) { settled = true; settleOnOpen() }
            }
            Screen.CLOSE -> signals.safety.trip("closed")
            else -> Unit
        }
    }

    fun today(): Long = SceneLedger.epochDay(_now.value, zone)

    private fun settleOnOpen() {
        viewModelScope.launch {
            val reading = signals.readOdometer()
            _odoKm.value = (reading as? OdometerReading.Km)?.value
            val now = signals.nowMillis()
            _now.value = now
            val r = repo.mutate { current ->
                // 판독 중(최대 수 초) 화면이 닫혔으면 커밋하지 않는다: 다음에 열 때 같은 증가분으로 장면을 보여 준다.
                if (signals.safety.isTripped || _screen.value != Screen.VILLAGE) {
                    return@mutate current to (SceneResult.Held("closed") to false)
                }
                val gearNow = signals.gear.value
                val (settledState, result) = SceneLedger.settle(current, reading, gearNow, now, zone)
                // 탑승·하차 한 문장을 각각 한 번만: 같은 저장 안에서 기록한다(재시작해도 두 번 말하지 않음).
                val withVoice = when (result) {
                    is SceneResult.Scene -> VoiceOnce.alight(settledState, result.record.eventId)
                    SceneResult.FirstMeet, SceneResult.Boarding, SceneResult.Reversed -> VoiceOnce.boarding(settledState)
                    is SceneResult.Held -> null
                }
                (withVoice ?: settledState) to (result to (withVoice != null))
            }
            if (!r.saved) Log.w(TAG, "상태 저장 실패")
            val (result, shouldSpeak) = r.value
            val shown: OpenResult = if (!r.saved) OpenResult.Held("save-failed") else when (result) {
                SceneResult.FirstMeet -> OpenResult.FirstMeet
                SceneResult.Boarding -> OpenResult.Boarding
                SceneResult.Reversed -> OpenResult.Reversed
                is SceneResult.Held -> OpenResult.Held(result.reason)
                is SceneResult.Scene -> OpenResult.Scene(result.record, result.newMaterials, result.alreadyGotToday, result.milestonesCrossed, result.monumentsGranted, result.monumentsPending, result.missionDone)
            }
            _open.value = shown
            Log.i(TAG, "settle=${result::class.simpleName} odo=${_odoKm.value} saved=${r.saved}")
            val st = state.value
            val bubble = when (shown) {
                is OpenResult.Scene -> {
                    val who = Resident.entries[Math.floorMod(st.ledger.sceneCount, Resident.entries.size)]
                    Bubble(who, lines.line(who, LineContext.SCENE, summary(shown.record.deltaKm, shown.newMaterials.size, elapsedDays(shown.record)), st.ledger.sceneCount))
                }
                OpenResult.FirstMeet, OpenResult.Boarding, OpenResult.Reversed -> {
                    val seed = (st.ledger.baselineKm ?: 0.0).toInt()
                    val who = Resident.entries[Math.floorMod(seed, Resident.entries.size)]
                    Bubble(who, lines.line(who, LineContext.BOARDING, summary(null, 0), seed))
                }
                else -> null
            }
            _bubble.value = bubble
            if (r.saved && shouldSpeak && bubble != null) speak(bubble.text)
        }
    }

    /** 대사 입력: 사실·수치는 모두 여기(코드)서 채운다. */
    /** 직전 정산 이후 경과일(같은 날이면 0). 직전 정산이 없으면 null. */
    fun elapsedDays(rec: SceneRecord): Int? =
        rec.sinceMillis?.let { (rec.epochDay - SceneLedger.epochDay(it, zone)).coerceAtLeast(0).toInt() }

    fun summary(deltaKm: Double?, materialsReceived: Int, elapsedDays: Int? = null): TripSummary {
        val st = state.value
        val today = today()
        return TripSummary(
            deltaKm = deltaKm?.let { floor(it).toInt() },
            timeSlot = SceneLedger.slotOf(_now.value, zone),
            villageDay = SceneLedger.villageDay(st, today),
            openQuests = Maintenance.openQuests(st, currentOdo(), today).map { it.def.title },
            placedToday = Decoration.placedToday(st, today)?.kind?.label,
            materialsReceived = materialsReceived,
            elapsedDays = elapsedDays?.takeIf { it >= 1 },
        )
    }

    /** 퀘스트 판정에 쓰는 주행거리: 이번에 읽은 값, 없으면 마지막 정산 기준값. */
    fun currentOdo(): Double? = _odoKm.value ?: state.value.ledger.baselineKm

    private fun inVillage(): Boolean = _screen.value == Screen.VILLAGE && !signals.safety.isTripped

    private suspend fun update(f: (AppState) -> AppState?): Boolean =
        repo.mutate { cur -> (f(cur) ?: cur).let { it to (it !== cur) } }.let { it.saved && it.value }

    // ── 사용자 조작(모두 P단 마을 화면에서만) ─────────────────────────

    fun chooseTag(eventId: String, tag: String) {
        if (!inVillage()) return
        viewModelScope.launch {
            if (update { SceneLedger.setTag(it, eventId, tag) }) _notice.value = "이번 여정에 「$tag」${josa(tag, "을", "를")} 남겼어요." else _notice.value = SAVE_FAIL
        }
    }

    fun place(materialId: String, slot: Int) {
        if (!inVillage()) return
        val today = today()
        viewModelScope.launch {
            if (update { Decoration.place(it, materialId, slot, today) }) {
                val label = state.value.materials.firstOrNull { it.id == materialId }?.kind?.label ?: "재료"
                _notice.value = "$label${josa(label, "을", "를")} ${slot + 1}번 자리에 놓았어요."
                playSfx(Sfx.PLACE)
                val st = state.value
                _bubble.value = Bubble(Resident.GARDENER, lines.line(Resident.GARDENER, LineContext.CHAT, summary(lastSceneTodayKm(st), 0), chatSeed++))
            } else _notice.value = "그 자리에는 놓을 수 없어요."
        }
    }

    fun saveSetup(preset: CarPreset, lastKm: Double, lastDay: Long, oilKm: Int, oilMonths: Int, unknown: Boolean = false) {
        if (!inVillage()) return
        val setup = Maintenance.newSetup(preset, lastKm, lastDay, oilKm, oilMonths, today(), lastServiceUnknown = unknown)
        if (setup == null) { _notice.value = "입력값을 확인해 주세요(미래 날짜·범위 밖 값은 저장하지 않아요)."; return }
        viewModelScope.launch {
            _notice.value = if (update { Maintenance.saveSetup(it, setup) }) "정비 공방 설정을 저장했어요." else SAVE_FAIL
        }
    }

    /**
     * 정비 퀘스트 응답: 했어요 / 예약했어요 / 다음에. 응답은 재료를 주지 않는다.
     * '했어요'로 쉬던 시설이 평상으로 돌아오면 점등 연출([wake])과 그 시설 주민의 한 줄 인사를 띄운다.
     * [serviceKm]·[serviceDay]는 '했어요'에서 정비 당시 값을 직접 넣었을 때만(조건을 만족하면 공방 증축).
     */
    fun respondQuest(def: QuestDef, response: QuestResponse, serviceKm: Double? = null, serviceDay: Long? = null) {
        if (!inVillage()) return
        val today = today()
        val odo = currentOdo()
        // 시설 쉬는 표시는 화면이 이번에 읽은 주행거리로 그린다(VillageScreen). 점등 판정도 같은 값으로 해야 그림과 맞는다.
        val drawnOdo = _odoKm.value
        viewModelScope.launch {
            val r = repo.mutate { cur ->
                val out = Maintenance.respond(cur, def, response, odo, today, serviceKm, serviceDay)
                if (out == null) cur to null else out.first to (out.second to Facilities.woke(cur, out.first, drawnOdo, today))
            }
            val res = r.value
            if (!r.saved || res == null) {
                _notice.value = if (!r.saved) SAVE_FAIL else "기록하지 못했어요. 정비 시점은 현재 주행거리 이하·직전 기준 이후, 날짜는 오늘 이전이어야 해요."
                return@launch
            }
            val (grew, woke) = res
            _notice.value = when (response) {
                QuestResponse.DONE -> "「${def.title}」 했어요로 기록했어요." + if (grew) " 공방이 한 칸 커졌어요." else ""
                QuestResponse.RESERVED -> "「${def.title}」 예약했어요로 기록했어요. ${Maintenance.RESERVE_RECHECK_DAYS}일 뒤에 다시 여쭐게요."
                QuestResponse.LATER -> "「${def.title}」 다음에로 기록했어요. ${Maintenance.LATER_KM.toInt()}km 또는 ${Maintenance.LATER_DAYS}일 중 먼저 오는 때에 다시 알려 드려요."
            }
            // 점등 연출과 인사는 '했어요'에만. 예약했어요·다음에로 미뤄도 시설은 쉬는 표시를 거두지만 조용히 바뀐다.
            val lit = if (response == QuestResponse.DONE) woke.firstOrNull() else null
            if (lit != null) {
                // 말풍선이 시설(특히 위쪽의 풍차·물레방아)을 가리지 않게, 연출 동안 비워 두었다가 끝나면 인사를 띄운다.
                val reduce = runCatching {
                    Settings.Global.getFloat(getApplication<Application>().contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
                }.getOrDefault(false)
                val greet = Bubble(lit.greeter, lit.wakeLine)
                _wake.value = FacilityWake(lit, SystemClock.uptimeMillis())
                playSfx(Sfx.LIGHT)
                if (reduce) _bubble.value = greet
                else {
                    _bubble.value = null
                    delay(WAKE_GREETING_DELAY_MS)
                    // 그 사이 다른 말풍선이 떴거나 화면이 닫혔으면 덮어쓰지 않는다.
                    if (_bubble.value == null && inVillage()) _bubble.value = greet
                }
            } else {
                _bubble.value = Bubble(Resident.CRAFTSMAN, lines.line(Resident.CRAFTSMAN, LineContext.WORKSHOP, summary(null, 0), state.value.maintenance.workshopLevel + chatSeed++))
            }
        }
    }

    fun tapResident(r: Resident) {
        if (!inVillage()) return
        val st = state.value
        _bubble.value = Bubble(r, lines.line(r, LineContext.CHAT, summary(lastSceneTodayKm(st), 0), chatSeed++))
    }

    fun showMaterialMemory(m: Material) {
        if (!inVillage()) return
        _notice.value = "${m.kind.label}: ${m.tag}"
    }

    fun dismissNotice() { _notice.value = null }

    /** 말풍선 닫기(탭·자동 숨김). 그 사이 다른 말풍선으로 바뀌었으면 건드리지 않는다. */
    fun dismissBubble(b: Bubble) { _bubble.compareAndSet(b, null) }

    // ── 화면 연출 1회 기록(UI 상태, 저장하지 않음). 같은 정산·같은 아이템으로는 연출을 되풀이하지 않는다. ──
    private val revealedScenes = HashSet<String>()
    private val shimmeredMaterials = HashSet<String>()

    fun sceneRevealPending(eventId: String, reduceMotion: Boolean): Boolean = RevealOnce.shouldPlay(eventId, revealedScenes, reduceMotion)
    fun markSceneRevealed(eventId: String) { revealedScenes += eventId }
    fun shimmerPending(newMaterials: List<Material>, reduceMotion: Boolean) = MaterialBook.shimmerKinds(newMaterials, shimmeredMaterials, reduceMotion)
    fun markShimmered(newMaterials: List<Material>) { newMaterials.forEach { shimmeredMaterials += it.id } }

    private fun lastSceneTodayKm(st: AppState): Double? = st.ledger.scenes.lastOrNull { it.epochDay == today() }?.deltaKm

    // ── 소리 ───────────────────────────────────────────────

    private var speakJob: Job? = null

    private fun speak(text: String) {
        speakJob?.cancel()
        val requestedAt = voice.generation()
        speakJob = viewModelScope.launch {
            voicePrefLoaded.await()
            withTimeoutOrNull(VOICE_INIT_WAIT_MS) { voice.status.first { it != VillageVoice.Status.INITIALIZING } }
            if (foreground && VoiceGate.mayStart(_screen.value, signals.safety.isTripped, _voiceOn.value, text)) voice.speak(text, requestedAt)
        }
    }

    /** 음성(대기 중인 발화 포함)과 효과음을 모두 멈춘다. 어느 스레드에서 불러도 된다. */
    private fun stopAllSound(reason: String) {
        speakJob?.cancel()
        voice.stop(reason)
        sfx.stopAll(reason)
    }

    /** 효과음(메인 스레드). 마을 화면·전면·소리 켬일 때만 난다([VillageSfx]가 다시 확인한다). */
    fun playSfx(s: Sfx) {
        if (!inVillage() || !foreground) return
        sfx.play(s)
    }

    @Volatile private var foreground = false
    fun setForeground(resumed: Boolean) {
        foreground = resumed
        if (!resumed) stopAllSound("background")
    }

    fun toggleVoice() {
        if (!voicePrefLoaded.isCompleted || !inVillage()) return
        val on = !_voiceOn.value
        _voiceOn.value = on
        if (!on) stopAllSound("muted")
        voicePrefs?.edit()?.putBoolean(VOICE_ON_KEY, on)?.apply()
    }

    /** [마을 닫기] 또는 홈 버튼·앱 전환: 연출 없이 종료. */
    fun closeSilently() { onGate(gate.close()) }

    override fun onCleared() {
        voice.shutdown()
        sfx.shutdown()
        signals.stop()
    }

    companion object {
        private const val TAG = "DriveVillage"
        const val PENDING_TIMEOUT_MS = 3_000L
        /** 점등 연출(VillageCanvas, 약 1.3초)이 끝난 뒤 인사를 띄운다. */
        private const val WAKE_GREETING_DELAY_MS = 1_300L
        private const val VOICE_INIT_WAIT_MS = 4_000L
        private const val VOICE_PREFS = "village_voice"
        private const val VOICE_ON_KEY = "voice_on"
        private const val SAVE_FAIL = "지금은 저장하지 못했어요. 앱을 다시 열어 주세요."
    }
}
