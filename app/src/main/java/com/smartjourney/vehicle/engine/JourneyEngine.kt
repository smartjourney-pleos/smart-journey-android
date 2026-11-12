package com.smartjourney.vehicle.engine

import com.smartjourney.vehicle.data.JourneyStorage
import com.smartjourney.vehicle.data.RegistrationRejectedException
import com.smartjourney.vehicle.data.WaitingBackend
import com.smartjourney.vehicle.domain.AlightDetector
import com.smartjourney.vehicle.domain.ApprovalMethod
import com.smartjourney.vehicle.domain.ArrivalResult
import com.smartjourney.vehicle.domain.GoldenTime
import com.smartjourney.vehicle.domain.PromptGate
import com.smartjourney.vehicle.domain.QueueStatus
import com.smartjourney.vehicle.domain.Store
import com.smartjourney.vehicle.domain.Waiting
import com.smartjourney.vehicle.domain.WaitingStatus
import com.smartjourney.vehicle.domain.goldenTime
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.UUID

private const val PROMPT_TIMEOUT_MS = 60_000L
private const val INPUT_STALE_MS = 60_000L
private const val ETA_PERIOD_MS = 60_000L

/**
 * 여정 엔진: 매장마다 골든타임 → 승인 → 접수 → 도착·하차 확인 → 호출 → 착석을 차례로 진행한다 (명세서 5장).
 * 접수·인원 변경은 [approve]로 사용자 승인을 받은 뒤에만 한다 (정책 2).
 * 상태 변경은 모두 [scope]의 디스패처 한 곳에서 일어난다.
 */
class JourneyEngine(
    private val signals: JourneySignals,
    private val backend: WaitingBackend,
    private val scope: CoroutineScope,
    private val storage: JourneyStorage? = null,
    private val now: () -> Long = System::currentTimeMillis,
    private val logger: (String) -> Unit = {},
) {
    private val _state = MutableStateFlow(JourneyState())
    val state: StateFlow<JourneyState> = _state

    init {
        if (storage != null) {
            scope.launch {
                _state.collect { s ->
                    if (s.active && s.stops.isNotEmpty()) {
                        storage.save(s)
                    } else {
                        storage.clear()
                    }
                }
            }
        }
    }

    private val _notices = MutableSharedFlow<String>(extraBufferCapacity = 8)

    /** 결과 알림 문구 (디자인 가이드 C-17) */
    val notices: SharedFlow<String> = _notices

    // 현재 매장을 처리하는 동안만 사는 것들
    private var stopJob: Job? = null
    private var gate = PromptGate()
    private var detector: AlightDetector? = null
    private var arrived = false
    private var verifying = false
    private var arrivalReported = false
    private var manualPrompted = false
    private var promptTimeout: Job? = null
    private var vehicle = Triple(false, false, 0) // 주행 중, 문 열림, 점유 수
    private var lastInputAt = 0L
    private var lastEtaMin: Int? = null
    private var lastEtaAt = 0L

    fun validateStart(partySize: Int, stops: List<Store>) {
        require(!_state.value.active) { "진행 중인 방문을 먼저 종료해 주세요" }
        require(partySize in 1..9) { "방문 인원은 1~9명" }
        require(stops.size in 1..3) { "코스는 1~3곳" }
        require(stops.none { target -> _state.value.stops.any { previous ->
            previous.store.id == target.id && (
                previous.submission == SubmissionStatus.UNKNOWN ||
                    previous.submission == SubmissionStatus.REQUESTING ||
                    previous.waiting?.status in setOf(WaitingStatus.REGISTERED, WaitingStatus.ARRIVED, WaitingStatus.CALLED)
                )
        } }) { "이 매장의 기존 접수 결과를 먼저 확인해 주세요. 중복 접수를 막기 위해 새 요청을 중단했어요" }
    }

    fun start(partySize: Int, stops: List<Store>, waitLimitMin: Int = DEFAULT_WAIT_LIMIT_MIN) {
        validateStart(partySize, stops)
        _state.value = JourneyState(
            active = true, partySize = partySize, waitLimitMin = waitLimitMin, stops = stops.map { StopState(it) },
        )
        log("여정 시작 · ${stops.joinToString(" → ") { it.name }} · ${partySize}명")
        runStop()
    }

    /**
     * 프로세스 재시작 시 영속 저장소에서 여정 상태를 복원한다.
     * 활성 여정이 존재하면 엔진 상태를 복원하고 현재 매장 감시(runStop)를 재개한다.
     */
    fun restore(): Boolean {
        val saved = storage?.restore() ?: return false
        if (!saved.active || saved.stops.isEmpty()) return false
        _state.value = saved
        log("여정 복원 · ${saved.current?.store?.name.orEmpty()} (${saved.index + 1}/${saved.stops.size}) · ${saved.partySize}명")
        runStop(restored = true)
        return true
    }

    /** 카드의 주 버튼: 등록 / 바꾸기 / 도착했어요 / 확인 */
    fun approve(method: ApprovalMethod = ApprovalMethod.TOUCH, promptId: String? = null) {
        val prompt = _state.value.prompt ?: return
        if (promptId != null && prompt.id != promptId) return
        promptTimeout?.cancel()
        when (prompt.kind) {
            Prompt.Kind.GOLDEN_TIME -> register(prompt, method)
            Prompt.Kind.PARTY_CHANGE -> {
                _state.update { it.copy(partySize = prompt.newPartySize, prompt = null) }
                log("인원 변경 · ${prompt.partySize}명 → ${prompt.newPartySize}명")
                notice("${prompt.storeName} 인원을 ${prompt.newPartySize}명으로 바꿨어요")
            }
            Prompt.Kind.MANUAL_ARRIVAL -> confirmManualArrival()
            Prompt.Kind.CALLED -> _state.update { it.copy(prompt = null) }
        }
    }

    /** 오래된 질문·중복 인식·기한 지난 음성 응답은 상태를 변경하지 않는다. Main에서 호출한다. */
    fun respondByVoice(promptId: String, approved: Boolean): Boolean {
        val prompt = _state.value.prompt ?: return false
        if (prompt.id != promptId || (prompt.expiresAt != null && now() >= prompt.expiresAt)) return false
        if (approved) approve(ApprovalMethod.VOICE, promptId) else defer(promptId)
        return true
    }

    /** 정차 중 홈에서 바꾼 대기 허용 시간. 이미 나간 질문은 바꾸지 않는다. */
    fun setWaitLimit(minutes: Int) {
        if (_state.value.active) _state.update { it.copy(waitLimitMin = minutes) }
    }

    /** 보류, 60초 무응답 */
    fun defer(promptId: String? = null) {
        val prompt = _state.value.prompt ?: return
        if (promptId != null && prompt.id != promptId) return
        promptTimeout?.cancel()
        if (prompt.kind == Prompt.Kind.GOLDEN_TIME) {
            if (prompt.waitLimitMin != null) {
                // 대기 허용 초과 질문을 보류하면 이번 매장은 다시 묻지 않는다. 대체 매장 추천·경로 변경도 하지 않는다.
                gate.close()
                log("${prompt.storeName} 대기 ${prompt.waitMin}분 · 허용 ${prompt.waitLimitMin}분 초과로 보류")
            } else {
                gate.onDeclined(_state.value.remainingMin ?: 0)
                log("${prompt.storeName} 웨이팅은 나중에")
            }
            updateStop { it.copy(phase = StopPhase.PLANNED) }
        }
        _state.update { it.copy(prompt = null) }
    }

    /** 알림을 미뤘거나 놓친 뒤, 정차 중 앱에서 직접 웨이팅을 걸 때. 같은 승인 카드를 다시 띄운다. */
    fun promptNow() {
        val s = _state.value
        val stop = s.current ?: return
        val queue = s.queue ?: return
        if (stop.phase != StopPhase.PLANNED || stop.submission != SubmissionStatus.NOT_REQUESTED || s.prompt != null) return
        showGoldenTime(stop, queue, s.remainingMin ?: 0)
    }

    /** 자동 확인에 실패했을 때 "도착했어요" */
    fun confirmManualArrival() = reportArrival(ArrivalResult.MANUAL, null)

    fun end() {
        if (!_state.value.active) return
        // 방문 추적 종료는 접수 취소 승인이 아니다. 확정된 접수는 그대로 보존한다 (AGENTS: 승인 없는 접수 변경 금지).
        finish()
    }

    // ---------- 매장 한 곳 ----------

    private fun runStop(restored: Boolean = false) {
        stopJob?.cancel()
        promptTimeout?.cancel()
        gate = PromptGate()
        detector = null
        val stop = _state.value.current ?: return finish()

        arrived = stop.arrival != null
        verifying = false
        arrivalReported = stop.arrival != null && !stop.partialArrival
        manualPrompted = false
        lastEtaMin = null
        lastInputAt = now()

        if (!restored) {
            _state.update {
                it.copy(remainingMin = null, queue = null, minutesUntilPrompt = null, alight = null, lowAccuracy = false, prompt = null)
            }
        } else {
            _state.update { it.copy(prompt = null) }
        }
        // 수집기가 돌기 전에 부모 Job을 먼저 걸어 둔다 (첫 값이 바로 들어와도 launchInStop이 동작하도록)
        stopJob = Job(scope.coroutineContext[Job])
        launchInStop {
            combine(signals.remainingSec(stop.store), backend.queueStatus(stop.store.id)) { sec, queue -> sec to queue }
                .collect { (sec, queue) -> onInputs(sec, queue) }
        }
        launchInStop { signals.arrivals.collect { if (isCurrentStop(it)) onArrived() else log("다른 매장 도착 신호 무시 · $it") } }
        launchInStop {
            signals.routeChanged.collect {
                if (!arrived) {
                    log("내비 경로가 바뀌거나 취소됨")
                    notice("내비 경로가 바뀌었어요. 웨이팅 시간 계산이 맞지 않을 수 있어요")
                }
            }
        }
        launchInStop {
            combine(signals.restricted, signals.anyDoorOpen, signals.occupied) { r, d, o -> Triple(r, d, o) }
                .collect { latest ->
                    if (latest.first && !vehicle.first) lastInputAt = now() // 출발 시점부터 다시 잰다
                    vehicle = latest
                    _state.update { it.copy(restricted = latest.first) }
                    feedDetector()
                }
        }
        launchInStop {
            while (true) {
                delay(1_000)
                onTick()
            }
        }
        if (_state.value.index > 0) launchInStop { watchPartyChange() }

        // 복원 시 이미 접수된 매장(REGISTERED, ARRIVED, CALLED)인 경우 백엔드 대기 상태 구독을 재개한다
        stop.waiting?.let { waiting ->
            launchInStop { backend.waiting(waiting.waitingId).collect(::onWaiting) }
            if (arrived) {
                startDetectorIfReady()
            }
        }
    }

    private fun onInputs(sec: Int, queue: QueueStatus) {
        val stop = _state.value.current ?: return
        lastInputAt = now()
        val remainingMin = (sec + 59) / 60 // 초 → 분 올림
        val decision = goldenTime(remainingMin, queue.estimatedWaitMin, queue.arrivalBufferMin)
        _state.update {
            it.copy(
                remainingMin = remainingMin,
                queue = queue,
                minutesUntilPrompt = (decision as? GoldenTime.Wait)?.minutesUntilPrompt,
                lowAccuracy = false,
            )
        }
        when (stop.phase) {
            StopPhase.PLANNED ->
                if (stop.submission == SubmissionStatus.NOT_REQUESTED && decision is GoldenTime.Prompt && _state.value.prompt == null && gate.shouldPrompt(remainingMin)) {
                    log("골든타임 · ${stop.store.name} 대기 ${queue.estimatedWaitMin}분, 도착 ${remainingMin}분 후")
                    showGoldenTime(stop, queue, remainingMin)
                }
            StopPhase.REGISTERED -> sendEta(remainingMin)
            else -> Unit
        }
    }

    private fun showGoldenTime(stop: StopState, queue: QueueStatus, remainingMin: Int) {
        gate.onPrompted()
        updateStop { it.copy(phase = StopPhase.PROMPTED) }
        show(
            Prompt(
                id = newPromptId(), kind = Prompt.Kind.GOLDEN_TIME, storeName = stop.store.name,
                partySize = _state.value.partySize, waitMin = queue.estimatedWaitMin, remainingMin = remainingMin,
                expiresAt = now() + PROMPT_TIMEOUT_MS,
                waitLimitMin = _state.value.waitLimitMin.takeIf { queue.estimatedWaitMin > it },
            ),
        )
        promptTimeout = launchInStop {
            delay(PROMPT_TIMEOUT_MS)
            defer()
        }
    }

    private fun register(prompt: Prompt, method: ApprovalMethod) {
        val stop = _state.value.current ?: return
        if (stop.submission != SubmissionStatus.NOT_REQUESTED) return
        val partySize = _state.value.partySize
        _state.update { it.copy(prompt = null) }
        updateStop { it.copy(submission = SubmissionStatus.REQUESTING) }
        launchInStop {
            try {
                val waiting = backend.register(stop.store.id, partySize, method, idempotencyKey = prompt.id)
                updateStop { it.copy(phase = StopPhase.REGISTERED, waiting = waiting, submission = SubmissionStatus.CONFIRMED) }
                log("접수 완료 · ${stop.store.name} ${partySize}명 · 번호 ${waiting.ticketNo} · 앞에 ${waiting.aheadTeams}팀")
                notice("${if (backend.isDemo) "데모 접수예요. " else ""}${stop.store.name}에 ${partySize}명으로 등록했어요. 앞에 ${waiting.aheadTeams}팀 있어요")
                _state.value.remainingMin?.let(::sendEta)
                launchInStop { backend.waiting(waiting.waitingId).collect(::onWaiting) }
                startDetectorIfReady()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // 타임아웃·연결 단절은 서버에서 이미 접수됐을 수 있다. 자동 재접수 금지.
                val rejected = e is RegistrationRejectedException
                updateStop {
                    it.copy(phase = StopPhase.PLANNED, submission = if (rejected) SubmissionStatus.REJECTED else SubmissionStatus.UNKNOWN)
                }
                log(if (rejected) "접수 거절" else "접수 결과 확인 필요")
                notice(
                    if (rejected) "접수가 거절됐어요. 정차 후 매장 정책을 확인해 주세요"
                    else "접수 결과를 확인하지 못했어요. 다시 접수하지 말고 정차 후 확인해 주세요",
                )
            }
        }
    }

    private fun sendEta(remainingMin: Int) {
        val waiting = _state.value.current?.waiting ?: return
        val last = lastEtaMin
        // 2분 이상 바뀌었을 때 또는 1분 주기로만 보낸다
        if (last != null && kotlin.math.abs(remainingMin - last) < 2 && now() - lastEtaAt < ETA_PERIOD_MS) return
        lastEtaMin = remainingMin
        lastEtaAt = now()
        launchInStop { runCatching { backend.updateEta(waiting.waitingId, remainingMin) } }
    }

    private fun onWaiting(waiting: Waiting) {
        val stop = _state.value.current ?: return
        updateStop { it.copy(waiting = waiting) }
        when (waiting.status) {
            WaitingStatus.CALLED -> if (stop.phase != StopPhase.CALLED) {
                updateStop { it.copy(phase = StopPhase.CALLED) }
                log("${stop.store.name} 호출 · 번호 ${waiting.ticketNo}")
                show(
                    Prompt(
                        id = newPromptId(), kind = Prompt.Kind.CALLED, storeName = stop.store.name,
                        partySize = waiting.partySize, ticketNo = waiting.ticketNo,
                    ),
                )
            }
            WaitingStatus.SEATED -> {
                updateStop { it.copy(phase = StopPhase.SEATED) }
                log("${stop.store.name} 착석")
                notice("${stop.store.name} · 즐거운 식사 되세요")
                next()
            }
            WaitingStatus.NO_SHOW, WaitingStatus.CANCELLED -> {
                updateStop { it.copy(phase = StopPhase.CANCELLED) }
                log("${stop.store.name} 웨이팅 취소")
                notice("${stop.store.name} 웨이팅이 취소됐어요")
                next()
            }
            else -> Unit
        }
    }

    private fun next() {
        val nextIndex = _state.value.index + 1
        if (nextIndex >= _state.value.stops.size) return finish()
        _state.update { it.copy(index = nextIndex) }
        log("다음 매장 · ${_state.value.current?.store?.name}")
        runStop()
    }

    private fun finish() {
        stopJob?.cancel()
        if (_state.value.current?.submission == SubmissionStatus.REQUESTING) {
            updateStop { it.copy(submission = SubmissionStatus.UNKNOWN) }
        }
        promptTimeout?.cancel()
        _state.update { it.copy(active = false, prompt = null) }
        log("여정 종료")
    }

    // ---------- 도착·하차 확인 (명세서 5.3) ----------

    private fun isCurrentStop(arrival: Arrival): Boolean {
        val s = _state.value
        return when (arrival) {
            Arrival.Current -> true
            is Arrival.Waypoint -> arrival.order - 1 == s.index
            Arrival.Destination -> s.index == s.stops.lastIndex
        }
    }

    private fun onArrived() {
        if (arrived) return
        arrived = true
        log("${_state.value.current?.store?.name} 도착")
        startDetectorIfReady()
    }

    private fun startDetectorIfReady() {
        val stop = _state.value.current ?: return
        val waiting = stop.waiting ?: return // 접수 전에 도착했으면 접수된 뒤에 시작한다
        if (!arrived || detector != null) return
        launchInStop {
            val supported = signals.sensorsSupported()
            if (detector == null) {
                detector = AlightDetector(waiting.partySize).also { it.onArrived(now(), supported) }
                feedDetector()
            }
        }
    }

    private fun feedDetector() {
        val d = detector ?: return
        if (d.state != AlightDetector.State.CONFIRMED && d.state != AlightDetector.State.MANUAL) {
            val (restricted, doorOpen, occupied) = vehicle
            d.onSignals(now(), restricted, doorOpen, occupied)
            if (d.state == AlightDetector.State.VERIFY && !verifying) {
                verifying = true
                launchInStop {
                    // 보내기 직전에 차량 값을 한 번 더 읽는다
                    val snapshot = signals.readNow()
                    d.onVerified(now(), snapshot.occupied, snapshot.anyDoorOpen)
                    verifying = false
                    afterDetector(d)
                }
            }
        }
        afterDetector(d)
    }

    private fun afterDetector(d: AlightDetector) {
        _state.update { it.copy(alight = d.state) }
        when (d.state) {
            AlightDetector.State.CONFIRMED -> {
                val alighted = d.alighted ?: 0
                val partySize = _state.value.current?.waiting?.partySize ?: 0
                if (alighted < partySize) onPartialArrival(alighted) else reportArrival(ArrivalResult.CONFIRMED, alighted)
            }
            // 일부 도착 뒤에는 나머지 일행을 기다린다. 주차가 길어져도 도착 확인 질문을 하지 않는다.
            AlightDetector.State.MANUAL -> if (!arrivalReported && !manualPrompted && _state.value.current?.partialArrival != true) {
                manualPrompted = true
                val s = _state.value
                log("자동으로 확인하지 못함 · 수동 확인 요청")
                show(
                    Prompt(
                        id = newPromptId(), kind = Prompt.Kind.MANUAL_ARRIVAL,
                        storeName = s.current?.store?.name.orEmpty(), partySize = s.partySize,
                    ),
                )
            }
            else -> Unit
        }
    }

    /**
     * 일행 일부만 내렸다 (운전자가 일행을 먼저 내려 주고 주차하러 가는 경우). 도착 완료·입장 가능으로 처리하지 않는다.
     * 나머지 일행의 하차를 보도록 감지를 다시 시작하고, 하차 인원이 늘었을 때만 갱신한다.
     */
    private fun onPartialArrival(alighted: Int) {
        val stop = _state.value.current ?: return
        val waiting = stop.waiting ?: return
        detector = AlightDetector(waiting.partySize).also { it.onArrived(now(), true) }
        if (stop.partialArrival && alighted <= (stop.alighted ?: 0)) return
        updateStop { it.copy(arrival = ArrivalResult.PARTIAL, alighted = alighted) }
        val line = _state.value.current!!.partialLine(waiting.partySize)
        log("일부 도착 · $line")
        notice("${stop.store.name} · $line")
        launchInStop { runCatching { backend.reportArrival(waiting.waitingId, ArrivalResult.PARTIAL, alighted, waiting.partySize) } }
    }

    private fun reportArrival(result: ArrivalResult, alighted: Int?) {
        val stop = _state.value.current ?: return
        val waiting = stop.waiting ?: return
        if (!arrived || arrivalReported) return
        arrivalReported = true
        _state.update { s -> s.copy(prompt = s.prompt?.takeIf { it.kind != Prompt.Kind.MANUAL_ARRIVAL }) }
        launchInStop {
            try {
                backend.reportArrival(waiting.waitingId, result, alighted, waiting.partySize)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                notice("매장에 알리지 못했어요. 다시 시도할게요")
                delay(5_000)
                arrivalReported = false // 다음 틱에 다시 보낸다
                return@launchInStop
            }
            // 도착 전에 호출됐으면 호출 상태를 유지한다
            updateStop { it.copy(phase = if (it.phase == StopPhase.CALLED) it.phase else StopPhase.ARRIVED, arrival = result, alighted = alighted) }
            val sent = if (backend.isDemo) "데모 매장에 전송" else "매장에 전송"
            if (result == ArrivalResult.CONFIRMED) {
                log("하차 추정 · $alighted/${waiting.partySize}명 · $sent")
                notice("차량 신호 기준 $alighted/${waiting.partySize}명 하차 추정 · $sent")
            } else {
                log("도착(고객 확인) · $sent")
                notice("${stop.store.name} 도착 확인 · $sent")
            }
        }
    }

    // ---------- 인원 변동 (명세서 5.4) ----------

    /**
     * 다음 매장으로 출발하기 전, 재탑승(문 열림 → 닫힘) 뒤 좌석 수가 5초 유지되고 접수 인원과 다르면 한 번 묻는다.
     * ponytail: 첫 번째 문 여닫힘만 본다. 여러 번 타고 내리는 경우까지 보려면 출발(주행 전환) 직전 값으로 바꾼다.
     */
    @OptIn(FlowPreview::class)
    private suspend fun watchPartyChange() {
        signals.anyDoorOpen.first { it }
        signals.anyDoorOpen.first { !it }
        val occupied = signals.occupied.debounce(5_000).first()
        val s = _state.value
        val stop = s.current ?: return
        if (occupied < 1 || occupied == s.partySize || stop.phase != StopPhase.PLANNED || s.restricted || s.prompt != null) return
        log("좌석 ${occupied}명 감지 · 인원 변경 제안")
        show(
            Prompt(
                id = newPromptId(), kind = Prompt.Kind.PARTY_CHANGE, storeName = stop.store.name,
                partySize = s.partySize, newPartySize = occupied,
            ),
        )
    }

    // ---------- helpers ----------

    private fun onTick() {
        feedDetector()
        val s = _state.value
        val phase = s.current?.phase
        // 주행 중인데 남은 시간이 60초 넘게 안 오면 마지막 값으로 계산 중임을 알린다
        val expectingInput = s.restricted && !arrived && (phase == StopPhase.PLANNED || phase == StopPhase.REGISTERED)
        if (expectingInput && !s.lowAccuracy && now() - lastInputAt > INPUT_STALE_MS) {
            _state.update { it.copy(lowAccuracy = true) }
        }
    }

    /** 현재 매장 작업의 자식으로 돌린다. 다음 매장으로 넘어가면 같이 취소된다. */
    private fun launchInStop(block: suspend CoroutineScope.() -> Unit): Job? {
        val parent = stopJob ?: return null
        return scope.launch(parent, block = block)
    }

    private fun show(prompt: Prompt) = _state.update { it.copy(prompt = prompt) }

    private fun updateStop(block: (StopState) -> StopState) = _state.update { s ->
        s.copy(stops = s.stops.mapIndexed { i, stop -> if (i == s.index) block(stop) else stop })
    }

    private fun notice(message: String) {
        _notices.tryEmit(message)
    }

    private fun newPromptId() = UUID.randomUUID().toString()

    private fun log(message: String) {
        logger(message)
        val time = TIME.format(Instant.ofEpochMilli(now()).atZone(ZoneId.systemDefault()))
        _state.update { it.copy(log = (it.log + "$time  $message").takeLast(30)) }
    }

    private companion object {
        val TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss")
    }
}
