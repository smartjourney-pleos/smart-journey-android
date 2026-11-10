package com.smartjourney.vehicle.core

import ai.pleos.playground.vehicle.api.model.area.DoorArea
import ai.pleos.playground.vehicle.api.model.area.SeatArea
import ai.pleos.playground.vehicle.constant.area.DoorValue
import ai.pleos.playground.vehicle.constant.area.SeatValue
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * 에뮬레이터 신호가 불안정하거나 Pleos 서비스가 없는 환경(시연·테스트)용 가짜 구현.
 * 좌석 점유 수, 안전벨트 수, 도어 열림 상태를 직접 바꿀 수 있다.
 */
class FakeVehicleGateway(
    occupied: Int = 0,
    buckled: Int = 0,
    doorOpen: Boolean = false,
) : VehicleGateway {

    private val occupiedState = MutableStateFlow(occupied)
    private val buckledState = MutableStateFlow(buckled)
    private val doorOpenState = MutableStateFlow(doorOpen)

    fun setOccupied(count: Int) { occupiedState.value = count }
    fun setBuckled(count: Int) { buckledState.value = count }
    fun setDoorOpen(open: Boolean) { doorOpenState.value = open }

    override suspend fun capabilities(): VehicleCaps = VehicleCaps(
        seatAreas = listOf(SeatValue.ROW_1_LEFT, SeatValue.ROW_1_RIGHT, SeatValue.ROW_2_LEFT, SeatValue.ROW_2_RIGHT)
            .map { SeatArea(it) },
        beltAreas = listOf(SeatValue.ROW_1_LEFT, SeatValue.ROW_1_RIGHT, SeatValue.ROW_2_LEFT, SeatValue.ROW_2_RIGHT)
            .map { SeatArea(it) },
        doorClosedValue = listOf(DoorValue.ROW_1_LEFT, DoorValue.ROW_1_RIGHT, DoorValue.ROW_2_LEFT, DoorValue.ROW_2_RIGHT)
            .associate { DoorArea(it) to 0 },
    )

    override fun occupiedCount(caps: VehicleCaps): Flow<Int> = occupiedState
    override fun buckledCount(caps: VehicleCaps): Flow<Int> = buckledState
    override fun anyDoorOpen(caps: VehicleCaps): Flow<Boolean> = doorOpenState

    override suspend fun readNow(caps: VehicleCaps): VehicleSnapshot =
        VehicleSnapshot(occupiedState.value, buckledState.value, doorOpenState.value)

    override fun release() = Unit
}
