package com.smartjourney.vehicle.core

// Pleos Vehicle SDK 2.3.2 래퍼 (스마트 저니 core-vehicle 모듈)
// - SDK의 콜백(성공/실패) 방식을 코루틴·Flow로 감싼다.
// - 화면·도메인은 이 인터페이스만 쓰고 SDK를 직접 부르지 않는다. (테스트·시연용 Fake로 교체 가능)
// - 시그니처는 Downloads/Vehicle-2/classes.jar(2.3.2)를 javap로 확인한 기준이다.

import android.content.Context
import android.util.Log
import ai.pleos.playground.vehicle.Vehicle
import ai.pleos.playground.vehicle.api.model.area.DoorArea
import ai.pleos.playground.vehicle.api.model.area.SeatArea
import ai.pleos.playground.vehicle.api.model.capability.AccessType
import ai.pleos.playground.vehicle.constant.area.DoorValue
import ai.pleos.playground.vehicle.listener.DoorPositionListener
import ai.pleos.playground.vehicle.listener.SeatBeltBuckledStateListener
import ai.pleos.playground.vehicle.listener.SeatOccupancyListener
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * Pleos Vehicle SDK 2.3.2 (`vehicle-data-1.1.11` AAR javap 확인 기준) 오류 코드 정의.
 * `vehicle-data`는 runtime 전이 의존성이라 컴파일 클래스패스에 노출되지 않으므로 앱 내부 enum으로 매핑한다.
 */
enum class VehicleErrorCode(val id: Int) {
    ERROR_INTERNAL_SERVICE(30001),
    ERROR_SERVICE_CONNECTION_FAILURE(40001),
    ERROR_DRIVING_RESTRICTED(50001),
    ERROR_NOT_SUPPORTED_PROPERTY(60001),
    ERROR_NOT_SUPPORTED_VALUE_TYPE(60002),
    ERROR_NOT_SUPPORTED_AREA(60003),
    ERROR_EMPTY_RESULT(70001),
    ERROR_SECURITY_PERMISSION(800001),
    ERROR_UNDEFINED(99999),
}

/** Vehicle SDK 오류 원인 분류. 연결·내부 서비스 일시 오류만 재시도 대상으로 본다. */
enum class VehicleErrorCategory {
    CONNECTION_OR_SERVICE,
    PERMISSION,
    DRIVING_RESTRICTED,
    UNSUPPORTED,
    EMPTY_RESULT,
    UNKNOWN,
}

data class VehicleErrorInfo(
    val code: VehicleErrorCode?,
    val category: VehicleErrorCategory,
    val retryable: Boolean,
)

/**
 * Vehicle SDK 2.3.2의 `onFailed(Exception)`을 표준 [VehicleErrorCode] 기준으로 분류한다.
 * 2.3.2 AAR에서는 내부 예외 클래스가 난독화(`callback.exception.a`)되어 있으므로 비공개 타입 캐스팅 대신
 * `SecurityException`/`UnsupportedOperationException`과 예외 메시지의 코드 이름·ID로 판별한다.
 */
fun classifyVehicleError(error: Exception): VehicleErrorInfo {
    if (error is SecurityException) {
        return VehicleErrorInfo(VehicleErrorCode.ERROR_SECURITY_PERMISSION, VehicleErrorCategory.PERMISSION, retryable = false)
    }
    if (error is UnsupportedOperationException) {
        return VehicleErrorInfo(VehicleErrorCode.ERROR_NOT_SUPPORTED_PROPERTY, VehicleErrorCategory.UNSUPPORTED, retryable = false)
    }
    val parsed = parseVehicleErrorCode(error.message)
    return when (parsed) {
        VehicleErrorCode.ERROR_SERVICE_CONNECTION_FAILURE,
        VehicleErrorCode.ERROR_INTERNAL_SERVICE ->
            VehicleErrorInfo(parsed, VehicleErrorCategory.CONNECTION_OR_SERVICE, retryable = true)
        VehicleErrorCode.ERROR_SECURITY_PERMISSION ->
            VehicleErrorInfo(parsed, VehicleErrorCategory.PERMISSION, retryable = false)
        VehicleErrorCode.ERROR_DRIVING_RESTRICTED ->
            VehicleErrorInfo(parsed, VehicleErrorCategory.DRIVING_RESTRICTED, retryable = false)
        VehicleErrorCode.ERROR_NOT_SUPPORTED_PROPERTY,
        VehicleErrorCode.ERROR_NOT_SUPPORTED_VALUE_TYPE,
        VehicleErrorCode.ERROR_NOT_SUPPORTED_AREA ->
            VehicleErrorInfo(parsed, VehicleErrorCategory.UNSUPPORTED, retryable = false)
        VehicleErrorCode.ERROR_EMPTY_RESULT ->
            VehicleErrorInfo(parsed, VehicleErrorCategory.EMPTY_RESULT, retryable = false)
        VehicleErrorCode.ERROR_UNDEFINED,
        null ->
            VehicleErrorInfo(parsed, VehicleErrorCategory.UNKNOWN, retryable = false)
    }
}

internal fun parseVehicleErrorCode(message: String?): VehicleErrorCode? {
    if (message.isNullOrBlank()) return null
    val trimmed = message.trim()
    // 난독화된 SDK 예외의 getMessage()는 ErrorCode.name 또는 "Error code:<NAME>"을 반환한다.
    VehicleErrorCode.entries.firstOrNull { trimmed.contains(it.name, ignoreCase = false) }?.let { return it }
    val numeric = Regex("""\b(\d{5,6})\b""").find(trimmed)?.groupValues?.get(1)?.toIntOrNull()
    if (numeric != null) {
        VehicleErrorCode.entries.firstOrNull { it.id == numeric }?.let { return it }
    }
    return null
}

data class VehicleCaps(
    val seatAreas: List<SeatArea>,          // 점유 읽기 가능한 좌석
    val beltAreas: List<SeatArea>,          // 안전벨트 읽기 가능한 좌석 (없을 수 있음)
    val doorClosedValue: Map<DoorArea, Int>, // 승객 문별 "닫힘" 값 (AreaBounds 최소값)
    val retryableFailure: Boolean = false,  // 서비스 연결·내부 일시 오류로 재시도 가치가 있는지
    val lastError: VehicleErrorCategory? = null,
) {
    val seatSupported get() = seatAreas.isNotEmpty()
    val doorSupported get() = doorClosedValue.isNotEmpty()
}

data class VehicleSnapshot(val occupied: Int?, val buckled: Int?, val anyDoorOpen: Boolean?)

interface VehicleGateway {
    suspend fun capabilities(): VehicleCaps
    fun occupiedCount(caps: VehicleCaps): Flow<Int>
    fun buckledCount(caps: VehicleCaps): Flow<Int>
    fun anyDoorOpen(caps: VehicleCaps): Flow<Boolean>
    suspend fun readNow(caps: VehicleCaps): VehicleSnapshot
    fun release()
}

private const val TAG = "PleosVehicle"

class PleosVehicleGateway(context: Context) : VehicleGateway {

    private val vehicle = Vehicle(context).apply { initialize() }
    private val seat get() = vehicle.getSeat()
    private val door get() = vehicle.getDoor()

    // 하차 판단에는 승객 문만 쓴다 (HOOD=보닛, REAR=트렁크 제외)
    private val passengerDoors = setOf(
        DoorValue.ROW_1_LEFT, DoorValue.ROW_1_RIGHT,
        DoorValue.ROW_2_LEFT, DoorValue.ROW_2_RIGHT,
        DoorValue.ROW_3_LEFT, DoorValue.ROW_3_RIGHT,
    )

    // ---------- 지원 여부 (CapabilityConfig를 주는 새 오버로드만 사용, 옛 오버로드는 deprecated) ----------

    override suspend fun capabilities(): VehicleCaps {
        var retryableFailure = false
        var lastError: VehicleErrorCategory? = null
        fun recordFailure(where: String, e: Exception) {
            val info = logFailure(where, e)
            if (info.retryable) retryableFailure = true
            lastError = info.category
        }

        val seats = runCatching {
            suspendCancellableCoroutine<List<SeatArea>> { c ->
                seat.checkSeatOccupancyCapabilities(
                    { config, areas -> c.resume(if (config.accessType.canRead()) areas else emptyList()) },
                    { e -> recordFailure("좌석 점유 지원 조회", e); c.resume(emptyList()) }
                )
            }
        }.onFailure { if (it is Exception) recordFailure("좌석 점유 지원 조회", it) }.getOrDefault(emptyList())

        val belts = runCatching {
            suspendCancellableCoroutine<List<SeatArea>> { c ->
                seat.checkSeatBeltBuckledCapabilities(
                    { config, areas -> c.resume(if (config.accessType.canRead()) areas else emptyList()) },
                    { e -> recordFailure("벨트 지원 조회", e); c.resume(emptyList()) }
                )
            }
        }.onFailure { if (it is Exception) recordFailure("벨트 지원 조회", it) }.getOrDefault(emptyList())

        val doors = runCatching {
            suspendCancellableCoroutine<Map<DoorArea, Int>> { c ->
                door.checkDoorPositionCapabilities(
                    { config, bounds ->
                        if (!config.accessType.canRead()) c.resume(emptyMap())
                        else c.resume(
                            bounds.filterKeys { area -> area.values.all { it in passengerDoors } }
                                .mapValues { (_, b) -> b.minValue }
                        )
                    },
                    { e -> recordFailure("도어 지원 조회", e); c.resume(emptyMap()) }
                )
            }
        }.onFailure { if (it is Exception) recordFailure("도어 지원 조회", it) }.getOrDefault(emptyMap())

        return VehicleCaps(
            seatAreas = seats,
            beltAreas = belts,
            doorClosedValue = doors,
            retryableFailure = retryableFailure,
            lastError = lastError,
        )
    }

    // ---------- 구독 (영역별 Boolean/Int → 합산) ----------

    override fun occupiedCount(caps: VehicleCaps): Flow<Int> =
        caps.seatAreas.map { area -> seatOccupied(area) }.sumTrue()

    override fun buckledCount(caps: VehicleCaps): Flow<Int> =
        caps.beltAreas.map { area -> seatBuckled(area) }.sumTrue()

    override fun anyDoorOpen(caps: VehicleCaps): Flow<Boolean> =
        combine(caps.doorClosedValue.map { (area, closed) -> doorOpen(area, closed) }) { it.any { open -> open } }
            .distinctUntilChanged()

    private fun seatOccupied(area: SeatArea): Flow<Boolean> = callbackFlow {
        val l = object : SeatOccupancyListener {
            override fun onSeatOccupancyUpdated(value: Boolean?) { trySend(value == true) }
            override fun onFailed(e: Exception) { logFailure("좌석 점유 구독", e) } // 값은 유지한다
        }
        seat.getCurrentSeatOccupancy(area, { trySend(it == true) }, { logFailure("좌석 점유 초기 조회", it) })
        seat.registerSeatOccupancy(area, l)
        awaitClose { seat.unregisterSeatOccupancy(area, l) }
    }

    private fun seatBuckled(area: SeatArea): Flow<Boolean> = callbackFlow {
        val l = object : SeatBeltBuckledStateListener {
            override fun onSeatBeltBuckledStateUpdated(value: Boolean?) { trySend(value == true) }
            override fun onFailed(e: Exception) { logFailure("벨트 구독", e) }
        }
        seat.getCurrentSeatBeltBuckledState(area, { trySend(it == true) }, { logFailure("벨트 초기 조회", it) })
        seat.registerSeatBeltBuckledState(area, l)
        awaitClose { seat.unregisterSeatBeltBuckledState(area, l) }
    }

    private fun doorOpen(area: DoorArea, closedValue: Int): Flow<Boolean> = callbackFlow {
        val l = object : DoorPositionListener {
            override fun onPositionUpdated(position: Int?) { trySend((position ?: closedValue) > closedValue) }
            override fun onFailed(e: Exception) { logFailure("도어 구독", e) }
        }
        door.getCurrentDoorPosition(area, { pos -> trySend((pos ?: closedValue) > closedValue) }, { logFailure("도어 초기 조회", it) })
        door.registerDoorPosition(area, l)
        awaitClose { door.unregisterDoorPosition(area, l) }
    }

    // ---------- 확정 직전 재확인용 즉시 읽기 ----------

    override suspend fun readNow(caps: VehicleCaps): VehicleSnapshot {
        val occ = caps.seatAreas.map { readBool("좌석 점유 즉시 조회") { ok, err -> seat.getCurrentSeatOccupancy(it, ok, err) } }
        val blt = caps.beltAreas.map { readBool("벨트 즉시 조회") { ok, err -> seat.getCurrentSeatBeltBuckledState(it, ok, err) } }
        val doors = caps.doorClosedValue.map { (area, closed) ->
            readInt("도어 즉시 조회") { ok, err -> door.getCurrentDoorPosition(area, ok, err) }?.let { it > closed }
        }
        return VehicleSnapshot(
            occupied = if (occ.any { it == null }) null else occ.count { it == true },
            buckled = if (blt.isEmpty() || blt.any { it == null }) null else blt.count { it == true },
            anyDoorOpen = if (doors.isEmpty() || doors.any { it == null }) null else doors.any { it == true },
        )
    }

    override fun release() = vehicle.release()

    // ---------- helpers ----------

    private suspend fun readBool(where: String, call: ((Boolean?) -> Unit, (Exception) -> Unit) -> Unit): Boolean? =
        suspendCancellableCoroutine { c ->
            call({ c.resume(it) }, { e -> logFailure(where, e); c.resume(null) })
        }

    private suspend fun readInt(where: String, call: ((Int?) -> Unit, (Exception) -> Unit) -> Unit): Int? =
        suspendCancellableCoroutine { c ->
            call({ c.resume(it) }, { e -> logFailure(where, e); c.resume(null) })
        }

    private fun List<Flow<Boolean>>.sumTrue(): Flow<Int> =
        if (isEmpty()) kotlinx.coroutines.flow.flowOf(0)
        else combine(this) { arr -> arr.count { it } }.distinctUntilChanged()

    private fun AccessType.canRead() = this == AccessType.READ || this == AccessType.READ_WRITE

    // Error code 가이드: 예외의 ErrorCode로 원인(ERROR_DRIVING_RESTRICTED, ERROR_SECURITY_PERMISSION,
    // ERROR_SERVICE_CONNECTION_FAILURE …)을 분류한다. 신호를 못 읽은 영역은 상위에서 수동 모드로 넘어간다.
    private fun logFailure(where: String, e: Exception): VehicleErrorInfo {
        val info = classifyVehicleError(e)
        val detail = info.code?.name ?: e::class.java.simpleName
        Log.w(TAG, "$where 실패 [${info.category}/$detail, retryable=${info.retryable}]")
        return info
    }
}

// 참고: SDK 콜백 값은 java.lang.Boolean/Integer(박싱)라 null 가능으로 다룬다.
// 참고: SDK 리스너는 VehicleListener(onFailed)를 상속해서 메서드가 2개라 람다로 못 쓴다. object 식으로 구현.
// AreaBounds<Int>는 minValue / maxValue 프로퍼티를 가진다 (2.3.2 jar 확인).
