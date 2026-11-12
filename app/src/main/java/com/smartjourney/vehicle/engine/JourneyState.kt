package com.smartjourney.vehicle.engine

import com.smartjourney.vehicle.core.VehicleSnapshot
import com.smartjourney.vehicle.domain.AlightDetector
import com.smartjourney.vehicle.domain.ArrivalResult
import com.smartjourney.vehicle.domain.QueueStatus
import com.smartjourney.vehicle.domain.Store
import com.smartjourney.vehicle.domain.Waiting
import kotlinx.coroutines.flow.Flow

/** 엔진이 읽는 차량·내비 신호. 실제 SDK와 데모 신호를 같은 모양으로 받는다. */
interface JourneySignals {
    val occupied: Flow<Int>
    val anyDoorOpen: Flow<Boolean>
    val restricted: Flow<Boolean>
    /** 도착한 매장. 내비는 경유지 도착을 "보낼 수 있다"고만 보장하므로 현재 매장과 맞는지 엔진이 확인한다. */
    val arrivals: Flow<Arrival>

    /** 운전자가 내비에서 경로를 취소했거나 목적지를 바꿨다. */
    val routeChanged: Flow<Unit>

    /** [target]까지 남은 시간(초). 구독하는 동안 추적한다. */
    fun remainingSec(target: Store): Flow<Int>
    suspend fun sensorsSupported(): Boolean
    suspend fun readNow(): VehicleSnapshot
}

sealed interface Arrival {
    /** 지금 향하는 매장 (데모 조작) */
    data object Current : Arrival
    /** 경유지 [order]번(1·2) = 코스의 order-1번째 매장 */
    data class Waypoint(val order: Int) : Arrival
    /** 최종 목적지 = 코스의 마지막 매장 */
    data object Destination : Arrival
}

/** 명세서 5.1의 웨이팅 상태. PLANNED·PROMPTED는 차량 앱 안에만 있다. */
enum class StopPhase { PLANNED, PROMPTED, REGISTERED, ARRIVED, CALLED, SEATED, CANCELLED }

/** 접수 요청의 결과. 요청 전송을 확정으로 보지 않는다(명세서 v4 7장). UNKNOWN은 재접수하지 않는다. */
enum class SubmissionStatus { NOT_REQUESTED, REQUESTING, CONFIRMED, REJECTED, UNKNOWN }

data class StopState(
    val store: Store,
    val phase: StopPhase = StopPhase.PLANNED,
    val waiting: Waiting? = null,
    val arrival: ArrivalResult? = null,
    val alighted: Int? = null,
    val submission: SubmissionStatus = SubmissionStatus.NOT_REQUESTED,
)

/** 운전자에게 묻는 한 장의 카드. 같은 [id]는 같은 질문이다(접수 멱등키로도 쓴다). */
data class Prompt(
    val id: String,
    val kind: Kind,
    val storeName: String,
    val partySize: Int,
    val newPartySize: Int = partySize,
    val waitMin: Int = 0,
    val remainingMin: Int = 0,
    val ticketNo: Int? = null,
    val expiresAt: Long? = null,
    /** 값이 있으면 대기 허용 시간 초과 확인 질문이다. 승인은 일반 접수와 같고, 보류하면 접수하지 않는다. */
    val waitLimitMin: Int? = null,
) {
    enum class Kind { GOLDEN_TIME, PARTY_CHANGE, MANUAL_ARRIVAL, CALLED }
}

data class JourneyState(
    val active: Boolean = false,
    val partySize: Int = 0,
    /** 사용자가 정한 대기 허용 시간(분). 예상 대기가 넘으면 접수를 권하지 않고 판단 질문을 한다. */
    val waitLimitMin: Int = DEFAULT_WAIT_LIMIT_MIN,
    val stops: List<StopState> = emptyList(),
    val index: Int = 0,
    val remainingMin: Int? = null,
    val queue: QueueStatus? = null,
    val minutesUntilPrompt: Int? = null,
    val restricted: Boolean = false,
    val alight: AlightDetector.State? = null,
    val lowAccuracy: Boolean = false,
    val prompt: Prompt? = null,
    val log: List<String> = emptyList(),
) {
    val current: StopState? get() = stops.getOrNull(index)
}

const val DEFAULT_WAIT_LIMIT_MIN = 30

/** 일부만 하차한 상태. 도착 완료나 입장 가능으로 안내하지 않는다. */
val StopState.partialArrival: Boolean get() = arrival == ArrivalResult.PARTIAL

/** "일행 3명 도착 추정 · 1명 이동 중" */
fun StopState.partialLine(partySize: Int): String {
    val arrived = alighted ?: 0
    return "일행 ${arrived}명 도착 추정 · ${(partySize - arrived).coerceAtLeast(0)}명 이동 중"
}

/** 카드와 알림이 같이 쓰는 문구 (디자인 가이드 6장). 버튼은 최대 2개다. */
data class PromptText(val title: String, val detail: String, val primary: String, val secondary: String?)

fun Prompt.text(): PromptText = when (kind) {
    Prompt.Kind.GOLDEN_TIME -> if (waitLimitMin != null) PromptText(
        title = "$storeName 대기가 ${waitMin}분이에요. 그래도 걸까요?",
        detail = "대기 ${waitMin}분 · 설정한 ${waitLimitMin}분보다 길어요 · ${partySize}명",
        primary = "걸기", secondary = "보류",
    ) else PromptText(
        title = "$storeName 지금 웨이팅을 걸까요?",
        detail = "도착 ${remainingMin}분 후 · 대기 ${waitMin}분 · ${partySize}명",
        primary = "걸기", secondary = "나중에",
    )
    Prompt.Kind.PARTY_CHANGE -> PromptText(
        title = "$storeName 인원을 ${newPartySize}명으로 바꿀까요?",
        detail = "${partySize}명 → ${newPartySize}명",
        primary = "바꾸기", secondary = "그대로",
    )
    Prompt.Kind.MANUAL_ARRIVAL -> PromptText(
        title = "자동으로 확인하지 못했어요",
        detail = "${storeName}에 도착했으면 음성으로 알려 주세요",
        primary = "도착했어요", secondary = "닫기",
    )
    Prompt.Kind.CALLED -> PromptText(
        title = "입장하세요! 번호 ${ticketNo}번",
        detail = "$storeName · ${partySize}명",
        primary = "확인", secondary = null,
    )
}

/** 상태 알림 한 줄 (디자인 가이드 N-01). */
fun JourneyState.statusLine(): String {
    val stop = current ?: return "여정이 끝났어요"
    val requestDetail = when (stop.submission) {
        SubmissionStatus.REQUESTING -> "접수 결과 확인 중"
        SubmissionStatus.UNKNOWN -> "접수 결과 확인 필요 · 다시 접수하지 마세요"
        SubmissionStatus.REJECTED -> "접수 거절 · 매장 정책을 확인해 주세요"
        else -> null
    }
    val detail = requestDetail ?: when (stop.phase) {
        StopPhase.PLANNED -> when {
            queue == null -> "웨이팅 시점 계산 중"
            queue.estimatedWaitMin > waitLimitMin -> "대기 ${queue.estimatedWaitMin}분 · 설정한 ${waitLimitMin}분보다 길어요"
            minutesUntilPrompt != null -> "웨이팅 알림까지 ${minutesUntilPrompt}분"
            else -> "지금 웨이팅을 걸 수 있어요" // 알림을 미뤘거나 놓친 뒤
        }
        StopPhase.PROMPTED -> "지금 웨이팅 걸까요?"
        StopPhase.REGISTERED, StopPhase.CALLED -> if (stop.partialArrival) stop.partialLine(partySize) else when {
            stop.phase == StopPhase.CALLED -> "입장 순서예요"
            else -> when (alight) {
            null, AlightDetector.State.WAIT_ARRIVAL -> "앞에 ${stop.waiting?.aheadTeams ?: 0}팀 · 도착 ${remainingMin?.let { "${it}분 후" } ?: "시간 확인 중"}"
            AlightDetector.State.MANUAL -> "도착했으면 음성으로 알려 주세요"
            else -> "내리는 인원 확인 중"
            }
        }
        StopPhase.ARRIVED ->
            if (stop.arrival == ArrivalResult.CONFIRMED) "차량 신호 기준 ${stop.alighted}/${stop.waiting?.partySize}명 하차 추정" else "도착 확인"
        StopPhase.SEATED -> "즐거운 식사 되세요"
        StopPhase.CANCELLED -> "웨이팅이 취소됐어요"
    }
    return "${stop.store.name} · $detail" + if (lowAccuracy) " (최근 정보 기준)" else ""
}
