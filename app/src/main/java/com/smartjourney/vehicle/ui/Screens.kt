package com.smartjourney.vehicle.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.smartjourney.vehicle.AppGraph
import com.smartjourney.vehicle.domain.AlightDetector
import com.smartjourney.vehicle.domain.ArrivalResult
import com.smartjourney.vehicle.domain.QueueStatus
import com.smartjourney.vehicle.domain.Store
import com.smartjourney.vehicle.engine.JourneyState
import com.smartjourney.vehicle.engine.StopPhase
import com.smartjourney.vehicle.engine.StopState
import com.smartjourney.vehicle.engine.statusLine
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.launch

@Composable
fun AppRoot(graph: AppGraph) {
    val consented by graph.consented.collectAsStateWithLifecycle()
    val journey by graph.engine.state.collectAsStateWithLifecycle()
    val restricted by graph.signals.restricted.collectAsStateWithLifecycle(initialValue = false)
    var showDemo by rememberSaveable { mutableStateOf(true) }

    BoxWithConstraints(Modifier.fillMaxSize().background(Palette.Background)) {
        val wide = maxWidth >= 1000.dp
        val main: @Composable (Modifier) -> Unit = { modifier ->
            Column(
                modifier.verticalScroll(rememberScrollState()).padding(32.dp),
                verticalArrangement = Arrangement.spacedBy(24.dp),
            ) {
                TopBar(restricted, showDemo, onToggleDemo = { showDemo = !showDemo })
                when {
                    !consented -> ConsentScreen(onAgree = { graph.setConsent(true) })
                    journey.active && restricted -> DrivingScreen(journey)
                    journey.active -> ProgressScreen(graph, journey)
                    restricted -> LockedScreen()
                    else -> SetupScreen(graph)
                }
            }
        }
        if (wide) {
            Row(Modifier.fillMaxSize()) {
                main(Modifier.weight(1f).fillMaxHeight())
                if (showDemo) DemoPanel(graph, journey, Modifier.width(460.dp).fillMaxHeight())
            }
        } else {
            // 세로 창(창 1개)에서는 패널을 아래에 둔다
            Column(Modifier.fillMaxSize()) {
                main(Modifier.weight(1f).fillMaxWidth())
                if (showDemo) DemoPanel(graph, journey, Modifier.fillMaxWidth().heightIn(max = 420.dp))
            }
        }
    }
}

@Composable
private fun TopBar(restricted: Boolean, showDemo: Boolean, onToggleDemo: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("스마트 저니", style = Type.Title)
        Badge(if (restricted) "주행 중" else "정차 중", if (restricted) Palette.Orange else Palette.Green)
        Spacer(Modifier.weight(1f))
        TextButton(onToggleDemo) { Text(if (showDemo) "데모 패널 숨기기" else "데모 패널", fontSize = 20.sp) }
    }
}

// ---------- V-01 데이터 전송 동의 ----------

@Composable
private fun ConsentScreen(onAgree: () -> Unit) {
    var later by rememberSaveable { mutableStateOf(false) }
    Panel {
        Text("매장에 이런 정보만 보내요", style = Type.Headline)
        listOf("인원 수", "하차 인원 수", "도착 예상 시간(분)", "도착 여부", "웨이팅 정보").forEach {
            Text("•  $it", style = Type.Body)
        }
        Text("위치 좌표와 이동 경로는 보내지 않아요", style = Type.Title, color = Palette.Primary)
        Text("받는 곳: 스마트 저니 서버, 웨이팅한 매장", style = Type.Label)
        Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            BigButton("동의하고 시작", onAgree, Modifier.weight(2f))
            BigButton("나중에", { later = true }, Modifier.weight(1f), primary = false)
        }
        Text(
            if (later) "동의하기 전에는 웨이팅을 걸 수 없어요" else "설정에서 언제든 철회할 수 있어요",
            style = Type.Label,
        )
    }
}

// ---------- V-02 ~ V-05 여정 만들기 (정차 중) ----------

@OptIn(FlowPreview::class)
@Composable
private fun SetupScreen(graph: AppGraph) {
    val stores by produceState(emptyList<Store>()) { value = graph.backend.stores() }
    val support by graph.signals.support.collectAsStateWithLifecycle()
    val useDemo by graph.demo.enabled.collectAsStateWithLifecycle()
    // 좌석 점유가 5초 동안 같으면 제안값으로 쓴다 (명세서 5.4)
    val sensed by remember(graph) { graph.signals.occupied.debounce(5_000) }.collectAsStateWithLifecycle(initialValue = null)
    var manual by rememberSaveable { mutableStateOf<Int?>(null) }
    var picked by rememberSaveable { mutableStateOf(listOf<Long>()) }
    var starting by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val party = manual ?: sensed?.coerceAtLeast(1)

    Panel {
        Text("오늘의 탑승 인원", style = Type.Label)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(24.dp)) {
            Text(party?.let { "${it}명" } ?: "좌석 확인 중…", style = Type.Display)
            Spacer(Modifier.weight(1f))
            BigButton("−", { manual = ((party ?: 2) - 1).coerceAtLeast(1) }, Modifier.width(110.dp), primary = false)
            BigButton("+", { manual = ((party ?: 0) + 1).coerceAtMost(9) }, Modifier.width(110.dp), primary = false)
        }
        Text(
            when {
                manual != null -> "직접 수정했어요"
                sensed != null -> "좌석 센서로 확인했어요"
                else -> "좌석 값이 5초 동안 같으면 인원으로 제안해요"
            },
            style = Type.Body,
        )
        Text("짐이 사람으로 잡힐 수 있어요. 다르면 고쳐 주세요 · 어른과 아이는 구분하지 않아요", style = Type.Label)
        Text(
            buildString {
                append(if (useDemo) "지금은 데모 신호를 쓰고 있어요" else "실제 차량 신호를 쓰고 있어요")
                support?.let { append(" · 좌석 센서 ${mark(it.seat)} · 도어 센서 ${mark(it.door)} · 주행 상태 ${mark(it.drivingState)}") }
            },
            style = Type.Label,
        )
    }

    Panel {
        Text("방문지 고르기 · 최대 3곳", style = Type.Label)
        stores.forEach { store ->
            val order = picked.indexOf(store.id)
            StoreRow(graph, store, order) {
                picked = if (order >= 0) picked - store.id else if (picked.size < 3) picked + store.id else picked
            }
        }
        if (picked.isNotEmpty()) {
            Text(
                "출발 → " + picked.mapNotNull { id -> stores.find { it.id == id }?.name }.joinToString(" → "),
                style = Type.Title,
            )
        }
    }

    error?.let { Text(it, style = Type.Body, color = Palette.Red) }
    BigButton(
        text = if (starting) "내비에 경로를 넣는 중…" else "여정 시작",
        onClick = {
            val stops = picked.mapNotNull { id -> stores.find { it.id == id } }
            starting = true
            error = null
            // 내비가 앞으로 나오면서 이 화면이 멈춰도 끝까지 진행되도록 앱 범위에서 돌린다
            graph.scope.launch {
                val result = graph.startJourney(party ?: 1, stops)
                starting = false
                if (result.isFailure) error = "내비에 넣지 못했어요. 다시 눌러 주세요 (${result.exceptionOrNull()?.message})"
            }
        },
        modifier = Modifier.fillMaxWidth(),
        enabled = party != null && picked.isNotEmpty() && !starting,
    )
    TextButton({ graph.setConsent(false) }) { Text("데이터 전송 동의 철회", fontSize = 20.sp, color = Palette.Muted) }
}

private fun mark(supported: Boolean) = if (supported) "사용 가능" else "미지원"

@Composable
private fun StoreRow(graph: AppGraph, store: Store, order: Int, onClick: () -> Unit) {
    val queue by remember(store.id) { graph.backend.queueStatus(store.id) }.collectAsStateWithLifecycle(initialValue = null)
    val selected = order >= 0
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(20.dp),
        color = if (selected) Palette.Primary.copy(alpha = 0.08f) else Palette.Background,
        border = BorderStroke(2.dp, if (selected) Palette.Primary else Palette.Line),
    ) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 96.dp).padding(horizontal = 24.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            Text(
                if (selected) "${order + 1}" else "＋",
                fontSize = 30.sp, fontWeight = FontWeight.Bold,
                color = if (selected) Palette.Primary else Palette.Muted,
            )
            Column(Modifier.weight(1f)) {
                Text(store.name, style = Type.Title)
                Text(store.address, style = Type.Label)
            }
            queue?.let { Text(waitText(it), style = Type.Body) }
        }
    }
}

private fun waitText(queue: QueueStatus) =
    if (queue.aheadTeams == 0) "바로 입장" else "대기 ${queue.estimatedWaitMin}분 · ${queue.aheadTeams}팀"

// ---------- V-06 여정 진행 현황 (정차 중) ----------

@Composable
private fun ProgressScreen(graph: AppGraph, journey: JourneyState) {
    val stop = journey.current ?: return
    Panel {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(stop.store.name, style = Type.Headline)
            PhaseBadge(stop.phase)
            Spacer(Modifier.weight(1f))
            Text("탑승 ${journey.partySize}명", style = Type.Body)
        }
        Text(journey.statusLine().substringAfter(" · "), style = Type.Title)
        Row(horizontalArrangement = Arrangement.spacedBy(56.dp)) {
            val waiting = stop.waiting
            if (waiting == null) {
                TimeBlock(journey.remainingMin?.toString() ?: "–", "분", "도착까지")
                TimeBlock(journey.queue?.estimatedWaitMin?.toString() ?: "–", "분", "예상 대기")
                TimeBlock(journey.minutesUntilPrompt?.toString() ?: "0", "분", "웨이팅 알림까지")
            } else {
                TimeBlock(waiting.ticketNo.toString(), "번", "내 번호")
                TimeBlock(waiting.aheadTeams.toString(), "팀", "앞에")
                TimeBlock(journey.remainingMin?.toString() ?: "–", "분", "도착까지")
            }
        }
    }

    if (stop.phase == StopPhase.PLANNED && journey.queue != null && journey.minutesUntilPrompt == null) {
        BigButton("지금 웨이팅 걸기", graph.engine::promptNow, Modifier.fillMaxWidth())
    }

    journey.alight?.takeIf { it != AlightDetector.State.WAIT_ARRIVAL }?.let { state ->
        AlightPanel(state, stop, onArrived = graph.engine::confirmManualArrival)
    }

    if (journey.stops.size > 1) {
        Panel {
            Text("오늘의 코스", style = Type.Label)
            journey.stops.forEachIndexed { i, s ->
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    Text("${i + 1}", style = Type.Title, color = if (i == journey.index) Palette.Primary else Palette.Muted)
                    Text(s.store.name, style = Type.Body)
                    PhaseBadge(s.phase)
                }
            }
        }
    }

    Panel {
        Text("이벤트 기록", style = Type.Label)
        journey.log.takeLast(8).forEach { Text(it, fontSize = 20.sp, color = Palette.Ink) }
    }

    BigButton("여정 끝내기", graph.engine::end, Modifier.fillMaxWidth(), primary = false)
}

/** V-10 도착·하차 확인. 몇 명이 내렸는지만 확인한다. */
@Composable
private fun AlightPanel(state: AlightDetector.State, stop: StopState, onArrived: () -> Unit) {
    Panel {
        Text("${stop.store.name}에 도착했어요", style = Type.Title)
        val manual = state == AlightDetector.State.MANUAL
        if (!manual) {
            val step = state.ordinal
            Check("정차", done = step > AlightDetector.State.WAIT_STOP.ordinal)
            Check("문 열림", done = step > AlightDetector.State.WAIT_DOOR.ordinal)
            Check("내린 뒤 문 닫힘 · 좌석 5초 유지", done = step > AlightDetector.State.STABILIZING.ordinal)
        }
        when {
            stop.arrival == ArrivalResult.CONFIRMED -> {
                Text(
                    "${stop.alighted} / ${stop.waiting?.partySize}명 하차 확인 · 매장에 알렸어요",
                    style = Type.Headline, color = Palette.Green,
                )
                Text("주차하고 따라가세요 · 몇 분이 내렸는지만 확인해요", style = Type.Label)
            }
            stop.arrival == ArrivalResult.MANUAL -> Text("도착을 알렸어요 (고객 확인)", style = Type.Title)
            manual -> {
                Text("자동으로 확인하지 못했어요. 도착했으면 눌러 주세요", style = Type.Body)
                BigButton("도착했어요", onArrived, Modifier.fillMaxWidth())
            }
            else -> Text("내리는 인원 확인 중…", style = Type.Body)
        }
    }
}

@Composable
private fun Check(label: String, done: Boolean) {
    Text(if (done) "✓  $label" else "…  $label", style = Type.Body, color = if (done) Palette.Green else Palette.Muted)
}

@Composable
private fun PhaseBadge(phase: StopPhase) {
    val (text, color: Color) = when (phase) {
        StopPhase.PLANNED -> "알림 대기" to Palette.Gray
        StopPhase.PROMPTED -> "승인 대기" to Palette.Primary
        StopPhase.REGISTERED -> "접수됨" to Palette.Blue
        StopPhase.ARRIVED -> "도착" to Palette.Green
        StopPhase.CALLED -> "호출됨" to Palette.Orange
        StopPhase.SEATED -> "완료" to Palette.Gray
        StopPhase.CANCELLED -> "취소" to Palette.Red
    }
    Badge(text, color)
}

// ---------- V-16 주행 중 앱을 열었을 때 · V-15 주행 중 잠금 ----------

@Composable
private fun DrivingScreen(journey: JourneyState) {
    val stop = journey.current ?: return
    Panel {
        Text(stop.store.name, style = Type.Headline)
        Text(journey.statusLine().substringAfter(" · "), style = Type.Display)
        Text("안전을 위해 차를 세운 뒤 사용할 수 있어요", style = Type.Label)
    }
}

@Composable
private fun LockedScreen() {
    Panel {
        Text("안전을 위해 차를 세운 뒤 사용할 수 있어요", style = Type.Headline)
        Text("인원 확인과 방문지 고르기는 정차 중에만 할 수 있어요", style = Type.Body)
    }
}
