package com.smartjourney.vehicle.voice

import com.smartjourney.vehicle.engine.JourneyState
import com.smartjourney.vehicle.engine.Prompt
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class LateVoiceResultControllerTest {
    private class Voice : VoiceGateway {
        val texts = mutableListOf<String>()
        var stopped = 0
        var gate: CompletableDeferred<Unit>? = null
        override suspend fun speak(text: String): Result<Unit> { texts += text; gate?.await(); return Result.success(Unit) }
        override suspend fun listen(): Result<String?> = error("Result announcement must never open the microphone")
        override fun stop() { stopped++ }
        override fun release() = Unit
    }
    private val result = LateVoiceResult("request-1", "접수가 거절됐어요")

    @Test fun `waits for Gleo grace and announces once`() = runTest {
        val voice = Voice(); val state = MutableStateFlow(JourneyState(active = true)); val quiet = MutableStateFlow(20_000L)
        var delivered = false
        val c = LateVoiceResultController(voice, state, quiet, Mutex(), { !delivered }, { delivered = true }, { testScheduler.currentTime })
        val task = launch { c.announce(result) }; runCurrent()
        advanceTimeBy(19_999); runCurrent(); assertTrue(voice.texts.isEmpty())
        advanceTimeBy(2); runCurrent(); task.join()
        assertEquals(listOf(result.text), voice.texts); assertEquals(1, voice.stopped)
        c.announce(result); assertEquals(1, voice.texts.size)
    }
    @Test fun `does not interrupt a current question or speak after invalidation`() = runTest {
        val voice = Voice(); val state = MutableStateFlow(JourneyState(active = true, prompt = Prompt("p", Prompt.Kind.GOLDEN_TIME, "A", 4)))
        val quiet = MutableStateFlow(0L); var valid = true
        val c = LateVoiceResultController(voice, state, quiet, Mutex(), { valid }, {}, { testScheduler.currentTime })
        val task = launch { c.announce(result) }; runCurrent(); assertTrue(voice.texts.isEmpty())
        valid = false; state.value = state.value.copy(active = false, prompt = null); runCurrent(); task.join()
        assertTrue(voice.texts.isEmpty())
    }
    @Test fun `shares speech lock with local question and rechecks Gleo lease`() = runTest {
        val voice = Voice(); val state = MutableStateFlow(JourneyState(active = true)); val quiet = MutableStateFlow(0L)
        val lock = Mutex(locked = true)
        val c = LateVoiceResultController(voice, state, quiet, lock, { true }, {}, { testScheduler.currentTime })
        val task = launch { c.announce(result) }; runCurrent(); assertTrue(voice.texts.isEmpty())
        quiet.value = 2_000; lock.unlock(); runCurrent()
        assertTrue(voice.texts.isEmpty()); advanceTimeBy(2_001); runCurrent(); task.join()
        assertEquals(listOf(result.text), voice.texts)
    }
    @Test fun `uses the latest result after waiting rather than the queued snapshot`() = runTest {
        val voice = Voice(); val quiet = MutableStateFlow(2_000L)
        var text = "결과 확인 필요"
        val c = LateVoiceResultController(voice, MutableStateFlow(JourneyState(active = true)), quiet, Mutex(), { true }, {},
            { testScheduler.currentTime }, latestText = { text })
        val task = launch { c.announce(result) }; runCurrent()
        text = "4명 접수 확정 번호 11번"
        advanceTimeBy(2_001); runCurrent(); task.join()
        assertEquals(listOf(text), voice.texts)
    }

    @Test fun `cancelling a result releases its own speech`() = runTest {
        val voice = Voice().apply { gate = CompletableDeferred() }
        val c = LateVoiceResultController(voice, MutableStateFlow(JourneyState()), MutableStateFlow(0L), Mutex(), { true }, {}, { 0L })
        val task = launch { c.announce(result) }; runCurrent(); task.cancel(); task.join()
        assertEquals(1, voice.stopped)
    }
}
