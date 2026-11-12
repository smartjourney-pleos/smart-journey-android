package com.smartjourney.vehicle.debug

import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import ai.pleos.playground.stt.SpeechToText
import ai.pleos.playground.stt.constant.Mode
import ai.pleos.playground.stt.constant.ErrorType
import ai.pleos.playground.stt.listener.OnInitializeListener
import ai.pleos.playground.stt.listener.ResultListener
import com.smartjourney.vehicle.graph
import com.smartjourney.vehicle.voice.voiceDecision
import com.smartjourney.vehicle.voice.finalSpeechListener
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import java.io.File
import java.util.Locale

/** 디버그 전용: 합성 PCM을 실제 Pleos ASR로 인식해 현재 질문에 전달한다. 차량 마이크는 녹음하지 않는다. */
class SpeechProbeActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prompt = graph.engine.state.value.prompt
        if (prompt == null) { Log.i(TAG, "No pending prompt"); finish(); return }
        graph.gleoPromptId.value = prompt.id
        lifecycleScope.launch {
            delay(300) // 현재 서비스의 마이크 해제가 끝나도록 한다.
            val stt = SpeechToText(applicationContext, Mode.ON_DEVICE)
            val initialized = CompletableDeferred<Unit>()
            val recognized = CompletableDeferred<Result<String?>>()
            val listener = finalSpeechListener(recognized)
            try {
                stt.initialize(object : OnInitializeListener {
                    override fun onSuccess() { initialized.complete(Unit) }
                    override fun onFailure(errorType: ErrorType) { initialized.completeExceptionally(IllegalStateException(errorType.name)) }
                })
                withTimeout(5_000) { initialized.await() }
                stt.setLocale(Locale.KOREA)
                stt.addListener(listener)
                val audio = File(filesDir, "voice-test.pcm").readBytes()
                require(audio.isNotEmpty() && audio.size <= 1_000_000)
                stt.sendAudio(audio)
                stt.completeAudioSend()
                val transcript = withTimeout(15_000) { recognized.await() }.getOrThrow().orEmpty()
                val decision = voiceDecision(transcript, prompt.kind)
                val accepted = decision?.let { graph.respondByVoice(prompt.id, it) } ?: false
                // 합성 음성의 인식 결과라 개인 발화가 아니다. 디버그 빌드에서만 남긴다.
                Log.i(TAG, "transcript=\"$transcript\" decision=$decision accepted=$accepted")
            } catch (e: Exception) { Log.e(TAG, "Speech probe failed: ${e.message}") }
            finally {
                runCatching { stt.removeListener(listener) }
                runCatching { stt.stop() }
                runCatching { stt.release() }
                graph.navi.showNavigation()
                finish()
            }
        }
    }
    private companion object { const val TAG = "SJ-SPEECH-PROBE" }
}
