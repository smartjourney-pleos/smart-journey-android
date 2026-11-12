package com.smartjourney.vehicle.core

import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NaviDisplayHandoffTest {
    private val destination = Place(37.41309722, 127.09383464, "42dot")

    @Test
    fun displayFailureDoesNotInvalidateAnAcceptedRoute() = runTest {
        val gateway = FakeNaviGateway()
        val failures = mutableListOf<Throwable>()
        val result = gateway.setRouteAndShowNavigation(destination, onDisplayFailure = failures::add)
        assertTrue(result.isSuccess)
        assertEquals(destination, gateway.lastRoute?.first)
        assertEquals(1, failures.size)
    }

    @Test
    fun failedRouteDoesNotRequestForeground() = runTest {
        val route = FakeNaviGateway().apply { routeResult = Result.failure(NaviException("경로 실패")) }
        var requested = false
        val gateway = object : NaviGateway by route {
            override suspend fun showNavigation(): Result<Unit> {
                requested = true
                return Result.success(Unit)
            }
        }
        assertTrue(gateway.setRouteAndShowNavigation(destination).isFailure)
        assertFalse(requested)
    }

    @Test
    fun displayTimeoutStillPreservesRouteSuccess() = runTest {
        val route = FakeNaviGateway()
        val gateway = object : NaviGateway by route {
            override suspend fun showNavigation(): Result<Unit> {
                delay(6_000)
                return Result.success(Unit)
            }
        }
        val failures = mutableListOf<Throwable>()
        assertTrue(gateway.setRouteAndShowNavigation(destination, onDisplayFailure = failures::add).isSuccess)
        assertEquals(1, failures.size)
    }
}
