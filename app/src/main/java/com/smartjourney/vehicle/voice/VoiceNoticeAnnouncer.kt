package com.smartjourney.vehicle.voice

import com.smartjourney.vehicle.engine.JourneyState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 주행 중 오버레이 팝업 없이도 음성만으로 접수 결과·호출·경로 변경을 확인할 수 있도록
 * `JourneyEngine.notices`와 음성 실패 안내를 TTS로 직렬 발화한다.
 *
 * - 진행 중인 질문(`state.prompt != null`)이나 Gleo 발화 유예(`gleoQuietUntil`) 중에는 발화를 생략해
 *   운전자 승인 질문이나 Gleo 응답을 방해하지 않는다.
 * - 실제 오디오 포커스 획득·선행 묵음·반납은 [VoiceGateway] 구현체(`PleosVoiceGateway`)가 일관 처리한다.
 */
class VoiceNoticeAnnouncer(
    private val voice: VoiceGateway,
    private val state: StateFlow<JourneyState>,
    private val consented: () -> Boolean,
    private val gleoQuietUntil: StateFlow<Long>,
    private val speechMutex: Mutex,
    private val now: () -> Long = System::currentTimeMillis,
) {
    /**
     * 단발성 결과 안내 문구를 발화한다.
     * 발화 조건(동의 완료, Gleo 유예 아님, 기본값일 때 활성 질문 없음)을 만족하지 않거나
     * 음성 출력에 실패하면 예외를 전파하지 않고 조용히 생략한다(시스템 알림 `Notices.result`가 병행 표시됨).
     */
    suspend fun announce(message: String, skipIfPromptActive: Boolean = true): Boolean {
        val text = message.trim()
        if (text.isEmpty() || !canSpeakNow(skipIfPromptActive)) return false
        return speechMutex.withLock {
            if (!canSpeakNow(skipIfPromptActive)) return@withLock false
            try {
                voice.speak(text).isSuccess
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                false
            } finally {
                voice.stop()
            }
        }
    }

    private fun canSpeakNow(skipIfPromptActive: Boolean): Boolean =
        consented() && (!skipIfPromptActive || state.value.prompt == null) && now() >= gleoQuietUntil.value
}

/**
 * 질문 음성 처리가 실패했을 때 운전자에게 "아무것도 실행하지 않았음"을 알리는 짧은 TTS 문구.
 * `SPEECH_UNAVAILABLE`는 TTS 자체가 불가한 상태이므로 재시도하지 않고 null을 반환한다.
 */
fun voiceUnavailableSpokenText(issue: VoiceIssue): String? = when (issue) {
    VoiceIssue.SPEECH_UNAVAILABLE -> null
    VoiceIssue.MICROPHONE_UNAVAILABLE -> "마이크를 쓰지 못해 실행하지 않았어요. 정차 후 확인해 주세요."
    VoiceIssue.NO_RESPONSE -> "응답이 없어 실행하지 않았어요."
    VoiceIssue.UNRECOGNIZED -> "답변을 이해하지 못해 실행하지 않았어요."
}
