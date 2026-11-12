package com.smartjourney.vehicle

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.View
import com.smartjourney.vehicle.ui.PopupViewport
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import com.smartjourney.vehicle.ui.AppRoot
import com.smartjourney.vehicle.ui.SmartJourneyTheme

/** 정차 중에 여는 화면: 동의, 인원 확인, 방문지 고르기, 여정 시작, 진행 현황. */
class MainActivity : ComponentActivity() {

    private val viewportListener = View.OnLayoutChangeListener { v, _, _, _, _, _, _, _, _ ->
        val xy = IntArray(2); v.getLocationOnScreen(xy)
        if (v.width > 0 && v.height > 0) graph.popupViewport.value = PopupViewport(xy[0], xy[1], v.width, v.height)
    }

    // 차량의 분할 창은 내비 뒤에서도 STARTED일 수 있어 전경 여부는 RESUMED로 판단한다.
    override fun onResume() { super.onResume(); graph.appVisible.value = true }
    override fun onPause() { graph.appVisible.value = false; super.onPause() }
    override fun onDestroy() { window.decorView.removeOnLayoutChangeListener(viewportListener); super.onDestroy() }

    private val askNotifications = registerForActivityResult(ActivityResultContracts.RequestPermission()) {}

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 상태 알림 한 줄과 승인 알림에 필요하다
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        window.decorView.addOnLayoutChangeListener(viewportListener)
        setContent { SmartJourneyTheme { AppRoot(graph) } }
    }
}
