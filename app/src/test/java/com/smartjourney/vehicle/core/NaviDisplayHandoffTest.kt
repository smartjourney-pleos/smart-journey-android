package com.smartjourney.vehicle.core

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class NaviDisplayHandoffTest {
    private val destination = Place(37.41309722, 127.09383464, "42dot")

    @Test fun coldNavigationIsOpenedAndReadyBeforeOneRouteRequest() = runTest {
        val ready = CompletableDeferred<Unit>()
        val calls = mutableListOf<String>()
        val route = FakeNaviGateway()
        val gateway = object : NaviGateway by route {
            override suspend fun showNavigation(): Result<Unit> {
                calls += "display"
                return Result.success(Unit)
            }
            override suspend fun awaitReady(dest: Place): Result<Unit> {
                calls += "ready"
                ready.await()
                return Result.success(Unit)
            }
            override suspend fun setRoute(dest: Place, waypoints: List<Place>): Result<Unit> {
                calls += "route"
                return route.setRoute(dest, waypoints)
            }
        }
        val result = async { gateway.setRouteAndShowNavigation(destination) }
        runCurrent()
        assertEquals(listOf("display", "ready"), calls)
        assertNull(route.lastRoute)
        ready.complete(Unit)
        assertTrue(result.await().isSuccess)
        assertEquals(listOf("display", "ready", "route"), calls)
        assertEquals(destination, route.lastRoute?.first)
    }

    @Test fun displayFailureDoesNotSendARoute() = runTest {
        val route = FakeNaviGateway()
        assertTrue(route.setRouteAndShowNavigation(destination).isFailure)
        assertNull(route.lastRoute)
    }

    @Test fun navigationReadinessTimeoutDoesNotSendARoute() = runTest {
        val route = FakeNaviGateway()
        val gateway = object : NaviGateway by route {
            override suspend fun showNavigation() = Result.success(Unit)
            override suspend fun awaitReady(dest: Place): Result<Unit> {
                delay(60_000)
                return Result.success(Unit)
            }
        }
        assertTrue(gateway.setRouteAndShowNavigation(destination).isFailure)
        assertEquals(15_000L, testScheduler.currentTime)
        assertNull(route.lastRoute)
    }

    @Test fun displayTimeoutDoesNotSendARoute() = runTest {
        val route = FakeNaviGateway()
        val gateway = object : NaviGateway by route {
            override suspend fun showNavigation(): Result<Unit> {
                delay(60_000)
                return Result.success(Unit)
            }
        }
        assertTrue(gateway.setRouteAndShowNavigation(destination).isFailure)
        assertEquals(5_000L, testScheduler.currentTime)
        assertNull(route.lastRoute)
    }

    @Test fun failedRouteIsNotRetriedOrReportedAsSuccess() = runTest {
        var requests = 0
        val route = FakeNaviGateway()
        val gateway = object : NaviGateway by route {
            override suspend fun showNavigation() = Result.success(Unit)
            override suspend fun setRoute(dest: Place, waypoints: List<Place>): Result<Unit> {
                requests++
                return Result.failure(NaviException("경로 실패"))
            }
        }
        assertTrue(gateway.setRouteAndShowNavigation(destination).isFailure)
        assertEquals(1, requests)
    }

    @Test fun cancellationDuringPreparationDoesNotSendARoute() = runTest {
        val route = FakeNaviGateway()
        val gateway = object : NaviGateway by route {
            override suspend fun showNavigation() = Result.success(Unit)
            override suspend fun awaitReady(dest: Place): Result<Unit> {
                delay(60_000)
                return Result.success(Unit)
            }
        }
        val result = async { gateway.setRouteAndShowNavigation(destination) }
        runCurrent()
        result.cancelAndJoin()
        assertTrue(result.isCancelled)
        assertNull(route.lastRoute)
    }
}
