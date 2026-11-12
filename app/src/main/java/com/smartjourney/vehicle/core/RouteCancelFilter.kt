package com.smartjourney.vehicle.core

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

// 우리 경로 요청 직후 이전 경로의 취소가 늦게 올 수 있는 시간. 내비가 현재 경로 상태에 답하지 않을 때만 쓴다.
internal const val ROUTE_SETTLE_MS = 3_000L
internal const val ROUTE_STATE_TIMEOUT_MS = 2_000L
// 경로 설정 중·직후에 온 운전자 취소를 방문이 받기 시작할 때까지 들고 있는 최대 시간
internal const val CANCEL_HOLD_MS = 10_000L

/**
 * `onRouteCancelled`가 운전자의 안내 종료인지 가린다.
 *
 * 우리가 경로를 넣을 때도 내비는 이전 경로의 취소를 보낸다(요청 중이나 끝난 직후). 내비는 요청 중에도 먼저 앞으로 나오므로
 * 운전자도 같은 시간대에 "안내 종료"를 누를 수 있어, 시각만으로는 둘을 구별할 수 없다. 그래서 우리 요청이 끝나기를 기다린 뒤
 * 내비에 안내 중인 경로가 있는지(`RouteStateInfo`) 묻는다. 없으면 운전자가 끝낸 것이고, 있으면 우리 경로로 바뀐 것이다.
 * 내비가 답하지 않을 때만 요청 중·직후 [ROUTE_SETTLE_MS] 안에 온 취소를 경로 교체로 본다.
 */
internal class RouteCancelFilter(
    private val scope: CoroutineScope,
    private val now: () -> Long,
    /** 지금 안내 중인 경로가 있는지. 알 수 없으면 null. */
    private val queryRouteActive: suspend () -> Boolean?,
    private val log: (String) -> Unit = {},
) {
    private val requesting = MutableStateFlow(false)
    @Volatile private var settledAt = -ROUTE_SETTLE_MS
    private val cancelledFlow = MutableSharedFlow<Unit>(extraBufferCapacity = 4)
    // 경로 요청마다 새로 만든다. 새 요청이 시작되면 그 전에 받은 취소의 상태 조회·보관 작업을 모두 끝낸다(늦은 응답이 새 방문을 닫지 않게).
    @Volatile private var generation = Job(scope.coroutineContext[Job])

    val cancelled: Flow<Unit> = cancelledFlow

    fun ownRouteRequest() = requesting.value || now() - settledAt < ROUTE_SETTLE_MS

    fun requestStarted() {
        generation.cancel()
        generation = Job(scope.coroutineContext[Job])
        requesting.value = true
    }

    fun requestFinished() {
        settledAt = now()
        requesting.value = false
    }

    fun onRouteCancelled() {
        val own = ownRouteRequest()
        scope.launch(generation) {
            requesting.first { !it }
            val active = withTimeoutOrNull(ROUTE_STATE_TIMEOUT_MS) { queryRouteActive() }
            val driver = if (active != null) !active else !own
            log("onRouteCancelled own=$own routeActive=$active driver=$driver")
            if (!driver) return@launch
            if (own) {
                // 우리 경로 설정 중·직후의 취소는 그 경로로 시작하는 방문의 것인데, 방문은 경로 설정과 내비 화면 전환이 끝난 뒤에
                // 시작된다. 그 전에 내보내면 받을 곳이 없으므로 받을 곳이 생길 때까지 든다. 새 경로 요청이 시작되면 세대와 함께 버려진다.
                withTimeoutOrNull(CANCEL_HOLD_MS) { cancelledFlow.subscriptionCount.first { it > 0 } }
            }
            cancelledFlow.tryEmit(Unit)
        }
    }
}
