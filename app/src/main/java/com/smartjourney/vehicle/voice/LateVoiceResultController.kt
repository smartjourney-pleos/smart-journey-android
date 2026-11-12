package com.smartjourney.vehicle.voice

import com.smartjourney.vehicle.engine.JourneyState
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

/** 늦은 결과는 진행 중인 질문을 가로채지 않고 앱 음성과 직렬로 한 번 안내한다. */
class LateVoiceResultController(
    private val voice: VoiceGateway,
    private val state: StateFlow<JourneyState>,
    private val gleoQuietUntil: StateFlow<Long>,
    private val speechMutex: Mutex,
    private val shouldAnnounce: (LateVoiceResult) -> Boolean,
    private val onAnnounced: (String) -> Unit,
    private val now: () -> Long = System::currentTimeMillis,
    private val latestText: (LateVoiceResult) -> String? = { it.text },
) {
    suspend fun announce(result: LateVoiceResult) {
        withTimeoutOrNull(60_000) {
            while (shouldAnnounce(result)) {
                val remaining = gleoQuietUntil.value - now()
                if (remaining > 0) { delay(remaining); continue }
                state.first { it.prompt == null || !shouldAnnounce(result) }
                var attempted = false
                speechMutex.withLock {
                    if (!shouldAnnounce(result)) return@withTimeoutOrNull
                    // 락을 기다리는 사이 새 질문이나 Gleo 대화가 시작되면 다시 기다린다.
                    if (state.value.prompt == null && now() >= gleoQuietUntil.value) {
                        attempted = true
                        try {
                            val text = latestText(result) ?: return@withTimeoutOrNull
                            if (voice.speak(text).isSuccess) onAnnounced(result.requestId)
                        } finally { voice.stop() }
                    }
                }
                if (attempted) return@withTimeoutOrNull
                delay(100)
            }
        }
    }
}
