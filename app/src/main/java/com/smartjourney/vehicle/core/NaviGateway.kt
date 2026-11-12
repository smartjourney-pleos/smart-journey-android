package com.smartjourney.vehicle.core

// Pleos NaviHelper SDK 3.0.5 래퍼 (스마트 저니 core-navi 모듈)
// - 길 안내·지도는 플레오스 내장 내비가 맡고, 앱은 목적지·경유지를 넣고 ETA·도착 이벤트만 받는다.
// - 화면·도메인은 이 인터페이스만 쓴다. (시연·테스트용 FakeNaviGateway로 교체 가능)

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withTimeoutOrNull

data class Place(
    val latitude: Double,
    val longitude: Double,
    val name: String,
    val poiId: String = "",
    val poiSubId: String = "",
    val address: String = "",
)

/** `onTripInfo` 값. NaviHelper API Reference(TripInfo): eta는 초, distance는 미터. */
data class NaviTrip(val eta: Int, val distance: Int, val solicited: Boolean = true)

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

    /** 운전자가 내비에서 경로를 취소했거나 목적지를 바꿨다 (`onRouteCancelled`, `onDestinationChanged`). */
    val routeChanged: Flow<Unit>

    /** [dest]까지의 trip 정보를 요청하고 그 결과를 돌려준다([trip]으로도 온다). 응답이 없으면 null. */
    suspend fun requestTrip(dest: Place): NaviTrip?

    /**
     * 목적지와 경유지(최대 2개)로 경로를 요청한다. `onRoutingCompleted`가 오면 성공이다.
     * `onRouteStarted`(탐색 시작)만 오면 뒤이은 `onError`가 없는지 잠시 확인한 뒤 성공으로 본다.
     */
    suspend fun setRoute(dest: Place, waypoints: List<Place> = emptyList()): Result<Unit>

    /** 내비 화면을 전경으로 요청한다. 지도 타일을 제공하거나 앱 안에 지도를 삽입하는 API는 아니다. */
    suspend fun showNavigation(): Result<Unit>

    fun release()
}

/** 경로 설정과 화면 전환은 별개다. 화면 요청 실패 때문에 이미 설정된 경로를 실패로 바꾸지 않는다. */
suspend fun NaviGateway.setRouteAndShowNavigation(
    dest: Place,
    waypoints: List<Place> = emptyList(),
    onDisplayFailure: (Throwable) -> Unit = {},
): Result<Unit> {
    val route = withTimeoutOrNull(20_000) { setRoute(dest, waypoints) }
        ?: Result.failure(NaviException("내비 경로 탐색 응답 없음"))
    if (route.isSuccess) {
        val display = withTimeoutOrNull(5_000) { showNavigation() }
            ?: Result.failure(NaviException("내비 화면 전환 응답 없음"))
        display.exceptionOrNull()?.let(onDisplayFailure)
    }
    return route
}
