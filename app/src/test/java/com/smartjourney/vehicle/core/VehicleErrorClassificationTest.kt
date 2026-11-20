package com.smartjourney.vehicle.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VehicleErrorClassificationTest {

    @Test
    fun 연결_및_내부서비스_오류만_재시도_가능으로_분류한다() {
        val conn = classifyVehicleError(IllegalStateException("ERROR_SERVICE_CONNECTION_FAILURE"))
        assertEquals(VehicleErrorCode.ERROR_SERVICE_CONNECTION_FAILURE, conn.code)
        assertEquals(VehicleErrorCategory.CONNECTION_OR_SERVICE, conn.category)
        assertTrue(conn.retryable)

        val internal = classifyVehicleError(RuntimeException("Error code:ERROR_INTERNAL_SERVICE"))
        assertEquals(VehicleErrorCode.ERROR_INTERNAL_SERVICE, internal.code)
        assertEquals(VehicleErrorCategory.CONNECTION_OR_SERVICE, internal.category)
        assertTrue(internal.retryable)
    }

    @Test
    fun 권한_주행제한_미지원_빈결과는_재시도_불가로_분류한다() {
        val perm = classifyVehicleError(SecurityException("missing permission"))
        assertEquals(VehicleErrorCode.ERROR_SECURITY_PERMISSION, perm.code)
        assertEquals(VehicleErrorCategory.PERMISSION, perm.category)
        assertFalse(perm.retryable)

        val driving = classifyVehicleError(Exception("ERROR_DRIVING_RESTRICTED"))
        assertEquals(VehicleErrorCode.ERROR_DRIVING_RESTRICTED, driving.code)
        assertEquals(VehicleErrorCategory.DRIVING_RESTRICTED, driving.category)
        assertFalse(driving.retryable)

        val unsupported = classifyVehicleError(UnsupportedOperationException("not supported"))
        assertEquals(VehicleErrorCode.ERROR_NOT_SUPPORTED_PROPERTY, unsupported.code)
        assertEquals(VehicleErrorCategory.UNSUPPORTED, unsupported.category)
        assertFalse(unsupported.retryable)

        val empty = classifyVehicleError(Exception("ERROR_EMPTY_RESULT"))
        assertEquals(VehicleErrorCode.ERROR_EMPTY_RESULT, empty.code)
        assertEquals(VehicleErrorCategory.EMPTY_RESULT, empty.category)
        assertFalse(empty.retryable)
    }

    @Test
    fun 숫자_에러코드와_알수없는_예외를_안전하게_처리한다() {
        assertEquals(VehicleErrorCode.ERROR_SERVICE_CONNECTION_FAILURE, parseVehicleErrorCode("40001"))
        assertEquals(VehicleErrorCode.ERROR_DRIVING_RESTRICTED, parseVehicleErrorCode("code=50001"))
        assertEquals(VehicleErrorCode.ERROR_SECURITY_PERMISSION, parseVehicleErrorCode("800001"))

        val unknown = classifyVehicleError(RuntimeException("unexpected failure"))
        assertNull(unknown.code)
        assertEquals(VehicleErrorCategory.UNKNOWN, unknown.category)
        assertFalse(unknown.retryable)
    }
}
