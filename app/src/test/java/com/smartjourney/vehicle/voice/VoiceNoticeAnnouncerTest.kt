package com.smartjourney.vehicle.voice

import com.smartjourney.vehicle.engine.JourneyState
import com.smartjourney.vehicle.engine.Prompt
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class VoiceNoticeAnnouncerTest {

    private class RecordingVoice(var speakResult: Result<Unit> = Result.success(Unit)) : VoiceGateway {
        val spoken = mutableListOf<String>()
        var stopCount = 0
        override suspend fun speak(text: String): Result<Unit> {
            spoken += text
            return speakResult
        }
        override suspend fun listen(): Result<String?> = Result.success(null)
        override fun stop() { stopCount++ }
        override fun release() = Unit
    }

    @Test
    fun 활성_질문과_Gleo_유예가_없으면_결과_안내를_발화한다() = runTest {
        val voice = RecordingVoice()
        val announcer = VoiceNoticeAnnouncer(
            voice = voice,
            state = MutableStateFlow(JourneyState(active = true)),
            consented = { true },
            gleoQuietUntil = MutableStateFlow(0L),
            speechMutex = Mutex(),
            now = { 10_000L },
        )

        assertTrue(announcer.announce("42dot에 4명으로 등록했어요. 번호 7번, 앞에 6팀 있어요"))
        assertEquals(listOf("42dot에 4명으로 등록했어요. 번호 7번, 앞에 6팀 있어요"), voice.spoken)
        assertEquals(1, voice.stopCount)
    }

    @Test
    fun 활성_질문이_있으면_일반_안내는_생략하고_미실행_안내는_발화한다() = runTest {
        val voice = RecordingVoice()
        val activePrompt = Prompt("p1", Prompt.Kind.GOLDEN_TIME, "42dot", 4)
        val announcer = VoiceNoticeAnnouncer(
            voice = voice,
            state = MutableStateFlow(JourneyState(active = true, prompt = activePrompt)),
            consented = { true },
            gleoQuietUntil = MutableStateFlow(0L),
            speechMutex = Mutex(),
            now = { 10_000L },
        )

        assertFalse("질문 중 일반 결과 안내로 방해하면 안 된다", announcer.announce("내비 경로가 바뀌었어요"))
        assertTrue(voice.spoken.isEmpty())

        assertTrue("질문 실패 직후 미실행 안내는 발화해야 한다", announcer.announce("응답이 없어 실행하지 않았어요.", skipIfPromptActive = false))
        assertEquals(listOf("응답이 없어 실행하지 않았어요."), voice.spoken)
    }

    @Test
    fun Gleo_유예_중이거나_동의_철회_상태에서는_발화하지_않는다() = runTest {
        val voice = RecordingVoice()
        var agreed = true
        val quietUntil = MutableStateFlow(20_000L)
        val announcer = VoiceNoticeAnnouncer(
            voice = voice,
            state = MutableStateFlow(JourneyState(active = true)),
            consented = { agreed },
            gleoQuietUntil = quietUntil,
            speechMutex = Mutex(),
            now = { 10_000L },
        )

        assertFalse("Gleo 발화 유예 중에는 중복 안내하지 않는다", announcer.announce("접수 완료"))
        quietUntil.value = 5_000L
        agreed = false
        assertFalse("동의 철회 후에는 발화하지 않는다", announcer.announce("접수 완료"))
        assertTrue(voice.spoken.isEmpty())
    }

    @Test
    fun 음성_실패_안내_문구는_TTS_불가만_제외하고_미실행을_명확히_전달한다() {
        assertNull(voiceUnavailableSpokenText(VoiceIssue.SPEECH_UNAVAILABLE))
        listOf(
            VoiceIssue.MICROPHONE_UNAVAILABLE,
            VoiceIssue.NO_RESPONSE,
            VoiceIssue.UNRECOGNIZED,
        ).forEach { issue ->
            val text = voiceUnavailableSpokenText(issue)
            assertNotNull(text)
            assertTrue("실행하지 않았음을 명확히 안내해야 한다: $text", text!!.contains("실행하지 않았어요"))
        }
    }
}
