package com.smartjourney.vehicle.domain

/**
 * 하차 확인 상태 머신 (명세서 5.3).
 * 도착 → 정차 → 문 열림 → 문 닫힘 + 점유 감소가 [settleMs] 동안 유지 → 재확인 일치 → 확정.
 * [timeoutMs] 안에 확정하지 못하면 수동("도착했어요")으로 넘긴다. 시간은 밖에서 넣어 준다.
 */
class AlightDetector(
    private val partySize: Int,
    private val settleMs: Long = 5_000,
    private val timeoutMs: Long = 180_000,
) {
    enum class State { WAIT_ARRIVAL, WAIT_STOP, WAIT_DOOR, WAIT_SETTLE, STABILIZING, VERIFY, CONFIRMED, MANUAL }

    /** 서버로 보내는 근거. 좌표는 없다. */
    data class Evidence(
        val arrivedAt: Long? = null,
        val doorOpenedAt: Long? = null,
        val settledAt: Long? = null,
        val occupancyBefore: Int? = null,
        val occupancyAfter: Int? = null,
    )

    var state = State.WAIT_ARRIVAL
        private set
    var alighted: Int? = null
        private set
    var evidence = Evidence()
        private set

    private var settleStart = 0L
    private var settleOccupied = 0

    fun onArrived(now: Long, sensorsSupported: Boolean = true) {
        if (state != State.WAIT_ARRIVAL) return
        evidence = evidence.copy(arrivedAt = now)
        state = if (sensorsSupported) State.WAIT_STOP else State.MANUAL
    }

    /** 신호가 바뀔 때와 1초마다 부른다. */
    fun onSignals(now: Long, restricted: Boolean, doorOpen: Boolean, occupied: Int) {
        // 한 번의 입력으로 여러 단계가 넘어갈 수 있어 변화가 없을 때까지 돈다
        do {
            val before = state
            when (state) {
                State.WAIT_STOP -> if (!restricted) {
                    evidence = evidence.copy(occupancyBefore = occupied)
                    state = State.WAIT_DOOR
                }
                State.WAIT_DOOR -> if (doorOpen) {
                    evidence = evidence.copy(doorOpenedAt = now)
                    state = State.WAIT_SETTLE
                }
                State.WAIT_SETTLE -> if (!doorOpen && occupied < partySize) {
                    settleStart = now
                    settleOccupied = occupied
                    state = State.STABILIZING
                }
                State.STABILIZING -> when {
                    doorOpen || occupied != settleOccupied -> state = State.WAIT_SETTLE
                    now - settleStart >= settleMs -> state = State.VERIFY
                }
                else -> Unit
            }
        } while (state != before)

        val waiting = state == State.WAIT_STOP || state == State.WAIT_DOOR || state == State.WAIT_SETTLE
        if (waiting && now - (evidence.arrivedAt ?: now) >= timeoutMs) state = State.MANUAL
    }

    /** VERIFY 상태에서 차량 값을 한 번 더 읽은 결과를 넘긴다. 못 읽은 값(null)은 불일치로 본다. */
    fun onVerified(now: Long, occupied: Int?, doorOpen: Boolean?) {
        if (state != State.VERIFY) return
        if (doorOpen == false && occupied == settleOccupied) {
            alighted = (partySize - occupied).coerceIn(0, partySize)
            evidence = evidence.copy(settledAt = now, occupancyAfter = occupied)
            state = State.CONFIRMED
        } else {
            state = State.WAIT_SETTLE
        }
    }
}
