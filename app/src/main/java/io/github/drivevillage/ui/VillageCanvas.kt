package io.github.drivevillage.ui

import android.provider.Settings
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import io.github.drivevillage.core.village.Facility
import io.github.drivevillage.core.village.Material
import io.github.drivevillage.core.village.Resident
import io.github.drivevillage.core.village.TimeSlot
import kotlinx.coroutines.delay
import kotlin.math.PI
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.sin

/**
 * 마을 전경. 입체 미니어처 디오라마 스타일의 AI 생성 에셋(레이어 PNG, README 참조)을 합성하고,
 * 움직임(배경 패럴랙스, 화로·가로등 깜박임, 주민 숨쉬기)은 Compose가 위치·투명도로만 만든다.
 * 공방은 정비 신고 누적(공방 단계)에 따라 커지고, 정비 시설은 짝이 된 정비가 도래하면 흐려진다.
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

private val GRAY = ColorFilter.colorMatrix(ColorMatrix().apply { setToSaturation(0f) })
private const val ANIM_FRAME_MS = 66L // 약 15fps: 느린 움직임이라 충분하고, 저사양 헤드유닛 부담을 줄인다.
private const val CYCLE_MS = 24_000L
private val LAMPS = listOf(Triple(0.40f, 0.68f, 0.28f), Triple(0.70f, 0.66f, 0.26f))

/** 발 위치(footX, footY)를 기준으로 높이 heightPx에 맞춰 스프라이트를 그린다. dim=true면 흑백·반투명. */
private fun DrawScope.sprite(
    img: ImageBitmap, footX: Float, footY: Float, heightPx: Float,
    dim: Boolean = false, dy: Float = 0f, scale: Float = 1f,
) {
    val hh = heightPx * scale
    val ww = hh * img.width / img.height
    drawImage(
        img,
        dstOffset = IntOffset((footX - ww / 2).toInt(), (footY - hh + dy).toInt()),
        dstSize = IntSize(ww.toInt(), hh.toInt()),
        alpha = if (dim) 0.45f else 1f,
        colorFilter = if (dim) GRAY else null,
        filterQuality = FilterQuality.Medium,
    )
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
) {
    val ctx = LocalContext.current
    // 그래픽은 IO 스레드에서 디코딩한다. 준비되기 전에는 아무것도 그리지 않는다.
    val art by produceState(VillageArt.peek()) { value = VillageArt.load(ctx) }
    // 시스템 '애니메이션 끄기'(배율 0)이면 정지 화면.
    val reduce = remember {
        runCatching { Settings.Global.getFloat(ctx.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f }.getOrDefault(false)
    }
    // 한 주기 24초. 모든 움직임은 이 위상의 정수배 사인이라 끊김 없이 반복된다. 화면이 사라지면 이 루프도 멈춘다.
    val phase = remember { mutableFloatStateOf(0f) }
    LaunchedEffect(reduce) {
        if (reduce) return@LaunchedEffect
        val t0 = System.nanoTime()
        while (true) {
            val ms = (System.nanoTime() - t0) / 1_000_000L
            phase.floatValue = (ms % CYCLE_MS).toFloat() / CYCLE_MS * (2 * PI).toFloat()
            delay(ANIM_FRAME_MS)
        }
    }
    val a = art
    Canvas(
        modifier.pointerInput(highlightSlots, placed, a != null) {
            if (a == null) return@pointerInput // 그래픽이 보이기 전에는 보이지 않는 슬롯·주민을 누를 수 없다.
            detectTapGestures { p ->
                val w = size.width.toFloat(); val h = size.height.toFloat()
                val unit = min(w, h)
                val slot = VillageLayout.slots.indexOfFirst { hypot(p.x - it.x * w, p.y - it.y * h) < unit * 0.07f }
                if (slot >= 0) { onSlot(slot); return@detectTapGestures }
                val r = VillageLayout.residents.entries.firstOrNull { (_, o) -> hypot(p.x - o.x * w, p.y - (o.y * h - unit * 0.085f)) < unit * 0.085f }
                if (r != null) onResident(r.key)
            }
        },
    ) {
        if (a == null) return@Canvas
        val w = size.width; val h = size.height; val u = min(w, h)
        val ph = phase.floatValue
        val night = timeSlot == TimeSlot.EVENING || timeSlot == TimeSlot.NIGHT
        val tint = timeTint(timeSlot)

        drawBackground(a.bg, ph, tint)

        // 뒤(위)에서 앞(아래)으로, 고정 순서.
        sprite(a.windmill, w * 0.52f, h * 0.54f, u * 0.36f, dim = Facility.WINDMILL in needingCare)
        sprite(a.mill, w * 0.80f, h * 0.60f, u * 0.34f, dim = Facility.MILL in needingCare)
        val lvl = workshopLevel.coerceIn(0, 4)
        val forgeScale = 0.76f + 0.06f * lvl // 정비 신고가 쌓일수록 공방이 커진다.
        val forgeH = u * 0.42f
        sprite(a.forge, w * 0.22f, h * 0.62f, forgeH, dim = Facility.FORGE in needingCare, scale = forgeScale)
        val forgeW = forgeH * forgeScale * a.forge.width / a.forge.height
        if (lvl >= 3) drawPennant(Offset(w * 0.22f + forgeW * 0.30f, h * 0.62f - forgeH * forgeScale * 0.92f), u)
        residentSprite(a, Resident.CRAFTSMAN, w, h, u, ph)
        residentSprite(a, Resident.GARDENER, w, h, u, ph)
        sprite(a.lamp, w * 0.40f, h * 0.68f, u * 0.28f, dim = Facility.LAMP in needingCare)
        sprite(a.lamp, w * 0.70f, h * 0.66f, u * 0.26f, dim = Facility.LAMP in needingCare)
        residentSprite(a, Resident.TRAVELER, w, h, u, ph)
        sprite(a.garden, w * 0.83f, h * 0.76f, u * 0.13f, dim = Facility.GARDEN in needingCare)

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

        if (Facility.FORGE !in needingCare) {
            val fx = w * 0.22f + forgeW * 0.07f
            val fy = h * 0.62f - forgeH * forgeScale * 0.25f
            val flick = 0.55f + 0.25f * sin(ph * 36f) + 0.1f * sin(ph * 53f)
            drawCircle(Brush.radialGradient(listOf(Color(0xFFFFC46B).copy(alpha = 0.4f * flick), Color.Transparent), Offset(fx, fy), u * 0.09f), u * 0.09f, Offset(fx, fy))
        }
        if (night) { // 가로등은 저녁·밤에 켜진다(정비 시설 흐림과 무관).
            for ((lx, ly, lh) in LAMPS) {
                val gc = Offset(w * lx - u * lh * a.lamp.width / a.lamp.height * 0.22f, h * ly - u * lh * 0.86f)
                val pulse = 0.8f + 0.2f * sin(ph * 12f + lx * 9f)
                drawCircle(Brush.radialGradient(listOf(Color(0xFFFFE08A).copy(alpha = 0.5f * pulse), Color.Transparent), gc, u * 0.09f), u * 0.09f, gc)
            }
        }

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
