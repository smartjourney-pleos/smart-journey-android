package com.smartjourney.vehicle.voice

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.smartjourney.vehicle.AppGraph
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
        val action = intent.action ?: return
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
        val id = parameters.optString("promptId")
        val decision = when (parameters.optString("decision")) {
            "approve" -> VoiceDecision.APPROVE
            "defer" -> VoiceDecision.DEFER
            else -> null
        }
        if (id.isBlank() || decision == null) {
            graph.gleoPromptId.value = prompt.id
            ask(context, RESPOND, prompt)
            return
        }
        if (id != prompt.id) {
            reply(context, RESPOND, "fail", "The question has changed. The previous answer was not applied.")
            return
        }
        graph.gleoPromptId.value = prompt.id
        val storeId = graph.engine.state.value.current?.store?.id
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

    private fun ask(context: Context, action: String, prompt: Prompt) = reply(context, action, "asking",
        "An explicit voice response is required for the current question.",
        JSONObject().put("promptId", prompt.id).put("question", prompt.spokenText()))

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
