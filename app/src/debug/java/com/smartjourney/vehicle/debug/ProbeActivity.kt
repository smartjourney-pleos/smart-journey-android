package com.smartjourney.vehicle.debug

import android.content.Intent
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import com.smartjourney.vehicle.core.NaviGateway
import com.smartjourney.vehicle.core.NaviGatewayFactory
import com.smartjourney.vehicle.core.Place
import com.smartjourney.vehicle.core.VehicleGateway
import com.smartjourney.vehicle.core.VehicleGatewayFactory
import kotlinx.coroutines.launch

/**
 * 에뮬레이터에서 Vehicle·Navi 신호를 로그(태그 PROBE)로 확인하는 디버그 전용 Activity.
 *
 *   adb shell am start -n com.smartjourney.vehicle/.debug.ProbeActivity
 *   adb shell am start -n com.smartjourney.vehicle/.debug.ProbeActivity --es cmd trip|route|map [--ed lat 37.5 --ed lng 127.0 --ed wlat .. --ed wlng ..]
 */
class ProbeActivity : ComponentActivity() {

    private lateinit var vehicle: VehicleGateway
    private lateinit var navi: NaviGateway

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        vehicle = VehicleGatewayFactory.create(this)
        navi = NaviGatewayFactory.create(this)
        log("gateways vehicle=${vehicle::class.simpleName} navi=${navi::class.simpleName}")

        lifecycleScope.launch {
            val caps = vehicle.capabilities()
            log("caps seats=${caps.seatAreas.size} belts=${caps.beltAreas.size} doors=${caps.doorClosedValue.size}")
            launch { vehicle.occupiedCount(caps).collect { log("occupied=$it") } }
            launch { vehicle.buckledCount(caps).collect { log("buckled=$it") } }
            launch { vehicle.anyDoorOpen(caps).collect { log("doorOpen=$it") } }
            launch { navi.trip.collect { log("trip=$it") } }
            launch { navi.arrivals.collect { log("arrival=$it") } }
        }
        handle(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle(intent)
    }

    private fun handle(i: Intent) {
        val cmd = i.getStringExtra("cmd") ?: return
        // 기본값: 서울시청 → 경유지 광화문
        val dest = Place(i.getDoubleExtra("lat", 37.5663), i.getDoubleExtra("lng", 126.9779), "목적지")
        val waypoints = if (i.hasExtra("wlat")) {
            listOf(Place(i.getDoubleExtra("wlat", 0.0), i.getDoubleExtra("wlng", 0.0), "경유지"))
        } else {
            emptyList()
        }
        lifecycleScope.launch {
            log("cmd=$cmd")
            when (cmd) {
                "trip" -> runCatching { navi.requestTrip(dest) }.onFailure { log("trip 요청 실패: $it") }
                "route" -> log("route 결과=${navi.setRoute(dest, waypoints)}")
                "map" -> log("내비 화면 전경 요청=${navi.showNavigation()}")
            }
        }
    }

    override fun onDestroy() {
        navi.release()
        vehicle.release()
        super.onDestroy()
    }

    private fun log(msg: String) = Log.i("PROBE", msg)
}
