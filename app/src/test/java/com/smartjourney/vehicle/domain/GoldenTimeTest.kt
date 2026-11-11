package com.smartjourney.vehicle.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GoldenTimeTest {

    @Test
    fun 이동_60분_대기_30분이면_40분_뒤에_알린다() {
        assertEquals(GoldenTime.Wait(40), goldenTime(remainingMin = 60, waitMin = 30, bufferMin = 10))
    }

    @Test
    fun 남은_시간이_target_이하가_되면_알린다() {
        assertEquals(GoldenTime.Wait(1), goldenTime(remainingMin = 21, waitMin = 30, bufferMin = 10))
        assertEquals(GoldenTime.Prompt, goldenTime(remainingMin = 20, waitMin = 30, bufferMin = 10))
    }

    @Test
    fun 대기가_여유보다_짧으면_바로_알린다() {
        assertEquals(GoldenTime.Prompt, goldenTime(remainingMin = 50, waitMin = 5, bufferMin = 10))
    }

    @Test
    fun 알림은_한_번이고_거절_뒤_5분_더_줄면_한_번만_다시_묻는다() {
        val gate = PromptGate()
        assertTrue(gate.shouldPrompt(20))
        gate.onPrompted()
        assertFalse("응답을 기다리는 중에는 다시 묻지 않는다", gate.shouldPrompt(19))

        gate.onDeclined(remainingMin = 20)
        assertFalse(gate.shouldPrompt(16))
        assertTrue(gate.shouldPrompt(15))
        gate.onPrompted()
        gate.onDeclined(remainingMin = 15)
        assertFalse("두 번째 거절 뒤에는 묻지 않는다", gate.shouldPrompt(0))
    }
}
