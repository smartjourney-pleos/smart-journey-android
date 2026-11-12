package com.smartjourney.vehicle.data

import com.smartjourney.vehicle.domain.ApprovalMethod
import com.smartjourney.vehicle.domain.ArrivalResult
import com.smartjourney.vehicle.domain.QueueStatus
import com.smartjourney.vehicle.domain.Store
import com.smartjourney.vehicle.domain.Waiting
import com.smartjourney.vehicle.domain.WaitingStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

private const val POLL_MS = 3_000L

/** 서버가 HTTP 오류로 답했다. [apiCode]는 서버 ErrorCode 이름. */
class ServerException(val status: Int, val apiCode: String?, message: String) : IOException(message)

/**
 * smart-journey-server 연동 (REST, 서버 docs/server/02_기능_API_명세.md).
 * 매장 POS 조작은 smart-journey-web에서 하고, 차량 앱은 그 결과를 3초마다 조회해 반영한다.
 * ponytail: STOMP 대신 REST 조회. 지연이 문제가 되면 /topic/waitings/{id} 구독으로 바꾼다.
 * ponytail: 기기 토큰은 메모리에만 둔다(프로세스가 바뀌면 다시 등록). 실서비스는 Keystore로 암호화해 저장한다.
 */
class ServerBackend(private val baseUrl: String) : WaitingBackend {

    /** 서버의 매장도 시연용 가상 매장이다. */
    override val isDemo = true

    @Volatile private var token: String? = null
    @Volatile private var journeyId: Long? = null

    override suspend fun startJourney(partySize: Int, storeIds: List<Long>, sensed: Boolean) {
        val body = JSONObject().put("partySize", partySize).put("partySource", if (sensed) "SENSOR" else "MANUAL")
            .put("storeIds", JSONArray(storeIds))
        journeyId = try {
            request("POST", "/api/v1/journeys", body).getLong("journeyId")
        } catch (e: ServerException) {
            if (e.apiCode != "ACTIVE_JOURNEY_EXISTS") throw e
            // 앞 방문을 앱에서 끝냈지만 서버 여정은 남아 있다. 서버 여정만 끝내고 다시 만든다(접수는 그대로).
            val previous = request("GET", "/api/v1/journeys/current").getLong("journeyId")
            request("POST", "/api/v1/journeys/$previous/end")
            request("POST", "/api/v1/journeys", body).getLong("journeyId")
        }
    }

    override suspend fun stores(): List<Store> {
        val array = JSONArray(raw("GET", "/api/v1/stores", null, null, auth = true))
        return (0 until array.length()).map { i ->
            val s = array.getJSONObject(i)
            Store(
                s.getLong("id"), s.getString("name"), s.optString("address"),
                s.getDouble("latitude"), s.getDouble("longitude"),
                poiId = s.optString("poiId").takeUnless { s.isNull("poiId") }.orEmpty(),
                poiSubId = s.optString("poiSubId").takeUnless { s.isNull("poiSubId") }.orEmpty(),
            )
        }
    }

    override fun queueStatus(storeId: Long): Flow<QueueStatus> = poll {
        val q = request("GET", "/api/v1/stores/$storeId/queue-status")
        QueueStatus(storeId, q.getInt("aheadTeams"), q.getInt("estimatedWaitMin"), q.getInt("arrivalBufferMin"))
    }

    override suspend fun register(storeId: Long, partySize: Int, method: ApprovalMethod, idempotencyKey: String): Waiting {
        if (journeyId == null) startJourney(partySize, listOf(storeId), sensed = false)
        val body = JSONObject().put("journeyId", journeyId).put("storeId", storeId)
            .put("partySize", partySize).put("approvalMethod", method.name)
        return try {
            request("POST", "/api/v1/waitings", body, idempotencyKey).toWaiting()
        } catch (e: ServerException) {
            // 요청 값·권한·매장 문제는 접수되지 않았음이 명확하다. 409(중복·동시 변경)와 5xx는 결과를 알 수 없다.
            if (e.status in 400..499 && e.status != 409) throw RegistrationRejectedException(e.message ?: "접수 거절")
            throw e
        }
    }

    override fun waiting(waitingId: String): Flow<Waiting> = poll { request("GET", "/api/v1/waitings/$waitingId").toWaiting() }

    override suspend fun updateEta(waitingId: String, etaMin: Int) {
        request("PUT", "/api/v1/waitings/$waitingId/eta", JSONObject().put("etaMin", etaMin.coerceIn(0, 600)))
    }

    override suspend fun reportArrival(waitingId: String, result: ArrivalResult, alighted: Int?, partySize: Int) {
        // ponytail: 서버에 일부 도착(PARTIAL) 상태가 없어 앱에만 표시한다. 서버 enum에 추가되면 그대로 보낸다.
        if (result == ArrivalResult.PARTIAL) return
        val body = JSONObject().put("result", result.name).put("partySize", partySize)
        if (result == ArrivalResult.CONFIRMED && alighted != null) body.put("alighted", alighted)
        request("POST", "/api/v1/waitings/$waitingId/arrival", body)
    }

    override suspend fun cancel(waitingId: String) {
        request("DELETE", "/api/v1/waitings/$waitingId")
    }

    // ---------- HTTP ----------

    private fun <T> poll(read: suspend () -> T): Flow<T> = flow {
        while (true) {
            runCatching { read() }.onSuccess { emit(it) }
            delay(POLL_MS)
        }
    }.distinctUntilChanged()

    private suspend fun ensureToken(): String = token ?: run {
        // 사용자는 앱의 동의 화면에서 동의했다. 서버가 요구하는 현재 고지 버전으로 기기를 등록한다.
        val version = JSONObject(raw("GET", "/api/v1/consent/notice", null, null, auth = false)).getString("version")
        val registered = JSONObject(raw("POST", "/api/v1/devices", JSONObject().put("consentVersion", version), null, auth = false))
        registered.getString("deviceToken").also { token = it }
    }

    private suspend fun request(method: String, path: String, body: JSONObject? = null, idempotencyKey: String? = null): JSONObject =
        raw(method, path, body, idempotencyKey, auth = true).let { if (it.isBlank()) JSONObject() else JSONObject(it) }

    private suspend fun raw(method: String, path: String, body: JSONObject?, idempotencyKey: String?, auth: Boolean): String {
        val deviceToken = if (auth) ensureToken() else null
        return withContext(Dispatchers.IO) {
            val connection = URL(baseUrl + path).openConnection() as HttpURLConnection
            try {
                connection.requestMethod = method
                connection.connectTimeout = 5_000
                connection.readTimeout = 5_000
                connection.setRequestProperty("Accept", "application/json")
                // 토큰은 헤더로만 보낸다 (URL 금지)
                deviceToken?.let { connection.setRequestProperty("X-Device-Token", it) }
                idempotencyKey?.let { connection.setRequestProperty("Idempotency-Key", it) }
                if (body != null) {
                    connection.doOutput = true
                    connection.setRequestProperty("Content-Type", "application/json")
                    connection.outputStream.use { it.write(body.toString().toByteArray()) }
                }
                val status = connection.responseCode
                val text = (if (status < 400) connection.inputStream else connection.errorStream)
                    ?.bufferedReader()?.use { it.readText() }.orEmpty()
                if (status == 401) token = null // 서버 DB가 초기화됐으면 다음 요청에서 다시 등록한다
                if (status >= 400) {
                    val error = runCatching { JSONObject(text) }.getOrNull()
                    throw ServerException(status, error?.optString("code"), error?.optString("message") ?: "HTTP $status")
                }
                text
            } finally {
                connection.disconnect()
            }
        }
    }

    private fun JSONObject.toWaiting() = Waiting(
        waitingId = getLong("waitingId").toString(),
        storeId = getLong("storeId"),
        ticketNo = getInt("ticketNo"),
        partySize = getInt("partySize"),
        status = runCatching { WaitingStatus.valueOf(getString("status")) }.getOrDefault(WaitingStatus.REGISTERED),
        aheadTeams = getInt("aheadTeams"),
        estimatedWaitMin = getInt("estimatedWaitMin"),
    )
}

/** 데모 패널의 스위치로 앱 안 FakeBackend와 서버 연동을 바꾼다. 진행 중인 방문이 없을 때만 바꾼다. */
class SwitchingBackend(
    val fake: FakeBackend,
    val server: ServerBackend?,
    private val useServer: () -> Boolean,
) : WaitingBackend {
    private val current: WaitingBackend get() = if (useServer() && server != null) server else fake
    override val isDemo get() = current.isDemo
    override suspend fun startJourney(partySize: Int, storeIds: List<Long>, sensed: Boolean) =
        current.startJourney(partySize, storeIds, sensed)
    override suspend fun stores() = current.stores()
    override fun queueStatus(storeId: Long) = current.queueStatus(storeId)
    override suspend fun register(storeId: Long, partySize: Int, method: ApprovalMethod, idempotencyKey: String) =
        current.register(storeId, partySize, method, idempotencyKey)
    override fun waiting(waitingId: String) = current.waiting(waitingId)
    override suspend fun updateEta(waitingId: String, etaMin: Int) = current.updateEta(waitingId, etaMin)
    override suspend fun reportArrival(waitingId: String, result: ArrivalResult, alighted: Int?, partySize: Int) =
        current.reportArrival(waitingId, result, alighted, partySize)
    override suspend fun cancel(waitingId: String) = current.cancel(waitingId)
}
