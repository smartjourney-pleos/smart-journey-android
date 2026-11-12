package com.smartjourney.vehicle.data

import com.smartjourney.vehicle.domain.ApprovalMethod
import com.smartjourney.vehicle.domain.ArrivalResult
import com.smartjourney.vehicle.domain.QueueStatus
import com.smartjourney.vehicle.domain.Store
import com.smartjourney.vehicle.domain.Waiting
import com.smartjourney.vehicle.domain.WaitingStatus
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map

/**
 * 중계 서버 계약 중 차량 앱이 쓰는 부분 (서버 문서 02).
 * 보내는 값은 인원, ETA(분), 하차 인원, 도착 여부뿐이다. 좌표는 없다 (정책 4).
 */
/** 제공자가 접수되지 않았다고 명시적으로 확인한 경우에만 사용한다. */
class RegistrationRejectedException(message: String) : Exception(message)

interface WaitingBackend {
    /** 앱 안의 시연 데이터인지. 화면·음성 결과에 데모임을 알린다. */
    val isDemo: Boolean get() = false

    /** 여정(코스)을 서버에 만든다. 앱 안 시연 백엔드는 할 일이 없다. */
    suspend fun startJourney(partySize: Int, storeIds: List<Long>, sensed: Boolean) {}
    suspend fun stores(): List<Store>                                   // GET /stores
    fun queueStatus(storeId: Long): Flow<QueueStatus>                   // GET queue-status + STOMP QUEUE_UPDATED
    suspend fun register(storeId: Long, partySize: Int, method: ApprovalMethod, idempotencyKey: String): Waiting  // POST /waitings
    fun waiting(waitingId: String): Flow<Waiting>                       // STOMP WAITING_UPDATED · CALLED
    suspend fun updateEta(waitingId: String, etaMin: Int)               // PUT /waitings/{id}/eta
    suspend fun reportArrival(waitingId: String, result: ArrivalResult, alighted: Int?, partySize: Int)  // POST …/arrival
    suspend fun cancel(waitingId: String)                               // DELETE /waitings/{id}
}

/**
 * 서버 없이 프로토타입을 돌리기 위한 앱 안 가짜 서버 겸 매장 POS.
 * ponytail: 메모리에만 있고 앱을 끄면 사라진다. 서버 Phase 1(여정·접수 API)이 나오면 HTTP·STOMP 구현으로 바꾼다.
 */
class FakeBackend : WaitingBackend {

    override val isDemo = true

    private val minutesPerTeam = 5
    private val arrivalBufferMin = 10

    // 실제 장소명·주소·좌표·POI를 함께 사용해 내비의 장소 조회와 앱의 표시를 일치시킨다.
    // POI 출처: Pleos-Playground/pleos-sample-navi-app, data/sample/NaviTestData.kt.
    // 주소·좌표는 2026-11-11 에뮬레이터 내비 poiDetail 응답으로 확인했다 (샘플의 42dot 주소는 이전 소재지).
    // 공식 샘플에서 확인한 두 장소만 제공한다. 웨이팅은 실제 영업 정보가 아닌 시연 데이터다.
    private val demoStores = listOf(
        Store(
            1, "42dot", "경기도 성남시 수정구 창업로40번길 20",
            37.41309722, 127.09383464,
            poiId = "36khp37hh8gqcm5nqhe5", poiSubId = "0",
        ),
        Store(
            2, "소프트웨어드림센터", "경기도 성남시 수정구 창업로40번길 20",
            37.41310555, 127.094275,
            poiId = "36khp2hdj2cfcm5nqhe3", poiSubId = "0",
        ),
    )

    /** 매장별 현장 대기 팀 수 */
    private val walkIns = MutableStateFlow(mapOf(1L to 6, 2L to 3))
    private val waitings = MutableStateFlow<Map<String, Waiting>>(emptyMap())
    private val byIdempotencyKey = mutableMapOf<String, String>()
    private var ticketSeq = 11

    /** 매장이 실제로 받은 정보. 데모에서 "무엇이 전송됐는지" 보여 줄 때 쓴다. */
    val received = MutableStateFlow<List<String>>(emptyList())

    override suspend fun stores(): List<Store> = demoStores

    override fun queueStatus(storeId: Long): Flow<QueueStatus> =
        walkIns.map { status(storeId, it[storeId] ?: 0) }.distinctUntilChanged()

    private fun status(storeId: Long, teams: Int) =
        QueueStatus(storeId, aheadTeams = teams, estimatedWaitMin = teams * minutesPerTeam, arrivalBufferMin = arrivalBufferMin)

    override suspend fun register(storeId: Long, partySize: Int, method: ApprovalMethod, idempotencyKey: String): Waiting {
        // 같은 키로 다시 오면 같은 웨이팅을 돌려준다 (화면·음성 동시 승인 대비)
        byIdempotencyKey[idempotencyKey]?.let { return waitings.value.getValue(it) }
        val teams = walkIns.value[storeId] ?: 0
        val waiting = Waiting(
            waitingId = "w-$ticketSeq", storeId = storeId, ticketNo = ticketSeq++, partySize = partySize,
            status = WaitingStatus.REGISTERED, aheadTeams = teams, estimatedWaitMin = teams * minutesPerTeam,
        )
        byIdempotencyKey[idempotencyKey] = waiting.waitingId
        waitings.value += waiting.waitingId to waiting
        receive("접수 · ${name(storeId)} ${partySize}명 · 번호 ${waiting.ticketNo} (${if (method == ApprovalMethod.VOICE) "음성" else "터치"} 승인)")
        return waiting
    }

    override fun waiting(waitingId: String): Flow<Waiting> =
        combine(waitings, walkIns) { all, teams ->
            all[waitingId]?.let { it.copy(aheadTeams = teams[it.storeId] ?: 0, estimatedWaitMin = (teams[it.storeId] ?: 0) * minutesPerTeam) }
        }.filterNotNull().distinctUntilChanged()

    override suspend fun updateEta(waitingId: String, etaMin: Int) {
        receive("ETA · 번호 ${ticket(waitingId)} · 도착 ${etaMin}분 전")
    }

    override suspend fun reportArrival(waitingId: String, result: ArrivalResult, alighted: Int?, partySize: Int) {
        // 호출된 뒤에 도착 보고가 오면 상태는 그대로 두고 기록만 한다
        // 일부 도착은 도착 완료가 아니다. 상태는 그대로 두고 기록만 한다.
        if (result != ArrivalResult.PARTIAL) {
            change(waitingId) { if (it.status == WaitingStatus.REGISTERED) it.copy(status = WaitingStatus.ARRIVED) else it }
        }
        receive(
            when (result) {
                ArrivalResult.CONFIRMED -> "도착 · 번호 ${ticket(waitingId)} · $alighted/${partySize}명 하차 (차량 확인)"
                ArrivalResult.PARTIAL -> "일부 도착 · 번호 ${ticket(waitingId)} · $alighted/${partySize}명 하차 추정 (차량 신호)"
                ArrivalResult.MANUAL -> "도착 · 번호 ${ticket(waitingId)} · 고객 확인"
                ArrivalResult.UNCONFIRMED -> "도착 · 번호 ${ticket(waitingId)} · 인원 미확인"
            },
        )
    }

    override suspend fun cancel(waitingId: String) {
        change(waitingId) { it.copy(status = WaitingStatus.CANCELLED) }
        receive("취소 · 번호 ${ticket(waitingId)}")
    }

    // ---------- 매장 POS 역할 (데모 패널에서 조작) ----------

    fun addWalkIn(storeId: Long, delta: Int) {
        walkIns.value += storeId to ((walkIns.value[storeId] ?: 0) + delta).coerceAtLeast(0)
    }

    fun call(waitingId: String) = change(waitingId) {
        it.copy(status = WaitingStatus.CALLED, qrPayload = "SJ-${it.storeId}-${it.ticketNo}")
    }

    fun seat(waitingId: String) = change(waitingId) { it.copy(status = WaitingStatus.SEATED) }

    private fun change(waitingId: String, block: (Waiting) -> Waiting) {
        val current = waitings.value[waitingId] ?: return
        waitings.value += waitingId to block(current)
    }

    private fun receive(line: String) {
        received.value = (received.value + line).takeLast(20)
    }

    private fun name(storeId: Long) = demoStores.first { it.id == storeId }.name
    private fun ticket(waitingId: String) = waitings.value[waitingId]?.ticketNo
}
