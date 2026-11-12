package com.smartjourney.vehicle.voice

import com.smartjourney.vehicle.domain.WaitingStatus
import com.smartjourney.vehicle.engine.JourneyState
import com.smartjourney.vehicle.engine.StopState
import com.smartjourney.vehicle.engine.SubmissionStatus
import com.smartjourney.vehicle.engine.statusLine
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject

/**
 * Android 전송과 분리한 Gleo 대화 처리. 현재는 상태 조회만 지원한다.
 *
 * Gleo Action은 모델이 만든 `ACTION_PARAM`만 전달한다(Gleo Interface 가이드 p108, 설치된 Gleo AI 4.2.0.15의
 * `sendActionCall` 확인). 새 사용자 발화를 증명하는 값이 없으므로, 모델이 보낸 결정·토큰은 사용자 승인의 증거가 될 수 없다.
 * 그래서 Gleo를 통한 접수·인원 변경 등의 승인은 받지 않고 토큰도 발급하지 않는다. 승인은 앱 자체 음성 질문(앱 STT 최종 인식)과
 * 정차 중 터치로만 한다. 상태 조회는 앱의 음성 질문을 대신 맡지 않는다.
 */
class GleoActionHandler(
    private val state: StateFlow<JourneyState>,
    private val consented: () -> Boolean,
    private val isDemo: () -> Boolean,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val announced = linkedSetOf<String>()

    fun handle(action: String): GleoReply {
        if (!consented()) return GleoReply(action, "fail", "Data sharing consent is required before starting a journey.")
        return when (action) {
            STATUS -> status(action)
            // 예전 스키마를 기억한 Gleo가 부를 수 있다. 아무것도 실행하지 않고 승인에 쓸 값도 돌려주지 않는다.
            RESPOND -> GleoReply(action, "fail",
                "Smart Journey does not accept approvals through Gleo, so nothing was executed. " +
                    "The user can answer the Smart Journey voice question directly or use the app screen after parking.")
            else -> GleoReply(action, "fail", "Unknown function.")
        }
    }

    /** 이미 결과를 Gleo로 알린 접수는 앱 TTS로 중복 안내하지 않는다. 재생 완료 증거는 아니다. */
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
        if (prompt != null && (prompt.expiresAt == null || now() < prompt.expiresAt)) {
            return GleoReply(action, "success",
                "A Smart Journey question is waiting. The app asks it with its own voice prompt, and approvals are not accepted through Gleo.",
                JSONObject().put("demo", isDemo()).put("active", true).put("pendingQuestion", true)
                    .put("storeName", prompt.storeName).put("approvalThroughGleo", false))
        }
        val stop = s.current
        if (stop != null && stop.submission != SubmissionStatus.NOT_REQUESTED) {
            stop.submissionId?.let(::markAnnounced)
            return result(action, stop)
        }
        return GleoReply(action, "success", "The current journey status is available.",
            JSONObject().put("demo", isDemo()).put("active", s.active)
                .put("status", if (s.active) s.statusLine() else "No active journey."))
    }

    private fun pending(action: String, stop: StopState) = GleoReply(action, "asking",
        "The registration is still being processed, not confirmed and not rejected. Tell the user to wait; do not ask for approval again or resubmit. " +
            "Use $STATUS to check the current result.",
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
