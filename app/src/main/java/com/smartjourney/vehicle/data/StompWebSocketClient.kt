package com.smartjourney.vehicle.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URI
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/** STOMP 인증·권한 거부. [unauthorized]가 true면 기기 토큰을 폐기하고 재등록해야 한다. */
class StompAuthException(val unauthorized: Boolean, message: String) : IOException(message)

/** WebSocket 또는 STOMP 프로토콜 규격 오류. */
class StompProtocolException(message: String) : IOException(message)

internal data class WsEndpoint(
    val secure: Boolean,
    val host: String,
    val port: Int,
    val requestPath: String,
    val hostHeader: String,
)

/**
 * 표준 JVM/Android 소켓만으로 구현한 RFC 6455 WebSocket + STOMP 1.2 클라이언트.
 * 외부 라이브러리(OkHttp/Krossbow)를 추가하지 않고 smart-journey-server(/ws, SockJS 미사용)를 구독한다.
 * 토큰은 URL 쿼리가 아니라 STOMP CONNECT 프레임의 X-Device-Token 헤더로만 보낸다(Pleos 보안 가이드).
 */
class StompWebSocketClient(
    baseUrl: String,
    private val allowCleartext: Boolean = false,
    wsPath: String = "/ws",
    private val connectTimeoutMs: Int = 5_000,
    private val clientHeartbeatMs: Long = 10_000L,
) {
    private val endpoint = parseEndpoint(baseUrl, wsPath, allowCleartext)
    private val random = SecureRandom()

    /**
     * 단일 STOMP 토픽을 구독하고 연결이 끊기거나 코루틴이 취소될 때까지 메시지를 전달한다.
     * 서버가 정상 종료(Close)하면 반환하고, 네트워크·프로토콜·인증 오류는 예외를 던진다.
     */
    suspend fun subscribe(
        destination: String,
        deviceToken: String,
        onConnected: suspend () -> Unit = {},
        onMessage: suspend (String) -> Unit,
    ) = withContext(Dispatchers.IO) {
        val socket = openSocket()
        coroutineScope {
            // Job.invokeOnCompletion은 블로킹 read() 종료 전까지 호출되지 않으므로
            // awaitCancellation() 감시 코루틴으로 취소 즉시 소켓을 닫아 블로킹 대기를 해제한다.
            val cancelWatcher = launch(Dispatchers.IO, start = CoroutineStart.UNDISPATCHED) {
                try {
                    awaitCancellation()
                } finally {
                    runCatching { socket.close() }
                }
            }
            var heartbeatJob: Job? = null
            var transport: WsTransport? = null
            try {
                val input = BufferedInputStream(socket.getInputStream())
                val output = socket.getOutputStream()
                performUpgradeHandshake(input, output)
                val ws = WsTransport(input, output, random)
                transport = ws

                // 1) STOMP CONNECT (토큰은 헤더에만 포함)
                ws.sendText(
                    StompCodec.encode(
                        StompFrame(
                            command = "CONNECT",
                            headers = linkedMapOf(
                                "accept-version" to "1.2,1.1",
                                "host" to endpoint.host,
                                "heart-beat" to "$clientHeartbeatMs,$clientHeartbeatMs",
                                "X-Device-Token" to deviceToken,
                            ),
                        ),
                    ),
                )

                // 2) CONNECTED 대기
                val connected = awaitConnectedFrame(ws)
                val (sendHeartbeatMs, receiveHeartbeatMs) = StompCodec.negotiateHeartbeat(
                    clientSendMs = clientHeartbeatMs,
                    clientReceiveMs = clientHeartbeatMs,
                    serverHeader = connected.headers["heart-beat"],
                )
                // 서버 하트비트 기대 주기가 있으면 2.5배를 읽기 타임아웃으로 잡아 터널·음영 단절을 빠르게 감지한다.
                socket.soTimeout = if (receiveHeartbeatMs > 0L) {
                    (receiveHeartbeatMs * 5L / 2L).coerceIn(15_000L, 120_000L).toInt()
                } else {
                    0
                }

                // 3) SUBSCRIBE
                ws.sendText(
                    StompCodec.encode(
                        StompFrame(
                            command = "SUBSCRIBE",
                            headers = linkedMapOf(
                                "id" to "sub-0",
                                "destination" to destination,
                                "ack" to "auto",
                            ),
                        ),
                    ),
                )

                if (sendHeartbeatMs > 0L) {
                    heartbeatJob = launch(Dispatchers.IO) {
                        while (isActive) {
                            delay(sendHeartbeatMs)
                            try {
                                ws.sendText(HEARTBEAT_BYTES)
                            } catch (e: CancellationException) {
                                throw e
                            } catch (_: IOException) {
                                // 송신 실패 시 소켓을 닫아 수신 루프가 즉시 재연결로 넘어가게 한다.
                                runCatching { socket.close() }
                                break
                            }
                        }
                    }
                }

                onConnected()

                while (currentCoroutineContext().isActive) {
                    val payload = ws.readMessage() ?: break
                    val frame = StompCodec.decode(payload) ?: continue
                    when (frame.command) {
                        "MESSAGE" -> onMessage(frame.body)
                        "ERROR" -> throw toStompError(frame)
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: IOException) {
                // 코루틴 취소 때문에 소켓을 닫아 발생한 IOException은 취소로 전파한다.
                if (!currentCoroutineContext().isActive) {
                    throw CancellationException("STOMP 구독이 취소되었어요", e)
                }
                throw e
            } finally {
                heartbeatJob?.cancel()
                cancelWatcher.cancel()
                if (!socket.isClosed) {
                    runCatching {
                        transport?.sendText(StompCodec.encode(StompFrame("DISCONNECT")))
                        transport?.sendClose()
                    }
                }
                runCatching { socket.close() }
            }
        }
    }

    private fun openSocket(): Socket {
        val raw = Socket()
        raw.connect(InetSocketAddress(endpoint.host, endpoint.port), connectTimeoutMs)
        if (!endpoint.secure) {
            if (!allowCleartext) {
                runCatching { raw.close() }
                throw IllegalArgumentException("릴리스 환경에서는 평문 WebSocket(ws://)을 허용하지 않아요")
            }
            raw.soTimeout = connectTimeoutMs
            return raw
        }

        val factory = SSLSocketFactory.getDefault() as SSLSocketFactory
        val ssl = factory.createSocket(raw, endpoint.host, endpoint.port, true) as SSLSocket
        // TLS 1.2 이상만 허용하고 표준 HTTPS 호스트명 검증을 강제한다(신뢰 우회 금지).
        val supported = ssl.supportedProtocols.toSet()
        val tlsProtocols = listOf("TLSv1.3", "TLSv1.2").filter { it in supported }
        if (tlsProtocols.isNotEmpty()) {
            ssl.enabledProtocols = tlsProtocols.toTypedArray()
        }
        val params = ssl.sslParameters
        params.endpointIdentificationAlgorithm = "HTTPS"
        ssl.sslParameters = params
        ssl.soTimeout = connectTimeoutMs
        ssl.startHandshake()
        return ssl
    }

    private fun performUpgradeHandshake(input: BufferedInputStream, output: OutputStream) {
        val keyBytes = ByteArray(16).also(random::nextBytes)
        val secKey = Base64.getEncoder().encodeToString(keyBytes)
        val request = buildString {
            append("GET ").append(endpoint.requestPath).append(" HTTP/1.1\r\n")
            append("Host: ").append(endpoint.hostHeader).append("\r\n")
            append("Upgrade: websocket\r\n")
            append("Connection: Upgrade\r\n")
            append("Sec-WebSocket-Key: ").append(secKey).append("\r\n")
            append("Sec-WebSocket-Version: 13\r\n")
            append("Sec-WebSocket-Protocol: v12.stomp, v11.stomp\r\n")
            append("\r\n")
        }
        output.write(request.toByteArray(StandardCharsets.US_ASCII))
        output.flush()

        val rawHeaders = readHttpHeaderBlock(input)
        val lines = rawHeaders.split("\r\n")
        val statusLine = lines.firstOrNull().orEmpty()
        val statusCode = statusLine.split(' ').getOrNull(1)?.toIntOrNull()
        if (statusCode != 101) {
            throw StompProtocolException("WebSocket 업그레이드 실패: $statusLine")
        }

        val headers = HashMap<String, String>()
        for (i in 1 until lines.size) {
            val idx = lines[i].indexOf(':')
            if (idx > 0) {
                val k = lines[i].substring(0, idx).trim().lowercase()
                val v = lines[i].substring(idx + 1).trim()
                headers[k] = v
            }
        }
        if (!headers["upgrade"].equals("websocket", ignoreCase = true)) {
            throw StompProtocolException("WebSocket Upgrade 헤더 누락")
        }
        if (headers["connection"]?.lowercase()?.contains("upgrade") != true) {
            throw StompProtocolException("WebSocket Connection 헤더 누락")
        }
        val expectedAccept = computeWebSocketAccept(secKey)
        if (headers["sec-websocket-accept"] != expectedAccept) {
            throw StompProtocolException("Sec-WebSocket-Accept 검증 실패")
        }
    }

    private fun readHttpHeaderBlock(input: BufferedInputStream): String {
        val buf = ByteArrayOutputStream()
        var state = 0
        while (buf.size() < MAX_HANDSHAKE_HEADER_BYTES) {
            val b = input.read()
            if (b < 0) throw EOFException("WebSocket 핸드셰이크 응답이 끊겼어요")
            buf.write(b)
            state = when (state) {
                0 -> if (b == '\r'.code) 1 else 0
                1 -> if (b == '\n'.code) 2 else if (b == '\r'.code) 1 else 0
                2 -> if (b == '\r'.code) 3 else 0
                3 -> if (b == '\n'.code) 4 else if (b == '\r'.code) 1 else 0
                else -> 0
            }
            if (state == 4) {
                return String(buf.toByteArray(), StandardCharsets.ISO_8859_1)
                    .removeSuffix("\r\n\r\n")
            }
        }
        throw StompProtocolException("WebSocket 핸드셰이크 헤더가 너무 큽니다")
    }

    private fun awaitConnectedFrame(ws: WsTransport): StompFrame {
        while (true) {
            val payload = ws.readMessage()
                ?: throw StompProtocolException("STOMP CONNECTED 전에 연결이 닫혔어요")
            val frame = StompCodec.decode(payload) ?: continue
            return when (frame.command) {
                "CONNECTED" -> frame
                "ERROR" -> throw toStompError(frame)
                else -> throw StompProtocolException("예상치 못한 STOMP 응답: ${frame.command}")
            }
        }
    }

    private fun toStompError(frame: StompFrame): IOException {
        val message = frame.headers["message"]?.takeIf { it.isNotBlank() }
            ?: frame.body.takeIf { it.isNotBlank() }
            ?: "STOMP ERROR"
        val upper = "$message ${frame.body}".uppercase()
        return when {
            "UNAUTHORIZED" in upper || "401" in upper -> StompAuthException(unauthorized = true, message = message)
            "FORBIDDEN" in upper || "403" in upper -> StompAuthException(unauthorized = false, message = message)
            else -> StompProtocolException(message)
        }
    }

    companion object {
        private const val WS_MAGIC_GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"
        private const val MAX_HANDSHAKE_HEADER_BYTES = 16 * 1024
        internal const val MAX_FRAME_BYTES = 1024 * 1024
        private val HEARTBEAT_BYTES = byteArrayOf('\n'.code.toByte())

        internal fun computeWebSocketAccept(secKey: String): String {
            val sha1 = MessageDigest.getInstance("SHA-1")
                .digest((secKey + WS_MAGIC_GUID).toByteArray(StandardCharsets.US_ASCII))
            return Base64.getEncoder().encodeToString(sha1)
        }

        internal fun parseEndpoint(baseUrl: String, wsPath: String, allowCleartext: Boolean): WsEndpoint {
            val uri = URI(baseUrl.trim().trimEnd('/'))
            val scheme = uri.scheme?.lowercase()
                ?: throw IllegalArgumentException("서버 주소에 스킴(https://)이 없어요")
            val secure = when (scheme) {
                "https", "wss" -> true
                "http", "ws" -> {
                    require(allowCleartext) { "릴리스 환경에서는 HTTPS 서버 주소만 허용해요" }
                    false
                }
                else -> throw IllegalArgumentException("지원하지 않는 서버 스킴이에요: $scheme")
            }
            val host = uri.host?.takeIf { it.isNotBlank() }
                ?: throw IllegalArgumentException("서버 호스트 주소가 비어 있어요")
            val defaultPort = if (secure) 443 else 80
            val port = if (uri.port > 0) uri.port else defaultPort
            val hostHeader = if (port == defaultPort) host else "$host:$port"
            val basePath = uri.rawPath.orEmpty().trimEnd('/')
            val normalizedWsPath = if (wsPath.startsWith("/")) wsPath else "/$wsPath"
            val requestPath = (basePath + normalizedWsPath).ifEmpty { "/ws" }
            return WsEndpoint(
                secure = secure,
                host = host,
                port = port,
                requestPath = requestPath,
                hostHeader = hostHeader,
            )
        }
    }
}

/** RFC 6455 클라이언트 프레임 송수신기. 송신은 마스킹을 적용하고 writeLock으로 직렬화한다. */
private class WsTransport(
    private val input: InputStream,
    private val output: OutputStream,
    private val random: SecureRandom,
) {
    private val writeLock = Any()

    fun sendText(payload: ByteArray) = sendFrame(OP_TEXT, payload)

    fun sendClose() = sendFrame(OP_CLOSE, byteArrayOf(0x03, 0xE8.toByte())) // 1000 Normal Closure

    private fun sendPong(payload: ByteArray) = sendFrame(OP_PONG, payload)

    private fun sendFrame(opcode: Int, payload: ByteArray) {
        val mask = ByteArray(4).also(random::nextBytes)
        val header = ByteArrayOutputStream(14)
        header.write(0x80 or (opcode and 0x0F))
        val len = payload.size
        when {
            len <= 125 -> header.write(0x80 or len)
            len <= 65_535 -> {
                header.write(0x80 or 126)
                header.write((len ushr 8) and 0xFF)
                header.write(len and 0xFF)
            }
            else -> {
                header.write(0x80 or 127)
                val len64 = len.toLong()
                for (shift in 56 downTo 0 step 8) {
                    header.write(((len64 ushr shift) and 0xFFL).toInt())
                }
            }
        }
        header.write(mask)
        val masked = ByteArray(len) { i -> (payload[i].toInt() xor mask[i and 3].toInt()).toByte() }
        synchronized(writeLock) {
            output.write(header.toByteArray())
            if (masked.isNotEmpty()) output.write(masked)
            output.flush()
        }
    }

    /**
     * 완전한 데이터 메시지(텍스트·바이너리) 1건을 읽는다.
     * Ping에는 즉시 Pong을 응답하고, 서버가 Close를 보내거나 EOF면 null을 반환한다.
     */
    fun readMessage(): ByteArray? {
        var fragmented: ByteArrayOutputStream? = null
        while (true) {
            val b0 = input.read()
            if (b0 < 0) return null
            val b1 = readByteOrEof()
            val fin = (b0 and 0x80) != 0
            val opcode = b0 and 0x0F
            val masked = (b1 and 0x80) != 0
            val length = readPayloadLength(b1 and 0x7F)
            val maskKey = if (masked) readFully(4) else null
            val payload = readFully(length)
            if (maskKey != null) {
                for (i in payload.indices) {
                    payload[i] = (payload[i].toInt() xor maskKey[i and 3].toInt()).toByte()
                }
            }

            when (opcode) {
                OP_PING -> runCatching { sendPong(payload) }
                OP_PONG -> Unit
                OP_CLOSE -> {
                    runCatching { sendClose() }
                    return null
                }
                OP_TEXT, OP_BINARY -> {
                    if (fin && fragmented == null) return payload
                    fragmented = ByteArrayOutputStream(payload.size).apply { write(payload) }
                    if (fin) return fragmented.toByteArray()
                }
                OP_CONTINUATION -> {
                    val acc = fragmented
                        ?: throw StompProtocolException("시작 프레임 없는 WebSocket continuation 프레임")
                    if (acc.size() + payload.size > StompWebSocketClient.MAX_FRAME_BYTES) {
                        throw StompProtocolException("WebSocket 메시지 크기 한도를 초과했어요")
                    }
                    acc.write(payload)
                    if (fin) return acc.toByteArray()
                }
                else -> throw StompProtocolException("지원하지 않는 WebSocket opcode: $opcode")
            }
        }
    }

    private fun readPayloadLength(len7: Int): Int {
        val lengthLong = when {
            len7 <= 125 -> len7.toLong()
            len7 == 126 -> {
                val b = readFully(2)
                (((b[0].toInt() and 0xFF) shl 8) or (b[1].toInt() and 0xFF)).toLong()
            }
            else -> {
                val b = readFully(8)
                var v = 0L
                for (i in 0 until 8) {
                    v = (v shl 8) or (b[i].toInt() and 0xFF).toLong()
                }
                v
            }
        }
        if (lengthLong < 0L || lengthLong > StompWebSocketClient.MAX_FRAME_BYTES) {
            throw StompProtocolException("WebSocket 프레임 크기가 허용 범위를 넘었어요: $lengthLong")
        }
        return lengthLong.toInt()
    }

    private fun readByteOrEof(): Int {
        val b = input.read()
        if (b < 0) throw EOFException("WebSocket 스트림이 예기치 않게 끝났어요")
        return b
    }

    private fun readFully(count: Int): ByteArray {
        if (count == 0) return ByteArray(0)
        val buf = ByteArray(count)
        var offset = 0
        while (offset < count) {
            val read = input.read(buf, offset, count - offset)
            if (read < 0) throw EOFException("WebSocket 프레임 본문이 잘렸어요")
            offset += read
        }
        return buf
    }

    companion object {
        private const val OP_CONTINUATION = 0x0
        private const val OP_TEXT = 0x1
        private const val OP_BINARY = 0x2
        private const val OP_CLOSE = 0x8
        private const val OP_PING = 0x9
        private const val OP_PONG = 0xA
    }
}
