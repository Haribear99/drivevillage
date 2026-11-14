package io.github.drivevillage

import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.drivevillage.core.gate.Screen
import io.github.drivevillage.ui.VillageScreen
import io.github.drivevillage.ui.VillageViewModel
import io.github.drivevillage.ui.VillageTheme

class MainActivity : ComponentActivity() {

    private val vm: VillageViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val screen by vm.screen.collectAsState()
            val loaded by vm.stateLoaded.collectAsState()
            LaunchedEffect(screen) { if (screen == Screen.CLOSE) closeWithoutAnimation() }
            VillageTheme {
                Box(Modifier.fillMaxSize().background(VillageTheme.background)) {
                    when (screen) {
                        // 신호 대기 중·종료 중: 마을·버튼·소리 없는 빈 화면.
                        Screen.PENDING, Screen.CLOSE -> Unit
                        Screen.STATIC_NOTICE -> Text(
                            "주차(P)한 뒤 다시 열어 주세요. 마을은 정차 중에만 열립니다.",
                            color = Color(0xFFE6E1D6), fontSize = 28.sp,
                            modifier = Modifier.align(Alignment.Center).padding(24.dp),
                        )
                        // 저장 상태를 다 읽기 전에는 마을 화면을 그리지 않는다(기본값 노출·덮어쓰기 방지).
                        Screen.VILLAGE -> if (loaded) VillageScreen(vm)
                    }
                    Flavor.SimulationOverlay(vm.signals)
                }
            }
        }
    }

    /**
     * 전면을 잃으면(플랫폼 주행 차단 화면이 위에 덮이는 경우 포함) 소리를 즉시 끊는다.
     * 차단 화면은 onStop 없이 onPause만 일으킬 수 있어 화면 판정과 별도로 둔다.
     */
    override fun onPause() {
        super.onPause()
        vm.setForeground(false)
    }

    override fun onResume() {
        super.onResume()
        vm.setForeground(true)
    }

    /** 홈 버튼·앱 전환으로 화면을 잃으면 연출 없이 종료한다. 다시 열면 새로 정산한다. */
    override fun onStop() {
        super.onStop()
        if (!isChangingConfigurations) {
            vm.closeSilently()
            closeWithoutAnimation()
        }
    }

    private fun closeWithoutAnimation() {
        if (isFinishing) return
        finish()
        if (Build.VERSION.SDK_INT >= 34) {
            overrideActivityTransition(OVERRIDE_TRANSITION_CLOSE, 0, 0)
        } else {
            @Suppress("DEPRECATION")
            overridePendingTransition(0, 0)
        }
    }
}
