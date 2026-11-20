package com.smartjourney.vehicle.voice

import android.content.Context
import android.util.Log
import ai.pleos.playground.tts.TextToSpeech
import ai.pleos.playground.tts.constant.Mode as TtsMode
import ai.pleos.playground.tts.constant.ErrorType as TtsError
import ai.pleos.playground.tts.listener.EventListener
import ai.pleos.playground.tts.listener.OnInitializeListener as TtsInitializeListener
import ai.pleos.playground.stt.SpeechToText
import ai.pleos.playground.stt.constant.Mode as SttMode
import ai.pleos.playground.stt.constant.ErrorType as SttError
import ai.pleos.playground.stt.listener.ResultListener
import ai.pleos.playground.stt.listener.OnInitializeListener as SttInitializeListener
import com.smartjourney.vehicle.BuildConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale

/** Pleos TTS/STT SDK 호출을 테스트 가능하게 분리한 내부 어댑터. */
internal interface PleosSpeechClient {
    suspend fun ensureTtsReady(): Boolean
    suspend fun ensureSttReady(): Boolean
    fun addTtsListener(listener: EventListener)
    fun removeTtsListener(listener: EventListener)
    fun speakText(text: String)
    fun stopTts()
    fun addSttListener(listener: ResultListener)
    fun removeSttListener(listener: ResultListener)
    fun requestStt()
    fun stopStt()
    fun release()
}

private class RealPleosSpeechClient(context: Context) : PleosSpeechClient {
    private val tts = TextToSpeech(context.applicationContext, TtsMode.ON_DEVICE)
    private val stt = SpeechToText(context.applicationContext, SttMode.ON_DEVICE)
    private val ttsReady = CompletableDeferred<Unit>()
    private val sttReady = CompletableDeferred<Unit>()
    private var initialized = false

    private fun initialize() {
        if (initialized) return
        initialized = true
        tts.initialize(object : TtsInitializeListener {
            override fun onSuccess() {
                runCatching {
                    tts.setLocale(Locale.KOREA)
                    tts.setAudioAttributes(assistantSpeechAudioAttributes())
                }.fold({ ttsReady.complete(Unit) }, { ttsReady.completeExceptionally(it) })
            }
            override fun onFailure(errorType: TtsError) { ttsReady.completeExceptionally(IllegalStateException(errorType.name)) }
        })
        stt.initialize(object : SttInitializeListener {
            override fun onSuccess() {
                runCatching { stt.setLocale(Locale.KOREA) }
                    .fold({ sttReady.complete(Unit) }, { sttReady.completeExceptionally(it) })
            }
            override fun onFailure(errorType: SttError) { sttReady.completeExceptionally(IllegalStateException(errorType.name)) }
        })
    }

    override suspend fun ensureTtsReady(): Boolean {
        initialize()
        return withTimeoutOrNull(4_000) { ttsReady.await(); true } == true
    }

    override suspend fun ensureSttReady(): Boolean {
        initialize()
        return withTimeoutOrNull(4_000) { sttReady.await(); true } == true
    }

    override fun addTtsListener(listener: EventListener) = tts.addEventListener(listener)
    override fun removeTtsListener(listener: EventListener) = tts.removeEventListener(listener)
    override fun speakText(text: String) = tts.speak(text)
    override fun stopTts() { if (initialized) runCatching { tts.stop() } }
    override fun addSttListener(listener: ResultListener) = stt.addListener(listener)
    override fun removeSttListener(listener: ResultListener) = stt.removeListener(listener)
    override fun requestStt() = stt.request()
    override fun stopStt() { if (initialized) runCatching { stt.stop() } }
    override fun release() {
        stopTts()
        stopStt()
        runCatching { tts.release() }
        runCatching { stt.release() }
    }
}

/** Pleos Speech Service의 온디바이스 모드. 서버 자격 증명이나 자체 녹음은 사용하지 않는다. */
class PleosVoiceGateway internal constructor(
    private val client: PleosSpeechClient,
    private val audioFocus: AudioFocusController,
    private val preRollSilence: PreRollSilence = AudioTrackPreRollSilence(),
    private val logWarning: (String) -> Unit = { Log.w("SJ-VOICE", it) },
) : VoiceGateway {

    constructor(context: Context) : this(
        client = RealPleosSpeechClient(context),
        audioFocus = SystemAudioFocusController(context),
        preRollSilence = AudioTrackPreRollSilence(),
    )

    override suspend fun speak(text: String): Result<Unit> = attempt {
        check(client.ensureTtsReady()) { "음성 안내 연결 시간 초과" }
        val done = CompletableDeferred<Result<Unit>>()
        // 포커스를 먼저 확보하고, 거절 시 강제 재생하지 않는다 (Pleos 오디오 포커스 가이드).
        val granted = audioFocus.request(AudioFocusPurpose.SPEECH_OUTPUT) { loss ->
            runCatching { client.stopTts() }
            done.complete(Result.failure(IllegalStateException("오디오 포커스 상실(${loss.name})")))
        }
        check(granted) { "오디오 포커스 획득 실패" }

        val listener = object : EventListener {
            override fun onReady() = Unit
            override fun onStart() = Unit
            override fun onUpdatedRms(rms: Double) = Unit
            override fun onDone() { done.complete(Result.success(Unit)) }
            override fun onStop() { done.complete(Result.failure(IllegalStateException("음성 안내 중단"))) }
            override fun onError(errorType: TtsError) { done.complete(Result.failure(IllegalStateException(errorType.name))) }
        }
        try {
            client.addTtsListener(listener)
            // 포커스 확보 후 첫 음절 잘림 방지를 위해 180ms 선행 묵음을 둔다.
            preRollSilence.play()
            // 선행 묵음 도중 포커스를 잃었으면 재생을 시작하지 않는다.
            if (done.isCompleted) done.await().getOrThrow()
            client.speakText(text)
            (withTimeoutOrNull(25_000) { done.await() }
                ?: Result.failure(IllegalStateException("음성 안내 시간 초과"))).getOrThrow()
        } finally {
            runCatching { client.removeTtsListener(listener) }
            runCatching { client.stopTts() }
            audioFocus.abandon()
        }
    }

    override suspend fun listen(): Result<String?> = attempt {
        check(client.ensureSttReady()) { "음성 응답 연결 시간 초과" }
        val done = CompletableDeferred<Result<String?>>()
        // 음성 인식 전에도 오디오 포커스를 확보해 배경 오디오 간섭과 강제 점유를 막는다.
        val granted = audioFocus.request(AudioFocusPurpose.SPEECH_INPUT) { loss ->
            runCatching { client.stopStt() }
            done.complete(Result.failure(IllegalStateException("오디오 포커스 상실(${loss.name})")))
        }
        check(granted) { "오디오 포커스 획득 실패" }

        val listener = finalSpeechListener(done)
        try {
            client.addSttListener(listener)
            client.requestStt()
            withTimeoutOrNull(8_000) { done.await() }?.getOrThrow()
        } finally {
            runCatching { client.removeSttListener(listener) }
            runCatching { client.stopStt() }
            audioFocus.abandon()
        }
    }

    override fun stop() {
        client.stopTts()
        client.stopStt()
        audioFocus.abandon()
    }

    override fun release() {
        stop()
        client.release()
    }

    private suspend fun <T> attempt(block: suspend () -> T): Result<T> = try {
        Result.success(block())
    } catch (e: CancellationException) { throw e }
    catch (e: Exception) {
        // 릴리스 로그에 발화·내부 세부 정보가 남지 않도록 예외 유형만 기록한다.
        val detail = if (BuildConfig.DEBUG) e.message else e::class.java.simpleName
        logWarning("Pleos 음성 처리 실패: $detail")
        Result.failure(e)
    }
}

/** 실제 Pleos는 onEndedRecognition 뒤에 최종 onUpdated를 보낼 수 있다. 종료는 무응답이 아니다. */
internal fun finalSpeechListener(done: CompletableDeferred<Result<String?>>) = object : ResultListener {
    override fun onReady() = Unit
    override fun onStartedRecognition() = Unit
    override fun onUpdated(stt: String, completed: Boolean) {
        if (completed) done.complete(Result.success(stt))
    }
    override fun onUpdatedEpdData(on: Long, off: Long) = Unit
    override fun onUpdatedRms(rms: Double) = Unit
    override fun onEndedRecognition() = Unit // 최종 텍스트 또는 상위의 유한 타임아웃을 기다린다.
    override fun onError(errorType: SttError) { done.complete(Result.failure(IllegalStateException(errorType.name))) }
}
