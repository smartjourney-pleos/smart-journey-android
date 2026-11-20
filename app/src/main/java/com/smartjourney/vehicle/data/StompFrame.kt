package com.smartjourney.vehicle.data

import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets

/**
 * STOMP 1.2 프레임 모델.
 * 외부 라이브러리 없이 smart-journey-server(/ws, SockJS 미사용)와 직접 통신하기 위해 최소 규격만 구현한다.
 */
data class StompFrame(
    val command: String,
    val headers: Map<String, String> = emptyMap(),
    val body: String = "",
)

/**
 * STOMP 1.2 프레임 직렬화·역직렬화 및 하트비트 협상 유틸리티.
 * 본문 길이(content-length)는 UTF-8 바이트 기준으로 처리해 한글 등 멀티바이트 문자열 잘림을 막는다.
 */
object StompCodec {

    private val NON_ESCAPED_COMMANDS = setOf("CONNECT", "STOMP", "CONNECTED")

    fun encode(frame: StompFrame): ByteArray {
        val escapeHeaders = frame.command !in NON_ESCAPED_COMMANDS
        val out = ByteArrayOutputStream()
        out.write(frame.command.toByteArray(StandardCharsets.UTF_8))
        out.write('\n'.code)
        for ((key, value) in frame.headers) {
            val k = if (escapeHeaders) escapeHeader(key) else key
            val v = if (escapeHeaders) escapeHeader(value) else value
            out.write(k.toByteArray(StandardCharsets.UTF_8))
            out.write(':'.code)
            out.write(v.toByteArray(StandardCharsets.UTF_8))
            out.write('\n'.code)
        }
        out.write('\n'.code)
        if (frame.body.isNotEmpty()) {
            out.write(frame.body.toByteArray(StandardCharsets.UTF_8))
        }
        out.write(0)
        return out.toByteArray()
    }

    /**
     * 원시 바이트를 STOMP 프레임으로 해석한다.
     * 개행(\r, \n)만 들어 있는 하트비트 프레임이면 null을 돌려준다.
     */
    fun decode(raw: ByteArray): StompFrame? {
        var start = 0
        while (start < raw.size && (raw[start] == '\n'.code.toByte() || raw[start] == '\r'.code.toByte())) {
            start++
        }
        if (start >= raw.size) return null

        val headerEnd = findHeaderEnd(raw, start)
        val headerSectionBytes = if (headerEnd != null) {
            raw.copyOfRange(start, headerEnd.first)
        } else {
            val nulIndex = indexOfByte(raw, 0, start)
            raw.copyOfRange(start, if (nulIndex >= 0) nulIndex else raw.size)
        }
        val headerLines = String(headerSectionBytes, StandardCharsets.UTF_8)
            .replace("\r\n", "\n")
            .split('\n')
        val command = headerLines.firstOrNull()?.trim().orEmpty()
        if (command.isEmpty()) return null

        val unescapeHeaders = command !in NON_ESCAPED_COMMANDS
        val headers = LinkedHashMap<String, String>()
        for (i in 1 until headerLines.size) {
            val line = headerLines[i]
            val colon = line.indexOf(':')
            if (colon <= 0) continue
            val rawKey = line.substring(0, colon)
            val rawValue = line.substring(colon + 1)
            val key = if (unescapeHeaders) unescapeHeader(rawKey) else rawKey
            val value = if (unescapeHeaders) unescapeHeader(rawValue) else rawValue
            // STOMP 1.2 규격: 같은 헤더가 반복되면 첫 번째 항목을 우선한다.
            if (!headers.containsKey(key)) {
                headers[key] = value
            }
        }

        val body = if (headerEnd != null) {
            val bodyStart = headerEnd.second
            val contentLength = headers["content-length"]?.toIntOrNull()?.takeIf { it >= 0 }
            val bodyEnd = if (contentLength != null) {
                (bodyStart + contentLength).coerceAtMost(raw.size)
            } else {
                val nulIndex = indexOfByte(raw, 0, bodyStart)
                if (nulIndex >= 0) nulIndex else raw.size
            }
            if (bodyEnd > bodyStart) {
                String(raw, bodyStart, bodyEnd - bodyStart, StandardCharsets.UTF_8)
            } else {
                ""
            }
        } else {
            ""
        }

        return StompFrame(command = command, headers = headers, body = body)
    }

    /**
     * 클라이언트 희망 주기와 서버 CONNECTED heart-beat 헤더를 비교해 실제 송신·수신 주기를 협상한다.
     * 반환값: Pair(클라이언트 송신 주기 ms, 서버 송신 기대 주기 ms). 0은 비활성화.
     */
    fun negotiateHeartbeat(
        clientSendMs: Long,
        clientReceiveMs: Long,
        serverHeader: String?,
    ): Pair<Long, Long> {
        val (serverSendMs, serverReceiveMs) = parseHeartbeat(serverHeader)
        val sendInterval = if (clientSendMs <= 0L || serverReceiveMs <= 0L) {
            0L
        } else {
            maxOf(clientSendMs, serverReceiveMs)
        }
        val receiveInterval = if (clientReceiveMs <= 0L || serverSendMs <= 0L) {
            0L
        } else {
            maxOf(clientReceiveMs, serverSendMs)
        }
        return sendInterval to receiveInterval
    }

    fun parseHeartbeat(header: String?): Pair<Long, Long> {
        if (header.isNullOrBlank()) return 0L to 0L
        val parts = header.split(',')
        if (parts.size != 2) return 0L to 0L
        val first = parts[0].trim().toLongOrNull()?.coerceAtLeast(0L) ?: return 0L to 0L
        val second = parts[1].trim().toLongOrNull()?.coerceAtLeast(0L) ?: return 0L to 0L
        return first to second
    }

    /** (헤더 끝 인덱스, 본문 시작 인덱스)를 찾는다. */
    private fun findHeaderEnd(bytes: ByteArray, start: Int): Pair<Int, Int>? {
        var i = start
        while (i < bytes.size) {
            if (bytes[i] == '\n'.code.toByte()) {
                if (i + 1 < bytes.size && bytes[i + 1] == '\n'.code.toByte()) {
                    return i to (i + 2)
                }
                if (i + 2 < bytes.size &&
                    bytes[i + 1] == '\r'.code.toByte() &&
                    bytes[i + 2] == '\n'.code.toByte()
                ) {
                    return i to (i + 3)
                }
            }
            if (bytes[i] == '\r'.code.toByte() &&
                i + 3 < bytes.size &&
                bytes[i + 1] == '\n'.code.toByte() &&
                bytes[i + 2] == '\r'.code.toByte() &&
                bytes[i + 3] == '\n'.code.toByte()
            ) {
                return i to (i + 4)
            }
            i++
        }
        return null
    }

    private fun indexOfByte(bytes: ByteArray, target: Byte, start: Int): Int {
        for (i in start until bytes.size) {
            if (bytes[i] == target) return i
        }
        return -1
    }

    private fun escapeHeader(value: String): String = buildString(value.length) {
        for (ch in value) {
            when (ch) {
                '\\' -> append("\\\\")
                '\r' -> append("\\r")
                '\n' -> append("\\n")
                ':' -> append("\\c")
                else -> append(ch)
            }
        }
    }

    private fun unescapeHeader(value: String): String = buildString(value.length) {
        var i = 0
        while (i < value.length) {
            val ch = value[i]
            if (ch == '\\' && i + 1 < value.length) {
                when (value[i + 1]) {
                    '\\' -> { append('\\'); i += 2 }
                    'r' -> { append('\r'); i += 2 }
                    'n' -> { append('\n'); i += 2 }
                    'c' -> { append(':'); i += 2 }
                    else -> { append(ch); i++ }
                }
            } else {
                append(ch)
                i++
            }
        }
    }
}
