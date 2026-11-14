package io.github.drivevillage.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import io.github.drivevillage.core.village.Decoration
import io.github.drivevillage.core.village.Material
import io.github.drivevillage.core.village.MaterialKind
import io.github.drivevillage.core.village.Resident
import io.github.drivevillage.core.village.TimeSlot
import kotlin.math.hypot
import kotlin.math.min

/**
 * 마을 전경. 오리지널 단순 도형·벡터로만 그린다(외부 아트·제3자 IP 없음).
 * 공방은 정비 신고 누적(공방 단계)에 따라 증축되고, 슬롯 6곳에 재료를 놓을 수 있다.
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

@Composable
fun VillageCanvas(
    modifier: Modifier,
    timeSlot: TimeSlot,
    workshopLevel: Int,
    placed: List<Material>,
    highlightSlots: Boolean,
    onResident: (Resident) -> Unit,
    onSlot: (Int) -> Unit,
) {
    Canvas(
        modifier.pointerInput(highlightSlots, placed) {
            detectTapGestures { p ->
                val w = size.width.toFloat(); val h = size.height.toFloat()
                val unit = min(w, h)
                val slot = VillageLayout.slots.indexOfFirst { hypot(p.x - it.x * w, p.y - it.y * h) < unit * 0.07f }
                if (slot >= 0) { onSlot(slot); return@detectTapGestures }
                val r = VillageLayout.residents.entries.firstOrNull { (_, o) -> hypot(p.x - o.x * w, p.y - (o.y * h - unit * 0.04f)) < unit * 0.09f }
                if (r != null) onResident(r.key)
            }
        },
    ) {
        val w = size.width; val h = size.height; val u = min(w, h)
        drawSky(timeSlot)
        // 언덕과 길
        drawPath(Path().apply {
            moveTo(0f, h * 0.55f); cubicTo(w * 0.3f, h * 0.45f, w * 0.7f, h * 0.48f, w, h * 0.52f); lineTo(w, h); lineTo(0f, h); close()
        }, Color(0xFF6FA86E))
        drawPath(Path().apply {
            moveTo(0f, h * 0.70f); cubicTo(w * 0.35f, h * 0.62f, w * 0.65f, h * 0.66f, w, h * 0.64f); lineTo(w, h); lineTo(0f, h); close()
        }, Color(0xFF5E9A5F))
        drawPath(Path().apply {
            moveTo(w * 0.46f, h); cubicTo(w * 0.50f, h * 0.85f, w * 0.40f, h * 0.78f, w * 0.52f, h * 0.70f)
            lineTo(w * 0.56f, h * 0.70f); cubicTo(w * 0.47f, h * 0.78f, w * 0.58f, h * 0.86f, w * 0.56f, h); close()
        }, Color(0xFFD8C08A))

        drawWorkshop(Offset(w * 0.18f, h * 0.60f), u, workshopLevel)
        drawWell(Offset(w * 0.64f, h * 0.52f), u)
        drawGarden(Offset(w * 0.80f, h * 0.70f), u)
        val night = timeSlot == TimeSlot.EVENING || timeSlot == TimeSlot.NIGHT
        drawLamp(Offset(w * 0.42f, h * 0.66f), u, night)
        drawLamp(Offset(w * 0.72f, h * 0.64f), u, night)

        VillageLayout.slots.forEachIndexed { i, o ->
            val c = Offset(o.x * w, o.y * h)
            val m = placed.firstOrNull { it.placedSlot == i }
            if (m == null) {
                drawCircle(
                    if (highlightSlots) Color(0xFFFFE08A) else Color(0x88FFFFFF), u * 0.045f, c,
                    style = Stroke(width = u * 0.008f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(u * 0.015f, u * 0.012f))),
                )
            } else drawMaterial(m.kind, c, u * 0.045f)
        }
        VillageLayout.residents.forEach { (r, o) -> drawResident(r, Offset(o.x * w, o.y * h), u) }
    }
}

private fun DrawScope.drawSky(slot: TimeSlot) {
    val (top, bottom) = when (slot) {
        TimeSlot.MORNING -> Color(0xFFF6C99A) to Color(0xFFBFE3F2)
        TimeSlot.DAY -> Color(0xFF7EC3EA) to Color(0xFFD5EEF7)
        TimeSlot.EVENING -> Color(0xFF5B4B8A) to Color(0xFFF29E6D)
        TimeSlot.NIGHT -> Color(0xFF14203A) to Color(0xFF34466E)
    }
    drawRect(Brush.verticalGradient(listOf(top, bottom)), size = size)
    val u = min(size.width, size.height)
    val orb = if (slot == TimeSlot.NIGHT || slot == TimeSlot.EVENING) Color(0xFFF4EBC8) else Color(0xFFFFD36B)
    drawCircle(orb, u * 0.06f, Offset(size.width * 0.86f, size.height * 0.16f))
}

/** 공방: 단계(정비 신고 누적)에 따라 굴뚝 → 곁채 → 2층 → 깃발 순으로 증축. */
private fun DrawScope.drawWorkshop(base: Offset, u: Float, level: Int) {
    val stage = level.coerceIn(0, 4)
    val bw = u * 0.22f; val bh = u * 0.15f
    val left = base.x - bw / 2; val top = base.y - bh
    if (stage >= 2) { // 곁채
        drawRect(Color(0xFFD9C3A0), Offset(left + bw, top + bh * 0.35f), Size(bw * 0.45f, bh * 0.65f))
        drawPath(Path().apply { moveTo(left + bw, top + bh * 0.35f); lineTo(left + bw * 1.45f, top + bh * 0.35f); lineTo(left + bw, top + bh * 0.05f); close() }, Color(0xFF9C4A35))
    }
    var roofBase = top
    if (stage >= 3) { // 2층
        drawRect(Color(0xFFE4D2B0), Offset(left + bw * 0.12f, top - bh * 0.6f), Size(bw * 0.76f, bh * 0.6f))
        drawRect(Color(0xFF8FB8D8), Offset(left + bw * 0.42f, top - bh * 0.45f), Size(bw * 0.16f, bh * 0.25f))
        roofBase = top - bh * 0.6f
    }
    drawRect(Color(0xFFE9D8B4), Offset(left, top), Size(bw, bh))
    val roofW = if (stage >= 3) bw * 0.9f else bw * 1.15f
    drawPath(Path().apply {
        moveTo(base.x - roofW / 2, roofBase); lineTo(base.x + roofW / 2, roofBase); lineTo(base.x, roofBase - bh * 0.6f); close()
    }, Color(0xFFC8553D))
    if (stage >= 1) { // 굴뚝과 연기
        drawRect(Color(0xFF6B4A2F), Offset(base.x + bw * 0.22f, roofBase - bh * 0.55f), Size(bw * 0.1f, bh * 0.35f))
        drawCircle(Color(0x99FFFFFF), u * 0.015f, Offset(base.x + bw * 0.27f, roofBase - bh * 0.7f))
        drawCircle(Color(0x77FFFFFF), u * 0.02f, Offset(base.x + bw * 0.31f, roofBase - bh * 0.9f))
    }
    if (stage >= 4) { // 깃발
        val px = base.x - roofW * 0.35f
        drawLine(Color(0xFF4A3A2A), Offset(px, roofBase - bh * 0.2f), Offset(px, roofBase - bh * 1.0f), strokeWidth = u * 0.006f)
        drawPath(Path().apply { moveTo(px, roofBase - bh * 1.0f); lineTo(px + bw * 0.25f, roofBase - bh * 0.88f); lineTo(px, roofBase - bh * 0.76f); close() }, Color(0xFFFFC46B))
    }
    // 문과 작업대(망치 표지)
    drawRect(Color(0xFF6B4A2F), Offset(base.x - bw * 0.08f, base.y - bh * 0.55f), Size(bw * 0.16f, bh * 0.55f))
    drawRect(Color(0xFF8FB8D8), Offset(left + bw * 0.1f, top + bh * 0.25f), Size(bw * 0.18f, bh * 0.25f))
    drawRoundRect(Color(0xFF4A3A2A), Offset(left + bw * 0.72f, top + bh * 0.22f), Size(bw * 0.16f, bh * 0.08f), CornerRadius(u * 0.005f))
    drawRect(Color(0xFF4A3A2A), Offset(left + bw * 0.785f, top + bh * 0.28f), Size(bw * 0.03f, bh * 0.2f))
}

private fun DrawScope.drawWell(c: Offset, u: Float) {
    drawRoundRect(Color(0xFF9AA3AD), Offset(c.x - u * 0.06f, c.y - u * 0.03f), Size(u * 0.12f, u * 0.06f), CornerRadius(u * 0.02f))
    drawOval(Color(0xFF3E6E9C), Offset(c.x - u * 0.045f, c.y - u * 0.04f), Size(u * 0.09f, u * 0.025f))
    drawLine(Color(0xFF6B4A2F), Offset(c.x - u * 0.05f, c.y - u * 0.03f), Offset(c.x - u * 0.05f, c.y - u * 0.12f), strokeWidth = u * 0.008f)
    drawLine(Color(0xFF6B4A2F), Offset(c.x + u * 0.05f, c.y - u * 0.03f), Offset(c.x + u * 0.05f, c.y - u * 0.12f), strokeWidth = u * 0.008f)
    drawPath(Path().apply { moveTo(c.x - u * 0.075f, c.y - u * 0.11f); lineTo(c.x + u * 0.075f, c.y - u * 0.11f); lineTo(c.x, c.y - u * 0.16f); close() }, Color(0xFF8A5A3A))
}

private fun DrawScope.drawGarden(c: Offset, u: Float) {
    drawRoundRect(Color(0xFF7A5536), Offset(c.x - u * 0.11f, c.y - u * 0.035f), Size(u * 0.22f, u * 0.07f), CornerRadius(u * 0.02f))
    val colors = listOf(Color(0xFFFF8FA3), Color(0xFFFFD36B), Color(0xFFB59CFF), Color(0xFFFF8FA3), Color(0xFFFFFFFF))
    colors.forEachIndexed { i, col ->
        val x = c.x - u * 0.085f + i * u * 0.042f
        drawLine(Color(0xFF3F7A3F), Offset(x, c.y - u * 0.02f), Offset(x, c.y - u * 0.055f), strokeWidth = u * 0.005f)
        drawCircle(col, u * 0.014f, Offset(x, c.y - u * 0.06f))
    }
}

private fun DrawScope.drawLamp(base: Offset, u: Float, lit: Boolean) {
    if (lit) drawCircle(Color(0x55FFE08A), u * 0.05f, Offset(base.x, base.y - u * 0.13f))
    drawLine(Color(0xFF3A3A44), base, Offset(base.x, base.y - u * 0.12f), strokeWidth = u * 0.008f)
    drawRoundRect(if (lit) Color(0xFFFFE08A) else Color(0xFFCFCFC0), Offset(base.x - u * 0.015f, base.y - u * 0.15f), Size(u * 0.03f, u * 0.035f), CornerRadius(u * 0.006f))
}

fun DrawScope.drawMaterial(kind: MaterialKind, c: Offset, r: Float) {
    when (kind) {
        MaterialKind.WOOD -> {
            drawRoundRect(Color(0xFFA0703F), Offset(c.x - r, c.y - r * 0.45f), Size(r * 2, r * 0.9f), CornerRadius(r * 0.3f))
            drawCircle(Color(0xFFD7B07A), r * 0.35f, Offset(c.x + r * 0.7f, c.y))
        }
        MaterialKind.STONE -> drawOval(Color(0xFF9AA3AD), Offset(c.x - r, c.y - r * 0.7f), Size(r * 2, r * 1.4f))
        MaterialKind.SEED -> {
            drawLine(Color(0xFF3F7A3F), Offset(c.x, c.y + r * 0.6f), Offset(c.x, c.y - r * 0.4f), strokeWidth = r * 0.2f)
            drawOval(Color(0xFF6CC56C), Offset(c.x - r * 0.9f, c.y - r * 0.8f), Size(r * 0.9f, r * 0.5f))
            drawOval(Color(0xFF6CC56C), Offset(c.x, c.y - r * 0.9f), Size(r * 0.9f, r * 0.5f))
            drawRoundRect(Color(0xFFB07A4A), Offset(c.x - r * 0.6f, c.y + r * 0.4f), Size(r * 1.2f, r * 0.6f), CornerRadius(r * 0.2f))
        }
        MaterialKind.GLASS -> {
            drawCircle(Color(0xFF6FB7E6), r * 0.8f, c)
            drawCircle(Color(0xCCFFFFFF), r * 0.22f, Offset(c.x - r * 0.3f, c.y - r * 0.3f))
        }
        MaterialKind.CLOTH -> {
            drawLine(Color(0xFF4A3A2A), Offset(c.x - r * 0.6f, c.y + r), Offset(c.x - r * 0.6f, c.y - r), strokeWidth = r * 0.15f)
            drawPath(Path().apply { moveTo(c.x - r * 0.6f, c.y - r); lineTo(c.x + r, c.y - r * 0.5f); lineTo(c.x - r * 0.6f, c.y); close() }, Color(0xFFE76F51))
        }
        MaterialKind.MONUMENT -> { // 이정표 기념비: 받침 위 돌기둥과 별
            drawRect(Color(0xFF8A8F98), Offset(c.x - r, c.y + r * 0.5f), Size(r * 2, r * 0.4f))
            drawRect(Color(0xFFB8BEC6), Offset(c.x - r * 0.45f, c.y - r * 1.2f), Size(r * 0.9f, r * 1.7f))
            drawCircle(Color(0xFFFFD36B), r * 0.28f, Offset(c.x, c.y - r * 0.7f))
        }
        MaterialKind.SPRING_GARLAND -> {
            listOf(Color(0xFFFF8FA3), Color(0xFFFFFFFF), Color(0xFFFFB3C6)).forEachIndexed { i, col ->
                drawCircle(col, r * 0.35f, Offset(c.x - r * 0.7f + i * r * 0.7f, c.y - (if (i == 1) r * 0.3f else 0f)))
            }
        }
        MaterialKind.SUMMER_LANTERN -> {
            drawLine(Color(0xFF4A3A2A), Offset(c.x, c.y - r), Offset(c.x, c.y - r * 0.6f), strokeWidth = r * 0.12f)
            drawOval(Color(0xFFFF9F43), Offset(c.x - r * 0.6f, c.y - r * 0.6f), Size(r * 1.2f, r * 1.3f))
            drawOval(Color(0x88FFFFFF), Offset(c.x - r * 0.25f, c.y - r * 0.35f), Size(r * 0.5f, r * 0.7f))
        }
        MaterialKind.AUTUMN_WREATH -> {
            drawCircle(Color(0xFFD9822B), r * 0.8f, c, style = Stroke(width = r * 0.35f))
            drawCircle(Color(0xFFB5452B), r * 0.18f, Offset(c.x, c.y + r * 0.8f))
        }
        MaterialKind.WINTER_STAR -> {
            for (k in 0 until 4) {
                val ang = Math.toRadians(45.0 * k)
                val dx = (kotlin.math.cos(ang) * r).toFloat(); val dy = (kotlin.math.sin(ang) * r).toFloat()
                drawLine(Color(0xFFE8F4FF), Offset(c.x - dx, c.y - dy), Offset(c.x + dx, c.y + dy), strokeWidth = r * 0.18f)
            }
        }
        MaterialKind.GEAR -> { // 공방 톱니
            drawCircle(Color(0xFF9C7A4F), r * 0.8f, c)
            for (k in 0 until 6) {
                val ang = Math.toRadians(60.0 * k)
                drawCircle(Color(0xFF9C7A4F), r * 0.22f, Offset(c.x + (kotlin.math.cos(ang) * r * 0.9f).toFloat(), c.y + (kotlin.math.sin(ang) * r * 0.9f).toFloat()))
            }
            drawCircle(Color(0xFF5A4630), r * 0.3f, c)
        }
        MaterialKind.MEMORIAL -> { // 정비 기념패
            drawRoundRect(Color(0xFFC9A227), Offset(c.x - r * 0.8f, c.y - r * 0.6f), Size(r * 1.6f, r * 1.2f), CornerRadius(r * 0.2f))
            drawRoundRect(Color(0xFFF2E3A0), Offset(c.x - r * 0.55f, c.y - r * 0.35f), Size(r * 1.1f, r * 0.7f), CornerRadius(r * 0.1f))
        }
    }
}

/** 주민: 오리지널 단순 도형(머리 원 + 몸통 + 직업 표지). */
private fun DrawScope.drawResident(r: Resident, foot: Offset, u: Float) {
    val body = when (r) {
        Resident.CRAFTSMAN -> Color(0xFF8C6A4A)
        Resident.TRAVELER -> Color(0xFF4F7CAC)
        Resident.GARDENER -> Color(0xFF7DAA5C)
    }
    val bw = u * 0.05f; val bh = u * 0.06f
    drawRoundRect(body, Offset(foot.x - bw / 2, foot.y - bh), Size(bw, bh), CornerRadius(u * 0.015f))
    val head = Offset(foot.x, foot.y - bh - u * 0.025f)
    drawCircle(Color(0xFFF2D3B3), u * 0.028f, head)
    drawCircle(Color(0xFF2B2B2B), u * 0.004f, Offset(head.x - u * 0.009f, head.y))
    drawCircle(Color(0xFF2B2B2B), u * 0.004f, Offset(head.x + u * 0.009f, head.y))
    when (r) {
        Resident.CRAFTSMAN -> { // 앞치마와 두건
            drawRect(Color(0xFFD9C3A0), Offset(foot.x - bw * 0.3f, foot.y - bh * 0.8f), Size(bw * 0.6f, bh * 0.7f))
            drawArc(Color(0xFFC8553D), 180f, 180f, true, Offset(head.x - u * 0.03f, head.y - u * 0.032f), Size(u * 0.06f, u * 0.04f))
        }
        Resident.TRAVELER -> { // 넓은 모자와 배낭
            drawRoundRect(Color(0xFF6B4A2F), Offset(foot.x + bw * 0.45f, foot.y - bh * 0.9f), Size(bw * 0.4f, bh * 0.6f), CornerRadius(u * 0.006f))
            drawOval(Color(0xFF3D5A80), Offset(head.x - u * 0.045f, head.y - u * 0.03f), Size(u * 0.09f, u * 0.018f))
            drawArc(Color(0xFF3D5A80), 180f, 180f, true, Offset(head.x - u * 0.022f, head.y - u * 0.05f), Size(u * 0.044f, u * 0.04f))
        }
        Resident.GARDENER -> { // 밀짚 차양과 물뿌리개
            drawOval(Color(0xFFE9C46A), Offset(head.x - u * 0.042f, head.y - u * 0.028f), Size(u * 0.084f, u * 0.02f))
            drawRoundRect(Color(0xFF8FB8D8), Offset(foot.x - bw * 1.3f, foot.y - bh * 0.45f), Size(bw * 0.6f, bh * 0.4f), CornerRadius(u * 0.005f))
        }
    }
}
