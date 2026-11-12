package com.smartjourney.vehicle.ui

import android.content.Context
import com.smartjourney.vehicle.BuildConfig
import com.smartjourney.vehicle.engine.Prompt

/** 마지막 앱 창 영역. 전체 디스플레이/계기판 크기로 팝업 위치를 추측하지 않는다. */
data class PopupViewport(val left: Int, val top: Int, val width: Int, val height: Int)
data class PopupPlacement(val x: Int, val y: Int, val width: Int)

fun popupPlacement(viewport: PopupViewport, width: Int, height: Int, margin: Int, bottomGap: Int): PopupPlacement? {
    if (width <= 0 || height <= 0 || width > viewport.width - margin * 2 || height > viewport.height - margin * 2) return null
    return PopupPlacement(viewport.left + margin,
        (viewport.top + viewport.height - bottomGap - height).coerceAtLeast(viewport.top + margin), width)
}

fun popupTouchAllowed(demo: Boolean, restricted: Boolean, debugBuild: Boolean = BuildConfig.DEBUG) =
    (debugBuild && demo) || !restricted

/** 표시·만료·재렌더링에서 같은 질문의 남은 시간을 사용한다. */
fun popupRemainingSeconds(deadline: Long, now: Long): Int =
    (((deadline - now).coerceAtLeast(0L) + 999L) / 1_000L).coerceAtMost(10L).toInt()

interface PromptPopup {
    fun render(prompt: Prompt?, demo: Boolean, restricted: Boolean, viewport: PopupViewport?)
    fun refresh() {}
    fun close()
}

/** 실제 표시 권한은 구현에서 매번 확인한다. release에도 동일한 표시 경로를 포함한다. */
fun createPromptPopup(context: Context): PromptPopup = MapPromptPopup(context)
