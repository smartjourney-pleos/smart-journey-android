package com.smartjourney.vehicle.voice

import com.smartjourney.vehicle.core.VehicleSnapshot
import com.smartjourney.vehicle.data.FakeBackend
import com.smartjourney.vehicle.data.RegistrationRejectedException
import com.smartjourney.vehicle.data.WaitingBackend
import com.smartjourney.vehicle.domain.ApprovalMethod
import com.smartjourney.vehicle.domain.Store
import com.smartjourney.vehicle.domain.Waiting
import com.smartjourney.vehicle.engine.Arrival
import com.smartjourney.vehicle.engine.JourneyEngine
import com.smartjourney.vehicle.engine.JourneySignals
import com.smartjourney.vehicle.engine.SubmissionStatus
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class GleoActionHandlerTest {
    private class Signals : JourneySignals {
        override val occupied = MutableStateFlow(4)
        override val anyDoorOpen = MutableStateFlow(false)
        override val restricted = MutableStateFlow(true)
        override val arrivals = MutableSharedFlow<Arrival>()
        override val routeChanged = emptyFlow<Unit>()
        val remaining = MutableStateFlow(20 * 60)
        override fun remainingSec(target: Store) = remaining
        override suspend fun sensorsSupported() = false
        override suspend fun readNow() = VehicleSnapshot(4, null, false)
    }
    private class Fixture(val scope: TestScope, val latency: Long = 0, val outcome: String = "confirmed") {
        val signals = Signals()
        val fake = FakeBackend()
        var requests = 0
        var offset = 0L
        var consent = true
        var owner: String? = null
        val late = mutableListOf<LateVoiceResult>()
        val backend = object : WaitingBackend by fake {
            override suspend fun register(storeId: Long, partySize: Int, method: ApprovalMethod, idempotencyKey: String): Waiting {
                requests++
                delay(latency)
                if (outcome == "rejected") throw RegistrationRejectedException("closed")
                val waiting = fake.register(storeId, partySize, method, idempotencyKey)
                if (outcome == "unknown") throw java.io.IOException("lost response")
                return waiting
            }
        }
        val engine = JourneyEngine(signals, backend, scope.backgroundScope, now = { scope.testScheduler.currentTime + offset })
        val handler = GleoActionHandler(engine.state, { consent }, { true },
            { id, decision -> consent && engine.respondByVoice(id, decision == VoiceDecision.APPROVE) },
            { owner = it }, scope.backgroundScope, { late += it }, now = { scope.testScheduler.currentTime + offset })
        suspend fun start(index: Int = 0) {
            engine.start(4, fake.stores().drop(index).take(1)); scope.runCurrent()
            assertNotNull(engine.state.value.prompt)
        }
        suspend fun ask() = handler.handle(GleoActionHandler.STATUS)
        suspend fun answer(token: String?, decision: String = "approve") = handler.handle(GleoActionHandler.RESPOND,
            JSONObject().put("decision", decision).apply { token?.let { put("questionToken", it) } })
    }

    @Test fun `delivery or a bare approve never authorizes a request`() = runTest {
        val f = Fixture(this); f.start(); f.ask()
        assertEquals("asking", f.answer(null).status)
        assertEquals(0, f.requests)
    }
    @Test fun `correct token approves once and links the receipt to the original question`() = runTest {
        val f = Fixture(this); f.start(); val id = f.engine.state.value.prompt!!.id
        val q = f.ask(); val token = q.data!!.getString("questionToken")
        val approved = f.answer(token)
        assertEquals("success", approved.status); assertEquals(11, approved.data!!.getInt("ticketNo"))
        assertEquals(4, approved.data!!.getInt("partySize")); assertEquals(id, f.engine.state.value.current!!.submissionId)
        assertEquals(SubmissionStatus.CONFIRMED, f.engine.state.value.current!!.submission)
        assertEquals("fail", f.answer(token).status); assertEquals(1, f.requests); assertNull(f.owner)
    }
    @Test fun `concurrent duplicate responses submit only once`() = runTest {
        val f = Fixture(this, 100); f.start(); val token = f.ask().data!!.getString("questionToken")
        val replies = mutableListOf<GleoReply>()
        backgroundScope.launch { replies += f.answer(token) }
        backgroundScope.launch { replies += f.answer(token) }
        runCurrent(); advanceTimeBy(101); runCurrent()
        assertEquals(1, f.requests)
        assertEquals(setOf("success", "fail"), replies.map { it.status }.toSet())
    }

    @Test fun `a question without engine expiry still has a bounded Gleo token lease`() = runTest {
        val p = com.smartjourney.vehicle.engine.Prompt("party", com.smartjourney.vehicle.engine.Prompt.Kind.PARTY_CHANGE, "B", 4, newPartySize = 3)
        val state = MutableStateFlow(com.smartjourney.vehicle.engine.JourneyState(active = true, prompt = p))
        var executions = 0
        val handler = GleoActionHandler(state, { true }, { true }, { _, _ -> executions++; true }, {}, backgroundScope, {}, now = { testScheduler.currentTime })
        val token = handler.handle(GleoActionHandler.STATUS).data!!.getString("questionToken")
        advanceTimeBy(60_000); runCurrent()
        val response = handler.handle(GleoActionHandler.RESPOND, JSONObject().put("decision", "approve").put("questionToken", token))
        assertEquals("asking", response.status); assertEquals(0, executions)
    }

    @Test fun `deferral consumes the token without registration`() = runTest {
        val f = Fixture(this); f.start()
        val token = f.ask().data!!.getString("questionToken")
        assertEquals("success", f.answer(token, "defer").status)
        assertNull(f.engine.state.value.prompt); assertEquals(0, f.requests)
        assertEquals("fail", f.answer(token).status)
    }
    @Test fun `old dialogue cannot approve a different store even after new question was delivered`() = runTest {
        val f = Fixture(this); f.start()
        val oldToken = f.ask().data!!.getString("questionToken")
        f.engine.defer(); f.engine.end(); f.signals.remaining.value = 5 * 60; f.start(1)
        f.ask()
        val reasked = f.answer(oldToken)
        assertEquals("asking", reasked.status); assertEquals(0, f.requests)
        assertTrue(reasked.data!!.getString("question").contains("소프트웨어드림센터"))
        assertEquals("success", f.answer(reasked.data!!.getString("questionToken")).status)
        assertEquals(2L, f.engine.state.value.current!!.waiting!!.storeId)
    }
    @Test fun `reasking rotates the token and invented tokens do not execute`() = runTest {
        val f = Fixture(this); f.start()
        val token = f.ask().data!!.getString("questionToken")
        assertNotEquals(token, f.ask().data!!.getString("questionToken"))
        assertEquals("asking", f.answer(token).status)
        assertEquals("asking", f.answer("42dot_waiting_prompt").status)
        assertEquals(0, f.requests)
    }
    @Test fun `invalid decision cannot approve even with a valid token`() = runTest {
        val f = Fixture(this); f.start(); val token = f.ask().data!!.getString("questionToken")
        assertEquals("asking", f.answer(token, "maybe").status); assertEquals(0, f.requests)
    }
    @Test fun `consent withdrawal invalidates the outstanding question`() = runTest {
        val f = Fixture(this); f.start(); val token = f.ask().data!!.getString("questionToken")
        f.consent = false
        assertEquals("fail", f.answer(token).status)
        f.consent = true
        assertEquals("asking", f.answer(token).status); assertEquals(0, f.requests)
    }
    @Test fun `expired prompt is rejected even before its timeout task runs`() = runTest {
        val f = Fixture(this); f.start(); val token = f.ask().data!!.getString("questionToken")
        f.offset = 60_000
        assertEquals("fail", f.answer(token).status); assertEquals(0, f.requests)
    }
    @Test fun `sub five second result is confirmed and not queued twice`() = runTest {
        val f = Fixture(this, 3_000); f.start(); val token = f.ask().data!!.getString("questionToken")
        var response: GleoReply? = null
        val job = backgroundScope.launch { response = f.answer(token) }
        runCurrent(); assertNull(response)
        advanceTimeBy(3_001); runCurrent()
        assertEquals("success", response!!.status); assertTrue(f.late.isEmpty()); job.cancel()
    }
    @Test fun `slow rejection is pending not success then delivers a failure announcement`() = runTest {
        val f = Fixture(this, 6_000, "rejected"); f.start(); val token = f.ask().data!!.getString("questionToken")
        var response: GleoReply? = null
        backgroundScope.launch { response = f.answer(token) }
        runCurrent(); advanceTimeBy(5_001); runCurrent()
        assertEquals("asking", response!!.status)
        assertFalse(response!!.data!!.getBoolean("registrationConfirmed"))
        assertFalse(response!!.data!!.getBoolean("requiresApproval"))
        assertEquals("REQUESTING", response!!.data!!.getString("registrationStatus"))
        assertEquals(0, f.late.size)
        advanceTimeBy(1_000); runCurrent()
        assertEquals(1, f.late.size); assertTrue(f.late.single().text.contains("거절"))
        assertEquals("fail", f.ask().status)
        assertFalse(f.handler.shouldAnnounce(f.late.single())) // Gleo 결과 응답으로 출력 소유권 인계
        assertEquals(1, f.requests)
    }
    @Test fun `slow success delivers the exact receipt and can be queried`() = runTest {
        val f = Fixture(this, 6_000); f.start(); val token = f.ask().data!!.getString("questionToken")
        backgroundScope.launch { f.answer(token) }
        runCurrent(); advanceTimeBy(6_001); runCurrent()
        assertTrue(f.late.single().text.contains("번호 11번"))
        assertTrue(f.handler.shouldAnnounce(f.late.single()))
        val result = f.ask()
        assertEquals("success", result.status); assertEquals("CONFIRMED", result.data!!.getString("registrationStatus"))
        assertFalse(f.handler.shouldAnnounce(f.late.single()))
    }
    @Test fun `slow unknown outcome forbids retry and sends uncertainty announcement`() = runTest {
        val f = Fixture(this, 6_000, "unknown"); f.start(); val token = f.ask().data!!.getString("questionToken")
        backgroundScope.launch { f.answer(token) }
        runCurrent(); advanceTimeBy(6_001); runCurrent()
        assertTrue(f.late.single().text.contains("다시 접수하지 말고"))
        assertEquals("UNKNOWN", f.ask().data!!.getString("registrationStatus"))
        assertEquals("fail", f.answer(token).status); assertEquals(1, f.requests)
    }
    @Test fun `ending journey suppresses pending followup`() = runTest {
        val f = Fixture(this, 8_000); f.start(); val token = f.ask().data!!.getString("questionToken")
        backgroundScope.launch { f.answer(token) }
        runCurrent(); advanceTimeBy(5_001); runCurrent(); f.engine.end(); runCurrent()
        advanceTimeBy(10_000); runCurrent(); assertTrue(f.late.isEmpty())
    }
    @Test fun `revoked consent suppresses late result`() = runTest {
        val f = Fixture(this, 6_000); f.start(); val token = f.ask().data!!.getString("questionToken")
        backgroundScope.launch { f.answer(token) }
        runCurrent(); advanceTimeBy(5_001); runCurrent(); f.consent = false
        advanceTimeBy(1_000); runCurrent(); assertTrue(f.late.isEmpty())
    }
    @Test fun `callback data remains an encoded JSON array and tokens stay out of instructions`() = runTest {
        val f = Fixture(this); f.start(); val reply = f.ask()
        val json = JSONObject(reply.toJson())
        val data = JSONArray(json.getString("data")).getJSONObject(0)
        assertEquals(GleoActionHandler.STATUS, json.getString("functionName"))
        assertEquals("voice", json.getString("mode"))
        assertFalse(reply.message.contains(data.getString("questionToken")))
        assertTrue(data.getString("question").contains("4명"))
    }
}

