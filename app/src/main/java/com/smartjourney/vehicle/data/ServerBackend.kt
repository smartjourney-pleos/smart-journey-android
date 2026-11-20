package com.smartjourney.vehicle.data

import com.smartjourney.vehicle.domain.ApprovalMethod
import com.smartjourney.vehicle.domain.ArrivalResult
import com.smartjourney.vehicle.domain.QueueStatus
import com.smartjourney.vehicle.domain.Store
import com.smartjourney.vehicle.domain.Waiting
import com.smartjourney.vehicle.domain.WaitingStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import javax.net.ssl.HttpsURLConnection

private const val POLL_MS = 3_000L
private const val RECONNECT_INITIAL_MS = 1_000L
private const val RECONNECT_MAX_MS = 30_000L

/** 서버가 HTTP 오류로 답했다. [apiCode]는 서버 ErrorCode 이름. */
class ServerException(val status: Int, val apiCode: String?, message: String) : IOException(message)

/**
 * smart-journey-server 연동 (REST + STOMP WebSocket `/ws`, 서버 docs/server/02_기능_API_명세.md).
 * - 대기 현황(`/topic/stores/{id}/queue`)과 웨이팅 상태(`/topic/waitings/{id}`)는 STOMP 실시간 푸시를 구독한다.
 * - 터널·음영 구간이나 초기 연결 실패 시에는 3초 REST 조회로 자동 폴백하고, 재연결 직후 REST로 누락 상태를 동기화한다.
 * - 기기 토큰은 [tokenStore]를 통해 Android Keystore AES-GCM 암호화 저장소에 보관하며,
 *   URL 쿼리가 아닌 HTTP/STOMP `X-Device-Token` 헤더로만 보내고 동의 철회·401·UNAUTHORIZED 시 폐기한다.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ServerBackend(
    baseUrl: String,
    /** 기기 토큰 암호화 저장소. 기본값은 테스트용 메모리 저장소다. */
    private val tokenStore: DeviceTokenStore = InMemoryDeviceTokenStore(),
    /**
     * 평문 HTTP/WS 허용 여부. 릴리스 빌드에서는 반드시 false(HTTPS/WSS 전용)로 고정한다.
     * 디버그(에뮬레이터 10.0.2.2)와 로컬 단위 테스트에서만 true를 사용한다.
     */
    private val allowCleartext: Boolean = false,
    /** STOMP 단절 시 폴백 REST 조회 주기(ms). */
    private val pollIntervalMs: Long = POLL_MS,
    /** STOMP 재연결 초기 백오프(ms). */
    private val reconnectInitialMs: Long = RECONNECT_INITIAL_MS,
    /** STOMP 재연결 최대 백오프(ms). */
    private val reconnectMaxMs: Long = RECONNECT_MAX_MS,
    /** 사용자가 앱에서 동의한 고지 버전. null이면 동의 전·철회 상태라 서버를 부르지 않는다. */
    private val consentVersion: () -> String?,
) : WaitingBackend {

    private val normalizedBaseUrl = baseUrl.trim().trimEnd('/')
    private val stompClient = StompWebSocketClient(
        baseUrl = normalizedBaseUrl,
        allowCleartext = allowCleartext,
    )

    /** 서버의 매장도 시연용 가상 매장이다. */
    override val isDemo = true

    @Volatile private var token: String? = null
    @Volatile private var journeyId: Long? = null
    private val tokenMutex = Mutex()

    fun clearToken() {
        token = null
        tokenStore.clear()
    }

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

    override fun queueStatus(storeId: Long): Flow<QueueStatus> = realtimeStream(
        destination = "/topic/stores/$storeId/queue",
        fetchSnapshot = { request("GET", "/api/v1/stores/$storeId/queue-status").toQueueStatus(storeId) },
        parseEvent = { type, payload, _ ->
            if (type == "QUEUE_UPDATED") payload.toQueueStatus(storeId) else null
        },
    )

    override suspend fun register(storeId: Long, partySize: Int, method: ApprovalMethod, idempotencyKey: String): Waiting {
        if (journeyId == null) startJourney(partySize, listOf(storeId), sensed = false)
        val body = JSONObject().put("journeyId", journeyId).put("storeId", storeId)
            .put("partySize", partySize).put("approvalMethod", method.name)
        return try {
            // 중복 접수를 막기 위해 5xx·409·타임아웃 시 자동 재시도하지 않는다.
            request("POST", "/api/v1/waitings", body, idempotencyKey).toWaiting()
        } catch (e: ServerException) {
            // 요청 값·권한·매장 문제는 접수되지 않았음이 명확하다. 409(중복·동시 변경)와 5xx는 결과를 알 수 없다.
            if (e.status in 400..499 && e.status != 409) throw RegistrationRejectedException(e.message ?: "접수 거절")
            throw e
        }
    }

    override fun waiting(waitingId: String): Flow<Waiting> {
        // 같은 웨이팅 구독 세션 동안 CALLED 이벤트로 받은 QR payload를 후속 WAITING_UPDATED·REST 동기화에서도 유지한다.
        val knownQrPayload = java.util.concurrent.atomic.AtomicReference<String?>(null)
        return realtimeStream(
            destination = "/topic/waitings/$waitingId",
            fetchSnapshot = {
                val fetched = request("GET", "/api/v1/waitings/$waitingId").toWaiting()
                val preservedQr = knownQrPayload.get()?.takeIf { fetched.status == WaitingStatus.CALLED }
                if (fetched.qrPayload == null && preservedQr != null) fetched.copy(qrPayload = preservedQr) else fetched
            },
            parseEvent = { type, payload, current ->
                when (type) {
                    "WAITING_UPDATED" -> {
                        val updated = payload.toWaiting()
                        if (updated.waitingId != waitingId) null
                        else {
                            val preservedQr = knownQrPayload.get()?.takeIf { updated.status == WaitingStatus.CALLED }
                            if (updated.qrPayload == null && preservedQr != null) updated.copy(qrPayload = preservedQr) else updated
                        }
                    }
                    "CALLED" -> {
                        val eventId = payload.optLong("waitingId", -1L).takeIf { it > 0L }?.toString()
                        if (eventId != null && eventId != waitingId) {
                            null
                        } else {
                            val qr = payload.optString("qrPayload")
                                .takeIf { it.isNotBlank() && !payload.isNull("qrPayload") }
                            if (qr != null) knownQrPayload.set(qr)
                            val base = current ?: try {
                                request("GET", "/api/v1/waitings/$waitingId").toWaiting()
                            } catch (e: CancellationException) {
                                throw e
                            } catch (_: Exception) {
                                null
                            }
                            base?.copy(
                                ticketNo = payload.optInt("ticketNo", base.ticketNo),
                                partySize = payload.optInt("partySize", base.partySize),
                                status = WaitingStatus.CALLED,
                                aheadTeams = 0,
                                estimatedWaitMin = 0,
                                qrPayload = qr ?: base.qrPayload,
                            )
                        }
                    }
                    else -> null
                }
            },
        )
    }

    override suspend fun updateEta(waitingId: String, etaMin: Int) {
        request("PUT", "/api/v1/waitings/$waitingId/eta", JSONObject().put("etaMin", etaMin.coerceIn(0, 600)))
    }

    override suspend fun reportArrival(waitingId: String, result: ArrivalResult, alighted: Int?, partySize: Int) {
        // smart-journey-server ArrivalResult(CONFIRMED·MANUAL·UNCONFIRMED)에는 PARTIAL이 없고
        // ArrivalService가 유효 요청을 ARRIVED로 전이하므로, 서버 계약 확장 전까지 일부 하차는 앱에만 표시한다.
        if (result == ArrivalResult.PARTIAL) return
        val body = JSONObject().put("result", result.name).put("partySize", partySize)
        if (result == ArrivalResult.CONFIRMED && alighted != null) body.put("alighted", alighted)
        request("POST", "/api/v1/waitings/$waitingId/arrival", body)
    }

    override suspend fun cancel(waitingId: String) {
        request("DELETE", "/api/v1/waitings/$waitingId")
    }

    // ---------- 실시간 STOMP + REST 폴백 ----------

    private fun <T> realtimeStream(
        destination: String,
        fetchSnapshot: suspend () -> T,
        parseEvent: suspend (type: String, payload: JSONObject, current: T?) -> T?,
    ): Flow<T> = channelFlow {
        val stompConnected = MutableStateFlow(false)
        val stateMutex = Mutex()
        var latest: T? = null

        suspend fun emitLatest(value: T) {
            stateMutex.withLock { latest = value }
            send(value)
        }

        suspend fun syncSnapshot() {
            try {
                emitLatest(fetchSnapshot())
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // 일시 네트워크 오류는 마지막 정상 상태를 유지하고 다음 주기·재연결에서 복구한다.
            }
        }

        // 초기 스냅샷을 즉시 읽고, STOMP가 끊긴 동안에만 3초 REST 폴링으로 폴백한다.
        launch {
            syncSnapshot()
            stompConnected.collectLatest { connected ->
                if (connected) {
                    // 구독 직후 1회 REST 동기화로 연결·재연결 공백 동안 놓친 이벤트를 보완한다.
                    syncSnapshot()
                } else {
                    while (true) {
                        delay(pollIntervalMs)
                        syncSnapshot()
                    }
                }
            }
        }

        // STOMP WebSocket 실시간 구독 루프 (지수 백오프로 자동 재연결)
        launch {
            var backoffMs = reconnectInitialMs
            while (isActive) {
                try {
                    val deviceToken = ensureToken()
                    stompClient.subscribe(
                        destination = destination,
                        deviceToken = deviceToken,
                        onConnected = {
                            backoffMs = reconnectInitialMs
                            stompConnected.value = true
                        },
                        onMessage = { body ->
                            val current = stateMutex.withLock { latest }
                            val updated = parseRealtimeEnvelope(body, current, parseEvent)
                            if (updated != null) emitLatest(updated)
                        },
                    )
                } catch (e: CancellationException) {
                    throw e
                } catch (e: StompAuthException) {
                    // 서버 DB 초기화·토큰 만료로 UNAUTHORIZED를 받으면 저장 토큰을 폐기하고 재등록한다.
                    if (e.unauthorized) clearToken()
                } catch (_: Exception) {
                    // 연결 실패·단절 시 폴백 폴러가 이어받고 지수 백오프로 재연결을 시도한다.
                } finally {
                    stompConnected.value = false
                }
                delay(backoffMs)
                backoffMs = (backoffMs * 2L).coerceAtMost(reconnectMaxMs)
            }
        }
    }.distinctUntilChanged()

    private suspend fun <T> parseRealtimeEnvelope(
        body: String,
        current: T?,
        parseEvent: suspend (type: String, payload: JSONObject, current: T?) -> T?,
    ): T? {
        val envelope = runCatching { JSONObject(body) }.getOrNull() ?: return null
        val type = envelope.optString("type").takeIf { it.isNotBlank() } ?: return null
        val payload = envelope.optJSONObject("payload") ?: return null
        return try {
            parseEvent(type, payload, current)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // 모르는 상태값·필드 오류는 무시하고 마지막 정상 상태를 유지한다.
            null
        }
    }

    // ---------- HTTP ----------

    private suspend fun ensureToken(): String = tokenMutex.withLock {
        val agreed = consentVersion()
        if (agreed == null) {
            clearToken()
            throw IOException("데이터 전송 동의가 필요해요")
        }
        token?.let { return it }
        tokenStore.load()?.takeIf { it.isNotBlank() }?.let {
            token = it
            return it
        }
        // 사용자가 본 고지와 서버의 현재 고지가 같을 때만 등록한다. 다르면 다시 동의받아야 한다.
        val version = JSONObject(raw("GET", "/api/v1/consent/notice", null, null, auth = false)).getString("version")
        if (version != agreed) throw IOException("서버의 고지 버전($version)이 앱에서 동의한 버전($agreed)과 달라요")
        val registered = JSONObject(raw("POST", "/api/v1/devices", JSONObject().put("consentVersion", agreed), null, auth = false))
        val newToken = registered.getString("deviceToken")
        // 등록 도중 동의가 철회되었으면 발급 토큰을 저장하지 않고 폐기한다.
        if (consentVersion() == null) {
            clearToken()
            throw IOException("데이터 전송 동의가 철회되었어요")
        }
        token = newToken
        tokenStore.save(newToken)
        newToken
    }

    private suspend fun request(method: String, path: String, body: JSONObject? = null, idempotencyKey: String? = null): JSONObject =
        raw(method, path, body, idempotencyKey, auth = true).let { if (it.isBlank()) JSONObject() else JSONObject(it) }

    private suspend fun raw(method: String, path: String, body: JSONObject?, idempotencyKey: String?, auth: Boolean): String {
        val deviceToken = if (auth) ensureToken() else null
        return withContext(Dispatchers.IO) {
            val connection = URL(normalizedBaseUrl + path).openConnection() as HttpURLConnection
            if (!allowCleartext && connection !is HttpsURLConnection) {
                connection.disconnect()
                throw IOException("릴리스 환경에서는 HTTPS 연결만 허용해요")
            }
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
                if (status == 401) clearToken() // 서버 DB가 초기화됐으면 저장 토큰도 폐기하고 다음 요청에서 재등록한다
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

    private fun JSONObject.toQueueStatus(expectedStoreId: Long): QueueStatus {
        val id = optLong("storeId", expectedStoreId)
        require(id == expectedStoreId) { "다른 매장의 대기 현황 이벤트($id != $expectedStoreId)" }
        return QueueStatus(
            storeId = expectedStoreId,
            aheadTeams = getInt("aheadTeams"),
            estimatedWaitMin = getInt("estimatedWaitMin"),
            arrivalBufferMin = getInt("arrivalBufferMin"),
        )
    }

    private fun JSONObject.toWaiting() = Waiting(
        waitingId = getLong("waitingId").toString(),
        storeId = getLong("storeId"),
        ticketNo = getInt("ticketNo"),
        partySize = getInt("partySize"),
        // 모르는 상태를 '접수됨'으로 바꾸지 않는다. 예외가 나면 이번 조회는 반영하지 않고 마지막 상태를 유지한다.
        status = WaitingStatus.valueOf(getString("status")),
        aheadTeams = getInt("aheadTeams"),
        estimatedWaitMin = getInt("estimatedWaitMin"),
        qrPayload = optString("qrPayload").takeIf { it.isNotBlank() && !isNull("qrPayload") },
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
