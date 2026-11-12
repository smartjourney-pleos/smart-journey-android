package com.smartjourney.vehicle.core

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 순정 내비 `onRouteCancelled`가 운전자의 "안내 종료"인지, 우리가 경로를 바꾸며 생긴 이전 경로의 취소인지 가르는 경계.
 * QA 재현(v0.2.8): 경로 설정이 끝나고 약 1.081초 뒤 운전자가 안내 종료 → 예전 3초 무시 구간에 걸려 방문이 닫히지 않았다.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RouteCancelFilterTest {

    /** 내비가 답할 안내 중 경로 유무. null이면 답하지 않는다. */
    private var routing: Boolean? = false
    private var queries = 0
    private var received = 0

    private fun TestScope.filter(subscribe: Boolean = true) = RouteCancelFilter(
        scope = backgroundScope,
        now = { testScheduler.currentTime },
        queryRouteActive = {
            queries++
            routing ?: run { delay(ROUTE_STATE_TIMEOUT_MS * 10); null }
        },
    ).also { if (subscribe) listen(it) }

    private fun TestScope.listen(filter: RouteCancelFilter) {
        backgroundScope.launch { filter.cancelled.collect { received++ } }
        runCurrent()
    }

    private fun TestScope.settleRoute(filter: RouteCancelFilter) {
        filter.requestStarted()
        advanceTimeBy(5_000)
        filter.requestFinished()
    }

    @Test
    fun 경로_설정_1081ms_뒤_안내_종료는_활성_경로가_없으면_전달된다() = runTest {
        val filter = filter()
        settleRoute(filter)
        advanceTimeBy(1_081)

        routing = false
        filter.onRouteCancelled()
        runCurrent()

        assertEquals(1, received)
    }

    @Test
    fun 경로_설정_직후_이전_경로의_늦은_취소는_새_경로가_활성이면_무시한다() = runTest {
        val filter = filter()
        settleRoute(filter)
        advanceTimeBy(1)

        routing = true
        filter.onRouteCancelled()
        runCurrent()

        assertEquals(0, received)
    }

    @Test
    fun 경로_설정_중_취소는_설정이_끝난_뒤에_상태를_묻는다() = runTest {
        val filter = filter()
        filter.requestStarted()
        routing = false
        filter.onRouteCancelled()
        runCurrent()
        assertEquals("요청 중에는 교체 중인 상태라 묻지 않는다", 0, queries)

        advanceTimeBy(4_000)
        filter.requestFinished()
        runCurrent()

        assertEquals(1, queries)
        assertEquals(1, received)
    }

    @Test
    fun 경로_설정_중_이전_경로의_취소는_새_경로가_활성이면_무시한다() = runTest {
        val filter = filter()
        filter.requestStarted()
        filter.onRouteCancelled()
        advanceTimeBy(4_000)
        routing = true
        filter.requestFinished()
        runCurrent()

        assertEquals(0, received)
    }

    @Test
    fun 설정_중_운전자_취소는_방문이_늦게_구독해도_전달된다() = runTest {
        val filter = filter(subscribe = false)
        filter.requestStarted()
        filter.onRouteCancelled()
        filter.requestFinished()
        advanceTimeBy(500) // 내비 화면 전환·여정 생성 뒤 엔진 시작

        listen(filter)
        runCurrent()

        assertEquals(1, received)
    }

    @Test
    fun 들고_있던_취소는_새_경로_요청이_시작되면_버린다() = runTest {
        val filter = filter(subscribe = false)
        filter.requestStarted()
        filter.onRouteCancelled()
        filter.requestFinished()
        runCurrent()

        filter.requestStarted() // 방문 시작 실패 뒤 다시 시도
        runCurrent()
        listen(filter)
        filter.requestFinished()
        runCurrent()

        assertEquals(0, received)
    }

    @Test
    fun 이전_요청의_상태_조회가_늦게_끝나도_새_요청이_시작됐으면_버린다() = runTest {
        // Codex 독립 QA(RouteCancelGenerationQaTest): A 완료 → A 취소 조회 대기 → B 요청·완료 → A의 늦은 "경로 없음" 응답
        val answer = CompletableDeferred<Boolean?>()
        val filter = RouteCancelFilter(backgroundScope, { testScheduler.currentTime }, { answer.await() })
        listen(filter)
        filter.requestStarted()
        filter.requestFinished()
        filter.onRouteCancelled()
        runCurrent()

        filter.requestStarted()
        filter.requestFinished()
        answer.complete(false)
        runCurrent()

        assertEquals(0, received)
    }

    @Test
    fun 새_요청이_시작된_뒤_온_취소는_그_요청의_세대로_처리된다() = runTest {
        val filter = filter()
        settleRoute(filter)
        filter.requestStarted()
        filter.onRouteCancelled() // B 요청 중에 온 취소(이전 경로 교체 또는 운전자)
        routing = false
        filter.requestFinished()
        runCurrent()

        assertEquals("B가 끝난 뒤 경로가 없으면 B의 방문에 전달돼야 한다", 1, received)
    }

    @Test
    fun 평소의_안내_종료는_바로_전달된다() = runTest {
        val filter = filter()
        filter.onRouteCancelled()
        runCurrent()

        assertEquals(1, received)
    }

    @Test
    fun 내비가_답하지_않으면_설정_직후_3초_안의_취소만_경로_교체로_본다() = runTest {
        routing = null
        val filter = filter()
        settleRoute(filter)

        advanceTimeBy(ROUTE_SETTLE_MS - 1)
        filter.onRouteCancelled()
        advanceTimeBy(ROUTE_STATE_TIMEOUT_MS + 1)
        assertEquals(0, received)

        settleRoute(filter)
        advanceTimeBy(ROUTE_SETTLE_MS)
        filter.onRouteCancelled()
        advanceTimeBy(ROUTE_STATE_TIMEOUT_MS + 1)
        assertEquals(1, received)
        assertTrue(queries >= 2)
    }
}
