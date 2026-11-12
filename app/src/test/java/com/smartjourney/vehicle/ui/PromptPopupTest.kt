package com.smartjourney.vehicle.ui

import org.junit.Assert.*
import org.junit.Test

class PromptPopupTest {
    @Test fun `card stays inside application window with offset`() {
        val window = PopupViewport(854, 90, 1680, 1210)
        val card = popupPlacement(window, 720, 380, 24, 220)!!
        assertEquals(878, card.x)
        assertEquals(700, card.y)
        assertTrue(card.x + card.width <= window.left + window.width - 24)
        assertTrue(card.y + 380 <= window.top + window.height - 24)
    }

    @Test fun `portrait window clamps card above bottom controls without clipping`() {
        assertEquals(PopupPlacement(44, 64, 400), popupPlacement(PopupViewport(20, 40, 448, 600), 400, 500, 24, 220))
    }

    @Test fun `card is omitted when application window is too small`() {
        assertNull(popupPlacement(PopupViewport(20, 40, 448, 300), 400, 500, 24, 220))
        assertNull(popupPlacement(PopupViewport(20, 40, 448, 600), 401, 300, 24, 220))
    }

    @Test fun `countdown keeps one absolute deadline across redraws and reaches zero`() {
        val deadline = 50_000L
        assertEquals(10, popupRemainingSeconds(deadline, 40_000L))
        assertEquals(9, popupRemainingSeconds(deadline, 41_000L))
        assertEquals(1, popupRemainingSeconds(deadline, 49_999L))
        assertEquals(0, popupRemainingSeconds(deadline, 50_000L))
        assertEquals(0, popupRemainingSeconds(deadline, 51_000L))
    }

    @Test fun `release never bypasses restriction for demo signals`() {
        assertFalse(popupTouchAllowed(demo = true, restricted = true, debugBuild = false))
        assertFalse(popupTouchAllowed(demo = false, restricted = true, debugBuild = false))
        assertTrue(popupTouchAllowed(demo = false, restricted = false, debugBuild = false))
        assertTrue(popupTouchAllowed(demo = true, restricted = true, debugBuild = true))
        assertFalse(popupTouchAllowed(demo = false, restricted = true, debugBuild = true))
    }
}
