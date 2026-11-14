package com.smartjourney.vehicle.ui

import com.smartjourney.vehicle.engine.JourneyState
import com.smartjourney.vehicle.engine.Prompt
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PopupStateTest {
    private val prompt = Prompt("question-1", Prompt.Kind.GOLDEN_TIME, "42dot", 4, waitMin = 30, remainingMin = 20)
    private val journey = MutableStateFlow(JourneyState(active = true, prompt = prompt, remainingMin = 20))
    private val demo = MutableStateFlow(true)
    private val restricted = MutableStateFlow(true)
    private val viewport = MutableStateFlow<PopupViewport?>(PopupViewport(854, 90, 1680, 1210))
    private val visible = MutableStateFlow(false)
    private val consent = MutableStateFlow(true)
    private val frames = mutableListOf<PopupState>()
    private val states get() = popupStates(journey, demo, restricted, viewport, visible, consent)

    @Test fun etaAndQueueUpdatesKeepTheSamePopupWindow() = runTest {
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { states.collect { frames += it } }
        runCurrent()
        for (minute in 19 downTo 10) {
            journey.value = journey.value.copy(remainingMin = minute, log = listOf("ETA updated $minute"))
            runCurrent()
        }
        assertEquals(1, frames.size)
        assertEquals(prompt, frames.single().prompt)
    }

    @Test fun restrictionWindowAndQuestionChangesStillReachThePopup() = runTest {
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { states.collect { frames += it } }
        runCurrent()
        restricted.value = false
        runCurrent()
        assertFalse(frames.last().restricted)
        viewport.value = PopupViewport(100, 80, 900, 700)
        runCurrent()
        assertEquals(viewport.value, frames.last().viewport)
        journey.value = journey.value.copy(prompt = prompt.copy(id = "question-2", partySize = 3))
        runCurrent()
        assertEquals("question-2", frames.last().prompt?.id)
        assertEquals(4, frames.size)
    }

    @Test fun foregroundConsentWithdrawalExpiryAndEndCloseThePopup() = runTest {
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { states.collect { frames += it } }
        runCurrent()
        visible.value = true
        runCurrent()
        assertNull(frames.last().prompt)
        visible.value = false
        runCurrent()
        assertNotNull(frames.last().prompt)
        consent.value = false
        runCurrent()
        assertNull(frames.last().prompt)
        consent.value = true
        runCurrent()
        journey.value = journey.value.copy(prompt = null)
        runCurrent()
        assertNull(frames.last().prompt)
        journey.value = journey.value.copy(active = false, prompt = prompt)
        runCurrent()
        assertNull(frames.last().prompt)
    }
}
