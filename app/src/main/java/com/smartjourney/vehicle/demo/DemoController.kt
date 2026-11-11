package com.smartjourney.vehicle.demo

import com.smartjourney.vehicle.BuildConfig
import com.smartjourney.vehicle.core.FakeVehicleGateway
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch

/**
 * 에뮬레이터에서 바꿀 수 없는 신호(좌석·도어·주행 상태·남은 시간·도착)를 손으로 넣는 데모 조작기.
 * 디자인 가이드 V-17 "데모용 신호 조작 패널"의 뒤쪽이다.
 */
class DemoController(private val scope: CoroutineScope) {

    /** true면 데모 신호, false면 실제 차량 신호(Pleos SDK)를 쓴다. 디버그 빌드 기본값 true. */
    val enabled = MutableStateFlow(BuildConfig.DEBUG)

    val vehicle = FakeVehicleGateway(occupied = 4, buckled = 4)
    val restricted = MutableStateFlow(false)

    /** 현재 목적 매장까지 남은 시간(초). 주행 중일 때만 줄어든다. */
    val remainingSec = MutableStateFlow<Int?>(null)

    /** 1초에 줄어드는 초. 60이면 실제 1초에 1분이 지나간다. */
    val speed = MutableStateFlow(60)

    private val _arrivals = MutableSharedFlow<Unit>(extraBufferCapacity = 4)
    val arrivals: SharedFlow<Unit> = _arrivals

    init {
        // 배포 빌드에서는 1초 타이머를 돌리지 않는다 (불필요한 상주·타이머 최소화)
        if (BuildConfig.DEBUG) scope.launch {
            while (true) {
                delay(1_000)
                if (!enabled.value) continue
                val sec = remainingSec.value ?: continue
                if (!restricted.value || sec <= 0) continue
                val next = (sec - speed.value).coerceAtLeast(0)
                remainingSec.value = next
                if (next == 0) _arrivals.tryEmit(Unit)
            }
        }
    }

    fun startLeg(initialSec: Int) {
        remainingSec.value = initialSec
    }

    fun addRemaining(deltaSec: Int) {
        remainingSec.value = ((remainingSec.value ?: return) + deltaSec).coerceAtLeast(60)
    }

    fun arrive() {
        remainingSec.value = 0
        _arrivals.tryEmit(Unit)
    }

    /** 여정을 시작하면 잠시 뒤 출발한다. 앱이 내비 뒤로 가도 시연이 이어지게 하려는 것이다. */
    fun onJourneyStarted() {
        scope.launch {
            delay(2_000)
            restricted.value = true
        }
    }

    /** 하차 장면: 정차 → 문 열림 → [leaving]명 하차 → 문 닫힘 */
    fun playAlight(leaving: Int) = scope.launch {
        restricted.value = false
        delay(1_500)
        vehicle.setDoorOpen(true)
        delay(2_000)
        vehicle.setOccupied((vehicle.occupied.value - leaving).coerceAtLeast(0))
        delay(1_500)
        vehicle.setDoorOpen(false)
    }

    /** 재탑승 장면: 문 열림 → 탑승 인원이 [total]명이 됨 → 문 닫힘 */
    fun playBoard(total: Int) = scope.launch {
        restricted.value = false
        vehicle.setDoorOpen(true)
        delay(2_000)
        vehicle.setOccupied(total)
        delay(1_500)
        vehicle.setDoorOpen(false)
    }
}
