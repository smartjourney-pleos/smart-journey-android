package com.smartjourney.vehicle.voice

import android.content.Context
import android.media.AudioAttributes
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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale

/** Pleos Speech Service의 온디바이스 모드. 서버 자격 증명이나 자체 녹음은 사용하지 않는다. */
class PleosVoiceGateway(context: Context) : VoiceGateway {
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
                    tts.setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANT)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
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

    override suspend fun speak(text: String): Result<Unit> = attempt {
        initialize()
        check(withTimeoutOrNull(4_000) { ttsReady.await(); true } == true) { "음성 안내 연결 시간 초과" }
        val done = CompletableDeferred<Result<Unit>>()
        val listener = object : EventListener {
            override fun onReady() = Unit
            override fun onStart() = Unit
            override fun onUpdatedRms(rms: Double) = Unit
            override fun onDone() { done.complete(Result.success(Unit)) }
            override fun onStop() { done.complete(Result.failure(IllegalStateException("음성 안내 중단"))) }
            override fun onError(errorType: TtsError) { done.complete(Result.failure(IllegalStateException(errorType.name))) }
        }
        tts.addEventListener(listener)
        try {
            tts.speak(text)
            (withTimeoutOrNull(25_000) { done.await() }
                ?: Result.failure(IllegalStateException("음성 안내 시간 초과"))).getOrThrow()
        } finally {
            runCatching { tts.removeEventListener(listener) }
            runCatching { tts.stop() }
        }
    }

    override suspend fun listen(): Result<String?> = attempt {
        initialize()
        check(withTimeoutOrNull(4_000) { sttReady.await(); true } == true) { "음성 응답 연결 시간 초과" }
        val done = CompletableDeferred<Result<String?>>()
        val listener = finalSpeechListener(done)
        stt.addListener(listener)
        try {
            stt.request()
            withTimeoutOrNull(8_000) { done.await() }?.getOrThrow()
        } finally {
            runCatching { stt.removeListener(listener) }
            runCatching { stt.stop() }
        }
    }

    override fun stop() {
        if (!initialized) return
        runCatching { tts.stop() }
        runCatching { stt.stop() }
    }

    override fun release() {
        stop()
        runCatching { tts.release() }
        runCatching { stt.release() }
    }

    private suspend fun <T> attempt(block: suspend () -> T): Result<T> = try {
        Result.success(block())
    } catch (e: CancellationException) { throw e }
    catch (e: Exception) {
        Log.w("SJ-VOICE", "Pleos 음성 처리 실패: ${e.message}")
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
