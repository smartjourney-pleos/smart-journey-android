package com.smartjourney.vehicle.core

import org.junit.Assert.*
import org.junit.Test

class DestinationConfirmationTest {
    private val requested = Place(37.41309722, 127.09383464, "42dot", "42dot-poi", "0")

    @Test fun samePoiCanUseADifferentVehicleEntranceCoordinate() {
        assertTrue(destinationMatches(requested, requested.copy(latitude = 37.41289678, poiSubId = "")))
    }

    @Test fun anotherPoiIsRejectedEvenAtTheSameCoordinate() {
        assertFalse(destinationMatches(requested, requested.copy(poiId = "another-poi")))
    }

    @Test fun anotherSubPoiIsRejected() {
        assertFalse(destinationMatches(requested.copy(poiSubId = "1"), requested.copy(poiSubId = "2")))
        assertFalse(destinationMatches(requested.copy(poiSubId = "1"), requested.copy(poiSubId = "")))
    }

    @Test fun coordinatesAreUsedWhenPoiIdentityIsUnavailable() {
        val coordinateOnly = requested.copy(poiId = "")
        assertTrue(destinationMatches(coordinateOnly, coordinateOnly.copy(latitude = requested.latitude + 0.00001)))
        assertFalse(destinationMatches(coordinateOnly, coordinateOnly.copy(latitude = requested.latitude + 0.01)))
        assertFalse(destinationMatches(coordinateOnly, coordinateOnly.copy(latitude = 0.0, longitude = 0.0)))
    }
}
