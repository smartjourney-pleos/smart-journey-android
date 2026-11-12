package com.smartjourney.vehicle.data

import com.smartjourney.vehicle.domain.Store
import com.smartjourney.vehicle.domain.Waiting
import com.smartjourney.vehicle.domain.WaitingStatus
import com.smartjourney.vehicle.engine.JourneyState
import com.smartjourney.vehicle.engine.StopPhase
import com.smartjourney.vehicle.engine.StopState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class JourneyStateJsonCodecTest {

    @Test
    fun json_직렬화_역직렬화가_일치한다() {
        val store = Store(1L, "42dot", "경기 성남시 판교역로", 37.4, 127.1, "POI-1", "SUB-1")
        val waiting = Waiting("w-1", 1L, 10, 4, WaitingStatus.REGISTERED, 2, 10)
        val stop = StopState(store, StopPhase.REGISTERED, waiting, submissionId = "request-1")
        val state = JourneyState(active = true, partySize = 4, stops = listOf(stop), index = 0, log = listOf("시작"))

        val json = JourneyStateJsonCodec.toJson(state, 1000L)
        val restored = JourneyStateJsonCodec.fromJson(json, now = 2000L, maxAgeMs = 10000L)

        assertNotNull(restored)
        assertEquals(true, restored?.active)
        assertEquals(4, restored?.partySize)
        assertEquals(1, restored?.stops?.size)
        assertEquals("42dot", restored?.stops?.get(0)?.store?.name)
        assertEquals(StopPhase.REGISTERED, restored?.stops?.get(0)?.phase)
        assertEquals("w-1", restored?.stops?.get(0)?.waiting?.waitingId)
        assertEquals("request-1", restored?.stops?.get(0)?.submissionId)
    }

    @Test
    fun 유효기간이_지난_데이터는_복원되지_않는다() {
        val store = Store(1L, "42dot", "경기 성남시 판교역로", 37.4, 127.1)
        val state = JourneyState(active = true, partySize = 2, stops = listOf(StopState(store)))
        val json = JourneyStateJsonCodec.toJson(state, 1000L)

        // 10000ms 제한인데 20000ms가 지난 시점
        val restored = JourneyStateJsonCodec.fromJson(json, now = 21000L, maxAgeMs = 10000L)
        assertNull(restored)
    }
}
