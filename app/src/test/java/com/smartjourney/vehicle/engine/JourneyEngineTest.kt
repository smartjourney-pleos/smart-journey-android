package com.smartjourney.vehicle.engine

import com.smartjourney.vehicle.core.VehicleSnapshot
import com.smartjourney.vehicle.data.FakeBackend
import com.smartjourney.vehicle.data.InMemoryJourneyStorage
import com.smartjourney.vehicle.domain.ArrivalResult
import com.smartjourney.vehicle.domain.Store
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class JourneyEngineTest {

    private class FakeSignals : JourneySignals {
        val occupiedNow = MutableStateFlow(4)
        val doorOpen = MutableStateFlow(false)
        val driving = MutableStateFlow(true)
        val remaining = MutableStateFlow(30 * 60)
        val arrive = MutableSharedFlow<Arrival>(extraBufferCapacity = 1)

        override val occupied: Flow<Int> = occupiedNow
        override val anyDoorOpen: Flow<Boolean> = doorOpen
        override val restricted: Flow<Boolean> = driving
        override val arrivals: Flow<Arrival> = arrive
        override val routeChanged: Flow<Unit> = emptyFlow()
        override fun remainingSec(target: Store): Flow<Int> = remaining
        override suspend fun sensorsSupported() = true
        override suspend fun readNow() = VehicleSnapshot(occupiedNow.value, null, doorOpen.value)
    }

    private val signals = FakeSignals()
    private val backend = FakeBackend() // 첫 장소: 현장 6팀 × 5분 = 대기 30분, 도착 여유 10분

    private suspend fun TestScope.startedEngine(stores: Int = 1): JourneyEngine {
        val engine = JourneyEngine(signals, backend, backgroundScope, now = { testScheduler.currentTime })
        engine.start(partySize = 4, stops = backend.stores().take(stores))
        runCurrent()
        return engine
    }

    @Test
    fun 골든타임_승인부터_하차_확인까지_이어진다() = runTest {
        val engine = startedEngine()
        assertEquals(StopPhase.PLANNED, engine.state.value.current?.phase)
        assertEquals("남은 30분, target 20분", 10, engine.state.value.minutesUntilPrompt)

        signals.remaining.value = 20 * 60
        runCurrent()
        assertEquals(StopPhase.PROMPTED, engine.state.value.current?.phase)
        assertEquals(Prompt.Kind.GOLDEN_TIME, engine.state.value.prompt?.kind)

        engine.approve()
        runCurrent()
        assertEquals(StopPhase.REGISTERED, engine.state.value.current?.phase)
        assertEquals(4, engine.state.value.current?.waiting?.partySize)
        assertNull(engine.state.value.prompt)

        // 도착 → 정차 → 문 열림 → 1명 남고 문 닫힘 → 5초 유지
        signals.remaining.value = 0
        signals.arrive.tryEmit(Arrival.Current)
        runCurrent()
        signals.driving.value = false
        signals.doorOpen.value = true
        runCurrent()
        signals.occupiedNow.value = 1
        signals.doorOpen.value = false
        runCurrent()
        advanceTimeBy(4_000)
        runCurrent()
        assertEquals("5초 전에는 확정하지 않는다", StopPhase.REGISTERED, engine.state.value.current?.phase)
        advanceTimeBy(3_000)
        runCurrent()

        val stop = engine.state.value.current!!
        assertEquals(StopPhase.ARRIVED, stop.phase)
        assertEquals(ArrivalResult.CONFIRMED, stop.arrival)
        assertEquals(3, stop.alighted)
        assertTrue(backend.received.value.any { "3/4명 하차" in it })
        assertEquals("${backend.stores()[0].name} · 3/4명 하차 확인", engine.state.value.statusLine())
    }

    @Test
    fun 승인_없이는_접수하지_않고_60초_뒤_카드를_닫는다() = runTest {
        val engine = startedEngine()
        signals.remaining.value = 20 * 60
        runCurrent()
        advanceTimeBy(61_000)
        runCurrent()

        assertEquals(StopPhase.PLANNED, engine.state.value.current?.phase)
        assertNull(engine.state.value.prompt)
        assertTrue("매장에 간 정보가 없어야 한다", backend.received.value.isEmpty())

        // 1분 더 줄어든 것만으로는 다시 묻지 않는다
        signals.remaining.value = 19 * 60
        runCurrent()
        assertNull(engine.state.value.prompt)
    }

    @Test
    fun 현장_대기가_늘면_알림_시점이_앞당겨진다() = runTest {
        val engine = startedEngine()
        assertEquals(10, engine.state.value.minutesUntilPrompt)

        backend.addWalkIn(storeId = 1, delta = 1) // 대기 35분 → target 25분
        runCurrent()
        assertEquals(5, engine.state.value.minutesUntilPrompt)

        backend.addWalkIn(storeId = 1, delta = 1) // 대기 40분 → target 30분 = 남은 시간
        runCurrent()
        assertEquals(Prompt.Kind.GOLDEN_TIME, engine.state.value.prompt?.kind)
    }

    @Test
    fun 호출과_착석이_오면_다음_매장으로_넘어간다() = runTest {
        val engine = startedEngine(stores = 2)
        signals.remaining.value = 20 * 60
        runCurrent()
        engine.approve()
        runCurrent()
        val waitingId = engine.state.value.current!!.waiting!!.waitingId

        backend.call(waitingId)
        runCurrent()
        assertEquals(StopPhase.CALLED, engine.state.value.current?.phase)
        assertEquals(Prompt.Kind.CALLED, engine.state.value.prompt?.kind)

        backend.seat(waitingId)
        runCurrent()
        assertEquals(1, engine.state.value.index)
        assertEquals(backend.stores()[1].name, engine.state.value.current?.store?.name)
        assertEquals(StopPhase.SEATED, engine.state.value.stops[0].phase)
        assertTrue(engine.state.value.active)
    }

    @Test
    fun 다른_매장의_도착_신호는_무시한다() = runTest {
        val engine = startedEngine(stores = 2)
        signals.remaining.value = 20 * 60
        runCurrent()
        engine.approve()
        runCurrent()

        signals.arrive.tryEmit(Arrival.Destination) // 마지막 장소 도착 → 지금은 첫 장소
        runCurrent()
        assertEquals(null, engine.state.value.alight)

        signals.arrive.tryEmit(Arrival.Waypoint(1)) // 첫 경유지 = 첫 장소
        runCurrent()
        assertEquals(com.smartjourney.vehicle.domain.AlightDetector.State.WAIT_STOP, engine.state.value.alight)
    }

    @Test
    fun 엔진_시작과_상태_변화가_저장소에_자동_저장되고_종료_시_삭제된다() = runTest {
        val storage = InMemoryJourneyStorage()
        val engine = JourneyEngine(signals, backend, backgroundScope, storage = storage, now = { testScheduler.currentTime })
        assertNull(storage.restore())

        engine.start(partySize = 4, stops = backend.stores().take(2))
        runCurrent()
        val saved = storage.restore()
        assertEquals(true, saved?.active)
        assertEquals(4, saved?.partySize)
        assertEquals(2, saved?.stops?.size)

        engine.end()
        runCurrent()
        assertNull(storage.restore())
    }

    @Test
    fun 프로세스_재시작_시_저장된_상태로_복원되고_이어서_동작한다() = runTest {
        val storage = InMemoryJourneyStorage()
        val engine = JourneyEngine(signals, backend, backgroundScope, storage = storage, now = { testScheduler.currentTime })
        engine.start(partySize = 4, stops = backend.stores().take(2))
        runCurrent()

        // 첫 매장 골든타임 도달 및 접수 승인
        signals.remaining.value = 20 * 60
        runCurrent()
        engine.approve()
        runCurrent()
        val waitingId = engine.state.value.stops[0].waiting!!.waitingId
        assertEquals(StopPhase.REGISTERED, engine.state.value.stops[0].phase)

        // 프로세스 재시작 시뮬레이션: 새로운 엔진 인스턴스에 동일한 storage 연결
        val restoredEngine = JourneyEngine(signals, backend, backgroundScope, storage = storage, now = { testScheduler.currentTime })
        assertEquals(false, restoredEngine.state.value.active)

        val restored = restoredEngine.restore()
        runCurrent()
        assertTrue(restored)
        assertTrue(restoredEngine.state.value.active)
        assertEquals(0, restoredEngine.state.value.index)
        assertEquals(StopPhase.REGISTERED, restoredEngine.state.value.stops[0].phase)
        assertEquals(waitingId, restoredEngine.state.value.stops[0].waiting?.waitingId)

        // 복원 후 백엔드 호출 및 착석 이벤트가 정상적으로 다음 매장으로 이어지는지 검증
        backend.call(waitingId)
        runCurrent()
        assertEquals(Prompt.Kind.CALLED, restoredEngine.state.value.prompt?.kind)
        restoredEngine.approve()
        runCurrent()

        backend.seat(waitingId)
        runCurrent()
        assertEquals(1, restoredEngine.state.value.index)
        assertEquals(backend.stores()[1].name, restoredEngine.state.value.current?.store?.name)
        assertEquals(StopPhase.SEATED, restoredEngine.state.value.stops[0].phase)
        assertTrue(restoredEngine.state.value.active)
    }
}
