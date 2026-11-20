package com.smartjourney.vehicle.voice

import ai.pleos.playground.stt.listener.ResultListener
import ai.pleos.playground.tts.listener.EventListener
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PleosVoiceAudioFocusTest {

    private class FakeAudioFocus(private val grant: Boolean = true) : AudioFocusController {
        val events = mutableListOf<String>()
        var lastPurpose: AudioFocusPurpose? = null
        var onLoss: ((AudioFocusLoss) -> Unit)? = null

        override fun request(purpose: AudioFocusPurpose, onFocusLost: (AudioFocusLoss) -> Unit): Boolean {
            lastPurpose = purpose
            events += "focus:request:${purpose.name}"
            if (!grant) return false
            onLoss = onFocusLost
            return true
        }

        override fun abandon() {
            events += "focus:abandon"
            onLoss = null
        }

        fun dispatchLoss(loss: AudioFocusLoss) {
            onLoss?.invoke(loss)
        }
    }

    private class FakeSpeechClient(private val events: MutableList<String>) : PleosSpeechClient {
        var ttsListener: EventListener? = null
        var sttListener: ResultListener? = null
        var autoCompleteTts = true
        var autoCompleteSttText: String? = "네"

        override suspend fun ensureTtsReady(): Boolean = true
        override suspend fun ensureSttReady(): Boolean = true
        override fun addTtsListener(listener: EventListener) { ttsListener = listener }
        override fun removeTtsListener(listener: EventListener) { if (ttsListener === listener) ttsListener = null }
        override fun speakText(text: String) {
            events += "tts:speak"
            if (autoCompleteTts) ttsListener?.onDone()
        }
        override fun stopTts() { events += "tts:stop" }
        override fun addSttListener(listener: ResultListener) { sttListener = listener }
        override fun removeSttListener(listener: ResultListener) { if (sttListener === listener) sttListener = null }
        override fun requestStt() {
            events += "stt:request"
            autoCompleteSttText?.let { sttListener?.onUpdated(it, true) }
        }
        override fun stopStt() { events += "stt:stop" }
        override fun release() { events += "client:release" }
    }

    @Test
    fun 오디오_포커스_거절시_TTS재생과_선행묵음을_실행하지_않는다() = runTest {
        val events = mutableListOf<String>()
        val focus = FakeAudioFocus(grant = false)
        val client = FakeSpeechClient(events)
        val gateway = PleosVoiceGateway(
            client = client,
            audioFocus = focus,
            preRollSilence = { events += "preroll" },
            logWarning = {},
        )

        val result = gateway.speak("지금 웨이팅을 걸까요?")

        assertTrue(result.isFailure)
        assertEquals(AudioFocusPurpose.SPEECH_OUTPUT, focus.lastPurpose)
        assertFalse("포커스 거절 시 선행 묵음을 실행하지 않는다", "preroll" in events)
        assertFalse("포커스 거절 시 강제 재생하지 않는다", "tts:speak" in events)
    }

    @Test
    fun 포커스_획득후_선행묵음_다음에_TTS를_재생하고_완료시_포커스를_반납한다() = runTest {
        val events = mutableListOf<String>()
        val focus = FakeAudioFocus(grant = true)
        val client = FakeSpeechClient(events)
        val gateway = PleosVoiceGateway(
            client = client,
            audioFocus = focus,
            preRollSilence = { events += "preroll" },
            logWarning = {},
        )

        val result = gateway.speak("지금 웨이팅을 걸까요?")

        assertTrue(result.isSuccess)
        assertEquals(listOf("preroll", "tts:speak", "tts:stop"), events)
        assertEquals(listOf("focus:request:SPEECH_OUTPUT", "focus:abandon"), focus.events)
    }

    @Test
    fun TTS재생중_오디오포커스_상실시_즉시_중단하고_포커스를_반납한다() = runTest {
        val events = mutableListOf<String>()
        val focus = FakeAudioFocus(grant = true)
        val client = FakeSpeechClient(events).apply { autoCompleteTts = false }
        val gateway = PleosVoiceGateway(
            client = client,
            audioFocus = focus,
            preRollSilence = { events += "preroll" },
            logWarning = {},
        )

        var speakResult: Result<Unit>? = null
        val job = launch { speakResult = gateway.speak("지금 웨이팅을 걸까요?") }
        runCurrent()
        assertTrue("tts:speak" in events)

        focus.dispatchLoss(AudioFocusLoss.TRANSIENT)
        job.join()

        assertTrue(speakResult?.isFailure == true)
        assertTrue("tts:stop" in events)
        assertEquals("focus:abandon", focus.events.last())
    }

    @Test
    fun 선행묵음중_포커스_상실시_TTS재생을_시작하지_않는다() = runTest {
        val events = mutableListOf<String>()
        val focus = FakeAudioFocus(grant = true)
        val client = FakeSpeechClient(events)
        val preRollGate = CompletableDeferred<Unit>()
        val gateway = PleosVoiceGateway(
            client = client,
            audioFocus = focus,
            preRollSilence = {
                events += "preroll"
                preRollGate.await()
            },
            logWarning = {},
        )

        var speakResult: Result<Unit>? = null
        val job = launch { speakResult = gateway.speak("지금 웨이팅을 걸까요?") }
        runCurrent()
        focus.dispatchLoss(AudioFocusLoss.PERMANENT)
        preRollGate.complete(Unit)
        job.join()

        assertTrue(speakResult?.isFailure == true)
        assertFalse("포커스 상실 후에는 TTS를 시작하지 않는다", "tts:speak" in events)
        assertEquals("focus:abandon", focus.events.last())
    }

    @Test
    fun 오디오_포커스_거절시_STT인식을_시작하지_않는다() = runTest {
        val events = mutableListOf<String>()
        val focus = FakeAudioFocus(grant = false)
        val client = FakeSpeechClient(events)
        val gateway = PleosVoiceGateway(
            client = client,
            audioFocus = focus,
            preRollSilence = {},
            logWarning = {},
        )

        val result = gateway.listen()

        assertTrue(result.isFailure)
        assertEquals(AudioFocusPurpose.SPEECH_INPUT, focus.lastPurpose)
        assertFalse("stt:request" in events)
    }

    @Test
    fun STT인식중_포커스_상실시_즉시_중단하고_포커스를_반납한다() = runTest {
        val events = mutableListOf<String>()
        val focus = FakeAudioFocus(grant = true)
        val client = FakeSpeechClient(events).apply { autoCompleteSttText = null }
        val gateway = PleosVoiceGateway(
            client = client,
            audioFocus = focus,
            preRollSilence = {},
            logWarning = {},
        )

        var listenResult: Result<String?>? = null
        val job = launch { listenResult = gateway.listen() }
        runCurrent()
        assertTrue("stt:request" in events)

        focus.dispatchLoss(AudioFocusLoss.CAN_DUCK)
        job.join()

        assertTrue(listenResult?.isFailure == true)
        assertTrue("stt:stop" in events)
        assertEquals("focus:abandon", focus.events.last())
    }

    @Test
    fun stop과_release는_오디오_포커스를_반납한다() {
        val events = mutableListOf<String>()
        val focus = FakeAudioFocus(grant = true)
        val client = FakeSpeechClient(events)
        val gateway = PleosVoiceGateway(client, focus, preRollSilence = {}, logWarning = {})

        gateway.stop()
        gateway.release()

        assertTrue(focus.events.count { it == "focus:abandon" } >= 2)
        assertTrue("client:release" in events)
    }
}
