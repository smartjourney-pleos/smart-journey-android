package com.smartjourney.vehicle.core

import ai.pleos.playground.navi.constants.NaviErrorCode
import ai.pleos.playground.navi.constants.NaviMode
import ai.pleos.playground.navi.constants.NavigationStatus
import ai.pleos.playground.navi.constants.RouteOption
import ai.pleos.playground.navi.constants.WaypointIndex
import ai.pleos.playground.navi.data.DestinationArrivedInfo
import ai.pleos.playground.navi.data.DestinationInfo
import ai.pleos.playground.navi.data.NavigationScreenRequest
import ai.pleos.playground.navi.data.RequestWaypointInfo
import ai.pleos.playground.navi.data.RouteInfo
import ai.pleos.playground.navi.data.RouteStartInfo
import ai.pleos.playground.navi.data.RouteStateInfo
import ai.pleos.playground.navi.data.RouteWithWaypointsRequest
import ai.pleos.playground.navi.data.TripInfo
import ai.pleos.playground.navi.data.TripRequest
import ai.pleos.playground.navi.data.WaypointArrivedInfo
import ai.pleos.playground.navi.helper.NaviHelper
import ai.pleos.playground.navi.helper.listener.NaviHelperEventListener
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.util.Log
import com.smartjourney.vehicle.BuildConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onSubscription
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withTimeoutOrNull

private const val ROUTE_TIMEOUT_MS = 15_000L
private const val TRIP_TIMEOUT_MS = 5_000L

// onRoutingCompleted가 없는 내비는 탐색 시작 뒤 경로 상태와 목적지를 조회해 확인한다.
private const val ROUTE_STARTED_GRACE_MS = 2_000L

class PleosNaviGateway(private val context: Context) : NaviGateway {

    private val navi = NaviHelper(context)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val connected = CompletableDeferred<Unit>()
    private val destinationFlow = MutableSharedFlow<DestinationInfo>(extraBufferCapacity = 4)

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
    private val routeStateFlow = MutableSharedFlow<Boolean>(extraBufferCapacity = 4)
    // 이전 경로를 바꾸며 오는 취소 이벤트를 운전자의 "안내 종료"로 오인하지 않는다.
    private val cancelFilter = RouteCancelFilter(scope, SystemClock::elapsedRealtime, ::routeActive) {
        if (BuildConfig.DEBUG) Log.i("SJ-NAVI", it)
    }

    override val trip: Flow<NaviTrip> = tripFlow
    override val arrivals: Flow<ArrivalEvent> = arrivalFlow
    override val routeChanged: Flow<Unit> = routeChangedFlow
    override val routeCancelled: Flow<Unit> = cancelFilter.cancelled

    private val listener = object : NaviHelperEventListener {
        override fun onNavigationStatus(status: NavigationStatus) {
            if (BuildConfig.DEBUG) Log.i("SJ-NAVI", "onNavigationStatus $status")
        }

        override fun onDestinationInfo(info: DestinationInfo) {
            destinationFlow.tryEmit(info)
        }

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

        override fun onRouteCancelled() = cancelFilter.onRouteCancelled()

        // 우리가 요청하지 않은 응답(다른 앱의 조회)도 현재 상태라 같이 받는다.
        // isRouting은 활성 경로 유무지만 에뮬레이터 내비는 안내 중에도 false만 보낸다. 경로 안내 모드(naviMode)도 함께 본다.
        override fun onRouteStateInfo(routeStateInfo: RouteStateInfo) {
            if (BuildConfig.DEBUG) Log.i("SJ-NAVI", "onRouteStateInfo isRouting=${routeStateInfo.isRouting} naviMode=${routeStateInfo.naviMode}")
            val guiding = routeStateInfo.naviMode == NaviMode.ROUTE_GUIDING || routeStateInfo.naviMode == NaviMode.SIMULATION_GUIDING
            routeStateFlow.tryEmit(routeStateInfo.isRouting || guiding)
        }

        // 목적지 변경은 경로 교체 때도 활성 경로가 남아 상태 조회로 가릴 수 없다. 우리가 경로를 요청하는 중·직후가 아닐 때만 전달한다.
        override fun onDestinationChanged() {
            val own = cancelFilter.ownRouteRequest()
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
        cancelFilter.requestStarted()
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
                        withTimeoutOrNull(ROUTE_STARTED_GRACE_MS) { result.await() }
                            ?: verifyRoute(dest)
                    }
                }
            } ?: Result.failure(NaviException("경로 탐색 응답 없음"))
        } finally {
            pendingRoute = null
            pendingStarted = null
            cancelFilter.requestFinished()
        }
    }

    override suspend fun showNavigation(): Result<Unit> {
        return try {
            withTimeoutOrNull(5_000) {
                connected.await()
                // 이 내비는 지도 초기화 전 SDK 화면 요청도 무시한다. 공개 LAUNCHER 진입점으로
                // 초기화를 시작한다. 경로/좌표는 Intent로 주입하지 않고 아래 SDK로만 요청한다.
                context.packageManager.getLaunchIntentForPackage("ai.umos.maps.android.navigation.app")?.let { launch ->
                    context.startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }
                navi.requestNavigationScreen(NavigationScreenRequest.Foreground)
                Result.success(Unit)
            } ?: Result.failure(NaviException("내비 화면 연결 응답 없음"))
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            Result.failure(error)
        }
    }

    override suspend fun awaitReady(dest: Place): Result<Unit> = try {
        connected.await()
        // 최초 상태 콜백을 보내지 않는 내비도 있다. 읽기 전용 조회에 새 응답이 와야 준비됐다고 본다.
        // 시간 제한은 호출자(setRouteAndShowNavigation)가 적용하며, 경로 변경 요청은 반복하지 않는다.
        while (requestTrip(dest) == null) delay(500)
        Result.success(Unit)
    } catch (error: CancellationException) {
        throw error
    } catch (error: Exception) {
        Result.failure(error)
    }

    private suspend fun verifyRoute(dest: Place): Result<Unit> {
        val confirmed = withTimeoutOrNull(4_000) {
            coroutineScope {
                val active = async { routeActive() }
                val destination = async {
                    destinationFlow.onSubscription { navi.getDestinationInfo() }.first()
                }
                val info = destination.await()
                active.await() == true && destinationMatches(dest,
                    Place(info.latitude, info.longitude, info.name, info.poiId, info.poiSubId.orEmpty(), info.address))
            }
        } == true
        if (BuildConfig.DEBUG) Log.i("SJ-NAVI", "route state and destination confirmed=$confirmed")
        return if (confirmed) Result.success(Unit) else Result.failure(NaviException("내비 경로와 목적지를 확인하지 못했어요"))
    }

    /** 내비에 지금 안내 중인 경로가 있는지 묻는다. 응답은 onRouteStateInfo로 온다. 조회하지 못하면 null. */
    private suspend fun routeActive(): Boolean? = try {
        routeStateFlow.onSubscription { navi.getRouteStateInfo() }.first()
    } catch (error: CancellationException) {
        throw error
    } catch (error: Exception) {
        null
    }

    override fun release() {
        scope.cancel()
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
