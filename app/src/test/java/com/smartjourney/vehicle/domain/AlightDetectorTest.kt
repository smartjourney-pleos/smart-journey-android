package com.smartjourney.vehicle.domain

import com.smartjourney.vehicle.domain.AlightDetector.State
import org.junit.Assert.assertEquals
import org.junit.Test

class AlightDetectorTest {

    private val detector = AlightDetector(partySize = 4)

    /** 도착 → 정차 → 문 열림 → [occupied]명 남고 문 닫힘까지 진행한다. 반환값은 문이 닫힌 시각. */
    private fun arriveAndLeave(occupied: Int): Long {
        detector.onArrived(now = 0)
        detector.onSignals(1_000, restricted = true, doorOpen = false, occupied = 4)
        assertEquals(State.WAIT_STOP, detector.state)
        detector.onSignals(2_000, restricted = false, doorOpen = false, occupied = 4)
        assertEquals(State.WAIT_DOOR, detector.state)
        detector.onSignals(3_000, restricted = false, doorOpen = true, occupied = 4)
        assertEquals(State.WAIT_SETTLE, detector.state)
        detector.onSignals(6_000, restricted = false, doorOpen = false, occupied = occupied)
        assertEquals(State.STABILIZING, detector.state)
        return 6_000
    }

    @Test
    fun 정상_흐름이면_3명_하차로_확정한다() {
        val closedAt = arriveAndLeave(occupied = 1)
        detector.onSignals(closedAt + 4_900, restricted = false, doorOpen = false, occupied = 1)
        assertEquals("5초가 지나기 전에는 확정하지 않는다", State.STABILIZING, detector.state)
        detector.onSignals(closedAt + 5_000, restricted = false, doorOpen = false, occupied = 1)
        assertEquals(State.VERIFY, detector.state)

        detector.onVerified(closedAt + 5_100, occupied = 1, doorOpen = false)
        assertEquals(State.CONFIRMED, detector.state)
        assertEquals(3, detector.alighted)
        assertEquals(4, detector.evidence.occupancyBefore)
        assertEquals(1, detector.evidence.occupancyAfter)
    }

    @Test
    fun 운전자까지_모두_내리면_전원_하차다() {
        val closedAt = arriveAndLeave(occupied = 0)
        detector.onSignals(closedAt + 5_000, restricted = false, doorOpen = false, occupied = 0)
        detector.onVerified(closedAt + 5_100, occupied = 0, doorOpen = false)
        assertEquals(4, detector.alighted)
    }

    @Test
    fun 점유가_3초마다_흔들리면_확정하지_않는다() {
        var t = arriveAndLeave(occupied = 1)
        repeat(4) {
            t += 3_000
            detector.onSignals(t, restricted = false, doorOpen = false, occupied = if (it % 2 == 0) 4 else 1)
            assertEquals(if (it % 2 == 0) State.WAIT_SETTLE else State.STABILIZING, detector.state)
        }
        // 마지막 값(1명)이 5초 유지된 뒤에야 재확인으로 넘어간다
        detector.onSignals(t + 5_000, restricted = false, doorOpen = false, occupied = 1)
        assertEquals(State.VERIFY, detector.state)
    }

    @Test
    fun 재확인_값이_다르면_다시_기다린다() {
        val closedAt = arriveAndLeave(occupied = 1)
        detector.onSignals(closedAt + 5_000, restricted = false, doorOpen = false, occupied = 1)
        detector.onVerified(closedAt + 5_100, occupied = 2, doorOpen = false)
        assertEquals(State.WAIT_SETTLE, detector.state)
        assertEquals(null, detector.alighted)
    }

    @Test
    fun 문이_3분_동안_안_열리면_수동으로_넘긴다() {
        detector.onArrived(now = 0)
        detector.onSignals(179_000, restricted = false, doorOpen = false, occupied = 4)
        assertEquals(State.WAIT_DOOR, detector.state)
        detector.onSignals(180_000, restricted = false, doorOpen = false, occupied = 4)
        assertEquals(State.MANUAL, detector.state)
    }

    @Test
    fun 센서를_못_쓰면_도착_즉시_수동이다() {
        detector.onArrived(now = 0, sensorsSupported = false)
        assertEquals(State.MANUAL, detector.state)
    }
}
