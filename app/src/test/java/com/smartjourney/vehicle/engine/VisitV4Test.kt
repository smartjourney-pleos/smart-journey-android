package com.smartjourney.vehicle.engine

import com.smartjourney.vehicle.core.VehicleSnapshot
import com.smartjourney.vehicle.data.FakeBackend
import com.smartjourney.vehicle.data.JourneyStateJsonCodec
import com.smartjourney.vehicle.domain.ArrivalResult
import com.smartjourney.vehicle.domain.Store
import com.smartjourney.vehicle.voice.VoiceDecision
import com.smartjourney.vehicle.voice.spokenText
import com.smartjourney.vehicle.voice.voiceDecision
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 개발명세서 v4 5·6장: 숫자가 든 음성 질문, 대기 허용 시간, 일부 하차. */
@OptIn(ExperimentalCoroutinesApi::class)
class VisitV4Test {
    private class Signals : JourneySignals {
        val occupiedNow = MutableStateFlow(4)
        val doorOpen = MutableStateFlow(false)
        val driving = MutableStateFlow(true)
        val remaining = MutableStateFlow(30 * 60)
        val arrive = MutableSharedFlow<Arrival>(extraBufferCapacity = 1)
        override val occupied = occupiedNow
        override val anyDoorOpen = doorOpen
        override val restricted = driving
        override val arrivals = arrive
        override val routeChanged = emptyFlow<Unit>()
        override fun remainingSec(target: Store) = remaining
        override suspend fun sensorsSupported() = true
        override suspend fun readNow() = VehicleSnapshot(occupiedNow.value, null, doorOpen.value)
    }

    private val signals = Signals()
    private val backend = FakeBackend() // 첫 장소: 6팀 × 5분 = 대기 30분, 도착 여유 10분

    private suspend fun TestScope.engine(waitLimitMin: Int = 30) =
        JourneyEngine(signals, backend, backgroundScope, now = { testScheduler.currentTime }).also {
            it.start(4, backend.stores().take(1), waitLimitMin)
            runCurrent()
        }

    @Test
    fun 질문에_도착과_대기_숫자를_넣고_짧은_답으로_승인한다() = runTest {
        val engine = engine()
        signals.remaining.value = 20 * 60 // 20 ≤ 30 − 10
        runCurrent()
        val prompt = engine.state.value.prompt!!
        assertNull(prompt.waitLimitMin)
        assertEquals("42dot까지 20분, 대기는 30분이에요. 4명으로 지금 웨이팅을 걸까요?", prompt.spokenText())
        listOf("응", "걸어 줘", "응, 걸어 줘!").forEach { assertEquals(it, VoiceDecision.APPROVE, voiceDecision(it, prompt.kind)) }
        listOf("응 걸지 마", "엄마 배고파", "걸어 볼까").forEach { assertNull(it, voiceDecision(it, prompt.kind)) }
        assertTrue(engine.respondByVoice(prompt.id, true))
        runCurrent()
        assertEquals(SubmissionStatus.CONFIRMED, engine.state.value.current!!.submission)
    }

    @Test
    fun 대기_허용_시간을_넘으면_판단_질문으로_바꾸고_보류하면_접수하지_않는다() = runTest {
        backend.addWalkIn(1, 12) // 18팀 = 대기 90분
        val engine = engine(waitLimitMin = 30)
        val prompt = engine.state.value.prompt!! // 30 ≤ 90 − 10 이라 바로 묻는다
        assertEquals(30, prompt.waitLimitMin)
        assertEquals("42dot 대기가 90분이에요. 그래도 걸까요?", prompt.spokenText())

        assertTrue(engine.respondByVoice(prompt.id, false))
        runCurrent()
        repeat(3) { signals.remaining.value -= 6 * 60; runCurrent() }
        assertNull("보류한 매장은 다시 묻지 않는다", engine.state.value.prompt)
        assertEquals(SubmissionStatus.NOT_REQUESTED, engine.state.value.current!!.submission)
        assertTrue("매장에 간 정보가 없다", backend.received.value.isEmpty())
        assertEquals("다른 매장으로 바꾸지 않는다", 1, engine.state.value.stops.size)
        assertTrue(engine.state.value.statusLine().contains("설정한 30분보다 길어요"))
    }

    @Test
    fun 대기_허용_초과도_승인하면_일반_접수와_같다() = runTest {
        backend.addWalkIn(1, 12)
        val engine = engine(waitLimitMin = 30)
        assertTrue(engine.respondByVoice(engine.state.value.prompt!!.id, true))
        runCurrent()
        assertEquals(SubmissionStatus.CONFIRMED, engine.state.value.current!!.submission)
    }

    @Test
    fun 일부만_내리면_도착_완료로_보지_않고_나머지가_내리면_확정한다() = runTest {
        val engine = engine()
        signals.remaining.value = 20 * 60; runCurrent()
        engine.approve(); runCurrent()

        signals.arrive.tryEmit(Arrival.Current); runCurrent()
        alight(remaining = 1) // 3명을 먼저 내려 준다
        val partial = engine.state.value.current!!
        assertEquals(ArrivalResult.PARTIAL, partial.arrival)
        assertEquals(StopPhase.REGISTERED, partial.phase)
        assertEquals("42dot · 일행 3명 도착 추정 · 1명 이동 중", engine.state.value.statusLine())
        assertFalse(backend.received.value.any { it.startsWith("도착") })
        assertTrue(backend.received.value.any { it.startsWith("일부 도착") })

        alight(remaining = 0) // 주차 후 운전자도 내린다
        val full = engine.state.value.current!!
        assertEquals(ArrivalResult.CONFIRMED, full.arrival)
        assertEquals(StopPhase.ARRIVED, full.phase)
        assertEquals(4, full.alighted)
    }

    @Test
    fun 접수_상태와_대기_허용_시간을_저장하고_요청_중은_확인_필요로_복원한다() {
        val state = JourneyState(
            active = true, partySize = 4, waitLimitMin = 45,
            stops = listOf(StopState(Store(1, "A", "", 0.0, 0.0), submission = SubmissionStatus.REQUESTING)),
        )
        val restored = JourneyStateJsonCodec.fromJson(JourneyStateJsonCodec.toJson(state, 0), 0, Long.MAX_VALUE)!!
        assertEquals(45, restored.waitLimitMin)
        assertEquals(SubmissionStatus.UNKNOWN, restored.current!!.submission)
    }

    private fun TestScope.alight(remaining: Int) {
        signals.driving.value = false
        signals.doorOpen.value = true
        runCurrent()
        signals.occupiedNow.value = remaining
        signals.doorOpen.value = false
        runCurrent()
        advanceTimeBy(7_000)
        runCurrent()
    }
}
