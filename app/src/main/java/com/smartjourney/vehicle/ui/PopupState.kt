package com.smartjourney.vehicle.ui

import com.smartjourney.vehicle.engine.JourneyState
import com.smartjourney.vehicle.engine.Prompt
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged

/** ETA 등 카드 표시와 무관한 상태 변화에서는 창을 다시 만들지 않는다. */
data class PopupState(
    val prompt: Prompt?,
    val demo: Boolean,
    val restricted: Boolean,
    val viewport: PopupViewport?,
)

fun popupStates(
    journeys: Flow<JourneyState>,
    demo: Flow<Boolean>,
    restricted: Flow<Boolean>,
    viewport: Flow<PopupViewport?>,
    appVisible: Flow<Boolean>,
    consented: Flow<Boolean>,
): Flow<PopupState> = combine(journeys, demo, restricted, viewport, appVisible) { s, d, r, v, visible ->
    PopupState(s.prompt.takeIf { s.active && !visible }, d, r, v)
}.combine(consented) { state, consent ->
    if (consent) state else state.copy(prompt = null)
}.distinctUntilChanged()
