package io.github.drivevillage.ui

import android.os.SystemClock
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import io.github.drivevillage.core.village.Facility
import io.github.drivevillage.core.village.Material
import io.github.drivevillage.core.village.Resident
import io.github.drivevillage.core.village.TimeSlot
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * 마을 전경. 입체 미니어처 디오라마 스타일의 AI 생성 에셋(레이어 PNG, README 참조)을 합성하고,
 * 움직임(배경 패럴랙스, 화로·가로등 깜박임, 주민 숨쉬기, 풍차 날개·물레방아 바퀴 회전, 굴뚝 연기, 놓기 반짝임, 탭 링)은
 * Compose가 위치·회전·크기·투명도로만 만든다.
 * 공방은 정비 신고 누적(공방 단계)에 따라 커지고, 정비 시설은 짝이 된 정비가 도래하면 '쉬는 중'(채도 낮춤·움직임 정지·작은 z)이 된다.
 * 시설을 누르면 [onFacility]로 알린다(안내 시트는 화면 쪽). '했어요'로 다시 켜지면 [wake]로 점등 연출을 1회 그린다.
 * 슬롯 8곳에 재료를 놓을 수 있다.
 */
object VillageLayout {
    /** 슬롯 위치(가로·세로 비율). */
    val slots = listOf(
        Offset(0.12f, 0.86f), Offset(0.28f, 0.80f), Offset(0.44f, 0.88f),
        Offset(0.60f, 0.80f), Offset(0.76f, 0.88f), Offset(0.90f, 0.78f),
        Offset(0.08f, 0.70f), Offset(0.66f, 0.93f),
    )
    val residents = mapOf(
        Resident.CRAFTSMAN to Offset(0.33f, 0.64f),
        Resident.TRAVELER to Offset(0.50f, 0.70f),
        Resident.GARDENER to Offset(0.88f, 0.64f),
    )
}

/**
 * 쉬는 시설: 회색이 아니라 같은 색 계열에서 채도 -35%, 명도 -10%, 알파 0.85(노트 04 가설값).
 * 에뮬레이터에서 채도만 낮추면 원래 채도가 낮은 풍차(크림·연두)는 평상과 거의 같아 보여 명도도 조금 낮췄다.
 */
private const val REST_SATURATION = 0.65f
private const val REST_BRIGHTNESS = 0.9f
private const val REST_ALPHA = 0.85f
private val REST = ColorFilter.colorMatrix(
    ColorMatrix().apply {
        setToSaturation(REST_SATURATION)
        timesAssign(ColorMatrix().apply { setToScale(REST_BRIGHTNESS, REST_BRIGHTNESS, REST_BRIGHTNESS, 1f) })
    },
)
/** 앰비언트 틱 간격(약 15fps). 프레임 정책과 근거는 [VillageClock] 참조. */
private const val TICK_MS = 66L
/** 틱 하나가 늦게 와도(멈췄다 다시 시작 등) 움직임이 튀지 않게 한 틱에 넘길 수 있는 최대 시간. */
private const val MAX_STEP_MS = 200L
private const val CYCLE_MS = 24_000L
/** 날개 한 바퀴 12초, 바퀴 한 바퀴 20초(15fps에서 날개 끝 이동은 틱당 약 2도). */
private const val BLADE_PERIOD_MS = 12_000f
private const val WHEEL_PERIOD_MS = 20_000f
private val LAMPS = listOf(Triple(0.40f, 0.68f, 0.28f), Triple(0.70f, 0.66f, 0.26f))
/** 점등 연출 길이(1회). */
private const val WAKE_MS = 1_300
/** 재료를 놓을 때 반짝임(1회), 탭 링(1회) 길이. */
private const val SPARKLE_MS = 800
private const val RING_MS = 300

/** 굴뚝 연기 퍼프: 고정 4개, 수명과 시드는 미리 정해 둔다(그릴 때 난수·할당 없음). */
private const val SMOKE_LIFE_MS = 4_800L
private val SMOKE_PHASE = floatArrayOf(0f, 0.26f, 0.51f, 0.77f)
private val SMOKE_DRIFT = floatArrayOf(0.9f, 1.25f, 0.7f, 1.1f)
private val SMOKE_SIZE = floatArrayOf(1f, 0.85f, 1.12f, 0.95f)
private val SMOKE_COLOR = Color(0xFFF1E6CC)

/**
 * 마을 움직임 시계. 값은 Canvas의 그리기 단계에서만 읽어, 틱마다 컴포지션·레이아웃 없이 다시 그리기만 일어난다.
 *
 * 프레임 정책(aaos33 에뮬레이터 실측으로 고름, 실기기 미확인): 약 15fps `delay` 틱 + 생명주기 STARTED 게이팅.
 * - 비교한 안: vsync마다 `withFrameNanos`로 깨어나 66ms마다 기록 / `delay` 뒤 `withFrameNanos` 1회 / `delay`만.
 *   그리는 횟수와 지연 지표는 셋이 비슷했고, 메인 스레드 CPU는 `delay`만 쓰는 안이 가장 낮았다(30초당 약 280틱 대
 *   약 400틱 대 약 590틱). 30fps(33ms)는 CPU가 두 배 넘게 들고 legacy jank가 약 47%로 올라 쓰지 않는다.
 * - `delay`는 창이 멈춰도 스스로 서지 않으므로, 루프는 생명주기 STARTED 동안만 돈다(ON_STOP이면 취소된다).
 */
private class VillageClock {
    /** 앰비언트 시간(ms). 멈춰 있는 동안은 흐르지 않는다. */
    val ms = mutableLongStateOf(0L)
    /** 날개·바퀴 각도(도). 쉬는 시설은 그 각도에 멈추고, 다시 켜지면 그 자리에서 이어 돈다. */
    val bladeDeg = mutableFloatStateOf(0f)
    val wheelDeg = mutableFloatStateOf(0f)

    /** [bladeSpin]·[wheelSpin]은 0(정지)~1(평상 속도). */
    fun advance(dt: Long, bladeSpin: Float, wheelSpin: Float) {
        ms.longValue += dt
        if (bladeSpin > 0f) bladeDeg.floatValue = (bladeDeg.floatValue + dt * 360f / BLADE_PERIOD_MS * bladeSpin) % 360f
        if (wheelSpin > 0f) wheelDeg.floatValue = (wheelDeg.floatValue - dt * 360f / WHEEL_PERIOD_MS * wheelSpin) % 360f
    }
}

/** 발 위치(footX, footY)를 기준으로 높이 heightPx에 맞춰 스프라이트를 그린다. */
private fun DrawScope.sprite(
    img: ImageBitmap, footX: Float, footY: Float, heightPx: Float,
    dy: Float = 0f, scale: Float = 1f,
) {
    val hh = heightPx * scale
    val ww = hh * img.width / img.height
    drawImage(
        img,
        dstOffset = IntOffset((footX - ww / 2).toInt(), (footY - hh + dy).toInt()),
        dstSize = IntSize(ww.toInt(), hh.toInt()),
        filterQuality = FilterQuality.Medium,
    )
}

/**
 * 회전체(풍차 날개·물레방아 바퀴). [hub]는 화면 좌표의 축, [size]는 그릴 높이,
 * ([pivotFx], [pivotFy])는 회전체 이미지 안에서 축이 있는 곳(가로·세로 비율).
 */
private class Rotor(val img: ImageBitmap, val hub: Offset, val size: Float, val pivotFx: Float, val pivotFy: Float)

/**
 * 시설 스프라이트가 차지하는 자리. 그리기와 탭 판정이 같은 값을 쓴다.
 * [body]는 몸체 이미지를 그리는 곳, [rect]는 탭 판정·쉬는 표시·점등 연출의 자리(몸체 + 회전체가 도는 원).
 */
private class FacilitySpot(val facility: Facility, val img: ImageBitmap, val body: Rect, val rotor: Rotor? = null) {
    val rect: Rect = if (rotor == null) body else {
        val r = rotor.size / 2
        Rect(min(body.left, rotor.hub.x - r), min(body.top, rotor.hub.y - r), max(body.right, rotor.hub.x + r), max(body.bottom, rotor.hub.y + r))
    }
}

private fun bodyRect(img: ImageBitmap, footX: Float, footY: Float, heightPx: Float): Rect {
    val ww = heightPx * img.width / img.height
    return Rect(footX - ww / 2, footY - heightPx, footX + ww / 2, footY)
}

private fun spotOf(f: Facility, img: ImageBitmap, footX: Float, footY: Float, heightPx: Float): FacilitySpot =
    FacilitySpot(f, img, bodyRect(img, footX, footY, heightPx))

/**
 * 몸체 위에 회전체를 겹친 시설. ([axleFx], [axleFy])는 몸체 이미지 안의 축 자리(비율).
 * 풍차: 탑 지붕 꼭대기의 둥근 못, 물레방아: 몸체 오른쪽 벽에서 튀어나온 축의 끝.
 */
private fun rotorSpotOf(
    f: Facility, body: ImageBitmap, footX: Float, footY: Float, heightPx: Float, axleFx: Float, axleFy: Float,
    rotor: ImageBitmap, rotorSize: Float, pivotFx: Float, pivotFy: Float,
): FacilitySpot {
    val b = bodyRect(body, footX, footY, heightPx)
    val hub = Offset(b.left + b.width * axleFx, b.top + b.height * axleFy)
    return FacilitySpot(f, body, b, Rotor(rotor, hub, rotorSize, pivotFx, pivotFy))
}

/** 시설 자리, 뒤(위)에서 앞(아래) 순서. 공방은 공방 단계에 따라 커진다. */
private fun facilitySpots(a: VillageArt, w: Float, h: Float, u: Float, workshopLevel: Int): List<FacilitySpot> {
    val forgeScale = 0.76f + 0.06f * workshopLevel.coerceIn(0, 4) // 정비 신고가 쌓일수록 공방이 커진다.
    return listOf(
        // 축 좌표는 원본 이미지에서 잰 값: 탑 못 중심 (179, 26)/324x512, 날개 허브 (253.7, 258)/506x512,
        // 몸체 축 끝 (386, 318)/484x512, 바퀴 축 (251, 256)/502x512.
        rotorSpotOf(
            Facility.WINDMILL, a.windmillTower, w * 0.52f, h * 0.54f, u * 0.25f, 0.5525f, 0.0508f,
            a.windmillBlades, u * 0.22f, 0.5014f, 0.5039f,
        ),
        rotorSpotOf(
            Facility.MILL, a.millBody, w * 0.80f, h * 0.60f, u * 0.34f, 0.7975f, 0.6211f,
            a.millWheel, u * 0.17f, 0.5f, 0.5f,
        ),
        spotOf(Facility.FORGE, a.forge, w * 0.22f, h * 0.62f, u * 0.42f * forgeScale),
        spotOf(Facility.LAMP, a.lamp, w * LAMPS[0].first, h * LAMPS[0].second, u * LAMPS[0].third),
        spotOf(Facility.LAMP, a.lamp, w * LAMPS[1].first, h * LAMPS[1].second, u * LAMPS[1].third),
        spotOf(Facility.GARDEN, a.garden, w * 0.83f, h * 0.76f, u * 0.13f),
    )
}

/** 말풍선이 피해야 할 자리: 시설(몸체 + 회전 원) + 공방 굴뚝 연기가 오르는 칸. 그리기·탭 판정과 같은 [facilitySpots] 값을 쓴다. */
internal fun villageObstacles(a: VillageArt, w: Float, h: Float, workshopLevel: Int): List<Rect> {
    val u = min(w, h)
    val spots = facilitySpots(a, w, h, u, workshopLevel)
    val fr = spots[2].rect // 공방
    val ex = fr.left + fr.width * 0.56f
    val ey = fr.top + fr.height * 0.06f
    return spots.map { it.rect } + Rect(ex - u * 0.03f, ey - u * 0.15f, ex + u * 0.09f, ey) // drawSmoke의 퍼프 범위
}

/** 시설을 그린다. 회전체가 있으면 몸체 위에 [rotorDeg]만큼 돌려 겹친다(쉬는 중이면 각도는 멈춘 값). */
private fun DrawScope.drawSpot(s: FacilitySpot, rest: Boolean, rotorDeg: Float = 0f) {
    drawImage(
        s.img,
        dstOffset = IntOffset(s.body.left.toInt(), s.body.top.toInt()),
        dstSize = IntSize(s.body.width.toInt(), s.body.height.toInt()),
        alpha = if (rest) REST_ALPHA else 1f,
        colorFilter = if (rest) REST else null,
        filterQuality = FilterQuality.Medium,
    )
    val r = s.rotor ?: return
    val hh = r.size
    val ww = hh * r.img.width / r.img.height
    // 위치는 소수점까지 옮긴 뒤 축을 중심으로 돌린다(정수로 자르면 도는 동안 축이 1px씩 흔들린다).
    withTransform({
        rotate(rotorDeg, r.hub)
        translate(r.hub.x - ww * r.pivotFx, r.hub.y - hh * r.pivotFy)
    }) {
        drawImage(
            r.img,
            dstSize = IntSize(ww.roundToInt(), hh.roundToInt()),
            alpha = if (rest) REST_ALPHA else 1f,
            colorFilter = if (rest) REST else null,
            filterQuality = FilterQuality.Medium,
        )
    }
}

@Composable
fun VillageCanvas(
    modifier: Modifier,
    timeSlot: TimeSlot,
    workshopLevel: Int,
    placed: List<Material>,
    highlightSlots: Boolean,
    onResident: (Resident) -> Unit,
    onSlot: (Int) -> Unit,
    needingCare: Set<Facility> = emptySet(),
    onFacility: (Facility) -> Unit = {},
    wake: FacilityWake? = null,
) {
    val ctx = LocalContext.current
    val onFacilityNow by rememberUpdatedState(onFacility)
    // 그래픽은 IO 스레드에서 디코딩한다. 준비되기 전에는 아무것도 그리지 않는다.
    val art by produceState(VillageArt.peek()) { value = VillageArt.load(ctx) }
    // 시스템 '애니메이션 끄기'(배율 0)이면 정지 화면. 앱이 떠 있는 동안 바꿔도 바로 반영된다.
    val reduce = rememberReduceMotion()
    val reduceNow by rememberUpdatedState(reduce)
    val restingNow by rememberUpdatedState(needingCare)
    val wakeNow by rememberUpdatedState(wake)
    // 점등 연출: '했어요'로 시설이 다시 켜진 순간 1회(약 1.3초). 애니메이션 끄기면 상태만 바뀐다.
    // 화면 회전 등으로 다시 그려질 때 되풀이하지 않도록, 사건 직후가 아니면 재생하지 않는다.
    val wakeAnim = remember { Animatable(1f) }
    LaunchedEffect(wake) {
        val ev = wake ?: return@LaunchedEffect
        if (reduce || SystemClock.uptimeMillis() - ev.atMs > WAKE_MS) { wakeAnim.snapTo(1f); return@LaunchedEffect }
        wakeAnim.snapTo(0f)
        wakeAnim.animateTo(1f, tween(WAKE_MS, easing = LinearEasing))
    }
    // 한 주기 24초. 앰비언트 움직임은 이 위상의 정수배 사인이라 끊김 없이 반복된다.
    // 루프는 창이 보이는 동안(STARTED)만 돌고, 화면이 사라지거나(컴포지션 이탈) 애니메이션 끄기가 켜지면 멈춘다.
    val clock = remember { VillageClock() }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(reduce, lifecycle) {
        if (reduce) return@LaunchedEffect
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            var last = System.nanoTime()
            while (true) {
                delay(TICK_MS)
                val now = System.nanoTime()
                val dt = ((now - last) / 1_000_000L).coerceIn(0L, MAX_STEP_MS)
                last = now
                val resting = restingNow
                val ev = wakeNow
                // 쉬는 시설은 회전을 멈추고, 점등 연출 동안 천천히 다시 돌기 시작한다.
                fun spin(f: Facility): Float = when {
                    f in resting -> 0f
                    ev?.facility == f && wakeAnim.value < 1f -> wakeAnim.value
                    else -> 1f
                }
                clock.advance(dt, spin(Facility.WINDMILL), spin(Facility.MILL))
            }
        }
    }
    // 재료를 마을에 놓은 순간 그 자리에 반짝임 1회. 처음 보이는 배치(이미 놓여 있던 것)에는 재생하지 않는다.
    val placedSlots = placed.mapNotNullTo(HashSet()) { it.placedSlot }
    val seenSlots = remember { mutableStateOf<Set<Int>>(placedSlots) }
    val sparkle = remember { Animatable(1f) }
    val sparkleSlot = remember { mutableIntStateOf(-1) }
    LaunchedEffect(placedSlots) {
        val added = placedSlots - seenSlots.value
        seenSlots.value = placedSlots
        val s = added.firstOrNull() ?: return@LaunchedEffect
        if (reduce) return@LaunchedEffect
        sparkleSlot.intValue = s
        sparkle.snapTo(0f)
        sparkle.animateTo(1f, tween(SPARKLE_MS, easing = LinearEasing))
    }
    // 시설·주민·빈 슬롯을 누르면 그 자리에 작은 링 1회(약 0.3초). 애니메이션 끄기면 그리지 않는다.
    val ring = remember { Animatable(1f) }
    val ringAt = remember { mutableStateOf(Offset.Zero) }
    val scope = rememberCoroutineScope()
    val ping: (Offset) -> Unit = { p ->
        if (!reduceNow) {
            ringAt.value = p
            scope.launch { ring.snapTo(0f); ring.animateTo(1f, tween(RING_MS, easing = LinearEasing)) }
        }
    }
    val a = art
    Canvas(
        modifier.pointerInput(highlightSlots, placed, a != null, workshopLevel) {
            if (a == null) return@pointerInput // 그래픽이 보이기 전에는 보이지 않는 슬롯·주민·시설을 누를 수 없다.
            detectTapGestures { p ->
                val w = size.width.toFloat(); val h = size.height.toFloat()
                val unit = min(w, h)
                val slot = VillageLayout.slots.indexOfFirst { hypot(p.x - it.x * w, p.y - it.y * h) < unit * 0.07f }
                if (slot >= 0) {
                    if (placed.none { it.placedSlot == slot }) VillageLayout.slots[slot].let { ping(Offset(it.x * w, it.y * h)) }
                    onSlot(slot); return@detectTapGestures
                }
                val r = VillageLayout.residents.entries.firstOrNull { (_, o) -> hypot(p.x - o.x * w, p.y - (o.y * h - unit * 0.085f)) < unit * 0.085f }
                if (r != null) {
                    ping(Offset(r.value.x * w, r.value.y * h - unit * 0.085f))
                    onResident(r.key); return@detectTapGestures
                }
                // 시설: 앞에 그려진 것부터 본다.
                val f = facilitySpots(a, w, h, unit, workshopLevel).asReversed().firstOrNull { it.rect.contains(p) }
                if (f != null) { ping(p); onFacilityNow(f.facility) }
            }
        },
    ) {
        if (a == null) return@Canvas
        val w = size.width; val h = size.height; val u = min(w, h)
        // 시계 값은 여기(그리기 단계)에서만 읽는다.
        val ms = clock.ms.longValue
        val ph = (ms % CYCLE_MS).toFloat() / CYCLE_MS * (2 * PI).toFloat()
        val night = timeSlot == TimeSlot.EVENING || timeSlot == TimeSlot.NIGHT
        val tint = timeTint(timeSlot)
        val spots = facilitySpots(a, w, h, u, workshopLevel)
        val (windmill, mill, forge) = spots
        val lamp1 = spots[3]; val lamp2 = spots[4]; val garden = spots[5]

        drawBackground(a.bg, ph, tint)

        // 뒤(위)에서 앞(아래)으로, 고정 순서. 쉬는 시설은 채도를 낮춰 그린다.
        drawSpot(windmill, Facility.WINDMILL in needingCare, clock.bladeDeg.floatValue)
        drawSpot(mill, Facility.MILL in needingCare, clock.wheelDeg.floatValue)
        drawSpot(forge, Facility.FORGE in needingCare)
        val lvl = workshopLevel.coerceIn(0, 4)
        val fr = forge.rect
        if (Facility.FORGE !in needingCare) drawSmoke(fr, ms, u) // 쉬는 공방은 연기도 멈춘다.
        if (lvl >= 3) drawPennant(Offset(fr.center.x + fr.width * 0.30f, fr.bottom - fr.height * 0.92f), u)
        residentSprite(a, Resident.CRAFTSMAN, w, h, u, ph)
        residentSprite(a, Resident.GARDENER, w, h, u, ph)
        drawSpot(lamp1, Facility.LAMP in needingCare)
        drawSpot(lamp2, Facility.LAMP in needingCare)
        residentSprite(a, Resident.TRAVELER, w, h, u, ph)
        drawSpot(garden, Facility.GARDEN in needingCare)

        VillageLayout.slots.forEachIndexed { i, o ->
            val c = Offset(o.x * w, o.y * h)
            val m = placed.firstOrNull { it.placedSlot == i } ?: return@forEachIndexed
            val img = a.materials.getValue(m.kind)
            // 슬롯 중심에 놓고, 가로세로 중 긴 쪽을 기준으로 크기를 맞춘다.
            val side = u * 0.13f
            val hh = if (img.height >= img.width) side else side * img.height / img.width
            sprite(img, c.x, c.y + hh / 2, hh)
        }

        // 시간대 색을 스프라이트에도 입힌다(밤에 건물만 낮처럼 밝게 남지 않도록). 등불·화로 빛은 그 위에 얹는다.
        if (tint.alpha > 0f) drawRect(tint.copy(alpha = tint.alpha * 0.6f), size = size)

        // 빈 슬롯 링은 틴트 뒤에 그려 밤에도 놓을 곳이 또렷하게 보이게 한다.
        VillageLayout.slots.forEachIndexed { i, o ->
            if (placed.any { it.placedSlot == i }) return@forEachIndexed
            drawCircle(
                if (highlightSlots) Color(0xFFFFE08A) else Color(0x88FFFFFF), u * 0.045f, Offset(o.x * w, o.y * h),
                style = Stroke(width = u * 0.008f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(u * 0.015f, u * 0.012f))),
            )
        }

        if (Facility.FORGE !in needingCare) { // 쉬는 공방은 화로 불빛이 꺼진다(깜박임도 멈춤).
            val fx = fr.center.x + fr.width * 0.07f
            val fy = fr.bottom - fr.height * 0.25f
            val flick = 0.55f + 0.25f * sin(ph * 36f) + 0.1f * sin(ph * 53f)
            drawCircle(Brush.radialGradient(listOf(Color(0xFFFFC46B).copy(alpha = 0.4f * flick), Color.Transparent), Offset(fx, fy), u * 0.09f), u * 0.09f, Offset(fx, fy))
        }
        if (night) { // 가로등은 저녁·밤에 켜진다. 쉬는 중이면 켜진 채 깜박임만 멈춘다.
            val lampResting = Facility.LAMP in needingCare
            for ((lx, ly, lh) in LAMPS) {
                val gc = Offset(w * lx - u * lh * a.lamp.width / a.lamp.height * 0.22f, h * ly - u * lh * 0.86f)
                val pulse = if (lampResting) 0.8f else 0.8f + 0.2f * sin(ph * 12f + lx * 9f)
                drawCircle(Brush.radialGradient(listOf(Color(0xFFFFE08A).copy(alpha = 0.5f * pulse), Color.Transparent), gc, u * 0.09f), u * 0.09f, gc)
            }
        }

        // 쉬는 시설마다 작은 "z" 하나(가로등은 두 개 중 앞의 하나에만). 숫자·배지·합산 표시는 두지 않는다.
        spots.distinctBy { it.facility }.forEach { s -> if (s.facility in needingCare) drawSleepZ(s.rect, u) }

        // 점등 연출: 빛이 번지는 원 + 반짝임(위치·크기·투명도만 바뀐다).
        val wp = wakeAnim.value
        val wf = wake?.facility
        if (wf != null && wp < 1f) spots.filter { it.facility == wf }.forEach { drawWake(it.rect, wp, u) }

        // 재료를 놓은 자리의 반짝임, 탭 링(1회씩).
        val sp = sparkle.value
        val ss = sparkleSlot.intValue
        if (sp < 1f && ss in VillageLayout.slots.indices) VillageLayout.slots[ss].let { drawSparkle(it.x * w, it.y * h, sp, u) }
        val rp = ring.value
        if (rp < 1f) drawTapRing(ringAt.value, rp, u)
    }
}

/**
 * 굴뚝 연기: 고정 4개 퍼프가 굴뚝 위 펠트 연기 끝에서 피어올라 커지며 흐려진다.
 * 위치·크기·투명도는 시간과 미리 정한 시드만의 함수라 그릴 때 객체를 만들지 않는다(원 그리기만).
 */
private fun DrawScope.drawSmoke(fr: Rect, ms: Long, u: Float) {
    val ex = fr.left + fr.width * 0.56f
    val ey = fr.top + fr.height * 0.06f
    for (i in SMOKE_PHASE.indices) {
        val f = ((ms + (SMOKE_PHASE[i] * SMOKE_LIFE_MS).toLong()) % SMOKE_LIFE_MS).toFloat() / SMOKE_LIFE_MS
        val x = ex + u * 0.035f * SMOKE_DRIFT[i] * f + sin(f * 6.283f + i) * u * 0.006f
        val y = ey - u * 0.11f * f
        val r = u * (0.011f + 0.018f * f) * SMOKE_SIZE[i]
        val a = (f / 0.15f).coerceAtMost(1f) * (1f - f) * 0.6f // 잠깐 짙어졌다가 천천히 사라진다
        drawCircle(SMOKE_COLOR.copy(alpha = a * 0.45f), r * 1.3f, Offset(x, y))
        drawCircle(SMOKE_COLOR.copy(alpha = a), r, Offset(x, y))
    }
}

/** 재료를 놓을 때의 반짝임 1회. p는 0→1. 선과 원만 쓴다. */
private fun DrawScope.drawSparkle(cx: Float, cy: Float, p: Float, u: Float) {
    val ease = 1f - (1f - p) * (1f - p)
    val c = Offset(cx, cy)
    drawCircle(Color(0xFFFFE6A0).copy(alpha = 0.45f * (1f - p)), u * (0.03f + 0.05f * ease), c)
    val twinkle = sin(p * PI.toFloat()).coerceIn(0f, 1f)
    val sr = u * 0.016f * twinkle
    val color = Color(0xFFFFF7D6).copy(alpha = twinkle)
    for (k in 0 until 8) {
        val ang = (k * 45f + 20f) * (PI.toFloat() / 180f)
        val d = u * (0.025f + (if (k % 2 == 0) 0.06f else 0.045f) * ease)
        val x = cx + cos(ang) * d
        val y = cy + sin(ang) * d * 0.85f
        drawLine(color, Offset(x - sr, y), Offset(x + sr, y), strokeWidth = u * 0.005f, cap = StrokeCap.Round)
        drawLine(color, Offset(x, y - sr), Offset(x, y + sr), strokeWidth = u * 0.005f, cap = StrokeCap.Round)
    }
}

/** 탭 링 1회: 누른 자리에서 작은 원이 퍼지며 사라진다. 짙은 테두리 위 크림 선이라 밤낮 모두 보인다. */
private fun DrawScope.drawTapRing(c: Offset, p: Float, u: Float) {
    val ease = 1f - (1f - p) * (1f - p)
    val radius = u * (0.025f + 0.045f * ease)
    val a = 1f - p
    drawCircle(Color(0xFF1C2130).copy(alpha = 0.5f * a), radius, c, style = Stroke(width = u * 0.012f))
    drawCircle(Color(0xFFFFF4CC).copy(alpha = 0.95f * a), radius, c, style = Stroke(width = u * 0.006f))
}

/** 쉬는 표시: 시설 오른쪽 위의 작은 "z" 한 글자(글꼴 없이 선으로). 짙은 테두리 위 크림 선이라 밤낮 모두 보인다. */
private fun DrawScope.drawSleepZ(r: Rect, u: Float) {
    val s = u * 0.034f
    val x = r.right - r.width * 0.22f
    val y = r.top + r.height * 0.06f
    val z = Path().apply { moveTo(x, y); lineTo(x + s, y); lineTo(x, y + s); lineTo(x + s, y + s) }
    drawPath(z, Color(0xCC1C2130), style = Stroke(width = u * 0.014f, cap = StrokeCap.Round, join = StrokeJoin.Round))
    drawPath(z, Color(0xFFF5EFE0), style = Stroke(width = u * 0.006f, cap = StrokeCap.Round, join = StrokeJoin.Round))
}

/** 점등 1회 연출. p는 0→1 진행도. */
private fun DrawScope.drawWake(r: Rect, p: Float, u: Float) {
    val ease = 1f - (1f - p) * (1f - p) // 처음 빠르고 끝에 느려진다
    val c = Offset(r.center.x, r.top + r.height * 0.45f)
    val span = maxOf(r.width, r.height)
    // 번지는 빛
    val radius = span * (0.30f + 0.55f * ease)
    val glowA = 0.85f * (1f - p)
    drawCircle(Brush.radialGradient(listOf(Color(0xFFFFD27A).copy(alpha = glowA), Color(0xFFFFE6A0).copy(alpha = glowA * 0.5f), Color.Transparent), c, radius), radius, c)
    // 반짝임 6개: 바깥으로 퍼지며 나타났다 사라진다
    val sparkA = sin(p * PI.toFloat()).coerceIn(0f, 1f)
    val sr = u * 0.026f * (1f - 0.4f * p)
    for (k in 0 until 6) {
        val ang = (k * 60f + 15f) * (PI.toFloat() / 180f)
        val d = span * (0.22f + 0.40f * ease)
        val sc = Offset(c.x + cos(ang) * d, c.y + sin(ang) * d * 0.8f)
        val star = Path().apply {
            moveTo(sc.x, sc.y - sr); lineTo(sc.x + sr * 0.3f, sc.y - sr * 0.3f); lineTo(sc.x + sr, sc.y)
            lineTo(sc.x + sr * 0.3f, sc.y + sr * 0.3f); lineTo(sc.x, sc.y + sr); lineTo(sc.x - sr * 0.3f, sc.y + sr * 0.3f)
            lineTo(sc.x - sr, sc.y); lineTo(sc.x - sr * 0.3f, sc.y - sr * 0.3f); close()
        }
        drawPath(star, Color(0xFFFFF4CC).copy(alpha = sparkA))
    }
}

private fun DrawScope.residentSprite(a: VillageArt, r: Resident, w: Float, h: Float, u: Float, ph: Float) {
    val o = VillageLayout.residents.getValue(r)
    val k = r.ordinal * 1.7f
    // 숨쉬기: 아주 작은 상하 이동과 세로 스케일
    sprite(a.npc.getValue(r), o.x * w, o.y * h, u * 0.17f, dy = sin(ph * 6f + k) * u * 0.0035f, scale = 1f + 0.012f * sin(ph * 6f + k))
}

/** 공방 3단계부터 지붕 위에 다는 작은 깃발(정비 신고 누적의 시각 신호). */
private fun DrawScope.drawPennant(top: Offset, u: Float) {
    drawLine(Color(0xFF5A4630), top, Offset(top.x, top.y - u * 0.07f), strokeWidth = u * 0.006f)
    val tri = Path().apply {
        moveTo(top.x, top.y - u * 0.07f); lineTo(top.x + u * 0.05f, top.y - u * 0.055f); lineTo(top.x, top.y - u * 0.04f); close()
    }
    drawPath(tri, Color(0xFFFFC46B))
}

private fun timeTint(slot: TimeSlot): Color = when (slot) {
    TimeSlot.MORNING -> Color(0x26FFB070)
    TimeSlot.DAY -> Color.Transparent
    TimeSlot.EVENING -> Color(0x555B4B8A)
    TimeSlot.NIGHT -> Color(0x99142040)
}

/** 배경: 커버 크롭 + 느린 가로 패럴랙스, 시간대 색 틴트. */
private fun DrawScope.drawBackground(bg: ImageBitmap, ph: Float, tint: Color) {
    val w = size.width; val h = size.height
    val scale = maxOf(w / bg.width, h / bg.height) * 1.08f
    val dw = bg.width * scale; val dh = bg.height * scale
    val shift = sin(ph) * (dw - w) * 0.4f
    drawImage(
        bg,
        dstOffset = IntOffset(((w - dw) / 2 + shift).toInt(), (h - dh).toInt()),
        dstSize = IntSize(dw.toInt(), dh.toInt()),
        filterQuality = FilterQuality.Medium,
    )
    if (tint.alpha > 0f) drawRect(tint, size = size)
}
