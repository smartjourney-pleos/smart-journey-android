package com.smartjourney.vehicle.engine

import com.smartjourney.vehicle.core.VehicleSnapshot
import com.smartjourney.vehicle.data.FakeBackend
import com.smartjourney.vehicle.data.InMemoryJourneyStorage
import com.smartjourney.vehicle.domain.Store
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 순정 내비의 "안내 종료"(`onRouteCancelled`)와 목적지 변경을 구분하고, 접수 전 방문만 정리하는지 확인한다. */
@OptIn(ExperimentalCoroutinesApi::class)
class RouteCancelledTest {

    private class Signals : JourneySignals {
        val remaining = MutableStateFlow(30 * 60)
        val cancelled = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
        val changed = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
        override val occupied: Flow<Int> = MutableStateFlow(4)
        override val anyDoorOpen: Flow<Boolean> = MutableStateFlow(false)
        override val restricted: Flow<Boolean> = MutableStateFlow(true)
        override val arrivals: Flow<Arrival> = MutableSharedFlow()
        override val routeChanged: Flow<Unit> = changed
        override val routeCancelled: Flow<Unit> = cancelled
        override fun remainingSec(target: Store): Flow<Int> = remaining
        override suspend fun sensorsSupported() = true
        override suspend fun readNow() = VehicleSnapshot(4, null, false)
    }

    private val signals = Signals()
    private val backend = FakeBackend()

    private fun TestScope.engine(storage: InMemoryJourneyStorage? = null) =
        JourneyEngine(signals, backend, backgroundScope, storage = storage, now = { testScheduler.currentTime })

    @Test
    fun 접수_전_방문은_내비_안내가_끝나면_닫힌다() = runTest {
        val engine = engine().also { it.start(4, backend.stores().take(2)); runCurrent() }

        signals.cancelled.tryEmit(Unit)
        runCurrent()

        assertFalse(engine.state.value.active)
        assertTrue("접수 요청이 나가지 않아야 한다", backend.received.value.isEmpty())
    }

    @Test
    fun 질문이_떠_있어도_접수_전이면_질문과_함께_닫히고_접수하지_않는다() = runTest {
        val engine = engine().also { it.start(4, backend.stores().take(1)); runCurrent() }
        signals.remaining.value = 20 * 60
        runCurrent()
        assertEquals(StopPhase.PROMPTED, engine.state.value.current?.phase)

        signals.cancelled.tryEmit(Unit)
        runCurrent()

        assertFalse(engine.state.value.active)
        assertNull(engine.state.value.prompt)
        assertTrue(backend.received.value.isEmpty())
    }

    @Test
    fun 접수한_방문은_내비_안내가_끝나도_유지되고_취소하지_않는다() = runTest {
        val engine = engine().also { it.start(4, backend.stores().take(1)); runCurrent() }
        signals.remaining.value = 20 * 60
        runCurrent()
        engine.approve()
        runCurrent()
        val waiting = engine.state.value.current!!.waiting!!
        val before = backend.received.value

        signals.cancelled.tryEmit(Unit)
        runCurrent()

        val stop = engine.state.value.current!!
        assertTrue(engine.state.value.active)
        assertEquals(StopPhase.REGISTERED, stop.phase)
        assertEquals(SubmissionStatus.CONFIRMED, stop.submission)
        assertEquals(waiting.waitingId, stop.waiting?.waitingId)
        assertEquals("취소 요청이 나가지 않아야 한다", before, backend.received.value)
    }

    @Test
    fun 접수_중이거나_결과를_모르면_미접수로_단정하지_않는다() = runTest {
        for (status in listOf(SubmissionStatus.REQUESTING, SubmissionStatus.UNKNOWN)) {
            val storage = InMemoryJourneyStorage()
            val store = backend.stores().first()
            storage.save(JourneyState(
                active = true, partySize = 4,
                stops = listOf(StopState(store, phase = StopPhase.PLANNED, submission = status, submissionId = "p1")),
            ))
            val engine = engine(storage)
            assertTrue(engine.restore())
            runCurrent()

            signals.cancelled.tryEmit(Unit)
            runCurrent()

            assertTrue("$status 방문은 닫지 않는다", engine.state.value.active)
            assertEquals(status, engine.state.value.current?.submission)
        }
    }

    @Test
    fun 목적지_변경은_방문을_닫지_않는다() = runTest {
        val engine = engine().also { it.start(4, backend.stores().take(1)); runCurrent() }

        signals.changed.tryEmit(Unit)
        runCurrent()

        assertTrue(engine.state.value.active)
        assertEquals(SubmissionStatus.NOT_REQUESTED, engine.state.value.current?.submission)
    }

    @Test
    fun 방문_끝내기_확인은_접수했거나_결과를_모를_때만_받는다() {
        val store = FakeBackend().let { kotlinx.coroutines.runBlocking { it.stores().first() } }
        fun stop(status: SubmissionStatus) = StopState(store, submission = status)
        assertFalse(stop(SubmissionStatus.NOT_REQUESTED).needsEndConfirm())
        assertFalse(stop(SubmissionStatus.REJECTED).needsEndConfirm())
        assertTrue(stop(SubmissionStatus.CONFIRMED).needsEndConfirm())
        assertTrue(stop(SubmissionStatus.REQUESTING).needsEndConfirm())
        assertTrue(stop(SubmissionStatus.UNKNOWN).needsEndConfirm())
        assertFalse((null as StopState?).needsEndConfirm())
    }
}
