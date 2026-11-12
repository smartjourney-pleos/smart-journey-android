package com.smartjourney.vehicle.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.smartjourney.vehicle.BuildConfig
import com.smartjourney.vehicle.engine.statusLine
import com.smartjourney.vehicle.graph
import kotlinx.coroutines.launch

/**
 * 데모 패널과 같은 조작을 터미널에서 한다. 앱이 내비 뒤에 있을 때 시연·확인용. 디버그 빌드 전용.
 *
 *   adb shell am broadcast --user current -n com.smartjourney.vehicle/.debug.DemoReceiver --es cmd <명령> [--ei n <값>]
 *
 * 명령: consent, start(n=매장 수), prompt(지금 걸기), approve, defer, drive, stop, remaining(n=분), speed(n=초/초), arrive,
 *       door(n=1 열림/0 닫힘), seats(n=명), alight(n=내리는 인원), board(n=탑승 인원), walkin(n=±팀),
 *       call, seat, arrived(수동 도착), mode(n=1 데모/0 실제), eta(n=1 실제 내비/0 데모), server(n=1 서버 연동/0 앱 안 데이터), limit(n=분), end, state
 */
class DemoReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (!BuildConfig.DEBUG) return
        val g = context.graph
        val n = intent.getIntExtra("n", 1)
        val current = g.engine.state.value.current
        val cmd = intent.getStringExtra("cmd")
        when (cmd) {
            "consent" -> g.setConsent(true)
            "start" -> g.scope.launch {
                val result = g.startJourney(partySize = g.demo.vehicle.occupied.value, stops = g.backend.stores().take(n))
                Log.i(TAG, "start → $result")
            }
            "prompt" -> g.engine.promptNow()
            "approve" -> g.engine.approve()
            "defer" -> g.engine.defer()
            "drive" -> g.demo.restricted.value = true
            "stop" -> g.demo.restricted.value = false
            "remaining" -> g.demo.remainingSec.value = n * 60
            "speed" -> g.demo.speed.value = n
            "arrive" -> g.demo.arrive()
            "door" -> g.demo.vehicle.setDoorOpen(n == 1)
            "seats" -> g.demo.vehicle.setOccupied(n)
            "alight" -> g.demo.playAlight(n)
            "board" -> g.demo.playBoard(n)
            "walkin" -> g.backend.fake.addWalkIn(current?.store?.id ?: 1, n)
            "call" -> current?.waiting?.let { g.backend.fake.call(it.waitingId) }
            "seat" -> current?.waiting?.let { g.backend.fake.seat(it.waitingId) }
            "server" -> Log.i(TAG, "server → ${g.setUseServer(n == 1)}")
            "arrived" -> g.engine.confirmManualArrival()
            "mode" -> g.demo.enabled.value = n == 1
            "eta" -> g.demo.realEta.value = n == 1
            "limit" -> g.setWaitLimit(n)
            "end" -> g.engine.end()
        }
        val s = g.engine.state.value
        Log.i(TAG, "$cmd → ${s.statusLine()} | prompt=${s.prompt?.kind} promptId=${s.prompt?.id} submission=${s.current?.submission} alight=${s.alight} restricted=${s.restricted}")
    }

    private companion object {
        const val TAG = "SJ-DEMO"
    }
}
