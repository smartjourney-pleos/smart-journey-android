package com.smartjourney.vehicle.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.nio.charset.StandardCharsets

class StompFrameTest {

    @Test
    fun `STOMP 프레임 직렬화·역직렬화와 헤더 이스케이프를 처리한다`() {
        val frame = StompFrame(
            command = "MESSAGE",
            headers = linkedMapOf(
                "destination" to "/topic/waitings/42",
                "custom" to "a:b\\c\nd",
            ),
            body = """{"type":"WAITING_UPDATED","payload":{"storeName":"소프트웨어드림센터"}}""",
        )

        val encoded = StompCodec.encode(frame)
        val decoded = StompCodec.decode(encoded)!!

        assertEquals("MESSAGE", decoded.command)
        assertEquals("/topic/waitings/42", decoded.headers["destination"])
        assertEquals("a:b\\c\nd", decoded.headers["custom"])
        assertEquals(frame.body, decoded.body)
    }

    @Test
    fun `content-length는 한글 UTF-8 바이트 길이 기준으로 정확히 본문을 자른다`() {
        val body = """{"storeName":"소프트웨어드림센터","ticketNo":12}"""
        val bodyBytes = body.toByteArray(StandardCharsets.UTF_8)
        val raw = ("MESSAGE\ndestination:/topic/waitings/1\ncontent-length:${bodyBytes.size}\n\n$body\u0000\n")
            .toByteArray(StandardCharsets.UTF_8)

        val decoded = StompCodec.decode(raw)!!

        assertEquals("MESSAGE", decoded.command)
        assertEquals(body, decoded.body)
    }

    @Test
    fun `하트비트 개행만 있는 프레임은 null로 해석한다`() {
        assertNull(StompCodec.decode("\n".toByteArray(StandardCharsets.UTF_8)))
        assertNull(StompCodec.decode("\r\n\r\n".toByteArray(StandardCharsets.UTF_8)))
    }

    @Test
    fun `중복 헤더가 있으면 STOMP 1_2 규격대로 첫 번째 값을 우선한다`() {
        val raw = "MESSAGE\nmessage-id:first\nmessage-id:second\n\nok\u0000"
            .toByteArray(StandardCharsets.UTF_8)

        val decoded = StompCodec.decode(raw)!!

        assertEquals("first", decoded.headers["message-id"])
        assertEquals("ok", decoded.body)
    }

    @Test
    fun `하트비트 협상은 서버와 클라이언트 값 중 큰 주기를 선택하고 0이면 비활성화한다`() {
        assertEquals(10_000L to 10_000L, StompCodec.negotiateHeartbeat(10_000L, 10_000L, "10000,10000"))
        assertEquals(15_000L to 20_000L, StompCodec.negotiateHeartbeat(10_000L, 10_000L, "20000,15000"))
        assertEquals(0L to 0L, StompCodec.negotiateHeartbeat(10_000L, 10_000L, "0,0"))
        assertEquals(0L to 0L, StompCodec.negotiateHeartbeat(10_000L, 10_000L, null))
    }

    @Test
    fun `RFC 6455 Sec-WebSocket-Accept 계산이 표준 테스트 벡터와 일치한다`() {
        assertEquals(
            "s3pPLMBiTxaQ9kYGzzhZRbK+xOo=",
            StompWebSocketClient.computeWebSocketAccept("dGhlIHNhbXBsZSBub25jZQ=="),
        )
    }

    @Test
    fun `릴리스 정책(allowCleartext=false)에서는 http 주소를 차단하고 https만 허용한다`() {
        try {
            StompWebSocketClient.parseEndpoint("http://10.0.2.2:8080", "/ws", allowCleartext = false)
            fail("평문 http 주소가 차단되어야 합니다")
        } catch (_: IllegalArgumentException) {
            // 기대 동작
        }

        val secure = StompWebSocketClient.parseEndpoint("https://api.example.com/sj/", "/ws", allowCleartext = false)
        assertTrue(secure.secure)
        assertEquals("api.example.com", secure.host)
        assertEquals(443, secure.port)
        assertEquals("/sj/ws", secure.requestPath)
        assertEquals("api.example.com", secure.hostHeader)

        val debug = StompWebSocketClient.parseEndpoint("http://10.0.2.2:8080", "/ws", allowCleartext = true)
        assertFalse(debug.secure)
        assertEquals("10.0.2.2", debug.host)
        assertEquals(8080, debug.port)
        assertEquals("/ws", debug.requestPath)
        assertEquals("10.0.2.2:8080", debug.hostHeader)
    }
}
