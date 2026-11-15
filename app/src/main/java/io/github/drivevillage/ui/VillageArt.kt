package io.github.drivevillage.ui

import android.content.Context
import android.graphics.BitmapFactory
import androidx.annotation.DrawableRes
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import io.github.drivevillage.R
import io.github.drivevillage.core.village.MaterialKind
import io.github.drivevillage.core.village.Resident
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** 마을 그래픽 묶음. 디코딩은 IO 스레드에서 하고, 메인 스레드는 완성본만 받는다. */
class VillageArt(
    val bg: ImageBitmap,
    val forge: ImageBitmap,
    val mill: ImageBitmap,
    val windmill: ImageBitmap,
    val lamp: ImageBitmap,
    val garden: ImageBitmap,
    val npc: Map<Resident, ImageBitmap>,
    val materials: Map<MaterialKind, ImageBitmap>,
) {
    companion object {
        @Volatile private var cached: VillageArt? = null
        private val lock = Mutex()

        fun peek(): VillageArt? = cached

        /** 동시에 여러 곳에서 불러도 한 번만 디코딩한다. 실패(null 반환·메모리 부족)하면 null이고, 화면은 그리지 않는다. */
        suspend fun load(context: Context): VillageArt? = cached ?: lock.withLock { cached ?: decodeOrNull(context)?.also { cached = it } }

        private suspend fun decodeOrNull(context: Context): VillageArt? = try {
            decode(context)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Throwable) {
            android.util.Log.w("VillageArt", "그래픽 디코딩 실패", e)
            null // 디코딩 실패·메모리 부족: 마을을 그리지 않을 뿐 앱은 종료하지 않는다.
        }

        private suspend fun decode(context: Context): VillageArt = withContext(Dispatchers.IO) {
            val res = context.applicationContext.resources
            fun img(@DrawableRes id: Int): ImageBitmap {
                val o = BitmapFactory.Options().apply { inScaled = false }
                return (BitmapFactory.decodeResource(res, id, o) ?: error("decode failed: $id")).asImageBitmap()
            }
            VillageArt(
                bg = img(R.drawable.vg_bg),
                forge = img(R.drawable.vg_forge),
                mill = img(R.drawable.vg_mill),
                windmill = img(R.drawable.vg_windmill),
                lamp = img(R.drawable.vg_lamp),
                garden = img(R.drawable.vg_garden),
                npc = mapOf(
                    Resident.CRAFTSMAN to img(R.drawable.vg_npc_artisan),
                    Resident.TRAVELER to img(R.drawable.vg_npc_traveler),
                    Resident.GARDENER to img(R.drawable.vg_npc_gardener),
                ),
                materials = MaterialKind.entries.associateWith { img(materialRes(it)) },
            )
        }

        private fun materialRes(k: MaterialKind): Int = when (k) {
            MaterialKind.WOOD -> R.drawable.vg_mat_wood
            MaterialKind.STONE -> R.drawable.vg_mat_stone
            MaterialKind.SEED -> R.drawable.vg_mat_seed
            MaterialKind.GLASS -> R.drawable.vg_mat_glass
            MaterialKind.CLOTH -> R.drawable.vg_mat_cloth
            MaterialKind.MONUMENT -> R.drawable.vg_mat_monument
            MaterialKind.SPRING_GARLAND -> R.drawable.vg_mat_spring_garland
            MaterialKind.SUMMER_LANTERN -> R.drawable.vg_mat_summer_lantern
            MaterialKind.AUTUMN_WREATH -> R.drawable.vg_mat_autumn_wreath
            MaterialKind.WINTER_STAR -> R.drawable.vg_mat_winter_star
            MaterialKind.GEAR -> R.drawable.vg_mat_gear
            MaterialKind.MEMORIAL -> R.drawable.vg_mat_memorial
        }
    }
}
