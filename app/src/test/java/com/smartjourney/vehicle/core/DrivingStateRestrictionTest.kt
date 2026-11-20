package com.smartjourney.vehicle.core

import android.car.drivingstate.CarUxRestrictions
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DrivingStateRestrictionTest {

    @Test
    fun 규제상태를_알수없으면_제한상태로_본다() {
        assertTrue(isUiRestricted(null, null))
        assertTrue(isUiRestricted(false, null))
        assertTrue(isUiRestricted(null, CarUxRestrictions.UX_RESTRICTIONS_BASELINE))
    }

    @Test
    fun distractionOptimization이_필요하면_항상_제한상태다() {
        assertTrue(isUiRestricted(true, CarUxRestrictions.UX_RESTRICTIONS_BASELINE))
        assertTrue(isUiRestricted(true, CarUxRestrictions.UX_RESTRICTIONS_FULLY_RESTRICTED))
    }

    @Test
    fun DO가_false여도_관련_activeRestrictions_플래그가_있으면_제한한다() {
        assertTrue(isUiRestricted(false, CarUxRestrictions.UX_RESTRICTIONS_NO_SETUP))
        assertTrue(isUiRestricted(false, CarUxRestrictions.UX_RESTRICTIONS_NO_KEYBOARD))
        assertTrue(isUiRestricted(false, CarUxRestrictions.UX_RESTRICTIONS_LIMIT_CONTENT))
        assertTrue(isUiRestricted(false, CarUxRestrictions.UX_RESTRICTIONS_NO_FILTERING))
        assertTrue(isUiRestricted(false, CarUxRestrictions.UX_RESTRICTIONS_FULLY_RESTRICTED))
    }

    @Test
    fun DO가_false이고_편집관련_플래그가_모두_해제된_경우에만_편집을_허용한다() {
        assertFalse(isUiRestricted(false, CarUxRestrictions.UX_RESTRICTIONS_BASELINE))
        // 영상 제한만 켜진 경우(정차/지역 정책 변형)에는 텍스트·방문지 편집을 차단하지 않는다.
        assertFalse(isUiRestricted(false, CarUxRestrictions.UX_RESTRICTIONS_NO_VIDEO))
    }
}
