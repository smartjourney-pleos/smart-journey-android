package com.smartjourney.vehicle.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.smartjourney.vehicle.AppGraph
import com.smartjourney.vehicle.engine.JourneyState

/**
 * V-17 데모용 신호 조작 패널. 에뮬레이터에서 바꿀 수 없는 차량 신호와, 아직 연결되지 않은 매장 POS 역할을 대신한다.
 * 실제 화면과 구분되도록 색을 다르게 쓴다.
 */
@Composable
fun DemoPanel(graph: AppGraph, journey: JourneyState, modifier: Modifier = Modifier) {
    val demo = graph.demo
    val useDemo by demo.enabled.collectAsStateWithLifecycle()
    val occupied by demo.vehicle.occupied.collectAsStateWithLifecycle()
    val doorOpen by demo.vehicle.doorOpen.collectAsStateWithLifecycle()
    val driving by demo.restricted.collectAsStateWithLifecycle()
    val remaining by demo.remainingSec.collectAsStateWithLifecycle()
    val speed by demo.speed.collectAsStateWithLifecycle()
    val received by graph.backend.received.collectAsStateWithLifecycle()

    val store = journey.current?.store
    val storeId = store?.id ?: 1L
    val queue by remember(storeId) { graph.backend.queueStatus(storeId) }.collectAsStateWithLifecycle(initialValue = null)
    val waitingId = journey.current?.waiting?.waitingId

    Column(
        modifier.background(Palette.DemoBackground).verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        Column {
            Text("데모 조작 패널", fontSize = 26.sp, fontWeight = FontWeight.Bold, color = Palette.DemoInk)
            Text("시연용 신호 조작이에요. 실제 화면이 아니에요", fontSize = 18.sp, color = Palette.DemoInk)
        }

        Section("차량 신호") {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Switch(checked = useDemo, onCheckedChange = { demo.enabled.value = it })
                Text(if (useDemo) "데모 신호 사용" else "실제 차량 신호 사용", fontSize = 20.sp, color = Palette.DemoInk)
            }
            if (!useDemo) Text("좌석·문·주행은 차량(Virtual Console)에서 바꿔야 해요", fontSize = 18.sp, color = Palette.DemoInk)
        }

        Section("좌석 · 문") {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("탑승 ${occupied}명", Modifier.weight(1f), fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
                SmallButton("−", { demo.vehicle.setOccupied((occupied - 1).coerceAtLeast(0)) }, enabled = useDemo)
                SmallButton("+", { demo.vehicle.setOccupied((occupied + 1).coerceAtMost(9)) }, enabled = useDemo)
            }
            Buttons {
                SmallButton(if (doorOpen) "문 닫기" else "문 열기", { demo.vehicle.setDoorOpen(!doorOpen) }, Modifier.weight(1f), useDemo)
            }
            Buttons {
                SmallButton("하차 장면 (3명)", { demo.playAlight(3) }, Modifier.weight(1f), useDemo)
                SmallButton("재탑승 (3명)", { demo.playBoard(3) }, Modifier.weight(1f), useDemo)
            }
        }

        Section("주행") {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    remaining?.let { "남은 시간 ${(it + 59) / 60}분" } ?: "경로 없음",
                    Modifier.weight(1f), fontSize = 22.sp, fontWeight = FontWeight.SemiBold,
                )
                SmallButton("−5분", { demo.addRemaining(-300) }, enabled = useDemo)
                SmallButton("+5분", { demo.addRemaining(300) }, enabled = useDemo)
            }
            Buttons {
                SmallButton(if (driving) "정차" else "주행 시작", { demo.restricted.value = !driving }, Modifier.weight(1f), useDemo)
                SmallButton("도착 이벤트", { demo.arrive() }, Modifier.weight(1f), useDemo)
            }
            Buttons {
                listOf(1 to "실시간", 60 to "1분/초", 300 to "5분/초").forEach { (value, label) ->
                    SmallButton(if (speed == value) "● $label" else label, { demo.speed.value = value }, Modifier.weight(1f), useDemo)
                }
            }
        }

        Section("매장 (POS 역할)") {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    "${store?.name ?: "A 맛집"} 현장 ${queue?.aheadTeams ?: 0}팀 · ${queue?.estimatedWaitMin ?: 0}분",
                    Modifier.weight(1f), fontSize = 22.sp, fontWeight = FontWeight.SemiBold,
                )
                SmallButton("−1", { graph.backend.addWalkIn(storeId, -1) })
                SmallButton("+1", { graph.backend.addWalkIn(storeId, 1) })
            }
            Buttons {
                SmallButton("점주 호출", { waitingId?.let(graph.backend::call) }, Modifier.weight(1f), waitingId != null)
                SmallButton("착석 처리", { waitingId?.let(graph.backend::seat) }, Modifier.weight(1f), waitingId != null)
            }
        }

        Section("매장이 받은 정보") {
            if (received.isEmpty()) Text("아직 보낸 정보가 없어요", fontSize = 18.sp, color = Palette.Muted)
            received.takeLast(6).forEach { Text(it, fontSize = 18.sp, color = Palette.Ink) }
        }
    }
}

@Composable
private fun Section(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(title, fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Palette.DemoInk)
        content()
    }
}

@Composable
private fun Buttons(content: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), content = content)
}
