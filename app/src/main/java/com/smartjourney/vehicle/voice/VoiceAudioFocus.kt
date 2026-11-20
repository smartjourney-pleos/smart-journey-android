package com.smartjourney.vehicle.voice

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.AudioTrack
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * 음성 입출력 목적에 따른 오디오 포커스 유형.
 * 웨이팅 안내는 내비게이션·긴급 컨텍스트로 격상하지 않고 ASSISTANT/SPEECH를 유지한다.
 */
enum class AudioFocusPurpose {
    SPEECH_OUTPUT,
    SPEECH_INPUT,
}

/** 오디오 포커스 상실 유형. */
enum class AudioFocusLoss {
    PERMANENT,
    TRANSIENT,
    CAN_DUCK,
}

/**
 * Pleos 오디오 포커스 제어 추상화.
 * 포커스 획득 실패 시 재생/인식을 차단하고, 상실·종료·Suspend 시 즉시 반납한다.
 */
interface AudioFocusController {
    fun request(purpose: AudioFocusPurpose, onFocusLost: (AudioFocusLoss) -> Unit): Boolean
    fun abandon()
}

/**
 * Android AudioManager(API 28+) 기반 오디오 포커스 제어 구현체.
 * TTS는 TRANSIENT_MAY_DUCK, STT는 배경 오디오 간섭을 막기 위해 TRANSIENT_EXCLUSIVE를 사용한다.
 */
class SystemAudioFocusController(context: Context) : AudioFocusController {
    private val audioManager = context.applicationContext.getSystemService(AudioManager::class.java)
    private val lock = Any()
    private var activeRequest: AudioFocusRequest? = null

    override fun request(purpose: AudioFocusPurpose, onFocusLost: (AudioFocusLoss) -> Unit): Boolean {
        val manager = audioManager ?: return false
        val gain = when (purpose) {
            AudioFocusPurpose.SPEECH_OUTPUT -> AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK
            AudioFocusPurpose.SPEECH_INPUT -> AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE
        }
        val request = AudioFocusRequest.Builder(gain)
            .setAudioAttributes(assistantSpeechAudioAttributes())
            // 짧은 음성 질문이 다른 음성과 겹쳐 오인식·오승인되는 것을 막기 위해 duck 대신 일시 중단을 받는다.
            .setWillPauseWhenDucked(true)
            .setOnAudioFocusChangeListener { change ->
                when (change) {
                    AudioManager.AUDIOFOCUS_LOSS -> onFocusLost(AudioFocusLoss.PERMANENT)
                    AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> onFocusLost(AudioFocusLoss.TRANSIENT)
                    AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> onFocusLost(AudioFocusLoss.CAN_DUCK)
                }
            }
            .build()

        synchronized(lock) {
            activeRequest?.let { previous ->
                runCatching { manager.abandonAudioFocusRequest(previous) }
                activeRequest = null
            }
            val result = runCatching { manager.requestAudioFocus(request) }
                .getOrDefault(AudioManager.AUDIOFOCUS_REQUEST_FAILED)
            // 지연(DELAYED)이나 거절(FAILED) 상태에서 강제 재생하지 않는다.
            if (result != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) return false
            activeRequest = request
            return true
        }
    }

    override fun abandon() {
        val manager = audioManager ?: return
        val toAbandon = synchronized(lock) {
            activeRequest.also { activeRequest = null }
        }
        if (toAbandon != null) {
            runCatching { manager.abandonAudioFocusRequest(toAbandon) }
        }
    }
}

/**
 * TTS 첫 음절 잘림을 줄이기 위한 선행 묵음(150~200ms 권장) 실행기.
 * 오디오 포커스 확보 이후, TTS 재생 직전에만 호출하며 포커스 확보를 대체하지 않는다.
 */
fun interface PreRollSilence {
    suspend fun play()
}

/**
 * 동일한 ASSISTANT/SPEECH 경로에 무음 PCM 버퍼를 180ms 재생해 차량 출력 경로를 예열한다.
 * AudioTrack 생성이 제한된 환경에서는 예외를 삼키고 동일 길이 대기로 안전하게 대체한다.
 */
class AudioTrackPreRollSilence(
    private val durationMs: Long = DEFAULT_PRE_ROLL_MS,
) : PreRollSilence {
    override suspend fun play() {
        if (durationMs <= 0L) return
        val track = withContext(Dispatchers.IO) {
            runCatching {
                val sampleCount = ((SAMPLE_RATE_HZ * durationMs) / 1_000L).toInt().coerceAtLeast(1)
                val silentPcm = ByteArray(sampleCount * BYTES_PER_SAMPLE)
                val candidate = AudioTrack.Builder()
                    .setAudioAttributes(assistantSpeechAudioAttributes())
                    .setAudioFormat(
                        AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(SAMPLE_RATE_HZ)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                            .build(),
                    )
                    .setBufferSizeInBytes(silentPcm.size)
                    .setTransferMode(AudioTrack.MODE_STATIC)
                    .build()
                if (candidate.state != AudioTrack.STATE_INITIALIZED || candidate.write(silentPcm, 0, silentPcm.size) <= 0) {
                    candidate.release()
                    return@runCatching null
                }
                candidate.play()
                candidate
            }.getOrNull()
        }
        if (track == null) {
            delay(durationMs)
            return
        }
        try {
            // 코루틴 취소(포커스 상실·질문 만료)에 즉시 반응하도록 스레드 블로킹 대신 delay를 사용한다.
            delay(durationMs)
        } finally {
            withContext(NonCancellable + Dispatchers.IO) {
                runCatching { track.stop() }
                runCatching { track.release() }
            }
        }
    }

    companion object {
        const val DEFAULT_PRE_ROLL_MS = 180L
        private const val SAMPLE_RATE_HZ = 16_000
        private const val BYTES_PER_SAMPLE = 2
    }
}

/** 웨이팅 음성 안내·인식에 공통 적용하는 AudioAttributes (내비·긴급 격상 금지). */
internal fun assistantSpeechAudioAttributes(): AudioAttributes =
    AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ASSISTANT)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()
