package com.smartjourney.vehicle.core

import ai.pleos.playground.navi.constants.NaviErrorCode
import ai.pleos.playground.navi.constants.RouteOption
import ai.pleos.playground.navi.constants.WaypointIndex
import ai.pleos.playground.navi.data.DestinationArrivedInfo
import ai.pleos.playground.navi.data.RequestWaypointInfo
import ai.pleos.playground.navi.data.RouteInfo
import ai.pleos.playground.navi.data.RouteStartInfo
import ai.pleos.playground.navi.data.RouteWithWaypointsRequest
import ai.pleos.playground.navi.data.TripInfo
import ai.pleos.playground.navi.data.TripRequest
import ai.pleos.playground.navi.data.WaypointArrivedInfo
import ai.pleos.playground.navi.helper.NaviHelper
import ai.pleos.playground.navi.helper.listener.NaviHelperEventListener
import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onSubscription
import kotlinx.coroutines.withTimeoutOrNull

private const val ROUTE_TIMEOUT_MS = 15_000L
private const val TRIP_TIMEOUT_MS = 5_000L

class PleosNaviGateway(context: Context) : NaviGateway {

    private val navi = NaviHelper(context)
    private val connected = CompletableDeferred<Unit>()

    // 경로 요청은 한 번에 하나만 기다린다. onRoutingCompleted·onRouteStarted / onError가 결과를 채운다.
    @Volatile private var pendingRoute: CompletableDeferred<Result<Unit>>? = null

    private val tripFlow = MutableSharedFlow<NaviTrip>(
        replay = 1, extraBufferCapacity = 8, onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    private val arrivalFlow = MutableSharedFlow<ArrivalEvent>(extraBufferCapacity = 8)

    override val trip: Flow<NaviTrip> = tripFlow
    override val arrivals: Flow<ArrivalEvent> = arrivalFlow

    private val listener = object : NaviHelperEventListener {
        override fun onTripInfo(info: TripInfo) {
            tripFlow.tryEmit(NaviTrip(eta = info.eta, distance = info.distance))
        }

        override fun onDestinationArrived(info: DestinationArrivedInfo) {
            arrivalFlow.tryEmit(ArrivalEvent.Destination(info.name))
        }

        override fun onWayPointArrived(info: WaypointArrivedInfo) {
            val order = if (info.index == WaypointIndex.FIRST) 1 else 2
            arrivalFlow.tryEmit(ArrivalEvent.Waypoint(order, info.name))
        }

        override fun onRoutingCompleted() {
            pendingRoute?.complete(Result.success(Unit))
        }

        // Pleos Connect 에뮬레이터 2.1.0은 onRoutingCompleted 없이 onRouteStarted만 보낸다. (경로 계산 뒤 안내 시작)
        override fun onRouteStarted(info: RouteStartInfo) {
            pendingRoute?.complete(Result.success(Unit))
        }

        override fun onError(errorCode: NaviErrorCode) {
            // trip 조회 실패는 진행 중인 경로 요청의 실패가 아니다.
            if (errorCode == NaviErrorCode.CAUSE_SEARCH_TRIP) return
            pendingRoute?.complete(Result.failure(NaviException(errorCode.name)))
        }
    }

    init {
        navi.initialize(
            onConnected = {
                navi.addListener(listener)
                connected.complete(Unit)
            },
            onFailed = { connected.completeExceptionally(it) },
        )
    }

    override suspend fun requestTrip(dest: Place): NaviTrip? {
        connected.await()
        // 구독을 먼저 걸고 요청한다. 기억해 둔 예전 값(replay)은 건너뛰고 새로 온 값만 받는다.
        return withTimeoutOrNull(TRIP_TIMEOUT_MS) {
            tripFlow
                .onSubscription {
                    navi.requestTrip(TripRequest(dest.latitude, dest.longitude, dest.poiId, dest.name, dest.poiSubId))
                }
                .drop(tripFlow.replayCache.size)
                .first()
        }
    }

    override suspend fun setRoute(dest: Place, waypoints: List<Place>): Result<Unit> {
        if (waypoints.size > 2) return Result.failure(IllegalArgumentException("경유지는 최대 2개"))
        try {
            connected.await()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return Result.failure(e)
        }

        val result = CompletableDeferred<Result<Unit>>().also { pendingRoute = it }
        try {
            if (waypoints.isEmpty()) {
                navi.requestRoute(dest.toRouteInfo())
            } else {
                val indexes = listOf(WaypointIndex.FIRST, WaypointIndex.SECOND)
                navi.requestRouteWithWaypoints(
                    RouteWithWaypointsRequest(
                        destination = dest.toRouteInfo(),
                        waypoints = waypoints.mapIndexed { i, p -> p.toWaypoint(indexes[i]) },
                    ),
                )
            }
            return withTimeoutOrNull(ROUTE_TIMEOUT_MS) { result.await() }
                ?: Result.failure(NaviException("경로 탐색 응답 없음"))
        } finally {
            pendingRoute = null
        }
    }

    override fun release() {
        navi.removeListener(listener)
        navi.release()
    }

    private fun Place.toRouteInfo() = RouteInfo(
        latitude = latitude,
        longitude = longitude,
        displayLatitude = null,
        displayLongitude = null,
        poiName = name,
        poiId = poiId,
        address = "",
        poiSubId = poiSubId,
        routeOption = RouteOption.RECOMMENDED,
        poiProvider = "",
        isMobileRequest = false,
    )

    private fun Place.toWaypoint(index: WaypointIndex) = RequestWaypointInfo(
        latitude = latitude,
        longitude = longitude,
        poiName = name,
        waypointIndex = index,
        poiId = poiId,
        address = "",
        poiSubId = poiSubId,
        poiProvider = "",
    )
}
