package com.smartjourney.vehicle.core

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FakeVehicleGatewayTest {

    @Test
    fun readNow는_설정한_값을_그대로_돌려준다() = runTest {
        val gateway = FakeVehicleGateway(occupied = 3, buckled = 2, doorOpen = true)
        val snapshot = gateway.readNow(gateway.capabilities())
        assertEquals(VehicleSnapshot(occupied = 3, buckled = 2, anyDoorOpen = true), snapshot)
    }

    @Test
    fun 값을_바꾸면_Flow에_반영된다() = runTest {
        val gateway = FakeVehicleGateway()
        val caps = gateway.capabilities()
        gateway.setOccupied(4)
        gateway.setDoorOpen(true)
        assertEquals(4, gateway.occupiedCount(caps).first())
        assertTrue(gateway.anyDoorOpen(caps).first())
    }
}
