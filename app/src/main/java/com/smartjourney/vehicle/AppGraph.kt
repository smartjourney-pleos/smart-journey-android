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
import com.smartjourney.vehicle.data.PreferencesJourneyStorage
import com.smartjourney.vehicle.demo.DemoController
import com.smartjourney.vehicle.domain.Store
import com.smartjourney.vehicle.engine.Arrival
import com.smartjourney.vehicle.engine.JourneyEngine
import com.smartjourney.vehicle.engine.JourneySignals
import com.smartjourney.vehicle.service.JourneyService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
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
    val backend = FakeBackend()
    val storage = PreferencesJourneyStorage(context)
    val signals = AppSignals(context, NaviGatewayFactory.create(context), demo, scope)
    val engine = JourneyEngine(signals, backend, scope, storage = storage, logger = { if (BuildConfig.DEBUG) Log.i(TAG, it) })

    private val prefs = context.getSharedPreferences("smart_journey", Context.MODE_PRIVATE)

    /**
     * 데이터 전송 동의 (정책 4). 동의 전에는 여정을 시작할 수 없다.
     * 고지 내용이 바뀌면 [CONSENT_VERSION]을 올려 다시 동의받는다. 실서버 연동 뒤에는 GET /consent/notice의 version을 쓴다.
     */
    val consented = MutableStateFlow(prefs.getString("consent_version", null) == CONSENT_VERSION)

    init {
        if (consented.value) {
            val restored = engine.restore()
            if (restored) {
                Log.i(TAG, "이전 여정 복원 완료 (${engine.state.value.current?.store?.name})")
                JourneyService.start(context)
            }
        }
    }

    fun setConsent(agreed: Boolean) {
        prefs.edit().putString("consent_version", if (agreed) CONSENT_VERSION else null).apply()
        consented.value = agreed
        if (!agreed) {
            engine.end(revokedConsent = true)
            storage.clear()
        }
    }

    /** 내비에 경로를 넣고 엔진을 켠다. 실제 신호 모드에서 내비 입력이 실패하면 시작하지 않는다. */
    suspend fun startJourney(partySize: Int, stops: List<Store>): Result<Unit> {
        if (!consented.value) return Result.failure(IllegalStateException("동의가 필요해요"))
        // 경로를 넣으면 내비가 앞으로 나오고 앱은 뒤로 간다. 그 전에 포그라운드 서비스를 띄워 둔다.
        JourneyService.start(context)
        val route = signals.setRoute(stops)
        if (route.isFailure && !demo.enabled.value) {
            JourneyService.stop(context)
            return route
        }
        if (route.isFailure) Log.w(TAG, "내비 경로 입력 실패, 데모 신호로 계속: ${route.exceptionOrNull()?.message}")
        engine.start(partySize, stops)
        if (demo.enabled.value) demo.onJourneyStarted()
        return Result.success(Unit)
    }
}

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

    override fun remainingSec(target: Store): Flow<Int> = demo.enabled.flatMapLatest { useDemo ->
        flow {
            val place = target.toPlace()
            // 내비가 주는 실제 남은 시간. 내비 연동이 없는 기기에서는 null.
            val first = withTimeoutOrNull(4_000) { runCatching { navi.requestTrip(place)?.eta }.getOrNull() }
            if (useDemo) {
                // 에뮬레이터는 차가 움직이지 않으므로, 실제 값에서 시작해 데모 조작기가 줄여 나간다
                demo.startLeg(first ?: DEFAULT_LEG_SEC)
                emitAll(demo.remainingSec.filterNotNull())
            } else {
                first?.let { emit(it) }
                while (true) {
                    delay(REAL_TRIP_PERIOD_MS)
                    runCatching { navi.requestTrip(place)?.eta }.getOrNull()?.let { emit(it) }
                }
            }
        }
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
