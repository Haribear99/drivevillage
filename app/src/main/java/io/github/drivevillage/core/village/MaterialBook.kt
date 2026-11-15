package io.github.drivevillage.core.village

/**
 * 재료 도감(꾸미기 탭)의 순수 계산. 저장 상태를 바꾸지 않고 읽기만 한다.
 *
 * - 12종을 늘 같은 순서([MaterialKind] 선언 순서: 정산 재료 → 이정표 → 계절 → 예전 정비 기념품)로 보여 준다.
 * - 한 번이라도 가진 종류는 '만났어요'. 아이템은 지워지지 않으므로 지금 가진 수 = 만난 뒤 모은 수다.
 * - 획득 계기 문구는 실제 지급 규칙([SceneLedger.settle])과 같아야 한다. 정비 응답은 재료를 주지 않는다.
 */
object MaterialBook {
    val ORDER: List<MaterialKind> = MaterialKind.entries

    /** 도감 한 칸. [unplaced]는 마을에 아직 놓지 않은 것(최근에 받은 순: 방금 받은 기억이 먼저 보인다). */
    data class Entry(val kind: MaterialKind, val owned: Int, val unplaced: List<Material>) {
        val met: Boolean get() = owned > 0
        val placed: Int get() = owned - unplaced.size
    }

    fun entries(state: AppState): List<Entry> {
        val byKind = state.materials.groupBy { it.kind }
        return ORDER.map { k ->
            val all = byKind[k].orEmpty()
            Entry(k, all.size, all.filter { it.placedSlot == null }.sortedWith(compareByDescending<Material> { it.epochDay }.thenBy { it.id }))
        }
    }

    fun metCount(state: AppState): Int = state.materials.mapTo(HashSet()) { it.kind }.size

    fun progressLabel(state: AppState): String = "${metCount(state)}/${ORDER.size} 만났어요"

    /** 보유 수 칩. 1개 이하면 칩을 그리지 않는다(null). */
    fun countChip(owned: Int): String? = if (owned <= 1) null else "×$owned"

    /** 아직 못 만난 종류의 획득 계기 한 줄. */
    fun hint(kind: MaterialKind): String = when (kind.source) {
        // 정산 재료는 하루 하나, 종류는 날짜로 돌아가며 정해진다([SceneLedger.settle]).
        ItemSource.JOURNEY -> "주행 정산 때 와요.\n날마다 종류가 바뀌어요" // 가로 패널(한 줄 약 15자)에서 낱말 중간에 끊기지 않게 직접 줄을 나눈다.
        ItemSource.MILESTONE -> "정산한 거리가 1,000km를\n넘을 때마다 와요"
        ItemSource.SEASON -> "${seasonWord(kind)} 첫 정산 때 와요"
        // 0.1.3까지의 정비 응답 보상. 지금은 새로 생기지 않는다(정비 응답은 재료를 주지 않음).
        ItemSource.MAINTENANCE, ItemSource.MEMORIAL -> "예전 버전의 기념품이라\n지금은 새로 생기지 않아요"
    }

    private fun seasonWord(kind: MaterialKind): String = when (kind) {
        MaterialKind.SPRING_GARLAND -> "봄"
        MaterialKind.SUMMER_LANTERN -> "여름"
        MaterialKind.AUTUMN_WREATH -> "가을"
        else -> "겨울"
    }

    /**
     * 처음 열었을 때 자세히 보여 줄 칸: 고른 재료 → 이번에 새로 받은 것 → 놓을 수 있는 첫 종류. 없으면 null(안내 문구).
     */
    fun defaultFocus(entries: List<Entry>, selectedId: String?, newMaterials: List<Material>): MaterialKind? {
        entries.firstOrNull { e -> selectedId != null && e.unplaced.any { it.id == selectedId } }?.let { return it.kind }
        val newKinds = newMaterials.mapTo(HashSet()) { it.kind }
        entries.firstOrNull { it.kind in newKinds && it.unplaced.isNotEmpty() }?.let { return it.kind }
        return entries.firstOrNull { it.unplaced.isNotEmpty() }?.kind
    }

    /**
     * 격자 열 수: 12의 약수(6·4·3) 중 칸이 [minCellDp] 이상인 가장 많은 열. 셋 다 안 되면 2열.
     * 열 수를 12의 약수로 두어 마지막 줄이 비지 않게 한다.
     */
    fun columns(widthDp: Float, gapDp: Float, minCellDp: Float): Int =
        listOf(6, 4, 3).firstOrNull { c -> (widthDp - gapDp * (c - 1)) / c >= minCellDp } ?: 2

    /**
     * 이번 화면에서 테두리 광택을 한 번 보여 줄 종류. 이번 정산으로 새로 받은 것 중 아직 광택을 보여 주지 않은 아이템의 종류.
     * 애니메이션 끄기면 비어 있다.
     */
    fun shimmerKinds(newMaterials: List<Material>, alreadyShown: Set<String>, reduceMotion: Boolean): Set<MaterialKind> =
        if (reduceMotion) emptySet() else newMaterials.filter { it.id !in alreadyShown }.mapTo(LinkedHashSet()) { it.kind }

    /** 정산 카드의 재료 칩: 같은 종류는 하나로 묶고(×n), 최대 [max]개. 나머지 종류 수를 함께 돌려준다. */
    fun chips(newMaterials: List<Material>, max: Int = 4): Pair<List<Pair<MaterialKind, Int>>, Int> {
        val grouped = newMaterials.groupBy { it.kind }.toList().sortedBy { ORDER.indexOf(it.first) }.map { it.first to it.second.size }
        return grouped.take(max) to (grouped.size - max).coerceAtLeast(0)
    }
}

/** 화면 연출을 정산(또는 아이템) id마다 한 번만 재생하기 위한 판정. */
object RevealOnce {
    fun shouldPlay(key: String, played: Set<String>, reduceMotion: Boolean): Boolean = !reduceMotion && key !in played
}

/**
 * 정산 카드 연출 타임라인(설계 가설값, 화면으로 조정). 모든 값은 연출 시작 뒤 경과 ms의 순수 함수다.
 * 거리 카운트업 약 1초 → 재료 칩 차례로 착지 → 도장. 전체는 [MAX_MS] 안에서 끝난다.
 * 핵심 정보(헤더·칩 글자·도장 문구)는 0초부터 화면에 있고, 여기서 정하는 것은 위치·투명도·숫자뿐이다.
 */
object SettleReveal {
    const val COUNT_MS = 1_000L
    const val CHIP_START_MS = 800L
    const val CHIP_STEP_MS = 400L
    const val CHIP_MS = 450L
    const val STAMP_GAP_MS = 150L
    const val STAMP_MS = 550L
    const val MAX_MS = 3_500L
    /** 칩이 착지하기 전의 투명도(0초부터 읽히도록 0이 아니다). */
    const val CHIP_ALPHA_FROM = 0.45f

    private fun chipEnd(chips: Int): Long = if (chips <= 0) COUNT_MS else CHIP_START_MS + CHIP_STEP_MS * (chips - 1) + CHIP_MS

    fun stampStartMs(chips: Int): Long = maxOf(COUNT_MS, chipEnd(chips)) + STAMP_GAP_MS

    fun totalMs(chips: Int, stamp: Boolean): Long =
        (if (stamp) stampStartMs(chips) + STAMP_MS else maxOf(COUNT_MS, chipEnd(chips))).coerceAtMost(MAX_MS)

    private fun progress(t: Long, start: Long, dur: Long): Float = ((t - start).toFloat() / dur).coerceIn(0f, 1f)

    /** 처음 빠르고 끝에 느려지는 곡선(3차). */
    fun easeOut(p: Float): Float { val q = 1f - p.coerceIn(0f, 1f); return 1f - q * q * q }

    /** 카운트업 중 보이는 거리. 끝나면 정확히 [finalKm]. */
    fun distanceAt(finalKm: Long, t: Long): Long =
        if (t >= COUNT_MS) finalKm else (finalKm * easeOut(progress(t, 0, COUNT_MS))).toLong().coerceIn(0, finalKm)

    fun chipAt(index: Int, t: Long): Float = progress(t, CHIP_START_MS + CHIP_STEP_MS * index, CHIP_MS)

    fun stampAt(chips: Int, t: Long): Float = progress(t, stampStartMs(chips), STAMP_MS)
}
