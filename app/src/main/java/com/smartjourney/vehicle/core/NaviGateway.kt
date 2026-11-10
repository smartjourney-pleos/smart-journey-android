package com.smartjourney.vehicle.core

// Pleos NaviHelper SDK 3.0.5 래퍼 (스마트 저니 core-navi 모듈)
// - 길 안내·지도는 플레오스 내장 내비가 맡고, 앱은 목적지·경유지를 넣고 ETA·도착 이벤트만 받는다.
// - 화면·도메인은 이 인터페이스만 쓴다. (시연·테스트용 FakeNaviGateway로 교체 가능)

import kotlinx.coroutines.flow.Flow

data class Place(
    val latitude: Double,
    val longitude: Double,
    val name: String,
    val poiId: String = "",
    val poiSubId: String = "",
)

/**
 * `onTripInfo` 값. eta·distance의 단위는 문서에 없어 SDK 값을 그대로 둔다.
 * ponytail: 에뮬레이터에서 단위를 확인한 뒤 remainingMin 같은 변환 필드를 여기에 추가.
 */
data class NaviTrip(val eta: Int, val distance: Int)

sealed interface ArrivalEvent {
    data class Destination(val name: String) : ArrivalEvent
    /** [order]는 경유지 순서(1 또는 2). */
    data class Waypoint(val order: Int, val name: String) : ArrivalEvent
}

class NaviException(message: String) : Exception(message)

interface NaviGateway {
    /** `requestTrip` 이후 `onTripInfo`로 오는 값. 마지막 값을 기억한다. */
    val trip: Flow<NaviTrip>
    val arrivals: Flow<ArrivalEvent>

    /** [dest]까지의 trip 정보를 요청한다. 결과는 [trip]으로 온다. */
    suspend fun requestTrip(dest: Place)

    /** 목적지와 경유지(최대 2개)로 경로를 요청한다. 탐색 완료(`onRoutingCompleted`)까지 기다린다. */
    suspend fun setRoute(dest: Place, waypoints: List<Place> = emptyList()): Result<Unit>

    fun release()
}
