package com.smartjourney.vehicle

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.smartjourney.vehicle.core.VehicleGateway
import com.smartjourney.vehicle.core.VehicleGatewayFactory

class MainActivity : ComponentActivity() {

    private lateinit var gateway: VehicleGateway

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        gateway = VehicleGatewayFactory.create(this)
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    SignalScreen(gateway)
                }
            }
        }
    }

    override fun onDestroy() {
        gateway.release()
        super.onDestroy()
    }
}

/** 차량 신호 연동 확인용 임시 화면. 이후 정차 화면/승인 카드로 대체한다. */
@androidx.compose.runtime.Composable
private fun SignalScreen(gateway: VehicleGateway) {
    var occupied by remember { mutableStateOf<Int?>(null) }
    var doorOpen by remember { mutableStateOf<Boolean?>(null) }

    LaunchedEffect(gateway) {
        val caps = gateway.capabilities()
        val snapshot = gateway.readNow(caps)
        occupied = snapshot.occupied
        doorOpen = snapshot.anyDoorOpen
    }

    Column(
        modifier = Modifier.padding(32.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("스마트 저니", style = MaterialTheme.typography.headlineMedium)
        Text("탑승 인원(좌석 점유): ${occupied ?: "확인 중"}")
        Text("도어 열림: ${doorOpen ?: "확인 중"}")
    }
}
