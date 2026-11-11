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
    val arrivals: Flow<Unit>

    /** [target]까지 남은 시간(초). 구독하는 동안 추적한다. */
    fun remainingSec(target: Store): Flow<Int>
    suspend fun sensorsSupported(): Boolean
    suspend fun readNow(): VehicleSnapshot
}

/** 명세서 5.1의 웨이팅 상태. PLANNED·PROMPTED는 차량 앱 안에만 있다. */
enum class StopPhase { PLANNED, PROMPTED, REGISTERED, ARRIVED, CALLED, SEATED, CANCELLED }

data class StopState(
    val store: Store,
    val phase: StopPhase = StopPhase.PLANNED,
    val waiting: Waiting? = null,
    val arrival: ArrivalResult? = null,
    val alighted: Int? = null,
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
) {
    enum class Kind { GOLDEN_TIME, PARTY_CHANGE, MANUAL_ARRIVAL, CALLED }
}

data class JourneyState(
    val active: Boolean = false,
    val partySize: Int = 0,
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

/** 카드와 알림이 같이 쓰는 문구 (디자인 가이드 6장). 버튼은 최대 2개다. */
data class PromptText(val title: String, val detail: String, val primary: String, val secondary: String?)

fun Prompt.text(): PromptText = when (kind) {
    Prompt.Kind.GOLDEN_TIME -> PromptText(
        title = "지금 $storeName 웨이팅 걸까요?",
        detail = "${partySize}명 · 대기 ${waitMin}분 · 도착 ${remainingMin}분 후",
        primary = "등록", secondary = "나중에",
    )
    Prompt.Kind.PARTY_CHANGE -> PromptText(
        title = "$storeName 인원을 ${newPartySize}명으로 바꿀까요?",
        detail = "${partySize}명 → ${newPartySize}명",
        primary = "바꾸기", secondary = "그대로",
    )
    Prompt.Kind.MANUAL_ARRIVAL -> PromptText(
        title = "자동으로 확인하지 못했어요",
        detail = "${storeName}에 도착했으면 눌러 주세요",
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
    val detail = when (stop.phase) {
        StopPhase.PLANNED -> when {
            queue == null -> "웨이팅 시점 계산 중"
            minutesUntilPrompt != null -> "웨이팅 알림까지 ${minutesUntilPrompt}분"
            else -> "지금 웨이팅을 걸 수 있어요" // 알림을 미뤘거나 놓친 뒤
        }
        StopPhase.PROMPTED -> "지금 웨이팅 걸까요?"
        StopPhase.REGISTERED -> when (alight) {
            null, AlightDetector.State.WAIT_ARRIVAL -> "앞에 ${stop.waiting?.aheadTeams ?: 0}팀 · 도착 ${remainingMin ?: 0}분 후"
            AlightDetector.State.MANUAL -> "도착했으면 눌러 주세요"
            else -> "내리는 인원 확인 중"
        }
        StopPhase.ARRIVED ->
            if (stop.arrival == ArrivalResult.CONFIRMED) "${stop.alighted}/${stop.waiting?.partySize}명 하차 확인" else "도착을 알렸어요"
        StopPhase.CALLED -> "입장하세요"
        StopPhase.SEATED -> "즐거운 식사 되세요"
        StopPhase.CANCELLED -> "웨이팅이 취소됐어요"
    }
    return "${stop.store.name} · $detail" + if (lowAccuracy) " (최근 정보 기준)" else ""
}
