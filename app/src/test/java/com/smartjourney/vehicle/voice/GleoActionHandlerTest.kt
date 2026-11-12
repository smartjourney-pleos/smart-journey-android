package com.smartjourney.vehicle.voice

import com.smartjourney.vehicle.core.VehicleSnapshot
import com.smartjourney.vehicle.data.FakeBackend
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
import java.io.File

/**
 * Gleo Action은 모델이 만든 값만 전달하고, 새 사용자 발화를 증명하는 값은 없다(Gleo Interface p108, 설치된 Gleo 4.2.0.15).
 * 그래서 Gleo로는 어떤 호출 조합으로도 접수·인원 변경이 실행되지 않고, 승인에 쓸 값도 발급되지 않아야 한다.
 */
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

    private class Fixture(val scope: TestScope, val latency: Long = 0) {
        val signals = Signals()
        val fake = FakeBackend()
        var requests = 0
        var consent = true
        val backend = object : WaitingBackend by fake {
            override suspend fun register(storeId: Long, partySize: Int, method: ApprovalMethod, idempotencyKey: String): Waiting {
                requests++
                delay(latency)
                return fake.register(storeId, partySize, method, idempotencyKey)
            }
        }
        val engine = JourneyEngine(signals, backend, scope.backgroundScope, now = { scope.testScheduler.currentTime })
        val handler = GleoActionHandler(engine.state, { consent }, { true }, now = { scope.testScheduler.currentTime })
        suspend fun start(index: Int = 0) {
            engine.start(4, fake.stores().drop(index).take(1)); scope.runCurrent()
            assertNotNull(engine.state.value.prompt)
        }
        fun status() = handler.handle(GleoActionHandler.STATUS)
        fun respond() = handler.handle(GleoActionHandler.RESPOND)
    }

    private fun GleoReply.dataObject(): JSONObject? = data

    private fun assertNoApprovalMaterial(reply: GleoReply) {
        val json = reply.toJson()
        assertFalse("승인용 토큰을 돌려주면 안 된다: $json", json.contains("questionToken"))
        assertFalse(json.contains("\"requiresApproval\":true"))
    }

    @Test fun `respond never executes and never issues a token`() = runTest {
        val f = Fixture(this); f.start()
        val reply = f.respond()
        assertEquals("fail", reply.status)
        assertNoApprovalMaterial(reply)
        assertEquals(0, f.requests)
        assertNotNull("앱 자체 질문은 그대로 남는다", f.engine.state.value.prompt)
    }

    @Test fun `status followed by respond still submits nothing`() = runTest {
        val f = Fixture(this); f.start()
        repeat(3) {
            val status = f.status()
            assertEquals("success", status.status)
            assertTrue(status.dataObject()!!.getBoolean("pendingQuestion"))
            assertFalse(status.dataObject()!!.getBoolean("approvalThroughGleo"))
            assertNoApprovalMaterial(status)
            assertEquals("fail", f.respond().status)
        }
        assertEquals(0, f.requests)
    }

    @Test fun `repeated status alone submits nothing and does not extend the question`() = runTest {
        val f = Fixture(this); f.start()
        val expiresAt = f.engine.state.value.prompt!!.expiresAt
        repeat(5) { f.status(); advanceTimeBy(1_000); runCurrent() }
        assertEquals(0, f.requests)
        assertEquals(expiresAt, f.engine.state.value.prompt?.expiresAt ?: expiresAt)
        advanceTimeBy(60_000); runCurrent()
        assertNull("질문은 원래 기한에 닫힌다", f.engine.state.value.prompt)
        assertEquals(0, f.requests)
    }

    @Test fun `expired, changed or withdrawn questions submit nothing through gleo`() = runTest {
        val f = Fixture(this); f.start()
        f.status()
        advanceTimeBy(60_000); runCurrent()
        assertEquals("fail", f.respond().status)

        f.engine.end(); f.signals.remaining.value = 5 * 60; f.start(1)
        f.status()
        assertEquals("fail", f.respond().status)

        f.consent = false
        assertEquals("fail", f.status().status)
        assertEquals("fail", f.respond().status)
        assertEquals(0, f.requests)
    }

    @Test fun `gleo status does not stop the app's own voice approval`() = runTest {
        val f = Fixture(this); f.start()
        val prompt = f.engine.state.value.prompt!!
        f.status(); f.respond()
        val voice = object : VoiceGateway {
            override suspend fun speak(text: String) = Result.success(Unit)
            override suspend fun listen(): Result<String?> = Result.success("응")
            override fun stop() = Unit
            override fun release() = Unit
        }
        val controller = VoicePromptController(voice, { f.engine.state.value.prompt },
            { id, decision -> f.consent && f.engine.respondByVoice(id, decision == VoiceDecision.APPROVE) },
            onUnavailable = {}, now = { testScheduler.currentTime })
        controller.handle(prompt); runCurrent()
        controller.handle(prompt); runCurrent()
        assertEquals("앱 STT 최종 인식으로만 한 번 접수한다", 1, f.requests)
        assertEquals(SubmissionStatus.CONFIRMED, f.engine.state.value.current!!.submission)
    }

    @Test fun `status reports a pending registration without asking for approval`() = runTest {
        val f = Fixture(this, 6_000); f.start()
        backgroundScope.launch { f.engine.respondByVoice(f.engine.state.value.prompt!!.id, true) }
        runCurrent()
        val pending = f.status()
        assertEquals("asking", pending.status)
        assertFalse(pending.dataObject()!!.getBoolean("requiresApproval"))
        assertEquals("REQUESTING", pending.dataObject()!!.getString("registrationStatus"))
        advanceTimeBy(6_001); runCurrent()
        val done = f.status()
        assertEquals("success", done.status); assertEquals(11, done.dataObject()!!.getInt("ticketNo"))
        assertEquals(1, f.requests)
    }

    @Test fun `confirmed result read through gleo is not announced twice by the app`() = runTest {
        val f = Fixture(this); f.start()
        f.engine.respondByVoice(f.engine.state.value.prompt!!.id, true); runCurrent()
        val id = f.engine.state.value.current!!.submissionId!!
        val late = LateVoiceResult(id, "결과")
        assertTrue(f.handler.shouldAnnounce(late))
        assertEquals("success", f.status().status)
        assertFalse(f.handler.shouldAnnounce(late))
    }

    @Test fun `callback data remains an encoded JSON array`() = runTest {
        val f = Fixture(this); f.start()
        val json = JSONObject(f.status().toJson())
        assertEquals(GleoActionHandler.STATUS, json.getString("functionName"))
        assertEquals("voice", json.getString("mode"))
        val data = JSONArray(json.getString("data")).getJSONObject(0)
        assertEquals("42dot", data.getString("storeName"))
    }

    @Test fun `schema exposes only the status function`() {
        val schema = JSONObject(File("src/main/assets/schema.json").readText())
        val names = (0 until schema.getJSONArray("actionCalls").length())
            .map { schema.getJSONArray("actionCalls").getJSONObject(it).getJSONObject("function").getString("name") }
        assertEquals(listOf(GleoActionHandler.STATUS), names)
    }
}
