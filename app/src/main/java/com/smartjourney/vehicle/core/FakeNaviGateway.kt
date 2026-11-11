package com.smartjourney.vehicle.core

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow

/** 에뮬레이터 내비가 불안정하거나 Pleos 서비스가 없는 환경(시연·테스트)용 가짜 구현. */
class FakeNaviGateway : NaviGateway {

    private val tripFlow = MutableSharedFlow<NaviTrip>(replay = 1)
    private val arrivalFlow = MutableSharedFlow<ArrivalEvent>(extraBufferCapacity = 8)

    override val trip: Flow<NaviTrip> = tripFlow
    override val arrivals: Flow<ArrivalEvent> = arrivalFlow

    /** 마지막으로 요청받은 경로. 테스트에서 확인용. */
    var lastRoute: Pair<Place, List<Place>>? = null
        private set
    var routeResult: Result<Unit> = Result.success(Unit)

    fun emitTrip(eta: Int, distance: Int = 0) { tripFlow.tryEmit(NaviTrip(eta, distance)) }
    fun emitArrival(event: ArrivalEvent) { arrivalFlow.tryEmit(event) }

    override suspend fun requestTrip(dest: Place): NaviTrip? = tripFlow.replayCache.lastOrNull()

    override suspend fun setRoute(dest: Place, waypoints: List<Place>): Result<Unit> {
        if (waypoints.size > 2) return Result.failure(IllegalArgumentException("경유지는 최대 2개"))
        lastRoute = dest to waypoints
        return routeResult
    }

    override fun release() = Unit
}
