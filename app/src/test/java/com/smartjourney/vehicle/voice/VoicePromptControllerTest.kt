package com.smartjourney.vehicle.voice

import com.smartjourney.vehicle.engine.Prompt
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class VoicePromptControllerTest {
    private val prompt = Prompt("current-question", Prompt.Kind.GOLDEN_TIME, "A 맛집", 4, remainingMin = 20, waitMin = 30)

    private class FakeVoice : VoiceGateway {
        val events = mutableListOf<String>()
        val replies = ArrayDeque<Result<String?>>()
        var speakResult = Result.success(Unit)
        var listenGate: CompletableDeferred<Unit>? = null
        var speakingGate: CompletableDeferred<Unit>? = null
        var hang = false
        override suspend fun speak(text: String): Result<Unit> {
            events += "speak"
            speakingGate?.await()
            return speakResult
        }
        override suspend fun listen(): Result<String?> {
            events += "listen"
            if (hang) awaitCancellation()
            listenGate?.await()
            return replies.removeFirstOrNull() ?: Result.success(null)
        }
        override fun stop() { events += "stop" }
        override fun release() = Unit
    }

    @Test fun 부정문과_불명확한_표현을_승인으로_오인하지_않는다() {
        listOf("네 아니요", "네비게이션 켜 줘", "등록하지 마", "등록 안 할래", "네? 아니 나중에", "", "아마", "Yes maybe")
            .forEach { assertNull(it, voiceDecision(it, prompt.kind)) }
        assertEquals(VoiceDecision.DEFER, voiceDecision("아니요", prompt.kind))
        assertEquals(VoiceDecision.DEFER, voiceDecision("나중에요", prompt.kind))
        assertEquals(VoiceDecision.APPROVE, voiceDecision("등록해 줘!", prompt.kind))
        assertNull("도착 응답을 웨이팅 등록으로 쓰지 않는다", voiceDecision("도착했어요", prompt.kind))
    }

    @Test fun 안내가_끝난_뒤에만_듣고_명확한_답변을_한번_전달한다() = runTest {
        val voice = FakeVoice().apply {
            speakingGate = CompletableDeferred()
            replies += Result.success("등록해 줘")
        }
        val actions = mutableListOf<Pair<String, VoiceDecision>>()
        val controller = VoicePromptController(voice, { prompt }, { id, answer -> actions.add(id to answer) }, {})
        val job = launch { controller.handle(prompt) }
        runCurrent()
        assertEquals(listOf("speak"), voice.events)
        voice.speakingGate!!.complete(Unit)
        job.join()
        assertEquals(listOf("speak", "listen", "stop"), voice.events)
        assertEquals(listOf(prompt.id to VoiceDecision.APPROVE), actions)
    }

    @Test fun 새_질문으로_바뀐_뒤_도착한_답변을_버린다() = runTest {
        var current = prompt
        val voice = FakeVoice().apply {
            listenGate = CompletableDeferred()
            replies += Result.success("네")
        }
        var approved = false
        val controller = VoicePromptController(voice, { current }, { _, _ -> approved = true; true }, {})
        val job = launch { controller.handle(prompt) }
        runCurrent()
        current = prompt.copy(id = "new-question")
        voice.listenGate!!.complete(Unit)
        job.join()
        assertFalse(approved)
        assertEquals("stop", voice.events.last())
    }

    @Test fun 무응답과_음성_연결_실패는_승인하지_않는다() = runTest {
        for (failSpeech in listOf(false, true)) {
            val voice = FakeVoice().apply {
                if (failSpeech) speakResult = Result.failure(IllegalStateException("not available"))
            }
            var approved = false
            var unavailable = false
            VoicePromptController(voice, { prompt }, { _, _ -> approved = true; true }, { unavailable = true }).handle(prompt)
            assertFalse(approved)
            assertTrue(unavailable)
        }
    }

    @Test fun 시간초과나_취소시_마이크를_닫고_승인하지_않는다() = runTest {
        val voice = FakeVoice().apply { hang = true }
        var actions = 0
        val controller = VoicePromptController(voice, { prompt }, { _, _ -> actions++; true }, {})
        val job = launch { controller.handle(prompt) }
        runCurrent()
        advanceTimeBy(10_001)
        runCurrent()
        assertTrue(job.isCompleted)
        assertEquals(0, actions)
        assertEquals("stop", voice.events.last())
        val cancelled = launch { controller.handle(prompt) }
        runCurrent()
        cancelled.cancel()
        cancelled.join()
        assertEquals(0, actions)
        assertEquals("stop", voice.events.last())
    }

    @Test fun 애매한_첫_답변은_한번만_재질문한다() = runTest {
        val voice = FakeVoice().apply {
            replies += Result.success("글쎄")
            replies += Result.success("나중에")
        }
        val actions = mutableListOf<VoiceDecision>()
        VoicePromptController(voice, { prompt }, { _, answer -> actions.add(answer) }, {}).handle(prompt)
        assertEquals(listOf("speak", "listen", "speak", "listen", "stop"), voice.events)
        assertEquals(listOf(VoiceDecision.DEFER), actions)
    }

    @Test fun 만료된_질문은_읽거나_응답받지_않는다() = runTest {
        val voice = FakeVoice()
        val expired = prompt.copy(expiresAt = 100)
        VoicePromptController(voice, { expired }, { _, _ -> fail("expired response"); false }, {}, now = { 100 }).handle(expired)
        assertEquals(listOf("stop"), voice.events)
    }
}
