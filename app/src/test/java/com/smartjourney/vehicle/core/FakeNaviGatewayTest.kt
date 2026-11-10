package com.smartjourney.vehicle.core

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FakeNaviGatewayTest {

    private val store = Place(37.5, 127.0, "매장")
    private val stops = List(3) { Place(37.0 + it, 127.0, "경유지$it") }

    @Test
    fun setRoute는_목적지와_경유지를_기록한다() = runTest {
        val gateway = FakeNaviGateway()
        assertTrue(gateway.setRoute(store, stops.take(2)).isSuccess)
        assertEquals(store to stops.take(2), gateway.lastRoute)
    }

    @Test
    fun 경유지가_3개면_실패한다() = runTest {
        assertTrue(FakeNaviGateway().setRoute(store, stops).isFailure)
    }

    @Test
    fun trip은_마지막_값을_기억한다() = runTest {
        val gateway = FakeNaviGateway()
        gateway.emitTrip(eta = 540, distance = 3200)
        assertEquals(NaviTrip(540, 3200), gateway.trip.first())
    }
}
