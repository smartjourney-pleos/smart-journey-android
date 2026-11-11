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
 * `onTripInfo` 값. 문서에는 단위가 없다. 에뮬레이터 2.1.0에서 같은 구간의 내비 엔진 로그
 * (reachTime 1965 / reachDist 21895)와 크기를 대조해 eta=초, distance=미터로 본다.
 * ponytail: 실차에서 단위가 다르면 이 두 필드의 변환만 고친다.
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

    /** [dest]까지의 trip 정보를 요청하고 그 결과를 돌려준다([trip]으로도 온다). 응답이 없으면 null. */
    suspend fun requestTrip(dest: Place): NaviTrip?

    /** 목적지와 경유지(최대 2개)로 경로를 요청한다. 경로가 잡힐 때(`onRoutingCompleted` 또는 `onRouteStarted`)까지 기다린다. */
    suspend fun setRoute(dest: Place, waypoints: List<Place> = emptyList()): Result<Unit>

    fun release()
}
