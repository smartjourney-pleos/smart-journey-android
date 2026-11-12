package com.smartjourney.vehicle.core

import ai.pleos.playground.navi.constants.NaviErrorCode
import ai.pleos.playground.navi.constants.RouteOption
import ai.pleos.playground.navi.constants.WaypointIndex
import ai.pleos.playground.navi.data.DestinationArrivedInfo
import ai.pleos.playground.navi.data.NavigationScreenRequest
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
import android.os.SystemClock
import android.util.Log
import com.smartjourney.vehicle.BuildConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onSubscription
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withTimeoutOrNull

private const val ROUTE_TIMEOUT_MS = 15_000L
private const val TRIP_TIMEOUT_MS = 5_000L

// onRouteStarted는 "탐색 시작" 이벤트다(API Reference). 그 뒤 이 시간 안에 onError가 없으면 성공으로 본다.
// 에뮬레이터 2.1.0은 onRoutingCompleted를 보내지 않아 이 확인이 필요하다.
private const val ROUTE_STARTED_GRACE_MS = 2_000L
// 경로 요청이 끝난 뒤 이전 경로의 취소 이벤트가 늦게 올 수 있는 시간
private const val ROUTE_SETTLE_MS = 3_000L

class PleosNaviGateway(context: Context) : NaviGateway {

    private val navi = NaviHelper(context)
    private val connected = CompletableDeferred<Unit>()

    // 경로 요청은 한 번에 하나만 기다린다. onRoutingCompleted / onError가 결과를 채우고,
    // onRouteStarted는 탐색이 시작됐다는 표시만 남긴다.
    @Volatile private var pendingRoute: CompletableDeferred<Result<Unit>>? = null
    @Volatile private var pendingStarted: CompletableDeferred<Unit>? = null
    // trip 조회 실패(CAUSE_SEARCH_TRIP)가 오면 requestTrip이 타임아웃까지 기다리지 않게 한다
    @Volatile private var pendingTripError: CompletableDeferred<Unit>? = null

    private val tripFlow = MutableSharedFlow<NaviTrip>(
        replay = 1, extraBufferCapacity = 8, onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    private val arrivalFlow = MutableSharedFlow<ArrivalEvent>(extraBufferCapacity = 8)
    private val routeChangedFlow = MutableSharedFlow<Unit>(extraBufferCapacity = 4)
    private val routeCancelledFlow = MutableSharedFlow<Unit>(extraBufferCapacity = 4)
    // 우리 경로 요청이 끝난 시각. 이전 경로를 바꾸며 늦게 오는 취소 이벤트를 운전자의 조작으로 오인하지 않는다.
    @Volatile private var routeSettledAt = -ROUTE_SETTLE_MS

    override val trip: Flow<NaviTrip> = tripFlow
    override val arrivals: Flow<ArrivalEvent> = arrivalFlow
    override val routeChanged: Flow<Unit> = routeChangedFlow
    override val routeCancelled: Flow<Unit> = routeCancelledFlow

    private fun ownRouteRequest() = pendingRoute != null || SystemClock.elapsedRealtime() - routeSettledAt < ROUTE_SETTLE_MS

    private val listener = object : NaviHelperEventListener {
        override fun onTripInfo(info: TripInfo) {
            // 요청 없이 온 콜백(solicited=false)이 내비가 주는 실시간 갱신이다. 에뮬레이터 검증에 이 로그를 쓴다.
            if (BuildConfig.DEBUG) Log.i("SJ-NAVI", "onTripInfo eta=${info.eta}s distance=${info.distance}m solicited=${pendingTripError != null}")
            tripFlow.tryEmit(NaviTrip(eta = info.eta, distance = info.distance, solicited = pendingTripError != null))
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

        override fun onRouteStarted(info: RouteStartInfo) {
            pendingStarted?.complete(Unit)
        }

        // 우리가 경로를 요청하는 중이 아닐 때만 운전자의 조작으로 본다. 취소와 목적지 변경은 따로 전달한다.
        override fun onRouteCancelled() {
            val own = ownRouteRequest()
            if (BuildConfig.DEBUG) Log.i("SJ-NAVI", "onRouteCancelled own=$own")
            if (!own) routeCancelledFlow.tryEmit(Unit)
        }

        override fun onDestinationChanged() {
            val own = ownRouteRequest()
            if (BuildConfig.DEBUG) Log.i("SJ-NAVI", "onDestinationChanged own=$own")
            if (!own) routeChangedFlow.tryEmit(Unit)
        }

        override fun onError(errorCode: NaviErrorCode) {
            // trip 조회 실패는 진행 중인 경로 요청의 실패가 아니다.
            if (errorCode == NaviErrorCode.CAUSE_SEARCH_TRIP) {
                pendingTripError?.complete(Unit)
                return
            }
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
        val tripError = CompletableDeferred<Unit>().also { pendingTripError = it }
        return try {
            withTimeoutOrNull(TRIP_TIMEOUT_MS) {
                select {
                    async {
                        tripFlow
                            .onSubscription {
                                navi.requestTrip(TripRequest(dest.latitude, dest.longitude, dest.poiId, dest.name, dest.poiSubId))
                            }
                            .drop(tripFlow.replayCache.size)
                            .first()
                    }.onAwait { it }
                    tripError.onAwait { null }
                }.also { coroutineContext.cancelChildren() }
            }
        } finally {
            pendingTripError = null
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
        val started = CompletableDeferred<Unit>().also { pendingStarted = it }
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
            return withTimeoutOrNull(ROUTE_TIMEOUT_MS) {
                select {
                    result.onAwait { it }
                    started.onAwait {
                        withTimeoutOrNull(ROUTE_STARTED_GRACE_MS) { result.await() } ?: Result.success(Unit)
                    }
                }
            } ?: Result.failure(NaviException("경로 탐색 응답 없음"))
        } finally {
            routeSettledAt = SystemClock.elapsedRealtime()
            pendingRoute = null
            pendingStarted = null
        }
    }

    override suspend fun showNavigation(): Result<Unit> {
        return try {
            withTimeoutOrNull(5_000) {
                connected.await()
                navi.requestNavigationScreen(NavigationScreenRequest.Foreground)
                Result.success(Unit)
            } ?: Result.failure(NaviException("내비 화면 연결 응답 없음"))
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            Result.failure(error)
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
        address = address,
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
        address = address,
        poiSubId = poiSubId,
        poiProvider = "",
    )
}
