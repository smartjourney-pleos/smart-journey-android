package com.smartjourney.vehicle.data

import android.content.Context
import com.smartjourney.vehicle.domain.ArrivalResult
import com.smartjourney.vehicle.domain.Store
import com.smartjourney.vehicle.domain.Waiting
import com.smartjourney.vehicle.domain.WaitingStatus
import com.smartjourney.vehicle.engine.JourneyState
import com.smartjourney.vehicle.engine.StopPhase
import com.smartjourney.vehicle.engine.StopState
import com.smartjourney.vehicle.engine.SubmissionStatus
import com.smartjourney.vehicle.engine.DEFAULT_WAIT_LIMIT_MIN
import org.json.JSONArray
import org.json.JSONObject

/**
 * 프로세스 강제 종료(LMK / Suspend) 시 여정 복원을 위한 영속화 저장소 인터페이스.
 */
interface JourneyStorage {
    fun save(state: JourneyState)
    fun restore(): JourneyState?
    fun clear()
}

/**
 * JSON 직렬화 및 역직렬화 도우미.
 */
object JourneyStateJsonCodec {

    fun toJson(state: JourneyState, now: Long): String {
        val json = JSONObject().apply {
            put("version", 1)
            put("savedAt", now)
            put("partySize", state.partySize)
            put("waitLimitMin", state.waitLimitMin)
            put("index", state.index)
            put("stops", JSONArray().apply {
                state.stops.forEach { stop ->
                    put(JSONObject().apply {
                        put("store", JSONObject().apply {
                            put("id", stop.store.id)
                            put("name", stop.store.name)
                            put("address", stop.store.address)
                            put("latitude", stop.store.latitude)
                            put("longitude", stop.store.longitude)
                            put("poiId", stop.store.poiId)
                            put("poiSubId", stop.store.poiSubId)
                        })
                        put("phase", stop.phase.name)
                        put("submission", stop.submission.name)
                        stop.submissionId?.let { put("submissionId", it) }
                        stop.waiting?.let { w ->
                            put("waiting", JSONObject().apply {
                                put("waitingId", w.waitingId)
                                put("storeId", w.storeId)
                                put("ticketNo", w.ticketNo)
                                put("partySize", w.partySize)
                                put("status", w.status.name)
                                put("aheadTeams", w.aheadTeams)
                                put("estimatedWaitMin", w.estimatedWaitMin)
                                w.qrPayload?.let { put("qrPayload", it) }
                            })
                        }
                        stop.arrival?.let { put("arrival", it.name) }
                        stop.alighted?.let { put("alighted", it) }
                    })
                }
            })
            put("log", JSONArray().apply {
                state.log.takeLast(30).forEach { put(it) }
            })
        }
        return json.toString()
    }

    fun fromJson(raw: String, now: Long, maxAgeMs: Long): JourneyState? {
        return runCatching {
            val json = JSONObject(raw)
            val savedAt = json.optLong("savedAt", 0L)
            if (savedAt > 0 && now - savedAt > maxAgeMs) {
                return null
            }
            val partySize = json.getInt("partySize")
            val index = json.optInt("index", 0)
            val stopsArray = json.getJSONArray("stops")
            val stops = (0 until stopsArray.length()).map { i ->
                val stopObj = stopsArray.getJSONObject(i)
                val storeObj = stopObj.getJSONObject("store")
                val store = Store(
                    id = storeObj.getLong("id"),
                    name = storeObj.getString("name"),
                    address = storeObj.getString("address"),
                    latitude = storeObj.getDouble("latitude"),
                    longitude = storeObj.getDouble("longitude"),
                    poiId = storeObj.optString("poiId", ""),
                    poiSubId = storeObj.optString("poiSubId", ""),
                )
                val phase = runCatching { StopPhase.valueOf(stopObj.getString("phase")) }.getOrDefault(StopPhase.PLANNED)
                val waiting = stopObj.optJSONObject("waiting")?.let { wObj ->
                    Waiting(
                        waitingId = wObj.getString("waitingId"),
                        storeId = wObj.getLong("storeId"),
                        ticketNo = wObj.getInt("ticketNo"),
                        partySize = wObj.getInt("partySize"),
                        status = runCatching { WaitingStatus.valueOf(wObj.getString("status")) }.getOrDefault(WaitingStatus.REGISTERED),
                        aheadTeams = wObj.getInt("aheadTeams"),
                        estimatedWaitMin = wObj.getInt("estimatedWaitMin"),
                        qrPayload = wObj.optString("qrPayload").takeIf { it.isNotEmpty() },
                    )
                }
                val arrival = stopObj.optString("arrival").takeIf { it.isNotEmpty() }?.let {
                    runCatching { ArrivalResult.valueOf(it) }.getOrNull()
                }
                val alighted = if (stopObj.has("alighted") && !stopObj.isNull("alighted")) stopObj.getInt("alighted") else null
                // 요청 중에 프로세스가 끝났으면 접수됐는지 알 수 없다. 재접수하지 않도록 확인 필요로 복원한다.
                val submission = runCatching { SubmissionStatus.valueOf(stopObj.getString("submission")) }
                    .getOrDefault(if (waiting != null) SubmissionStatus.CONFIRMED else SubmissionStatus.NOT_REQUESTED)
                    .let { if (it == SubmissionStatus.REQUESTING) SubmissionStatus.UNKNOWN else it }
                StopState(store, phase, waiting, arrival, alighted, submission,
                    stopObj.optString("submissionId").takeIf { it.isNotBlank() })
            }
            val logsArray = json.optJSONArray("log")
            val logs = if (logsArray != null) {
                (0 until logsArray.length()).map { logsArray.getString(it) }
            } else emptyList()

            JourneyState(
                active = true,
                partySize = partySize,
                waitLimitMin = json.optInt("waitLimitMin", DEFAULT_WAIT_LIMIT_MIN),
                stops = stops,
                index = index.coerceIn(0, (stops.size - 1).coerceAtLeast(0)),
                log = logs,
            )
        }.getOrNull()
    }
}

/**
 * Android SharedPreferences 기반의 여정 영속화 구현체.
 */
class PreferencesJourneyStorage(
    context: Context,
    private val maxAgeMs: Long = 12 * 60 * 60 * 1000L, // 12시간 이내의 여정만 복원
    private val now: () -> Long = System::currentTimeMillis,
) : JourneyStorage {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    override fun save(state: JourneyState) {
        if (!state.active || state.stops.isEmpty()) {
            clear()
            return
        }
        val serialized = JourneyStateJsonCodec.toJson(state, now())
        prefs.edit().putString(KEY_JOURNEY_JSON, serialized).apply()
    }

    override fun restore(): JourneyState? {
        val raw = prefs.getString(KEY_JOURNEY_JSON, null) ?: return null
        val restored = JourneyStateJsonCodec.fromJson(raw, now(), maxAgeMs)
        if (restored == null) {
            clear()
        }
        return restored
    }

    override fun clear() {
        prefs.edit().remove(KEY_JOURNEY_JSON).apply()
    }

    companion object {
        private const val PREFS_NAME = "smart_journey_storage"
        private const val KEY_JOURNEY_JSON = "journey_state_json"
    }
}

/**
 * 단위 테스트용 인메모리 저장소.
 */
class InMemoryJourneyStorage : JourneyStorage {
    private var saved: JourneyState? = null

    override fun save(state: JourneyState) {
        saved = if (state.active && state.stops.isNotEmpty()) state else null
    }

    override fun restore(): JourneyState? = saved

    override fun clear() {
        saved = null
    }
}
