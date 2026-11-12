package com.smartjourney.vehicle.core

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RuntimeVersionsTest {
    @Test
    fun comparesNumericVersionsRatherThanText() {
        assertTrue(versionAtLeast("2.3.22", "2.3.13"))
        assertTrue(versionAtLeast("2.3.13", "2.3.13"))
        assertTrue(versionAtLeast("3.0.4", "2.0.1"))
        assertFalse(versionAtLeast("2.3.9", "2.3.13"))
        assertFalse(versionAtLeast("1.99.99", "2.0.1"))
    }

    @Test
    fun allowsBuildSuffixesAndMissingZeroComponents() {
        assertTrue(versionAtLeast("3.3.2-rc5", "3.3.2"))
        assertTrue(versionAtLeast(" 2.3.13+65 ", "2.3.13"))
        assertTrue(versionAtLeast("2.3", "2.3.0"))
        assertFalse(versionAtLeast("2.3", "2.3.1"))
    }

    @Test
    fun unknownOrMalformedVersionsAreNotSupported() {
        listOf("", "unknown", "2..3", "2.beta.99", "999999999999999999999999").forEach {
            assertFalse(versionAtLeast(it, "2.3.13"))
        }
        assertFalse(versionAtLeast("2.3.13", "unknown"))
        assertFalse(ServiceVersion("Vehicle", "2.3.2", null, "2.3.13").ok)
        assertFalse(ServiceVersion("Vehicle", "2.3.2", "2.3.9", "2.3.13").ok)
        assertTrue(ServiceVersion("Vehicle", "2.3.2", "2.3.22", "2.3.13").ok)
    }
}
