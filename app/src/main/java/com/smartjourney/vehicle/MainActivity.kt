package com.smartjourney.vehicle

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import com.smartjourney.vehicle.ui.AppRoot
import com.smartjourney.vehicle.ui.SmartJourneyTheme

/** 정차 중에 여는 화면: 동의, 인원 확인, 방문지 고르기, 여정 시작, 진행 현황. */
class MainActivity : ComponentActivity() {

    private val askNotifications = registerForActivityResult(ActivityResultContracts.RequestPermission()) {}

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 상태 알림 한 줄과 승인 알림에 필요하다
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        setContent { SmartJourneyTheme { AppRoot(graph) } }
    }
}
