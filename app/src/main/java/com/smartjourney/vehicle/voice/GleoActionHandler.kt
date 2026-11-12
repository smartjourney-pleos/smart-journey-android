package com.smartjourney.vehicle.voice

import com.smartjourney.vehicle.domain.WaitingStatus
import com.smartjourney.vehicle.engine.JourneyState
import com.smartjourney.vehicle.engine.Prompt
import com.smartjourney.vehicle.engine.StopState
import com.smartjourney.vehicle.engine.SubmissionStatus
import com.smartjourney.vehicle.engine.statusLine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** Android 전송과 분리한 Gleo 대화 처리. 전달 사실을 재생 완료/사용자 발화의 증거로 쓰지 않는다. */
class GleoActionHandler(
    private val state: StateFlow<JourneyState>,
    private val consented: () -> Boolean,
    private val isDemo: () -> Boolean,
    private val respond: (String, VoiceDecision) -> Boolean,
    private val handoff: (String?) -> Unit,
    private val scope: CoroutineScope,
    private val enqueueLateResult: (LateVoiceResult) -> Unit,
    private val now: () -> Long = System::currentTimeMillis,
    private val newToken: () -> String = { UUID.randomUUID().toString() },
) {
    private data class Question(val prompt: Prompt, val token: String, val expiresAt: Long)
    private var question: Question? = null
    private val watching = mutableSetOf<String>()
    private val announced = linkedSetOf<String>()

    suspend fun handle(action: String, parameters: JSONObject = JSONObject()): GleoReply {
        if (!consented()) {
            invalidate()
            return GleoReply(action, "fail", "Data sharing consent is required before starting a journey.")
        }
        return when (action) {
            STATUS -> status(action)
            RESPOND -> answer(action, parameters)
            else -> GleoReply(action, "fail", "Unknown function.")
        }
    }

    fun invalidate() { question = null; handoff(null) }

    /** Gleo 결과 응답으로 출력 소유권을 넘긴 건은 앱 TTS로 중복 안내하지 않는다. 재생 완료 증거는 아니다. */
    fun shouldAnnounce(result: LateVoiceResult): Boolean = consented() && state.value.active &&
        result.requestId !in announced && state.value.stops.any { it.submissionId == result.requestId }

    fun markAnnounced(requestId: String) {
        announced += requestId
        while (announced.size > 32) announced.remove(announced.first())
    }

    private fun status(action: String): GleoReply {
        val s = state.value
        s.current?.takeIf { it.submission == SubmissionStatus.REQUESTING }?.let { return pending(action, it) }
        val prompt = s.prompt
        if (prompt != null && (prompt.expiresAt == null || now() < prompt.expiresAt)) return ask(action, prompt)
        invalidate()
        val stop = s.current
        if (stop != null && stop.submission != SubmissionStatus.NOT_REQUESTED) {
            stop.submissionId?.let(::markAnnounced)
            return result(action, stop)
        }
        return GleoReply(action, "success", "The current journey status is available.",
            JSONObject().put("demo", isDemo()).put("active", s.active)
                .put("status", if (s.active) s.statusLine() else "No active journey."))
    }

    private suspend fun answer(action: String, parameters: JSONObject): GleoReply {
        val prompt = state.value.prompt ?: return GleoReply(action, "fail", "There is no pending question. Check the current status; do not submit again.")
        if (prompt.expiresAt != null && now() >= prompt.expiresAt) {
            invalidate()
            return GleoReply(action, "fail", "The question has expired. Nothing was executed.")
        }
        val decision = when (parameters.optString("decision")) {
            "approve" -> VoiceDecision.APPROVE
            "defer" -> VoiceDecision.DEFER
            else -> null
        }
        val issued = question
        // 상태 조회만 했다는 이유나 모델이 만든 ID만으로 승인하지 않는다.
        // Gleo가 새 사용자 응답으로 호출했다는 신뢰 경계는 남으며, 실제 음성 재시험이 필요하다.
        if (decision == null || issued == null || issued.prompt != prompt || now() >= issued.expiresAt ||
            parameters.optString("questionToken") != issued.token
        ) return ask(action, prompt)

        question = null // 중복·동시 호출보다 먼저 소모한다.
        val accepted = respond(prompt.id, decision)
        handoff(null) // 엔진이 질문을 소모한 다음 자체 음성 소유권을 돌려준다.
        if (!accepted) return GleoReply(action, "fail", "The question has expired or has already been answered.")
        if (decision != VoiceDecision.APPROVE || prompt.kind != Prompt.Kind.GOLDEN_TIME) {
            return GleoReply(action, "success", "The voice response was accepted.", JSONObject().put("decision", parameters.getString("decision")))
        }
        val completed = withTimeoutOrNull(5_000) {
            state.first { s -> s.stops.none { it.submissionId == prompt.id && it.submission == SubmissionStatus.REQUESTING } }
        }
        val stop = (completed ?: state.value).stops.find { it.submissionId == prompt.id }
            ?: return GleoReply(action, "fail", "The original request is no longer available. Check the current status; do not submit again.")
        if (stop.submission == SubmissionStatus.REQUESTING) {
            watchLateResult(prompt.id, isDemo())
            return pending(action, stop)
        }
        markAnnounced(prompt.id)
        return result(action, stop)
    }

    private fun ask(action: String, prompt: Prompt): GleoReply {
        val token = newToken()
        question = Question(prompt, token, minOf(prompt.expiresAt ?: Long.MAX_VALUE, now() + 60_000))
        handoff(prompt.id)
        return GleoReply(action, "asking",
            "Read data.question to the user in Korean. Wait for a NEW explicit user answer after the question. " +
                "Only then call $RESPOND with decision approve or defer and copy data.questionToken EXACTLY. " +
                "Never invent a token, infer approval from silence, or reuse an answer from an earlier question. " +
                "If the token is unavailable, get the current question again. Do not claim completion unless registrationStatus is CONFIRMED.",
            JSONObject().put("demo", isDemo()).put("question", prompt.spokenText().filterNot { it.isISOControl() }.take(200))
                .put("questionToken", token).put("requiresApproval", true))
    }

    private fun pending(action: String, stop: StopState) = GleoReply(action, "asking",
        "The registration is still being processed, not confirmed and not rejected. Tell the user to wait; do not ask for approval again or resubmit. " +
            "The app will announce the final result while the journey remains active. Use $STATUS to check the current result.",
        details(stop).put("requiresApproval", false))

    private fun result(action: String, stop: StopState): GleoReply = when (stop.submission) {
        SubmissionStatus.CONFIRMED -> GleoReply(action, "success",
            if (isDemo()) "Demo registration confirmed. This is not a real restaurant booking." else "The waiting registration was confirmed.", details(stop))
        SubmissionStatus.REJECTED -> GleoReply(action, "fail", "The registration was rejected. Nothing was confirmed. Check the restaurant policy.", details(stop))
        SubmissionStatus.UNKNOWN -> GleoReply(action, "fail", "The registration result is unknown. Do not submit again. Check the existing request after parking.", details(stop))
        SubmissionStatus.REQUESTING -> pending(action, stop)
        else -> GleoReply(action, "fail", "No registration has been confirmed.", details(stop))
    }

    private fun details(stop: StopState) = JSONObject().put("demo", isDemo()).put("storeName", stop.store.name)
        .put("registrationStatus", stop.submission.name).put("registrationConfirmed", stop.submission == SubmissionStatus.CONFIRMED)
        .apply { stop.waiting?.let { put("partySize", it.partySize); put("ticketNo", it.ticketNo); put("aheadTeams", it.aheadTeams) } }

    private fun watchLateResult(requestId: String, demo: Boolean) {
        if (!watching.add(requestId)) return
        scope.launch {
            try {
                val s = withTimeoutOrNull(60_000) {
                    state.first { !it.active || it.stops.none { stop -> stop.submissionId == requestId && stop.submission == SubmissionStatus.REQUESTING } }
                } ?: state.value
                val stop = s.stops.find { it.submissionId == requestId } ?: return@launch
                val result = LateVoiceResult(requestId, lateResultText(stop, demo) ?: return@launch)
                if (shouldAnnounce(result)) enqueueLateResult(result)
            } finally { watching.remove(requestId) }
        }
    }

    /** 대기 중 상태가 바뀌었으면 발화 직전에 최신 결과를 읽는다. */
    fun lateResultText(result: LateVoiceResult): String? = state.value.stops
        .find { it.submissionId == result.requestId }?.let { lateResultText(it, isDemo()) }

    private fun lateResultText(stop: StopState, demo: Boolean): String? = when (stop.submission) {
        SubmissionStatus.CONFIRMED -> stop.waiting?.let {
            when (it.status) {
                WaitingStatus.CANCELLED, WaitingStatus.NO_SHOW ->
                    "${stop.store.name} 접수가 취소됐어요. 정차 후 매장 안내를 확인해 주세요."
                WaitingStatus.SEATED -> "${stop.store.name} 착석 처리됐어요."
                WaitingStatus.CALLED -> "${stop.store.name}에서 번호 ${it.ticketNo}번을 호출했어요. 일행 도착을 확인해 주세요."
                else -> "${if (demo) "데모 접수예요. " else ""}${stop.store.name} ${it.partySize}명 접수 확정, 번호 ${it.ticketNo}번이에요."
            }
        }
        SubmissionStatus.REJECTED -> "${stop.store.name} 접수가 거절됐어요. 접수되지 않았어요. 정차 후 매장 정책을 확인해 주세요."
        else -> "${stop.store.name} 접수 결과를 확인하지 못했어요. 다시 접수하지 말고 안전하게 정차한 뒤 확인해 주세요."
    }

    companion object {
        const val STATUS = "GET_SMART_JOURNEY_STATUS"
        const val RESPOND = "RESPOND_SMART_JOURNEY_PROMPT"
    }
}

data class GleoReply(val action: String, val status: String, val message: String, val data: JSONObject? = null) {
    fun toJson(): String = JSONObject().put("functionName", action).put("status", status).put("message", message).put("mode", "voice")
        .apply { data?.let { put("data", JSONArray().put(it).toString()) } }.toString()
}

data class LateVoiceResult(val requestId: String, val text: String)
