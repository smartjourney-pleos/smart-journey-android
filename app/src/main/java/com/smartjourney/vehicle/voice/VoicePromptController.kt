package com.smartjourney.vehicle.voice

import com.smartjourney.vehicle.engine.Prompt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull

enum class VoiceDecision { APPROVE, DEFER }
enum class VoiceIssue { SPEECH_UNAVAILABLE, MICROPHONE_UNAVAILABLE, NO_RESPONSE, UNRECOGNIZED }

interface VoiceGateway {
    suspend fun speak(text: String): Result<Unit>
    suspend fun listen(): Result<String?>
    fun stop()
    fun release()
}

/** 문장 일부의 '네'를 승인으로 오인하지 않는다. 정해진 명확한 응답만 실행한다. */
fun voiceDecision(utterance: String, kind: Prompt.Kind): VoiceDecision? {
    val text = utterance.trim().lowercase().replace(Regex("[\\s.!?,。！？]+"), "")
    val defer = setOf(
        "아니", "아니요", "아니오", "안돼", "안돼요", "하지마", "하지마세요", "걸지마", "걸지마세요",
        "안걸어", "안걸래", "안걸어요", "안할래", "안할게요",
        "보류", "보류해줘", "미뤄줘", "미뤄주세요", "미룰게", "미룰게요", "패스", "취소",
        "나중에", "나중에요", "나중에할게요", "그대로", "그대로요", "닫기", "닫아줘",
    )
    if (text in defer) return VoiceDecision.DEFER
    val approve = setOf("네", "예", "응", "좋아", "좋아요") + when (kind) {
        // "응, 걸어 줘"처럼 짧은 답. 공백·문장부호는 위에서 지운다.
        Prompt.Kind.GOLDEN_TIME -> setOf(
            "걸어", "걸어줘", "걸어주세요", "응걸어줘", "네걸어줘", "웨이팅걸어줘", "그래도걸어줘",
            "등록", "등록해", "등록해줘", "등록해주세요",
        )
        Prompt.Kind.PARTY_CHANGE -> setOf("바꾸기", "바꿔줘", "바꿔주세요", "변경해줘")
        Prompt.Kind.MANUAL_ARRIVAL -> setOf("도착", "도착했어", "도착했어요")
        Prompt.Kind.CALLED -> setOf("확인", "알겠어", "알겠어요")
    }
    return if (text in approve) VoiceDecision.APPROVE else null
}

fun Prompt.spokenText(): String = when (kind) {
    // ON_DEVICE STT에서 "걸어 줘"가 오인식되는 사례를 줄이기 위해 짧은 키워드("응 / 나중에")를 함께 안내한다.
    Prompt.Kind.GOLDEN_TIME -> if (waitLimitMin != null) {
        "$storeName 대기 ${waitMin}분이에요. 그래도 걸까요? 응이나 나중에라고 말해 주세요."
    } else {
        "${storeName}까지 ${remainingMin}분, 대기 ${waitMin}분이에요. ${partySize}명 걸까요? 응이나 나중에라고 말해 주세요."
    }
    Prompt.Kind.PARTY_CHANGE -> "$storeName 인원을 ${partySize}명에서 ${newPartySize}명으로 바꿀까요? 바꿔 줘, 또는 그대로라고 말씀해 주세요."
    Prompt.Kind.MANUAL_ARRIVAL -> "$storeName 도착을 자동으로 확인하지 못했어요. 도착하셨다면 도착했어요, 아니면 나중에라고 말씀해 주세요."
    Prompt.Kind.CALLED -> "$storeName 입장 순서예요. ${partySize}명, 대기 번호 ${ticketNo ?: "없음"}번이에요. 확인이라고 말씀해 주세요."
}

/** 1차 인식이 모호할 때 질문 유형에 맞는 짧은 키워드로 한 번만 다시 안내한다. */
fun Prompt.retrySpokenText(): String = when (kind) {
    Prompt.Kind.GOLDEN_TIME -> "잘 이해하지 못했어요. 걸려면 응, 미루려면 나중에라고 말씀해 주세요."
    Prompt.Kind.PARTY_CHANGE -> "잘 이해하지 못했어요. 바꾸려면 바꿔 줘, 유지하려면 그대로라고 말씀해 주세요."
    Prompt.Kind.MANUAL_ARRIVAL -> "잘 이해하지 못했어요. 도착하셨으면 도착했어요, 아니면 나중에라고 말씀해 주세요."
    Prompt.Kind.CALLED -> "잘 이해하지 못했어요. 확인하셨으면 확인이라고 말씀해 주세요."
}

/** 질문을 다 읽은 뒤 마이크를 열고, 그 질문에 대한 최종 응답만 한 번 전달한다. */
class VoicePromptController(
    private val voice: VoiceGateway,
    private val currentPrompt: () -> Prompt?,
    private val respond: (String, VoiceDecision) -> Boolean,
    private val onUnavailable: (VoiceIssue) -> Unit,
    private val now: () -> Long = System::currentTimeMillis,
    /** 앱 TTS가 질문을 읽기 시작할 때 호출. 콜드 부팅 발화 도중 질문이 잘리지 않게 상한만 확보한다. */
    private val onSpeaking: (String) -> Unit = {},
    /** 앱 TTS가 질문을 끝까지 읽은 관측. 외부 Gleo 응답의 인증으로 사용하지 않는다. */
    private val onSpoken: (String) -> Unit = {},
) {
    private fun isCurrent(prompt: Prompt) = currentPrompt()?.id == prompt.id &&
        (prompt.expiresAt == null || now() < prompt.expiresAt)

    suspend fun handle(prompt: Prompt) {
        try {
            if (!isCurrent(prompt)) return
            onSpeaking(prompt.id)
            if (voice.speak(prompt.spokenText()).isFailure) { onUnavailable(VoiceIssue.SPEECH_UNAVAILABLE); return }
            onSpoken(prompt.id)
            repeat(2) { attempt ->
                if (!isCurrent(prompt)) return
                val result = withTimeoutOrNull(10_000) { voice.listen() }
                if (!isCurrent(prompt)) return
                val text = result?.getOrNull()
                if (result?.isFailure == true) { onUnavailable(VoiceIssue.MICROPHONE_UNAVAILABLE); return }
                val decision = text?.let { voiceDecision(it, prompt.kind) }
                if (decision != null) {
                    respond(prompt.id, decision)
                    return
                }
                if (attempt == 0 && text != null && result?.isSuccess == true) {
                    if (voice.speak(prompt.retrySpokenText()).isFailure) {
                        onUnavailable(VoiceIssue.SPEECH_UNAVAILABLE); return
                    }
                } else {
                    onUnavailable(if (text == null) VoiceIssue.NO_RESPONSE else VoiceIssue.UNRECOGNIZED)
                    return
                }
            }
        } catch (e: CancellationException) {
            throw e
        } finally { voice.stop() }
    }
}
