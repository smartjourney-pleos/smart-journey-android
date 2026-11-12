package com.smartjourney.vehicle.voice

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.smartjourney.vehicle.BuildConfig
import com.smartjourney.vehicle.graph
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import org.json.JSONObject
import org.json.JSONTokener

/** Gleo Interface의 service action은 Broadcast. 전송 권한은 Manifest에서 제한한다. */
class GleoActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action?.removePrefix("${context.packageName}.") ?: return
        if (action != STATUS && action != RESPOND) return
        val pending = goAsync()
        val graph = context.graph
        // Gleo 재생 완료 이벤트는 제공되지 않는다. 결과 TTS는 이 유한한 대화 유예 뒤에만 시도한다.
        graph.gleoQuietUntil.value = System.currentTimeMillis() + GLEO_SPEECH_GRACE_MS
        graph.scope.launch {
            try {
                val response = graph.gleoActions.handle(action, if (action == RESPOND) parameters(intent) else JSONObject())
                reply(context, response)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w("GleoAction", "음성 요청을 처리하지 못함")
                reply(context, GleoReply(action, "fail", "The voice request could not be processed. Nothing further was submitted."))
            } finally {
                graph.gleoQuietUntil.value = System.currentTimeMillis() + GLEO_SPEECH_GRACE_MS
                pending.finish()
            }
        }
    }

    private fun parameters(intent: Intent): JSONObject {
        val raw = intent.getStringExtra("ACTION_PARAM") ?: return JSONObject().apply {
            intent.getStringExtra("questionToken")?.let { put("questionToken", it) }
            intent.getStringExtra("decision")?.let { put("decision", it) }
        }
        return when (val value = JSONTokener(raw).nextValue()) {
            is JSONObject -> value
            is String -> JSONObject(value)
            else -> throw IllegalArgumentException("ACTION_PARAM must be a JSON object")
        }
    }

    private fun reply(context: Context, response: GleoReply) {
        if (BuildConfig.DEBUG) Log.i("GleoAction", "${response.action} → ${response.status}")
        context.sendBroadcast(Intent("ai.pleos.playground.intent.action.APP_ACTION_RESPONSE")
            .setPackage("ai.umos.ambientai").putExtra("packageName", context.packageName).putExtra("response", response.toJson()),
            "ai.umos.gleo.permission.APP_ACTION")
    }

    companion object {
        const val STATUS = GleoActionHandler.STATUS
        const val RESPOND = GleoActionHandler.RESPOND
        private const val GLEO_SPEECH_GRACE_MS = 20_000L
    }
}
