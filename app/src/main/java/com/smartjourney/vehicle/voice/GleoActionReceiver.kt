package com.smartjourney.vehicle.voice

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.smartjourney.vehicle.AppGraph
import com.smartjourney.vehicle.BuildConfig
import com.smartjourney.vehicle.engine.Prompt
import com.smartjourney.vehicle.engine.StopPhase
import com.smartjourney.vehicle.engine.statusLine
import com.smartjourney.vehicle.graph
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener

/** Gleo Interface의 component=service는 Service 실행이 아니라 Broadcast다. UI를 열지 않는다. */
class GleoActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        // Gleo AI 4.2.0.15는 "<패키지명>.<function 이름>"으로 보낸다(에뮬레이터 AppActionViewModel.sendActionCall 확인).
        // 문서 예시처럼 이름만 오는 경우도 받는다. 응답의 functionName은 스키마의 function 이름을 쓴다.
        val action = intent.action?.removePrefix("${context.packageName}.") ?: return
        if (action != STATUS && action != RESPOND) return
        val pending = goAsync()
        val graph = context.graph
        graph.scope.launch {
            try {
                if (!graph.consented.value) {
                    reply(context, action, "fail", "Data sharing consent is required before starting a journey.")
                    return@launch
                }
                when (action) {
                    STATUS -> status(context, graph)
                    RESPOND -> respond(context, graph, parameters(intent))
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w("GleoAction", "음성 요청을 처리하지 못함", e)
                reply(context, action, "fail", "The voice request could not be processed.")
            } finally { pending.finish() }
        }
    }

    private fun status(context: Context, graph: AppGraph) {
        val state = graph.engine.state.value
        val prompt = state.prompt
        if (prompt != null) {
            // Gleo가 대화를 맡은 동안 자체 STT를 중단해 마이크가 겹치지 않게 한다.
            graph.gleoPromptId.value = prompt.id
            ask(context, STATUS, prompt)
        } else {
            reply(context, STATUS, "success", "The current journey status is available.",
                JSONObject().put("demo", graph.backend.isDemo).put("active", state.active)
                    .put("status", if (state.active) state.statusLine() else "No active journey."))
        }
    }

    private suspend fun respond(context: Context, graph: AppGraph, parameters: JSONObject) {
        val prompt = graph.engine.state.value.prompt
        if (prompt == null) {
            reply(context, RESPOND, "fail", "There is no pending question to answer.")
            return
        }
        val decision = when (parameters.optString("decision")) {
            "approve" -> VoiceDecision.APPROVE
            "defer" -> VoiceDecision.DEFER
            else -> null
        }
        // 승인은 Gleo로 방금 읽어 준 질문(앱이 기억한 ID)에만 적용한다. 언어 모델이 넘기는 질문 ID는 쓰지 않는다:
        // 에뮬레이터에서 Gleo가 실제 ID 대신 "42dot_waiting_prompt" 같은 값을 지어내 보낸 것을 확인했다.
        // 승인은 사용자가 실제로 들은 질문에만 적용한다: Gleo가 읽어 준 질문(asking 응답) 또는 앱 음성으로 끝까지 읽은 질문.
        // 언어 모델이 넘기는 질문 ID는 쓰지 않는다(에뮬레이터에서 "42dot_waiting_prompt"처럼 지어낸 값을 보냈다).
        // 짧은 "네"는 Gleo가 일상 대화로 분류하므로 사용자는 "스마트 저니 등록해 줘"처럼 앱 이름을 넣어 답한다.
        val heard = graph.gleoPromptId.value == prompt.id || graph.spokenPromptId.value == prompt.id
        if (decision == null || !heard) {
            // 답이 모호하거나 이 질문을 들려준 적이 없다(질문이 바뀐 경우 포함). 실행하지 않고 질문을 읽게 한다.
            graph.gleoPromptId.value = prompt.id
            ask(context, RESPOND, prompt)
            return
        }
        val id = prompt.id
        val storeId = graph.engine.state.value.current?.store?.id
        // 한 번 쓴 근거로 다음 질문을 승인하지 않도록 지운다.
        graph.gleoPromptId.value = null
        graph.spokenPromptId.value = null
        if (!graph.respondByVoice(id, decision)) {
            reply(context, RESPOND, "fail", "The question has expired or has already been answered.")
            return
        }
        if (decision == VoiceDecision.APPROVE && prompt.kind == Prompt.Kind.GOLDEN_TIME) {
            // Gleo 타임아웃(7초) 전에 실제 접수 결과 또는 처리 중 상태를 답한다.
            val state = withTimeoutOrNull(5_000) {
                graph.engine.state.first {
                    it.current?.store?.id != storeId || it.current?.waiting != null || it.current?.phase == StopPhase.PLANNED
                }
            }
            val waiting = state?.current?.takeIf { it.store.id == storeId }?.waiting
            when {
                waiting != null -> reply(context, RESPOND, "success", if (graph.backend.isDemo) "Demo registration completed. This is not a real restaurant booking." else "The waiting registration was completed.",
                    JSONObject().put("storeName", prompt.storeName).put("partySize", waiting.partySize).put("ticketNo", waiting.ticketNo))
                state?.current?.submission == com.smartjourney.vehicle.engine.SubmissionStatus.UNKNOWN ->
                    reply(context, RESPOND, "fail", "The registration result is unknown. Do not submit again. Check the existing request after parking.")
                state == null -> reply(context, RESPOND, "success", "The voice approval was accepted and registration is still being processed.")
                else -> reply(context, RESPOND, "fail", "The waiting registration was not completed.")
            }
        } else {
            reply(context, RESPOND, "success", "The voice response was accepted.",
                JSONObject().put("decision", if (decision == VoiceDecision.APPROVE) "approve" else "defer"))
        }
    }

    // 지시문은 고정 문장이다. 매장 이름 등 외부 데이터는 지시문에 넣지 않고 data.question으로만 넘긴다.
    private fun ask(context: Context, action: String, prompt: Prompt) = reply(context, action, "asking",
        "Read the question in data.question to the user in Korean exactly. " +
            "Then tell the user to answer by saying 스마트 저니 등록해 줘 or 스마트 저니 나중에. " +
            "When the user approves, call $RESPOND with decision approve. When the user declines or says later, call $RESPOND with decision defer. " +
            "Do not say it is done before $RESPOND returns success.",
        JSONObject().put("question", prompt.spokenText().filterNot { it.isISOControl() }.take(200)))

    private fun parameters(intent: Intent): JSONObject {
        val raw = intent.getStringExtra("ACTION_PARAM")
        if (raw == null) return JSONObject().apply {
            intent.getStringExtra("promptId")?.let { put("promptId", it) }
            intent.getStringExtra("decision")?.let { put("decision", it) }
        }
        // 문서의 JSON 객체 형식과 JSON 문자열로 한 번 더 감싼 예시를 모두 받는다.
        return when (val value = JSONTokener(raw).nextValue()) {
            is JSONObject -> value
            is String -> JSONObject(value)
            else -> throw IllegalArgumentException("ACTION_PARAM must be a JSON object")
        }
    }

    private fun reply(context: Context, action: String, status: String, message: String, data: JSONObject? = null) {
        if (BuildConfig.DEBUG) Log.i("GleoAction", "$action → $status") // 발화·질문 내용은 남기지 않는다
        val response = JSONObject().put("functionName", action).put("status", status)
            .put("message", message).put("mode", "voice")
        data?.let { response.put("data", JSONArray().put(it).toString()) }
        // 에뮬레이터 2.1.0에서 확인한 Gleo 앱. 질문 ID를 다른 앱에 브로드캐스트하지 않는다.
        context.sendBroadcast(Intent("ai.pleos.playground.intent.action.APP_ACTION_RESPONSE")
            .setPackage("ai.umos.ambientai")
            .putExtra("packageName", context.packageName).putExtra("response", response.toString()),
            "ai.umos.gleo.permission.APP_ACTION")
    }

    companion object {
        const val STATUS = "GET_SMART_JOURNEY_STATUS"
        const val RESPOND = "RESPOND_SMART_JOURNEY_PROMPT"
    }
}
