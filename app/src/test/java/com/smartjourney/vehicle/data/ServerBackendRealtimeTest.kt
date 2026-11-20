package com.smartjourney.vehicle.data

import com.smartjourney.vehicle.domain.ApprovalMethod
import com.smartjourney.vehicle.domain.ArrivalResult
import com.smartjourney.vehicle.domain.QueueStatus
import com.smartjourney.vehicle.domain.Waiting
import com.smartjourney.vehicle.domain.WaitingStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * 외부 라이브러리 없이 로컬 루프백 소켓 목 서버로 smart-journey-server의 REST + STOMP WebSocket(/ws) 계약을 검증한다.
 */
class ServerBackendRealtimeTest {

    private lateinit var server: MockSmartJourneyServer

    @Before
    fun setUp() {
        server = MockSmartJourneyServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.close()
    }

    @Test
    fun `릴리스 설정(allowCleartext=false)에서는 평문 HTTP 서버 주소로 생성할 수 없다`() {
        try {
            ServerBackend(baseUrl = server.baseUrl, allowCleartext = false) { "2026-11-v1" }
            fail("평문 HTTP ServerBackend 생성이 차단되어야 합니다")
        } catch (_: IllegalArgumentException) {
            // 기대 동작
        }
    }

    @Test
    fun `STOMP로 매장 대기 현황 QUEUE_UPDATED를 실시간 수신하고 토큰은 헤더로만 보낸다`() = runBlocking {
        val backend = createBackend(pollIntervalMs = 10_000L)
        val received = CopyOnWriteArrayList<QueueStatus>()
        val gotInitial = CountDownLatch(1)
        val gotPush = CountDownLatch(1)

        val job = launch(Dispatchers.Default) {
            backend.queueStatus(1L).collect { status ->
                received.add(status)
                if (status.aheadTeams == 3) gotInitial.countDown()
                if (status.aheadTeams == 7) gotPush.countDown()
            }
        }

        try {
            assertTrue("초기 REST 스냅샷 수신", gotInitial.await(3, TimeUnit.SECONDS))
            assertTrue("STOMP 구독 완료 대기", server.awaitSubscription("/topic/stores/1/queue", 3_000))

            // 토큰은 URL 쿼리가 아니라 STOMP CONNECT 헤더에만 포함되어야 한다.
            assertFalse(
                "WebSocket 업그레이드 요청 경로에 토큰이 노출되면 안 된다",
                server.wsUpgradePaths.any { it.contains("token", ignoreCase = true) || it.contains(server.validToken) },
            )
            assertEquals(server.validToken, server.lastStompConnectToken.get())

            // 10초 폴링 주기보다 훨씬 짧은 시간에 STOMP 푸시가 즉시 반영되어야 한다.
            server.pushToTopic(
                "/topic/stores/1/queue",
                """{"type":"QUEUE_UPDATED","eventId":"e-1","payload":{"storeId":1,"aheadTeams":7,"estimatedWaitMin":35,"arrivalBufferMin":10}}""",
            )
            assertTrue("STOMP 실시간 QUEUE_UPDATED 수신", gotPush.await(2, TimeUnit.SECONDS))
            assertEquals(QueueStatus(1L, 7, 35, 10), received.last())
        } finally {
            job.cancelAndJoin()
        }
    }

    @Test
    fun `STOMP WAITING_UPDATED와 CALLED 이벤트를 실시간 반영하고 QR payload를 유지한다`() = runBlocking {
        val backend = createBackend(pollIntervalMs = 10_000L)
        val received = CopyOnWriteArrayList<Waiting>()
        val gotInitial = CountDownLatch(1)
        val gotUpdated = CountDownLatch(1)
        val gotCalled = CountDownLatch(1)

        val job = launch(Dispatchers.Default) {
            backend.waiting("101").collect { w ->
                received.add(w)
                if (w.aheadTeams == 4 && w.status == WaitingStatus.REGISTERED) gotInitial.countDown()
                if (w.aheadTeams == 2 && w.status == WaitingStatus.REGISTERED) gotUpdated.countDown()
                if (w.status == WaitingStatus.CALLED && w.qrPayload == "SJ1.called.qr") gotCalled.countDown()
            }
        }

        try {
            assertTrue("초기 웨이팅 스냅샷", gotInitial.await(3, TimeUnit.SECONDS))
            assertTrue("STOMP 웨이팅 토픽 구독", server.awaitSubscription("/topic/waitings/101", 3_000))

            server.pushToTopic(
                "/topic/waitings/101",
                """{"type":"WAITING_UPDATED","eventId":"e-2","payload":{"waitingId":101,"storeId":1,"ticketNo":15,"partySize":3,"status":"REGISTERED","aheadTeams":2,"estimatedWaitMin":10}}""",
            )
            assertTrue("WAITING_UPDATED 실시간 반영", gotUpdated.await(2, TimeUnit.SECONDS))

            // 서버 CALLED 이벤트 payload는 TicketResponse 형식(storeId·status 없음, qrPayload 포함)
            server.pushToTopic(
                "/topic/waitings/101",
                """{"type":"CALLED","eventId":"e-3","payload":{"waitingId":101,"storeName":"42dot","ticketNo":15,"partySize":3,"qrPayload":"SJ1.called.qr"}}""",
            )
            assertTrue("CALLED 실시간 반영 및 QR 병합", gotCalled.await(2, TimeUnit.SECONDS))

            // 이후 qrPayload 없는 WAITING_UPDATED가 와도 기존 CALLED QR이 지워지지 않아야 한다.
            server.pushToTopic(
                "/topic/waitings/101",
                """{"type":"WAITING_UPDATED","eventId":"e-4","payload":{"waitingId":101,"storeId":1,"ticketNo":15,"partySize":3,"status":"CALLED","aheadTeams":0,"estimatedWaitMin":0}}""",
            )
            delay(200)
            val latest = received.last()
            assertEquals(WaitingStatus.CALLED, latest.status)
            assertEquals("SJ1.called.qr", latest.qrPayload)
        } finally {
            job.cancelAndJoin()
        }
    }

    @Test
    fun `STOMP 연결이 실패하면 REST 폴링으로 자동 폴백하고 서버 복구 시 STOMP로 자동 재연결한다`() = runBlocking {
        server.wsRejectUpgrade.set(true)
        val backend = createBackend(pollIntervalMs = 150L, reconnectInitialMs = 150L, reconnectMaxMs = 300L)
        val gotFallback = CountDownLatch(1)
        val gotRecoveredPush = CountDownLatch(1)

        val job = launch(Dispatchers.Default) {
            backend.waiting("101").collect { w ->
                if (w.aheadTeams == 1 && w.status == WaitingStatus.REGISTERED) gotFallback.countDown()
                if (w.status == WaitingStatus.SEATED) gotRecoveredPush.countDown()
            }
        }

        try {
            // WS가 거절된 상태에서 REST 스냅샷이 갱신되면 폴백 폴링으로 반영되어야 한다.
            delay(100)
            server.waitingJson.set(
                """{"waitingId":101,"storeId":1,"ticketNo":15,"partySize":3,"status":"REGISTERED","aheadTeams":1,"estimatedWaitMin":5}""",
            )
            assertTrue("STOMP 단절 중 REST 폴링 폴백 동작", gotFallback.await(3, TimeUnit.SECONDS))

            // WS 업그레이드를 다시 허용하면 백오프 재연결 후 실시간 푸시가 동작해야 한다.
            server.wsRejectUpgrade.set(false)
            assertTrue("STOMP 자동 재연결 성공", server.awaitSubscription("/topic/waitings/101", 3_000))
            server.pushToTopic(
                "/topic/waitings/101",
                """{"type":"WAITING_UPDATED","eventId":"e-5","payload":{"waitingId":101,"storeId":1,"ticketNo":15,"partySize":3,"status":"SEATED","aheadTeams":0,"estimatedWaitMin":0}}""",
            )
            assertTrue("재연결 후 STOMP 실시간 수신", gotRecoveredPush.await(2, TimeUnit.SECONDS))
        } finally {
            job.cancelAndJoin()
        }
    }

    @Test
    fun `만료 토큰으로 HTTP 401·STOMP UNAUTHORIZED를 받으면 폐기 후 1회 재등록해 복구한다`() = runBlocking {
        val tokenStore = InMemoryDeviceTokenStore().apply { save("expired-token") }
        val backend = createBackend(tokenStore = tokenStore, pollIntervalMs = 150L, reconnectInitialMs = 150L)

        // 첫 호출은 만료 토큰으로 401이 나고 토큰을 폐기해야 한다.
        try {
            backend.stores()
            fail("만료 토큰 요청은 401 ServerException이어야 합니다")
        } catch (e: ServerException) {
            assertEquals(401, e.status)
        }
        assertEquals(null, tokenStore.load())

        // 동시 요청 3건이 들어와도 ensureToken() Mutex 덕분에 /api/v1/devices는 정확히 1회만 호출되어야 한다.
        val results = listOf(
            async(Dispatchers.Default) { backend.stores() },
            async(Dispatchers.Default) { backend.stores() },
            async(Dispatchers.Default) { backend.stores() },
        ).awaitAll()
        assertEquals(3, results.size)
        assertEquals(1, server.deviceRegisterCount.get())
        assertEquals(server.validToken, tokenStore.load())
    }

    @Test
    fun `접수 API(POST waitings)는 5xx·409·400에서 절대 자동 재시도하지 않고 오류를 정확히 분류한다`() = runBlocking {
        val backend = createBackend()
        backend.startJourney(partySize = 2, storeIds = listOf(1L), sensed = true)

        server.nextRegisterStatus.set(500)
        try {
            backend.register(1L, 2, ApprovalMethod.VOICE, "idem-500-key")
            fail("500 예외 필요")
        } catch (e: ServerException) {
            assertEquals(500, e.status)
        }
        assertEquals(1, server.registerCallCount.get())

        server.nextRegisterStatus.set(409)
        try {
            backend.register(1L, 2, ApprovalMethod.VOICE, "idem-409-key")
            fail("409 예외 필요")
        } catch (e: ServerException) {
            assertEquals(409, e.status)
        }
        assertEquals(2, server.registerCallCount.get())

        server.nextRegisterStatus.set(400)
        try {
            backend.register(1L, 2, ApprovalMethod.TOUCH, "idem-400-key")
            fail("400은 RegistrationRejectedException이어야 함")
        } catch (_: RegistrationRejectedException) {
            // 기대 동작
        }
        assertEquals(3, server.registerCallCount.get())
    }

    @Test
    fun `일부 도착(PARTIAL)은 서버로 보내지 않고 확정 도착(CONFIRMED)만 전송한다`() = runBlocking {
        val backend = createBackend()

        backend.reportArrival("101", ArrivalResult.PARTIAL, alighted = 2, partySize = 4)
        assertTrue("PARTIAL은 서버 enum 부재로 전송하지 않는다", server.arrivalRequests.isEmpty())

        backend.reportArrival("101", ArrivalResult.CONFIRMED, alighted = 4, partySize = 4)
        assertEquals(1, server.arrivalRequests.size)
        val payload = JSONObject(server.arrivalRequests.first())
        assertEquals("CONFIRMED", payload.getString("result"))
        assertEquals(4, payload.getInt("alighted"))
        assertEquals(4, payload.getInt("partySize"))
    }

    @Test
    fun `STOMP CONNECT에서 UNAUTHORIZED를 받으면 저장 토큰을 폐기하고 재등록해 구독을 복구한다`() = runBlocking {
        val tokenStore = InMemoryDeviceTokenStore().apply { save("expired-stomp-token") }
        val backend = createBackend(tokenStore = tokenStore, pollIntervalMs = 10_000L, reconnectInitialMs = 150L)

        val job = launch(Dispatchers.Default) {
            backend.queueStatus(1L).collect { }
        }
        try {
            // 만료 토큰으로 STOMP UNAUTHORIZED / HTTP 401 발생 후 자동 폐기·재등록되어 구독이 성공해야 한다.
            assertTrue("재등록 후 STOMP 구독 복구", server.awaitSubscription("/topic/stores/1/queue", 3_000))
            assertEquals(server.validToken, tokenStore.load())
            assertEquals(server.validToken, server.lastStompConnectToken.get())
        } finally {
            job.cancelAndJoin()
        }
    }

    @Test
    fun `STOMP 구독 중 코루틴을 취소하면 블로킹 소켓 대기 없이 즉시 종료된다`() = runBlocking {
        val backend = createBackend(pollIntervalMs = 10_000L)
        val job = launch(Dispatchers.Default) {
            backend.waiting("101").collect { }
        }
        assertTrue("STOMP 연결 완료", server.awaitSubscription("/topic/waitings/101", 3_000))

        // 읽기 타임아웃(25초)을 기다리지 않고 2초 안에 즉시 취소되어야 한다.
        withTimeout(2_000L) {
            job.cancelAndJoin()
        }
    }

    private fun createBackend(
        tokenStore: DeviceTokenStore = InMemoryDeviceTokenStore(),
        pollIntervalMs: Long = 3_000L,
        reconnectInitialMs: Long = 200L,
        reconnectMaxMs: Long = 1_000L,
    ) = ServerBackend(
        baseUrl = server.baseUrl,
        tokenStore = tokenStore,
        allowCleartext = true,
        pollIntervalMs = pollIntervalMs,
        reconnectInitialMs = reconnectInitialMs,
        reconnectMaxMs = reconnectMaxMs,
        consentVersion = { "2026-11-v1" },
    )
}

/**
 * smart-journey-server의 REST + 순수 WebSocket(/ws) STOMP 브로커 계약을 로컬 루프백에서 흉내 내는 경량 테스트 서버.
 */
private class MockSmartJourneyServer : AutoCloseable {
    val validToken = "dev-tok-valid-1234"
    val wsRejectUpgrade = AtomicBoolean(false)
    val nextRegisterStatus = AtomicInteger(201)
    val registerCallCount = AtomicInteger(0)
    val deviceRegisterCount = AtomicInteger(0)
    val wsUpgradePaths = CopyOnWriteArrayList<String>()
    val lastStompConnectToken = AtomicReference<String?>(null)
    val arrivalRequests = CopyOnWriteArrayList<String>()
    val waitingJson = AtomicReference(
        """{"waitingId":101,"storeId":1,"ticketNo":15,"partySize":3,"status":"REGISTERED","aheadTeams":4,"estimatedWaitMin":20}""",
    )

    private val serverSocket = ServerSocket(0, 50, InetAddress.getLoopbackAddress())
    private val running = AtomicBoolean(true)
    private val clients = CopyOnWriteArrayList<Socket>()
    private val subscriptions = CopyOnWriteArrayList<Subscription>()
    private var acceptThread: Thread? = null

    val baseUrl: String get() = "http://127.0.0.1:${serverSocket.localPort}"

    private data class Subscription(
        val destination: String,
        val output: OutputStream,
        val lock: Any,
    )

    fun start() {
        acceptThread = Thread({
            while (running.get()) {
                val socket = try {
                    serverSocket.accept()
                } catch (_: Exception) {
                    break
                }
                clients.add(socket)
                Thread({ handleClient(socket) }, "mock-sj-client").apply { isDaemon = true }.start()
            }
        }, "mock-sj-accept").apply { isDaemon = true }.also { it.start() }
    }

    fun awaitSubscription(destination: String, timeoutMs: Long): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (subscriptions.any { it.destination == destination }) return true
            Thread.sleep(20)
        }
        return false
    }

    fun pushToTopic(destination: String, jsonBody: String) {
        val frame = StompCodec.encode(
            StompFrame(
                command = "MESSAGE",
                headers = linkedMapOf(
                    "subscription" to "sub-0",
                    "message-id" to "msg-${System.nanoTime()}",
                    "destination" to destination,
                    "content-type" to "application/json",
                ),
                body = jsonBody,
            ),
        )
        for (sub in subscriptions.filter { it.destination == destination }) {
            synchronized(sub.lock) {
                runCatching { writeServerWsTextFrame(sub.output, frame) }
            }
        }
    }

    override fun close() {
        running.set(false)
        runCatching { serverSocket.close() }
        clients.forEach { runCatching { it.close() } }
        clients.clear()
        subscriptions.clear()
    }

    private fun handleClient(socket: Socket) {
        socket.use { sock ->
            val input = BufferedInputStream(sock.getInputStream())
            val output = sock.getOutputStream()
            val headerText = readHttpHeaders(input) ?: return
            val lines = headerText.split("\r\n")
            val requestLine = lines.firstOrNull()?.split(' ') ?: return
            if (requestLine.size < 2) return
            val method = requestLine[0]
            val path = requestLine[1]
            val headers = LinkedHashMap<String, String>()
            for (i in 1 until lines.size) {
                val idx = lines[i].indexOf(':')
                if (idx > 0) {
                    headers[lines[i].substring(0, idx).trim().lowercase()] = lines[i].substring(idx + 1).trim()
                }
            }

            if (path.startsWith("/ws") && headers["upgrade"].equals("websocket", ignoreCase = true)) {
                wsUpgradePaths.add(path)
                handleWebSocket(input, output, headers)
                return
            }

            val contentLength = headers["content-length"]?.toIntOrNull() ?: 0
            val bodyBytes = if (contentLength > 0) readFully(input, contentLength) else ByteArray(0)
            val body = String(bodyBytes, StandardCharsets.UTF_8)
            handleRest(method, path, headers, body, output)
        }
    }

    private fun handleWebSocket(input: InputStream, output: OutputStream, headers: Map<String, String>) {
        if (wsRejectUpgrade.get()) {
            writeHttpResponse(output, 503, """{"code":"WS_UNAVAILABLE","message":"ws disabled"}""")
            return
        }
        val secKey = headers["sec-websocket-key"] ?: return
        val accept = StompWebSocketClient.computeWebSocketAccept(secKey)
        val upgrade = "HTTP/1.1 101 Switching Protocols\r\n" +
            "Upgrade: websocket\r\n" +
            "Connection: Upgrade\r\n" +
            "Sec-WebSocket-Accept: $accept\r\n" +
            "Sec-WebSocket-Protocol: v12.stomp\r\n\r\n"
        output.write(upgrade.toByteArray(StandardCharsets.US_ASCII))
        output.flush()

        val writeLock = Any()
        val mySubs = mutableListOf<Subscription>()
        try {
            while (running.get()) {
                val msg = readClientWsFrame(input) ?: break
                val frame = StompCodec.decode(msg) ?: continue
                when (frame.command) {
                    "CONNECT", "STOMP" -> {
                        val tok = frame.headers["X-Device-Token"]
                        lastStompConnectToken.set(tok)
                        if (tok != validToken) {
                            val err = StompCodec.encode(StompFrame("ERROR", mapOf("message" to "UNAUTHORIZED")))
                            synchronized(writeLock) { writeServerWsTextFrame(output, err) }
                            break
                        }
                        val connected = StompCodec.encode(
                            StompFrame("CONNECTED", mapOf("version" to "1.2", "heart-beat" to "0,0")),
                        )
                        synchronized(writeLock) { writeServerWsTextFrame(output, connected) }
                    }
                    "SUBSCRIBE" -> {
                        val dest = frame.headers["destination"] ?: continue
                        val sub = Subscription(dest, output, writeLock)
                        mySubs.add(sub)
                        subscriptions.add(sub)
                    }
                    "DISCONNECT" -> break
                }
            }
        } catch (_: Exception) {
            // 클라이언트 종료
        } finally {
            subscriptions.removeAll(mySubs.toSet())
        }
    }

    private fun handleRest(
        method: String,
        path: String,
        headers: Map<String, String>,
        body: String,
        output: OutputStream,
    ) {
        when {
            method == "GET" && path == "/api/v1/consent/notice" -> {
                writeHttpResponse(output, 200, """{"version":"2026-11-v1"}""")
                return
            }
            method == "POST" && path == "/api/v1/devices" -> {
                deviceRegisterCount.incrementAndGet()
                writeHttpResponse(output, 200, """{"deviceToken":"$validToken"}""")
                return
            }
        }

        if (headers["x-device-token"] != validToken) {
            writeHttpResponse(output, 401, """{"code":"UNAUTHORIZED","message":"invalid token"}""")
            return
        }

        when {
            method == "GET" && path == "/api/v1/stores" ->
                writeHttpResponse(
                    output,
                    200,
                    """[{"id":1,"name":"42dot","address":"성남","latitude":37.41,"longitude":127.09,"poiId":"p1","poiSubId":"0"}]""",
                )
            method == "GET" && path == "/api/v1/stores/1/queue-status" ->
                writeHttpResponse(
                    output,
                    200,
                    """{"storeId":1,"aheadTeams":3,"estimatedWaitMin":15,"arrivalBufferMin":10}""",
                )
            method == "POST" && path == "/api/v1/journeys" ->
                writeHttpResponse(output, 200, """{"journeyId":77}""")
            method == "POST" && path == "/api/v1/waitings" -> {
                registerCallCount.incrementAndGet()
                val status = nextRegisterStatus.get()
                if (status >= 400) {
                    writeHttpResponse(output, status, """{"code":"ERR_$status","message":"error $status"}""")
                } else {
                    writeHttpResponse(output, status, waitingJson.get())
                }
            }
            method == "GET" && path == "/api/v1/waitings/101" ->
                writeHttpResponse(output, 200, waitingJson.get())
            method == "POST" && path == "/api/v1/waitings/101/arrival" -> {
                arrivalRequests.add(body)
                writeHttpResponse(output, 200, """{"waitingId":101}""")
            }
            else -> writeHttpResponse(output, 404, """{"code":"NOT_FOUND","message":"$method $path"}""")
        }
    }

    private fun writeHttpResponse(output: OutputStream, status: Int, body: String) {
        val bytes = body.toByteArray(StandardCharsets.UTF_8)
        val header = "HTTP/1.1 $status OK\r\n" +
            "Content-Type: application/json\r\n" +
            "Content-Length: ${bytes.size}\r\n" +
            "Connection: close\r\n\r\n"
        output.write(header.toByteArray(StandardCharsets.US_ASCII))
        output.write(bytes)
        output.flush()
    }

    private fun readHttpHeaders(input: InputStream): String? {
        val buf = ByteArrayOutputStream()
        var state = 0
        while (buf.size() < 16_384) {
            val b = input.read()
            if (b < 0) return null
            buf.write(b)
            state = when (state) {
                0 -> if (b == '\r'.code) 1 else 0
                1 -> if (b == '\n'.code) 2 else if (b == '\r'.code) 1 else 0
                2 -> if (b == '\r'.code) 3 else 0
                3 -> if (b == '\n'.code) 4 else if (b == '\r'.code) 1 else 0
                else -> 0
            }
            if (state == 4) {
                return String(buf.toByteArray(), StandardCharsets.ISO_8859_1).removeSuffix("\r\n\r\n")
            }
        }
        return null
    }

    private fun readClientWsFrame(input: InputStream): ByteArray? {
        val b0 = input.read()
        if (b0 < 0) return null
        val b1 = input.read()
        if (b1 < 0) return null
        val opcode = b0 and 0x0F
        if (opcode == 0x8) return null
        val masked = (b1 and 0x80) != 0
        var len = b1 and 0x7F
        if (len == 126) {
            val ext = readFully(input, 2)
            len = ((ext[0].toInt() and 0xFF) shl 8) or (ext[1].toInt() and 0xFF)
        }
        val mask = if (masked) readFully(input, 4) else null
        val data = readFully(input, len)
        if (mask != null) {
            for (i in data.indices) data[i] = (data[i].toInt() xor mask[i and 3].toInt()).toByte()
        }
        return data
    }

    private fun writeServerWsTextFrame(output: OutputStream, payload: ByteArray) {
        output.write(0x81)
        when {
            payload.size <= 125 -> output.write(payload.size)
            payload.size <= 65_535 -> {
                output.write(126)
                output.write((payload.size ushr 8) and 0xFF)
                output.write(payload.size and 0xFF)
            }
            else -> error("테스트 프레임 크기 초과")
        }
        output.write(payload)
        output.flush()
    }

    private fun readFully(input: InputStream, count: Int): ByteArray {
        val out = ByteArray(count)
        var offset = 0
        while (offset < count) {
            val n = input.read(out, offset, count - offset)
            if (n < 0) break
            offset += n
        }
        return out
    }
}
