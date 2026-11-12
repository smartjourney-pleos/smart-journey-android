package com.smartjourney.vehicle.engine

import com.smartjourney.vehicle.core.VehicleSnapshot
import com.smartjourney.vehicle.data.FakeBackend
import com.smartjourney.vehicle.data.WaitingBackend
import com.smartjourney.vehicle.data.RegistrationRejectedException
import com.smartjourney.vehicle.domain.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SubmissionSafetyTest {
    private class Signals : JourneySignals {
        override val occupied = MutableStateFlow(4)
        override val anyDoorOpen = MutableStateFlow(false)
        override val restricted = MutableStateFlow(false)
        override val arrivals = MutableSharedFlow<Arrival>()
        override val routeChanged = kotlinx.coroutines.flow.emptyFlow<Unit>()
        val remaining = MutableStateFlow(20 * 60)
        override fun remainingSec(target: Store) = remaining
        override suspend fun sensorsSupported() = false
        override suspend fun readNow() = VehicleSnapshot(4, null, false)
    }

    @Test fun `unknown result never causes another automatic submission`() = runTest {
        val signals = Signals()
        val fake = FakeBackend()
        var requests = 0
        val backend = object : WaitingBackend by fake {
            override suspend fun register(storeId: Long, partySize: Int, method: ApprovalMethod, idempotencyKey: String): Waiting {
                requests++
                // The server received it, but the response was lost.
                fake.register(storeId, partySize, method, idempotencyKey)
                throw java.io.IOException("response lost")
            }
        }
        val engine = JourneyEngine(signals, backend, backgroundScope)
        engine.start(4, fake.stores().take(1)); runCurrent()
        val id = engine.state.value.prompt!!.id
        assertTrue(engine.respondByVoice(id, true)); runCurrent()
        assertEquals(SubmissionStatus.UNKNOWN, engine.state.value.current!!.submission)
        assertFalse(engine.respondByVoice(id, true))
        repeat(4) { signals.remaining.value -= 60; fake.addWalkIn(1, 1); runCurrent() }
        assertNull(engine.state.value.prompt)
        assertEquals(1, requests)
    }

    @Test fun `waiting for response is not confirmed and ending does not cancel booking`() = runTest {
        val fake = FakeBackend()
        val result = CompletableDeferred<Waiting>()
        var requests = 0
        var cancellations = 0
        val backend = object : WaitingBackend by fake {
            override suspend fun register(storeId: Long, partySize: Int, method: ApprovalMethod, idempotencyKey: String): Waiting {
                requests++
                return result.await()
            }
            override suspend fun cancel(waitingId: String) { cancellations++ }
        }
        val engine = JourneyEngine(Signals(), backend, backgroundScope)
        engine.start(4, fake.stores().take(1)); runCurrent()
        val id = engine.state.value.prompt!!.id
        engine.respondByVoice(id, true); runCurrent()
        assertEquals(SubmissionStatus.REQUESTING, engine.state.value.current!!.submission)
        assertNull(engine.state.value.current!!.waiting)
        assertFalse(engine.respondByVoice(id, true))
        result.complete(fake.register(1, 4, ApprovalMethod.VOICE, id)); runCurrent()
        assertEquals(SubmissionStatus.CONFIRMED, engine.state.value.current!!.submission)
        engine.end(); runCurrent()
        assertEquals(0, cancellations)
        assertEquals(1, requests)
        assertNotNull(engine.state.value.current!!.waiting)
        assertFalse(engine.state.value.active)
    }

    @Test fun `tracking ended while request is in flight retains uncertainty`() = runTest {
        val fake = FakeBackend()
        val backend = object : WaitingBackend by fake {
            override suspend fun register(storeId: Long, partySize: Int, method: ApprovalMethod, idempotencyKey: String): Waiting =
                CompletableDeferred<Waiting>().await()
        }
        val engine = JourneyEngine(Signals(), backend, backgroundScope)
        engine.start(4, fake.stores().take(1)); runCurrent()
        engine.approve(); runCurrent(); engine.end(); runCurrent()
        assertEquals(SubmissionStatus.UNKNOWN, engine.state.value.current!!.submission)
        assertTrue(runCatching { engine.start(4, fake.stores().take(1)) }.isFailure)
    }

    @Test fun `explicit rejection is separate from uncertain delivery`() = runTest {
        val fake = FakeBackend()
        val backend = object : WaitingBackend by fake {
            override suspend fun register(storeId: Long, partySize: Int, method: ApprovalMethod, idempotencyKey: String): Waiting =
                throw RegistrationRejectedException("closed")
        }
        val signals = Signals()
        val engine = JourneyEngine(signals, backend, backgroundScope)
        engine.start(4, fake.stores().take(1)); runCurrent(); engine.approve(); runCurrent()
        assertEquals(SubmissionStatus.REJECTED, engine.state.value.current!!.submission)
        signals.remaining.value = 19 * 60; runCurrent()
        assertNull(engine.state.value.prompt)
        assertTrue(engine.state.value.statusLine().contains("접수 거절"))
    }
}
