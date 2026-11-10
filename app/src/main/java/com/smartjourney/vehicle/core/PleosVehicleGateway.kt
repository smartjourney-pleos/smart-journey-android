package com.smartjourney.vehicle.core

// Pleos Vehicle SDK 2.3.2 래퍼 (스마트 저니 core-vehicle 모듈)
// - SDK의 콜백(성공/실패) 방식을 코루틴·Flow로 감싼다.
// - 화면·도메인은 이 인터페이스만 쓰고 SDK를 직접 부르지 않는다. (테스트·시연용 Fake로 교체 가능)
// - 시그니처는 Downloads/Vehicle-2/classes.jar(2.3.2)를 javap로 확인한 기준이다.

import android.content.Context
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

data class VehicleCaps(
    val seatAreas: List<SeatArea>,          // 점유 읽기 가능한 좌석
    val beltAreas: List<SeatArea>,          // 안전벨트 읽기 가능한 좌석 (없을 수 있음)
    val doorClosedValue: Map<DoorArea, Int> // 승객 문별 "닫힘" 값 (AreaBounds 최소값)
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
        val seats = runCatching {
            suspendCancellableCoroutine<List<SeatArea>> { c ->
                seat.checkSeatOccupancyCapabilities(
                    { config, areas -> c.resume(if (config.accessType.canRead()) areas else emptyList()) },
                    { _ -> c.resume(emptyList()) }
                )
            }
        }.getOrDefault(emptyList())

        val belts = runCatching {
            suspendCancellableCoroutine<List<SeatArea>> { c ->
                seat.checkSeatBeltBuckledCapabilities(
                    { config, areas -> c.resume(if (config.accessType.canRead()) areas else emptyList()) },
                    { _ -> c.resume(emptyList()) }
                )
            }
        }.getOrDefault(emptyList())

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
                    { _ -> c.resume(emptyMap()) }
                )
            }
        }.getOrDefault(emptyMap())

        return VehicleCaps(seats, belts, doors)
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
            override fun onFailed(e: Exception) { /* 값 유지, 상위에서 타임아웃 처리 */ }
        }
        seat.getCurrentSeatOccupancy(area, { trySend(it == true) }, { /* 초기값 실패는 리스너 값으로 대체 */ })
        seat.registerSeatOccupancy(area, l)
        awaitClose { seat.unregisterSeatOccupancy(area, l) }
    }

    private fun seatBuckled(area: SeatArea): Flow<Boolean> = callbackFlow {
        val l = object : SeatBeltBuckledStateListener {
            override fun onSeatBeltBuckledStateUpdated(value: Boolean?) { trySend(value == true) }
            override fun onFailed(e: Exception) { }
        }
        seat.getCurrentSeatBeltBuckledState(area, { trySend(it == true) }, { })
        seat.registerSeatBeltBuckledState(area, l)
        awaitClose { seat.unregisterSeatBeltBuckledState(area, l) }
    }

    private fun doorOpen(area: DoorArea, closedValue: Int): Flow<Boolean> = callbackFlow {
        val l = object : DoorPositionListener {
            override fun onPositionUpdated(position: Int?) { trySend((position ?: closedValue) > closedValue) }
            override fun onFailed(e: Exception) { }
        }
        door.getCurrentDoorPosition(area, { pos -> trySend((pos ?: closedValue) > closedValue) }, { })
        door.registerDoorPosition(area, l)
        awaitClose { door.unregisterDoorPosition(area, l) }
    }

    // ---------- 확정 직전 재확인용 즉시 읽기 ----------

    override suspend fun readNow(caps: VehicleCaps): VehicleSnapshot {
        val occ = caps.seatAreas.map { readBool { ok, err -> seat.getCurrentSeatOccupancy(it, ok, err) } }
        val blt = caps.beltAreas.map { readBool { ok, err -> seat.getCurrentSeatBeltBuckledState(it, ok, err) } }
        val doors = caps.doorClosedValue.map { (area, closed) ->
            readInt { ok, err -> door.getCurrentDoorPosition(area, ok, err) }?.let { it > closed }
        }
        return VehicleSnapshot(
            occupied = if (occ.any { it == null }) null else occ.count { it == true },
            buckled = if (blt.isEmpty() || blt.any { it == null }) null else blt.count { it == true },
            anyDoorOpen = if (doors.isEmpty() || doors.any { it == null }) null else doors.any { it == true },
        )
    }

    override fun release() = vehicle.release()

    // ---------- helpers ----------

    private suspend fun readBool(call: ((Boolean?) -> Unit, (Exception) -> Unit) -> Unit): Boolean? =
        suspendCancellableCoroutine { c -> call({ c.resume(it) }, { c.resume(null) }) }

    private suspend fun readInt(call: ((Int?) -> Unit, (Exception) -> Unit) -> Unit): Int? =
        suspendCancellableCoroutine { c -> call({ c.resume(it) }, { c.resume(null) }) }

    private fun List<Flow<Boolean>>.sumTrue(): Flow<Int> =
        if (isEmpty()) kotlinx.coroutines.flow.flowOf(0)
        else combine(this) { arr -> arr.count { it } }.distinctUntilChanged()

    private fun AccessType.canRead() = this == AccessType.READ || this == AccessType.READ_WRITE
}

// 참고: SDK 콜백 값은 java.lang.Boolean/Integer(박싱)라 null 가능으로 다룬다.
// 참고: SDK 리스너는 VehicleListener(onFailed)를 상속해서 메서드가 2개라 람다로 못 쓴다. object 식으로 구현.
// AreaBounds<Int>는 minValue / maxValue 프로퍼티를 가진다 (2.3.2 jar 확인).
