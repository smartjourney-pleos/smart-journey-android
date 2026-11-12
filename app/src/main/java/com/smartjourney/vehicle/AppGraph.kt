package com.smartjourney.vehicle

import android.app.Application
import android.content.Context
import android.util.Log
import com.smartjourney.vehicle.core.ArrivalEvent
import com.smartjourney.vehicle.core.CarUxDrivingStateGateway
import com.smartjourney.vehicle.core.DrivingStateGateway
import com.smartjourney.vehicle.core.NaviGateway
import com.smartjourney.vehicle.core.NaviGatewayFactory
import com.smartjourney.vehicle.core.Place
import com.smartjourney.vehicle.core.PleosVehicleGateway
import com.smartjourney.vehicle.core.VehicleCaps
import com.smartjourney.vehicle.core.VehicleGateway
import com.smartjourney.vehicle.core.VehicleSnapshot
import com.smartjourney.vehicle.core.setRouteAndShowNavigation
import com.smartjourney.vehicle.data.FakeBackend
import com.smartjourney.vehicle.data.ServerBackend
import com.smartjourney.vehicle.data.SwitchingBackend
import com.smartjourney.vehicle.data.PreferencesJourneyStorage
import com.smartjourney.vehicle.demo.DemoController
import com.smartjourney.vehicle.ui.PopupViewport
import com.smartjourney.vehicle.domain.Store
import com.smartjourney.vehicle.engine.Arrival
import com.smartjourney.vehicle.engine.DEFAULT_WAIT_LIMIT_MIN
import com.smartjourney.vehicle.engine.JourneyEngine
import com.smartjourney.vehicle.engine.JourneySignals
import com.smartjourney.vehicle.service.JourneyService
import com.smartjourney.vehicle.voice.GleoActionHandler
import com.smartjourney.vehicle.voice.LateVoiceResult
import kotlinx.coroutines.channels.Channel
import com.smartjourney.vehicle.voice.VoiceDecision
import com.smartjourney.vehicle.voice.VoiceIssue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

private const val TAG = "SJ"
private const val DEFAULT_LEG_SEC = 25 * 60
private const val MIN_DEMO_LEG_SEC = 5 * 60
private const val REAL_TRIP_PERIOD_MS = 30_000L
private const val CAPS_ATTEMPTS = 3
const val CONSENT_VERSION = "2026-11-11"

/** 보유 기간. 서버의 파기 작업(DataRetentionJob, 30일)과 맞춘다. */
const val RETENTION_DAYS = 30

class SmartJourneyApp : Application() {
    lateinit var graph: AppGraph
        private set

    override fun onCreate() {
        super.onCreate()
        graph = AppGraph(this)
    }
}

val Context.graph: AppGraph get() = (applicationContext as SmartJourneyApp).graph

/** 앱 전체에서 하나씩만 쓰는 객체들. Hilt 없이 손으로 묶는다. */
class AppGraph(private val context: Context) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    val demo = DemoController(scope)
    /** true면 smart-journey-server(웹 POS와 공유)로 접수한다. 디버그 빌드의 데모 패널에서 켠다. */
    val useServer = MutableStateFlow(false)
    val backend = SwitchingBackend(
        fake = FakeBackend(),
        server = BuildConfig.SERVER_URL.takeIf { it.isNotBlank() }?.let { url -> ServerBackend(url) { CONSENT_VERSION.takeIf { consented.value } } },
        useServer = { useServer.value },
    )

    /** 진행 중인 방문이 없을 때만 바꾼다. 방문 도중 바꾸면 접수 결과를 다른 곳에서 찾게 된다. */
    fun setUseServer(on: Boolean): Boolean {
        if (engine.state.value.active || (on && backend.server == null)) return false
        useServer.value = on
        return true
    }
    val storage = PreferencesJourneyStorage(context)
    val navi = NaviGatewayFactory.create(context)
    val signals = AppSignals(context, navi, demo, scope)
    val engine = JourneyEngine(signals, backend, scope, storage = storage, logger = { if (BuildConfig.DEBUG) Log.i(TAG, it) })

    private val prefs = context.getSharedPreferences("smart_journey", Context.MODE_PRIVATE)

    /**
     * 데이터 전송 동의 (정책 4). 동의 전에는 여정을 시작할 수 없다.
     * 고지 내용이 바뀌면 [CONSENT_VERSION]을 올려 다시 동의받는다. 실서버 연동 뒤에는 GET /consent/notice의 version을 쓴다.
     */
    val consented = MutableStateFlow(prefs.getString("consent_version", null) == CONSENT_VERSION)

    /** 대기 허용 시간(분). 정차 중 홈에서 바꾼다 (명세서 v4 6장). */
    val waitLimitMin = MutableStateFlow(prefs.getInt("wait_limit_min", DEFAULT_WAIT_LIMIT_MIN))

    fun setWaitLimit(minutes: Int) {
        val value = minutes.coerceIn(10, 120)
        prefs.edit().putInt("wait_limit_min", value).apply()
        waitLimitMin.value = value
        engine.setWaitLimit(value)
    }

    /** 마지막 음성 실패. 정차 후 예외 화면에서 확인한다. 질문은 실행하지 않았다. */
    val voiceIssue = MutableStateFlow<VoiceIssue?>(null)

    /** 내비에 경로를 넣는 중. 이 동안 서비스가 '여정 없음'으로 보고 스스로 멈추지 않게 한다. */
    val preparingJourney = MutableStateFlow(false)

    /** 내비 경로 입력 실패 등 방문 시작 오류. */
    val startError = MutableStateFlow<String?>(null)

    /** 디버그 음성 검증(SpeechProbeActivity)이 질문을 맡으면 서비스 STT가 마이크를 가로채지 않는다. 릴리스에서는 설정하는 곳이 없다. */
    val voiceProbePromptId = MutableStateFlow<String?>(null)

    /** 앱 TTS 완료 기록. Gleo 승인 인증으로 사용하지 않는다. */
    val spokenPromptId = MutableStateFlow<String?>(null)
    val popupViewport = MutableStateFlow<PopupViewport?>(null)
    val appVisible = MutableStateFlow(false)

    val gleoQuietUntil = MutableStateFlow(0L)
    // Gleo 승인 경로를 닫아 지금은 넣는 곳이 없다. 증거가 있는 Gleo 승인 경로가 생기면 결과 안내에 다시 쓴다.
    val lateVoiceResults = Channel<LateVoiceResult>(Channel.BUFFERED)
    val gleoActions by lazy { GleoActionHandler(engine.state, { consented.value }, { backend.isDemo }) }


    init {
        if (consented.value) {
            val restored = engine.restore()
            if (restored) {
                Log.i(TAG, "이전 여정 복원 완료 (${engine.state.value.stops.size}곳)") // 매장 이름은 로그에 남기지 않는다
                JourneyService.start(context)
            }
        }
    }

    fun setConsent(agreed: Boolean) {
        prefs.edit().putString("consent_version", if (agreed) CONSENT_VERSION else null).apply()
        consented.value = agreed
        if (!agreed) {
            spokenPromptId.value = null
            engine.end()
            storage.clear()
        }
    }

    fun respondByVoice(promptId: String, decision: VoiceDecision): Boolean =
        consented.value && engine.respondByVoice(promptId, decision == VoiceDecision.APPROVE)

    fun respondFromPopup(promptId: String, approved: Boolean, demoSignal: Boolean, restricted: Boolean): Boolean =
        consented.value && android.provider.Settings.canDrawOverlays(context) &&
            com.smartjourney.vehicle.ui.popupTouchAllowed(demoSignal && demo.enabled.value, restricted) &&
            engine.respondByTouch(promptId, approved)

    /** 내비에 경로를 넣고 엔진을 켠다. 실제 신호 모드에서 내비 입력이 실패하면 시작하지 않는다. */
    /**
     * 한 번에 하나만 시작한다. 화면 버튼은 탭 전환 등으로 다시 켜질 수 있으므로 여기서 막는다.
     * 모두 Main 스레드에서 불리므로 확인과 설정 사이에 다른 호출이 끼어들지 않는다.
     */
    suspend fun startJourney(partySize: Int, stops: List<Store>): Result<Unit> {
        if (preparingJourney.value) return Result.failure(IllegalStateException("이미 방문을 시작하고 있어요"))
        preparingJourney.value = true
        return try { startJourneyOnce(partySize, stops) } finally { preparingJourney.value = false }
    }

    private suspend fun startJourneyOnce(partySize: Int, stops: List<Store>): Result<Unit> {
        if (!consented.value) return Result.failure(IllegalStateException("동의가 필요해요"))
        runCatching { engine.validateStart(partySize, stops) }.exceptionOrNull()?.let { return Result.failure(it) }
        // 경로를 넣으면 내비가 앞으로 나오고 앱은 뒤로 간다. 그 전에 포그라운드 서비스를 띄워 둔다.
        if (!JourneyService.start(context)) {
            startError.value = "앱 화면을 연 상태에서 방문을 시작해 주세요"
            return Result.failure(IllegalStateException("포그라운드 서비스를 시작할 수 없어요"))
        }
        val route = signals.setRoute(stops)
        if (route.isFailure && !demo.enabled.value) {
            JourneyService.stop(context)
            startError.value = "내비에 경로를 넣지 못했어요 (${route.exceptionOrNull()?.message})"
            return route
        }
        startError.value = null
        // 서버 연동이면 여정을 먼저 만든다. 실패하면 방문을 시작하지 않는다.
        runCatching { backend.startJourney(partySize, stops.map { it.id }, sensed = false) }.exceptionOrNull()?.let {
            JourneyService.stop(context)
            startError.value = "접수 서버에 연결하지 못했어요 (${it.message})"
            return Result.failure(it)
        }
        if (route.isFailure) Log.w(TAG, "내비 경로 입력 실패, 데모 신호로 계속: ${route.exceptionOrNull()?.message}")
        engine.start(partySize, stops, waitLimitMin.value)
        if (demo.enabled.value) demo.onJourneyStarted()
        return Result.success(Unit)
    }
}

enum class EtaSource { NAVI_LIVE, NAVI_REQUEST, DEMO }

/** 실제 SDK 신호와 데모 신호 중 하나를 골라 엔진에 준다. 데모 패널의 스위치로 바꾼다. */
@OptIn(ExperimentalCoroutinesApi::class)
class AppSignals(
    context: Context,
    private val navi: NaviGateway,
    private val demo: DemoController,
    scope: CoroutineScope,
) : JourneySignals {

    data class Support(val seat: Boolean, val door: Boolean, val drivingState: Boolean)

    private val realVehicle: VehicleGateway? = runCatching { PleosVehicleGateway(context.applicationContext) }
        .onFailure { Log.w(TAG, "Vehicle SDK 연결 실패", it) }.getOrNull()
    private val realDriving: DrivingStateGateway? = runCatching { CarUxDrivingStateGateway(context.applicationContext) }
        .onFailure { Log.w(TAG, "CarUxRestrictions 연결 실패", it) }.getOrNull()

    // Vehicle.initialize()에는 연결 완료 콜백이 없어 첫 조회가 서비스 연결 전에 실패할 수 있다.
    // Error code 가이드(ERROR_SERVICE_CONNECTION_FAILURE·ERROR_INTERNAL_SERVICE → 재시도)에 따라 몇 번 다시 묻는다.
    private val realCaps = scope.async {
        val vehicle = realVehicle ?: return@async null
        var caps: VehicleCaps? = null
        for (attempt in 1..CAPS_ATTEMPTS) {
            caps = withTimeoutOrNull(3_000) { runCatching { vehicle.capabilities() }.getOrNull() }
            if (caps != null && (caps.seatSupported || caps.doorSupported)) break
            if (attempt < CAPS_ATTEMPTS) delay(2_000L * attempt)
        }
        caps
    }

    /** 실제 차량 신호를 쓸 수 있는지. null이면 확인 중 (디자인 가이드 V-00). */
    val support = MutableStateFlow<Support?>(null)

    // CarUx 리스너는 하나뿐이라 한 번만 구독해 나눠 쓴다
    private val realRestricted: Flow<Boolean>? =
        realDriving?.restricted?.shareIn(scope, SharingStarted.WhileSubscribed(), replay = 1)

    init {
        scope.launch {
            val caps = realCaps.await()
            support.value = Support(caps?.seatSupported == true, caps?.doorSupported == true, realDriving != null)
            if (BuildConfig.DEBUG) Log.i(TAG, "실제 신호 지원: ${support.value}")
            // 데모 신호는 실제 차량 값에서 시작한다
            val snapshot = caps?.let { c -> runCatching { realVehicle?.readNow(c) }.getOrNull() } ?: return@launch
            if (BuildConfig.DEBUG) Log.i(TAG, "실제 차량 값: $snapshot, 문 닫힘 값: ${caps.doorClosedValue.values}")
            snapshot.occupied?.let(demo.vehicle::setOccupied)
            snapshot.anyDoorOpen?.let(demo.vehicle::setDoorOpen)
        }
    }

    private fun <T> vehicleSignal(demoFlow: Flow<T>, fallback: T, real: (VehicleGateway, VehicleCaps) -> Flow<T>?): Flow<T> =
        demo.enabled.flatMapLatest { useDemo ->
            if (useDemo) demoFlow
            else flow {
                val caps = realCaps.await()
                val source = if (caps == null || realVehicle == null) null else real(realVehicle, caps)
                if (source == null) emit(fallback) else emitAll(source)
            }
        }

    override val occupied: Flow<Int> =
        vehicleSignal(demo.vehicle.occupied, fallback = 0) { v, c -> if (c.seatSupported) v.occupiedCount(c) else null }

    override val anyDoorOpen: Flow<Boolean> =
        vehicleSignal(demo.vehicle.doorOpen, fallback = false) { v, c -> if (c.doorSupported) v.anyDoorOpen(c) else null }

    // 실제 신호 모드에서 주행 상태를 못 읽으면 주행 중으로 본다 (정책 3)
    override val restricted: Flow<Boolean> =
        demo.enabled.flatMapLatest { useDemo -> if (useDemo) demo.restricted else realRestricted ?: flowOf(true) }

    override val arrivals: Flow<Arrival> = merge(
        demo.arrivals.map { Arrival.Current },
        navi.arrivals.map {
            when (it) {
                is ArrivalEvent.Waypoint -> Arrival.Waypoint(it.order)
                is ArrivalEvent.Destination -> Arrival.Destination
            }
        },
    )

    override val routeChanged: Flow<Unit> = navi.routeChanged
    override val routeCancelled: Flow<Unit> = navi.routeCancelled

    /** 남은 시간을 어디서 받았는지. 화면의 실제 SDK/데모 입력 배지에 쓴다. */
    val etaSource = MutableStateFlow<EtaSource?>(null)

    override fun remainingSec(target: Store): Flow<Int> =
        combine(demo.enabled, demo.realEta) { useDemo, realEta -> useDemo && !realEta }.flatMapLatest { demoEta ->
            flow {
                val place = target.toPlace()
                // 내비가 주는 실제 남은 시간. 내비 연동이 없는 기기에서는 null.
                val first = withTimeoutOrNull(4_000) { runCatching { navi.requestTrip(place)?.eta }.getOrNull() }
                if (demoEta) {
                    // 에뮬레이터는 차가 움직이지 않으므로, 실제 값에서 시작해 데모 조작기가 줄여 나간다
                    etaSource.value = EtaSource.DEMO
                    // 목적지가 바로 앞이면(에뮬레이터 기본 위치) 시연이 안 되므로 기본 구간으로 시작한다
                    demo.startLeg(first?.takeIf { it >= MIN_DEMO_LEG_SEC } ?: DEFAULT_LEG_SEC)
                    emitAll(demo.remainingSec.filterNotNull())
                } else {
                    etaSource.value = if (first != null) EtaSource.NAVI_REQUEST else null
                    first?.let { emit(it) }
                    // 안내 중 내비가 보내는 onTripInfo를 구독해 제안 시점을 다시 계산한다.
                    // 콜백이 오지 않는 내비를 대비해 requestTrip 조회를 느린 주기로 함께 돌린다.
                    emitAll(
                        merge(
                            navi.trip.drop(1).map { trip ->
                                if (!trip.solicited) etaSource.value = EtaSource.NAVI_LIVE
                                else if (etaSource.value == null) etaSource.value = EtaSource.NAVI_REQUEST
                                trip.eta
                            },
                            flow {
                                while (true) {
                                    delay(REAL_TRIP_PERIOD_MS)
                                    runCatching { navi.requestTrip(place) } // 결과는 navi.trip으로 온다
                                }
                            },
                        ),
                    )
                }
            }.distinctUntilChanged()
        }

    override suspend fun sensorsSupported(): Boolean =
        demo.enabled.value || realCaps.await()?.let { it.seatSupported && it.doorSupported } == true

    override suspend fun readNow(): VehicleSnapshot {
        if (demo.enabled.value) return demo.vehicle.readNow(demo.vehicle.capabilities())
        val caps = realCaps.await()
        return if (caps == null || realVehicle == null) VehicleSnapshot(null, null, null) else realVehicle.readNow(caps)
    }

    /** 마지막 매장이 목적지, 그 앞 매장들이 경유지(최대 2개)다. */
    suspend fun setRoute(stops: List<Store>): Result<Unit> {
        val places = stops.map { it.toPlace() }
        return navi.setRouteAndShowNavigation(places.last(), places.dropLast(1)) { error ->
            Log.w(TAG, "경로는 설정됐지만 내비 화면 전환 요청에 실패했어요", error)
        }
    }

    private fun Store.toPlace() = Place(latitude, longitude, name, poiId, poiSubId, address)
}
