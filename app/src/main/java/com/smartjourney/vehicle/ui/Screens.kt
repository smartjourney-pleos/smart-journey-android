package com.smartjourney.vehicle.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.smartjourney.vehicle.R
import com.smartjourney.vehicle.BuildConfig
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
    var showDemo by rememberSaveable { mutableStateOf(false) }

    Surface(Modifier.fillMaxSize(), color = Palette.Background) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val wide = maxWidth >= 1400.dp
            val main: @Composable (Modifier) -> Unit = { modifier ->
                Column(modifier) {
                    TopBar(restricted, showDemo, onToggleDemo = { showDemo = !showDemo })
                    if (consented && !journey.active && !restricted) {
                        SetupScreen(graph, Modifier.weight(1f))
                    } else Column(
                        Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(PleosSpace.Page),
                        verticalArrangement = Arrangement.spacedBy(PleosSpace.Section),
                    ) {
                        when {
                            !consented -> ConsentScreen(onAgree = { graph.setConsent(true) })
                            journey.active && restricted -> DrivingScreen(journey)
                            journey.active -> ProgressScreen(graph, journey)
                            restricted -> LockedScreen()
                        }
                    }
                }
            }
            if (wide) {
                Row(Modifier.fillMaxSize()) {
                    main(Modifier.weight(1f).fillMaxHeight())
                    if (BuildConfig.DEBUG && showDemo) {
                        VerticalDivider(Modifier.fillMaxHeight(), color = Palette.Line)
                        DemoPanel(graph, journey, Modifier.width(540.dp).fillMaxHeight())
                    }
                }
            } else {
                Column(Modifier.fillMaxSize()) {
                    main(Modifier.weight(1f).fillMaxWidth())
                    if (BuildConfig.DEBUG && showDemo) {
                        HorizontalDivider(color = Palette.Line)
                        DemoPanel(graph, journey, Modifier.fillMaxWidth().heightIn(max = 420.dp))
                    }
                }
            }
        }
    }
}

/** Pleos Page Navigation: 40dp gutters, 80dp app icon, title, trailing actions. */
@Composable
private fun TopBar(restricted: Boolean, showDemo: Boolean, onToggleDemo: () -> Unit) {
    Surface(color = Palette.Surface) {
        Column {
            Row(
                Modifier.fillMaxWidth().padding(start = 40.dp, end = 24.dp, top = 32.dp, bottom = 24.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(24.dp),
            ) {
                Surface(shape = RoundedCornerShape(20.dp), color = Palette.Primary, modifier = Modifier.size(80.dp)) {
                    Icon(painterResource(R.drawable.ic_launcher_foreground), null, tint = Palette.OnPrimary)
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("스마트 저니", style = Type.Title, maxLines = 1)
                    Text(if (restricted) "주행 중 · 안전하게 이동하세요" else "정차 중 · 다음 여정을 준비하세요", style = Type.Caption)
                }
                if (BuildConfig.DEBUG) {
                    SmallButton(if (showDemo) "데모 닫기" else "데모", onToggleDemo)
                }
            }
            HorizontalDivider(color = Palette.Line)
        }
    }
}

// ---------- V-01 데이터 전송 동의 ----------

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ConsentScreen(onAgree: () -> Unit) {
    var later by rememberSaveable { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("시작하기 전에", style = Type.Label)
        Text("기다림은 줄이고,\n여정은 여유롭게", style = Type.Headline)
    }
    Panel {
        Text("웨이팅에 필요한 정보만 보내요", style = Type.Title)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            listOf("탑승 인원", "하차 인원", "도착 예상 시간", "도착 여부", "웨이팅 정보").forEach {
                Badge(it, Palette.Gray)
            }
        }
        HorizontalDivider(color = Palette.Line)
        Text("위치 좌표와 이동 경로는 보내지 않아요", style = Type.Body)
        Text("받는 곳: 스마트 저니 서버 · 웨이팅한 매장", style = Type.Label)
    }
    BigButton("동의하고 시작", onAgree, Modifier.fillMaxWidth())
    BigButton("나중에", { later = true }, Modifier.fillMaxWidth(), primary = false)
    Text(if (later) "동의하기 전에는 웨이팅을 걸 수 없어요" else "설정에서 언제든 데이터 전송 동의를 철회할 수 있어요", style = Type.Caption)
}

// ---------- V-02 ~ V-05 여정 만들기 (정차 중) ----------

@OptIn(FlowPreview::class)
@Composable
private fun SetupScreen(graph: AppGraph, modifier: Modifier = Modifier) {
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

    Column(modifier.fillMaxWidth()) {
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(PleosSpace.Page),
            verticalArrangement = Arrangement.spacedBy(PleosSpace.Section),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("새 여정", style = Type.Label)
                Text("어디로 떠나볼까요?", style = Type.Headline)
            }
            BoxWithConstraints(Modifier.fillMaxWidth()) {
                val passengers: @Composable () -> Unit = {
                    Panel {
                        Text("오늘의 탑승 인원", style = Type.Title)
                        Text(
                            if (manual != null) "직접 설정한 인원이에요" else if (sensed != null) "좌석 센서로 확인했어요" else "좌석을 확인하고 있어요",
                            style = Type.Label,
                        )
                        PartyStepper(party, onChange = { manual = it })
                        Text("짐이 탑승 인원으로 잡힐 수 있어요.\n인원이 다르면 직접 바꿔 주세요.", style = Type.Caption)
                        if (useDemo) Badge("데모 신호 사용 중", Palette.Gray)
                        else Text("실제 차량 신호 사용 중", style = Type.Caption)
                        if (!useDemo && support?.seat == false) Text("좌석 센서를 지원하지 않아요. 인원을 직접 설정해 주세요.", style = Type.Caption)
                    }
                }
                val destinations: @Composable () -> Unit = {
                    Panel {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("방문지 선택", Modifier.weight(1f), style = Type.Title)
                            Text("${picked.size} / 3", style = Type.Label)
                        }
                        Text("방문할 순서대로 최대 3곳을 골라 주세요", style = Type.Caption)
                        stores.forEach { store ->
                            val order = picked.indexOf(store.id)
                            StoreRow(graph, store, order, canSelect = picked.size < 3 || order >= 0) {
                                picked = if (order >= 0) picked - store.id else if (picked.size < 3) picked + store.id else picked
                            }
                        }
                        if (picked.isNotEmpty()) {
                            HorizontalDivider(color = Palette.Line)
                            Text("오늘의 코스", style = Type.Caption)
                            Text(picked.mapNotNull { id -> stores.find { it.id == id }?.name }.joinToString(" → "), style = Type.Body)
                        }
                    }
                }
                if (maxWidth >= 1100.dp) {
                    Row(horizontalArrangement = Arrangement.spacedBy(32.dp)) {
                        Column(Modifier.weight(0.85f)) { passengers() }
                        Column(Modifier.weight(1.4f)) { destinations() }
                    }
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(32.dp)) {
                        passengers()
                        destinations()
                    }
                }
            }

        }
        Surface(color = Palette.Surface) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 40.dp, vertical = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
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
        }
    }
}

@Composable
private fun StoreRow(graph: AppGraph, store: Store, order: Int, canSelect: Boolean, onClick: () -> Unit) {
    val queue by remember(store.id) { graph.backend.queueStatus(store.id) }.collectAsStateWithLifecycle(initialValue = null)
    val selected = order >= 0
    Surface(
        modifier = Modifier.fillMaxWidth().toggleable(value = selected, enabled = canSelect, role = Role.Checkbox, onValueChange = { onClick() }),
        shape = RoundedCornerShape(24.dp),
        color = if (selected) Palette.Button else Palette.Surface,
        border = BorderStroke(2.dp, if (selected) Palette.Primary else Palette.Line),
    ) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 128.dp).padding(24.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(24.dp),
        ) {
            Surface(Modifier.size(56.dp), shape = RoundedCornerShape(16.dp), color = if (selected) Palette.Primary else Palette.Button) {
                Box(contentAlignment = Alignment.Center) {
                    if (selected) Text("${order + 1}", style = Type.Title, color = Palette.OnPrimary)
                    else Icon(painterResource(R.drawable.pleos_plus), null, Modifier.size(44.dp), tint = Palette.Ink)
                }
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(store.name, style = Type.Title)
                Text(store.address, style = Type.Caption)
                queue?.let { Text(waitText(it), style = Type.Label.copy(color = Palette.Gray)) }
            }
        }
    }
}

private fun waitText(queue: QueueStatus) =
    if (queue.aheadTeams == 0) "바로 입장" else "대기 ${queue.estimatedWaitMin}분 · ${queue.aheadTeams}팀"

// ---------- V-06 여정 진행 현황 (정차 중) ----------

@Composable
private fun ProgressScreen(graph: AppGraph, journey: JourneyState) {
    val stop = journey.current ?: return
    Text("여정 현황 · 탑승 ${journey.partySize}명", style = Type.Label)
    Panel {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(stop.store.name, Modifier.weight(1f), style = Type.Title)
            PhaseBadge(stop.phase)
        }
        Text(journey.statusLine().substringAfter(" · "), style = Type.Body)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
            val waiting = stop.waiting
            if (waiting == null) {
                TimeBlock(journey.remainingMin?.toString() ?: "–", "분", "도착까지", Modifier.weight(1f))
                TimeBlock(journey.queue?.estimatedWaitMin?.toString() ?: "–", "분", "예상 대기", Modifier.weight(1f))
                TimeBlock(journey.minutesUntilPrompt?.toString() ?: "0", "분", "알림까지", Modifier.weight(1f))
            } else {
                TimeBlock(waiting.ticketNo.toString(), "번", "내 번호", Modifier.weight(1f))
                TimeBlock(waiting.aheadTeams.toString(), "팀", "앞에", Modifier.weight(1f))
                TimeBlock(journey.remainingMin?.toString() ?: "–", "분", "도착까지", Modifier.weight(1f))
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
                    Text(s.store.name, Modifier.weight(1f), style = Type.Body)
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
        Text("차를 세운 뒤\n이어서 이용해 주세요", style = Type.Headline)
        Text("인원 확인과 방문지 고르기는 정차 중에만 할 수 있어요", style = Type.Body)
    }
}
