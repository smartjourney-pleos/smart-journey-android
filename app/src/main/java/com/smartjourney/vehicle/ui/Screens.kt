package com.smartjourney.vehicle.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.VerticalDivider
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.smartjourney.vehicle.AppGraph
import com.smartjourney.vehicle.BuildConfig
import com.smartjourney.vehicle.EtaSource
import com.smartjourney.vehicle.R
import com.smartjourney.vehicle.RETENTION_DAYS
import com.smartjourney.vehicle.core.readServiceVersions
import com.smartjourney.vehicle.domain.AlightDetector
import com.smartjourney.vehicle.domain.ArrivalResult
import com.smartjourney.vehicle.domain.QueueStatus
import com.smartjourney.vehicle.domain.Store
import com.smartjourney.vehicle.engine.JourneyState
import com.smartjourney.vehicle.engine.Prompt
import com.smartjourney.vehicle.engine.StopPhase
import com.smartjourney.vehicle.engine.StopState
import com.smartjourney.vehicle.engine.SubmissionStatus
import com.smartjourney.vehicle.engine.partialArrival
import com.smartjourney.vehicle.engine.partialLine
import com.smartjourney.vehicle.engine.statusLine
import com.smartjourney.vehicle.engine.text
import com.smartjourney.vehicle.voice.VoiceIssue
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.launch

/** 정차 중 화면의 탭. 주행 음성 흐름(핵심 화면 3)은 화면이 없다. */
private enum class Tab(val label: String) { HOME("홈"), STATUS("방문 현황"), ISSUES("확인 필요") }

@Composable
fun AppRoot(graph: AppGraph) {
    val consented by graph.consented.collectAsStateWithLifecycle()
    val journey by graph.engine.state.collectAsStateWithLifecycle()
    // 규제 상태를 아직 모르면 제한으로 본다 (개발 규칙 1장)
    val restricted by graph.signals.restricted.collectAsStateWithLifecycle(initialValue = true)
    var showDemo by rememberSaveable { mutableStateOf(false) }
    var tab by rememberSaveable { mutableStateOf(Tab.HOME) }
    val issues = rememberIssues(graph, journey)

    Surface(Modifier.fillMaxSize(), color = Palette.Background) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val wide = maxWidth >= 1400.dp
            val main: @Composable (Modifier) -> Unit = { modifier ->
                Column(modifier) {
                    TopBar(restricted, showDemo, onToggleDemo = { showDemo = !showDemo })
                    when {
                        restricted -> Page { DrivingScreen(journey, consented) }
                        !consented -> Page { ConsentScreen(graph) }
                        else -> {
                            Tabs(tab, issues.size, onSelect = { tab = it })
                            when (tab) {
                                Tab.HOME -> HomeScreen(graph, journey, onShowStatus = { tab = Tab.STATUS }, modifier = Modifier.weight(1f))
                                Tab.STATUS -> Page(Modifier.weight(1f)) { StatusScreen(graph, journey) }
                                Tab.ISSUES -> Page(Modifier.weight(1f)) { IssuesScreen(graph, issues) }
                            }
                        }
                    }
                }
            }
            val demoVisible = BuildConfig.DEBUG && showDemo
            if (wide) {
                Row(Modifier.fillMaxSize()) {
                    main(Modifier.weight(1f).fillMaxHeight())
                    if (demoVisible) {
                        VerticalDivider(Modifier.fillMaxHeight(), color = Palette.Line)
                        DemoPanel(graph, journey, Modifier.width(540.dp).fillMaxHeight())
                    }
                }
            } else {
                Column(Modifier.fillMaxSize()) {
                    main(Modifier.weight(1f).fillMaxWidth())
                    if (demoVisible) {
                        HorizontalDivider(color = Palette.Line)
                        DemoPanel(graph, journey, Modifier.fillMaxWidth().heightIn(max = 420.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun Page(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(PleosSpace.Page),
        verticalArrangement = Arrangement.spacedBy(PleosSpace.Section), content = content,
    )
}

/** Pleos Page Navigation: 40dp gutters, 80dp app icon, title, trailing actions. 시연 데이터 표시는 항상 보인다. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TopBar(restricted: Boolean, showDemo: Boolean, onToggleDemo: () -> Unit) {
    Surface(color = Palette.Surface) {
        Column {
            FlowRow(
                Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("스마트 저니", style = Type.Title)
                    Text(if (restricted) "주행 규제 중 · 음성 안내" else "방문 준비 · 결과 확인", style = Type.Caption)
                }
                Badge("시연용 데이터 · 실제 매장 미접수", Palette.Orange)
                if (restricted) Badge("주행 규제 적용 중", Palette.Orange)
                if (BuildConfig.DEBUG) SmallButton(if (showDemo) "데모 닫기" else "데모", onToggleDemo)
            }
            HorizontalDivider(color = Palette.Line)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Tabs(selected: Tab, issueCount: Int, onSelect: (Tab) -> Unit) {
    Surface(color = Palette.Surface) {
        Column {
            FlowRow(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Tab.entries.forEach { tab ->
                    val label = if (tab == Tab.ISSUES && issueCount > 0) "${tab.label} ${issueCount}건" else tab.label
                    val on = tab == selected
                    Surface(
                        Modifier.heightIn(min = 64.dp).toggleable(on, role = Role.Tab) { onSelect(tab) },
                        shape = RoundedCornerShape(16.dp),
                        color = if (on) Palette.Primary else Palette.Button,
                    ) {
                        Box(Modifier.padding(horizontal = 16.dp, vertical = 14.dp), contentAlignment = Alignment.Center) {
                            Text(label, style = Type.Body, color = if (on) Palette.OnPrimary else Palette.Ink, maxLines = 1)
                        }
                    }
                }
            }
            HorizontalDivider(color = Palette.Line)
        }
    }
}

/** 값의 출처. 실제 SDK 값과 데모 입력을 화면에서 구분한다. */
@Composable
private fun SourceBadge(real: Boolean, label: String) =
    Badge(if (real) "실제 SDK · $label" else "데모 입력 · $label", if (real) Palette.Green else Palette.Gray)

// ---------- 핵심 1. 동의 V-00 ----------

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ConsentScreen(graph: AppGraph) {
    val support by graph.signals.support.collectAsStateWithLifecycle()
    var later by rememberSaveable { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("시작하기 전에", style = Type.Label)
        Text("도착 시각에 맞춰\n방문을 준비해요", style = Type.Headline)
    }
    Panel {
        Text("왜 필요한가요", style = Type.Title)
        Text("도착까지 남은 시간과 매장 대기를 비교해 웨이팅을 걸 시점을 알려 드리고, 승인한 접수만 매장에 보내요.", style = Type.Body)
        HorizontalDivider(color = Palette.Line)
        Text("보내는 정보", style = Type.Title)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            listOf("매장 식별자", "도착 예상 시간", "방문 인원", "접수 상태", "하차 추정 인원").forEach { Badge(it, Palette.Gray) }
        }
        Text("위치 좌표·이동 경로·차대번호는 보내지 않아요", style = Type.Body)
        Text("받는 곳: 스마트 저니 서버 · 접수한 매장 · 보관: ${RETENTION_DAYS}일 뒤 삭제", style = Type.Label)
        Text("동의하지 않아도 내비는 그대로 쓸 수 있어요. 동의는 홈 아래에서 언제든 철회할 수 있어요.", style = Type.Label)
    }
    Panel {
        Text("이 차량에서 쓸 수 있는 기능", style = Type.Title)
        val s = support
        if (s == null) Text("차량 기능을 확인하고 있어요", style = Type.Label)
        else {
            Text("좌석 점유: ${if (s.seat) "지원" else "미지원 · 인원을 직접 입력해요"}", style = Type.Body)
            Text("도어 상태: ${if (s.door) "지원" else "미지원 · 하차는 직접 확인해요"}", style = Type.Body)
            Text("주행 규제 상태: ${if (s.drivingState) "지원" else "미지원 · 항상 주행 중으로 취급해요"}", style = Type.Body)
        }
    }
    BigButton("동의하고 시작", { graph.setConsent(true) }, Modifier.fillMaxWidth())
    BigButton("나중에", { later = true }, Modifier.fillMaxWidth(), primary = false)
    if (later) Text("동의하기 전에는 웨이팅을 준비할 수 없어요", style = Type.Caption)
}

// ---------- 핵심 2. 홈(방문 계획) V-02 ----------

@OptIn(FlowPreview::class)
@Composable
private fun HomeScreen(graph: AppGraph, journey: JourneyState, onShowStatus: () -> Unit, modifier: Modifier = Modifier) {
    val stores by produceState(emptyList<Store>()) { value = graph.backend.stores() }
    val useDemo by graph.demo.enabled.collectAsStateWithLifecycle()
    val support by graph.signals.support.collectAsStateWithLifecycle()
    val waitLimit by graph.waitLimitMin.collectAsStateWithLifecycle()
    // 좌석 점유가 5초 동안 같으면 제안값으로 쓴다
    val sensed by remember(graph) { graph.signals.occupied.debounce(5_000) }.collectAsStateWithLifecycle(initialValue = null)
    var manual by rememberSaveable { mutableStateOf<Int?>(null) }
    var picked by rememberSaveable { mutableStateOf(listOf<Long>()) }
    var starting by remember { mutableStateOf(false) }
    val startError by graph.startError.collectAsStateWithLifecycle()
    val suggestion = sensed?.takeIf { it > 0 && (useDemo || support?.seat == true) }
    val party = manual ?: suggestion

    Column(modifier.fillMaxWidth()) {
        Page(Modifier.weight(1f)) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("방문 계획", style = Type.Label)
                Text("도착에 맞춰 방문을 준비해요", style = Type.Headline)
            }
            if (journey.active) CurrentVisitCard(graph, journey, onShowStatus)

            BoxWithConstraints(Modifier.fillMaxWidth()) {
                val people: @Composable () -> Unit = {
                    Panel {
                        Text("방문 인원", style = Type.Title)
                        Text(
                            suggestion?.let { "탑승 인원 제안 ${it}명 · 방문 인원을 확인해 주세요" }
                                ?: when {
                                    !useDemo && support?.seat == false -> "좌석 센서를 지원하지 않아요 · 직접 입력해 주세요"
                                    sensed == 0 -> "좌석에서 감지한 인원이 없어요 · 직접 입력해 주세요"
                                    else -> "좌석을 확인하고 있어요"
                                },
                            style = Type.Label,
                        )
                        PartyStepper(party, onChange = { manual = it })
                        Text(if (manual != null) "직접 정한 인원이에요" else "짐·아이 좌석이 인원으로 잡힐 수 있어요", style = Type.Caption)
                        SourceBadge(real = !useDemo, label = "좌석 점유")
                    }
                }
                val limit: @Composable () -> Unit = {
                    Panel {
                        Text("대기 허용 시간", style = Type.Title)
                        Text("대기 ${waitLimit}분까지 기다려요", style = Type.Body)
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            SmallButton("−10분", { graph.setWaitLimit(waitLimit - 10) }, enabled = waitLimit > 10)
                            SmallButton("+10분", { graph.setWaitLimit(waitLimit + 10) }, enabled = waitLimit < 120)
                        }
                        Text("예상 대기가 이보다 길면 대기 시간을 알리고 접수 여부를 물어요", style = Type.Caption)
                    }
                }
                if (maxWidth >= 1100.dp) {
                    Row(horizontalArrangement = Arrangement.spacedBy(32.dp)) {
                        Column(Modifier.weight(1f)) { people() }
                        Column(Modifier.weight(1f)) { limit() }
                    }
                } else Column(verticalArrangement = Arrangement.spacedBy(32.dp)) { people(); limit() }
            }

            Panel {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("방문지 · 지원 방식", Modifier.weight(1f), style = Type.Title)
                    Text("${picked.size}곳 / 최대 3곳", style = Type.Label)
                }
                Text("방문할 순서대로 골라 주세요. 목록에 없는 매장은 접수를 지원하지 않아요.", style = Type.Caption)
                stores.forEach { store ->
                    val order = picked.indexOf(store.id)
                    StoreRow(graph, store, order, waitLimit, enabled = !journey.active && (picked.size < 3 || order >= 0)) {
                        picked = if (order >= 0) picked - store.id else picked + store.id
                    }
                }
            }
        }
        if (!journey.active) Surface(color = Palette.Surface) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 40.dp, vertical = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                val names = picked.mapNotNull { id -> stores.find { it.id == id }?.name }
                if (names.isNotEmpty()) Text("${names.joinToString(" → ")} · ${party ?: "–"}명 · 웨이팅 연동(데모) · 순정 내비로 안내", style = Type.Label)
                startError?.let { Text(it, style = Type.Body, color = Palette.Red) }
                BigButton(
                    text = if (starting) "내비에 경로를 넣는 중…" else "방문 시작",
                    onClick = {
                        val stops = picked.mapNotNull { id -> stores.find { it.id == id } }
                        starting = true
                        // 내비가 앞으로 나오면서 이 화면이 멈춰도 끝까지 진행되도록 앱 범위에서 돌린다
                        graph.scope.launch {
                            val result = graph.startJourney(party ?: 1, stops)
                            starting = false
                            if (result.isFailure && graph.startError.value == null) graph.startError.value = result.exceptionOrNull()?.message
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
private fun CurrentVisitCard(graph: AppGraph, journey: JourneyState, onShowStatus: () -> Unit) {
    val stop = journey.current ?: return
    Panel {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Text("진행 중 · ${stop.store.name}", Modifier.weight(1f), style = Type.Title)
            SubmissionBadge(stop)
        }
        Text(journey.statusLine().substringAfter(" · "), style = Type.Body)
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            if (maxWidth < 600.dp) Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                BigButton("방문 현황 보기", onShowStatus, Modifier.fillMaxWidth())
                BigButton("방문 끝내기", graph.engine::end, Modifier.fillMaxWidth(), primary = false)
            } else Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                BigButton("방문 현황 보기", onShowStatus, Modifier.weight(1f))
                BigButton("방문 끝내기", graph.engine::end, Modifier.weight(1f), primary = false)
            }
        }
        Text("방문을 끝내도 이미 접수한 웨이팅은 취소되지 않아요", style = Type.Caption)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun StoreRow(graph: AppGraph, store: Store, order: Int, waitLimit: Int, enabled: Boolean, onClick: () -> Unit) {
    val queue by remember(store.id) { graph.backend.queueStatus(store.id) }.collectAsStateWithLifecycle(initialValue = null)
    val selected = order >= 0
    Surface(
        modifier = Modifier.fillMaxWidth().toggleable(value = selected, enabled = enabled, role = Role.Checkbox, onValueChange = { onClick() }),
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
                FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Badge("웨이팅 연동 · 데모", Palette.Blue)
                    Badge("예약·전화 미지원", Palette.Gray)
                }
                if (queue == null) Text("대기정보 없음 · 확인 중", style = Type.Label)
                queue?.let {
                    Text(waitText(it), style = Type.Label.copy(color = if (it.estimatedWaitMin > waitLimit) Palette.Orange else Palette.Gray))
                }
            }
        }
    }
}

private fun waitText(queue: QueueStatus) = when {
    queue.aheadTeams == 0 -> "대기 없음 (시연용 대기정보)"
    else -> "대기 ${queue.estimatedWaitMin}분 · ${queue.aheadTeams}팀 (시연용 대기정보)"
}

// ---------- 핵심 4. 방문 현황 V-06 (결과 상세·다음 방문 인원 포함) + 핵심 5. 도착 후 확인 V-10 ----------

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun StatusScreen(graph: AppGraph, journey: JourneyState) {
    val etaSource by graph.signals.etaSource.collectAsStateWithLifecycle()
    val stop = journey.current
    if (stop == null) {
        Panel {
            Text("진행 중인 방문이 없어요", style = Type.Title)
            Text("홈에서 방문지를 고르고 방문을 시작해 주세요", style = Type.Body)
        }
        return
    }
    Text("방문 현황 · 방문 인원 ${journey.partySize}명 · 대기 허용 ${journey.waitLimitMin}분${if (!journey.active) " · 종료됨" else ""}", style = Type.Label)
    Panel {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(stop.store.name, Modifier.weight(1f), style = Type.Title)
            SubmissionBadge(stop)
        }
        Text(journey.statusLine().substringAfter(" · "), style = Type.Body)
        FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            TimeBlock(journey.remainingMin?.toString() ?: "–", "분", "도착 예상", Modifier.width(220.dp))
            val waiting = stop.waiting
            if (waiting == null) {
                TimeBlock(journey.queue?.estimatedWaitMin?.toString() ?: "–", "분", "예상 대기", Modifier.width(220.dp))
                TimeBlock(journey.minutesUntilPrompt?.toString() ?: "–", "분", "질문까지", Modifier.width(220.dp))
            } else {
                // 번호는 접수 응답을 받은 뒤에만 보인다
                TimeBlock(waiting.ticketNo.toString(), "번", "접수 번호", Modifier.width(220.dp))
                TimeBlock(waiting.aheadTeams.toString(), "팀", "앞에", Modifier.width(220.dp))
            }
        }
        @OptIn(ExperimentalLayoutApi::class)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            when (etaSource) {
                EtaSource.NAVI_LIVE -> SourceBadge(true, "도착 예상(내비 실시간)")
                EtaSource.NAVI_REQUEST -> SourceBadge(true, "도착 예상(내비 조회)")
                EtaSource.DEMO -> SourceBadge(false, "도착 예상")
                null -> Badge("도착 예상 정보 없음", Palette.Gray)
            }
            Badge("대기·접수 · 시연용 데이터", Palette.Orange)
        }
        if (journey.lowAccuracy) Text("도착 예상이 1분 넘게 갱신되지 않았어요 · 최근 정보 기준", style = Type.Caption)
        ResultDetail(stop)
    }

    // 정차 중에 남아 있는 질문은 화면에서도 답할 수 있다. 주행 중에는 음성으로만 묻는다.
    journey.prompt?.let { PromptCard(graph, it) }

    if (journey.active && stop.phase == StopPhase.PLANNED && stop.submission == SubmissionStatus.NOT_REQUESTED &&
        journey.queue != null && journey.minutesUntilPrompt == null && journey.prompt == null
    ) {
        BigButton("지금 웨이팅 걸기", graph.engine::promptNow, Modifier.fillMaxWidth())
    }

    if (stop.arrival != null || journey.alight?.let { it != AlightDetector.State.WAIT_ARRIVAL } == true) {
        ArrivalPanel(graph, journey, stop)
    }

    if (journey.stops.size > 1) {
        Panel {
            Text("오늘의 방문", style = Type.Label)
            journey.stops.forEachIndexed { i, s ->
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    Text("${i + 1}", style = Type.Title, color = if (i == journey.index) Palette.Primary else Palette.Muted)
                    Text(s.store.name, Modifier.weight(1f), style = Type.Body)
                    SubmissionBadge(s)
                }
            }
        }
    }

    Panel {
        Text("기록", style = Type.Label)
        journey.log.takeLast(8).forEach { Text(it, fontSize = 20.sp, color = Palette.Ink) }
    }
    if (journey.active) BigButton("방문 끝내기 (접수는 유지)", graph.engine::end, Modifier.fillMaxWidth(), primary = false)
}

@Composable
private fun ResultDetail(stop: StopState) {
    val text = when (stop.submission) {
        SubmissionStatus.NOT_REQUESTED -> "아직 접수하지 않았어요. 질문에 승인한 경우에만 접수해요."
        SubmissionStatus.REQUESTING -> "접수 결과를 확인하고 있어요"
        SubmissionStatus.CONFIRMED -> stop.waiting?.let { "${it.partySize}명 접수 확정 · 번호 ${it.ticketNo} (데모 접수, 실제 매장 미접수)" }
        SubmissionStatus.REJECTED -> "매장이 접수를 거절했어요. 매장 정책을 확인해 주세요."
        SubmissionStatus.UNKNOWN -> "처리 결과를 확인하지 못했어요. 중복 접수를 막기 위해 다시 요청하지 않았어요."
    } ?: return
    Text(text, style = Type.Label)
}

@Composable
private fun SubmissionBadge(stop: StopState) {
    val (text, color: Color) = when {
        stop.phase == StopPhase.SEATED -> "완료" to Palette.Gray
        stop.phase == StopPhase.CANCELLED -> "취소" to Palette.Red
        stop.phase == StopPhase.CALLED -> "호출됨" to Palette.Orange
        stop.submission == SubmissionStatus.REQUESTING -> "요청 중" to Palette.Blue
        stop.submission == SubmissionStatus.CONFIRMED -> "확정" to Palette.Green
        stop.submission == SubmissionStatus.REJECTED -> "실패" to Palette.Red
        stop.submission == SubmissionStatus.UNKNOWN -> "확인 필요" to Palette.Orange
        stop.phase == StopPhase.PROMPTED -> "질문 중" to Palette.Blue
        else -> "요청 전" to Palette.Gray
    }
    Badge(text, color)
}

@Composable
private fun PromptCard(graph: AppGraph, prompt: Prompt) {
    val text = prompt.text()
    Panel {
        Text(if (prompt.kind == Prompt.Kind.PARTY_CHANGE) "다음 방문 인원" else "음성 질문", style = Type.Label)
        Text(text.title, style = Type.Title)
        Text(text.detail, style = Type.Body)
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            if (maxWidth < 600.dp) Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                BigButton(text.primary, { graph.engine.approve(promptId = prompt.id) }, Modifier.fillMaxWidth())
                text.secondary?.let { BigButton(it, { graph.engine.defer(prompt.id) }, Modifier.fillMaxWidth(), primary = false) }
            } else Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                BigButton(text.primary, { graph.engine.approve(promptId = prompt.id) }, Modifier.weight(1f))
                text.secondary?.let { BigButton(it, { graph.engine.defer(prompt.id) }, Modifier.weight(1f), primary = false) }
            }
        }
        Text("주행 중에는 같은 질문을 음성으로 해요. 승인한 경우에만 실행해요.", style = Type.Caption)
    }
}

/** V-10 도착 후 확인. 하차는 추정이며 매장 입장·신원 확인이 아니다. */
@Composable
private fun ArrivalPanel(graph: AppGraph, journey: JourneyState, stop: StopState) {
    val useDemo by graph.demo.enabled.collectAsStateWithLifecycle()
    val party = stop.waiting?.partySize ?: journey.partySize
    Panel {
        Text("도착 후 확인", style = Type.Label)
        Text("${stop.store.name} · 목적지 도착 감지", style = Type.Title)
        val state = journey.alight
        when {
            stop.partialArrival -> {
                Text(stop.partialLine(party), style = Type.Headline, color = Palette.Orange)
                Text("일행이 모두 도착하기 전이에요. 동시 입장 매장은 아직 입장할 수 없어요.", style = Type.Body)
            }
            stop.arrival == ArrivalResult.CONFIRMED ->
                Text("차량 신호 기준 ${stop.alighted}/${party}명 하차 추정", style = Type.Headline, color = Palette.Green)
            stop.arrival == ArrivalResult.MANUAL -> Text("도착을 직접 확인했어요", style = Type.Title)
            state == AlightDetector.State.MANUAL -> {
                Text("하차를 자동으로 확인하지 못했어요", style = Type.Body)
                BigButton("도착했어요", graph.engine::confirmManualArrival, Modifier.fillMaxWidth())
            }
            else -> Text("내리는 인원을 확인하고 있어요", style = Type.Body)
        }
        if (stop.arrival != null) Text("매장 전송: 앱 내부 시연 데이터 · 실제 매장 미접수", style = Type.Label)
        stop.waiting?.takeIf { stop.submission == SubmissionStatus.CONFIRMED }?.let {
            Text("접수 번호 ${it.ticketNo} · ${it.partySize}명 · 매장 호출을 기다려 주세요", style = Type.Body)
        }
        SourceBadge(real = !useDemo, label = "좌석·도어")
    }
}

// ---------- 핵심 6. 예외·미지원 V-15 ----------

private data class Issue(val title: String, val detail: String)

@Composable
private fun rememberIssues(graph: AppGraph, journey: JourneyState): List<Issue> {
    val voiceIssue by graph.voiceIssue.collectAsStateWithLifecycle()
    val support by graph.signals.support.collectAsStateWithLifecycle()
    val useDemo by graph.demo.enabled.collectAsStateWithLifecycle()
    val startError by graph.startError.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val versions = remember(context) { readServiceVersions(context) }
    return buildList {
        voiceIssue?.let {
            add(Issue("음성 응답을 받지 못했어요", when (it) {
                VoiceIssue.SPEECH_UNAVAILABLE -> "음성 안내에 연결하지 못했어요. 실행하지 않았어요."
                VoiceIssue.MICROPHONE_UNAVAILABLE -> "마이크에 연결하지 못했어요. 실행하지 않았어요."
                VoiceIssue.NO_RESPONSE -> "응답이 없어 실행하지 않았어요."
                VoiceIssue.UNRECOGNIZED -> "답변이 모호해 실행하지 않았어요. 방문 현황에서 확인해 주세요."
            }))
        }
        journey.stops.filter { it.submission == SubmissionStatus.UNKNOWN }.forEach {
            add(Issue("${it.store.name} 접수 결과 확인 필요", "응답이 오지 않았어요. 이미 접수됐을 수 있어 다시 요청하지 않아요."))
        }
        journey.stops.filter { it.submission == SubmissionStatus.REJECTED }.forEach {
            add(Issue("${it.store.name} 접수 실패", "매장이 접수를 거절했어요."))
        }
        startError?.let { add(Issue("내비 연결 실패", it)) }
        if (!useDemo && support?.seat == false) add(Issue("좌석 센서 미지원", "방문 인원은 직접 입력해 주세요."))
        if (!useDemo && support?.door == false) add(Issue("도어 신호 미지원", "하차는 자동으로 추정하지 않고 직접 확인해요."))
        versions.filterNot { it.ok }.forEach {
            add(Issue("${it.label} 서비스 확인 필요", "서비스 ${it.installed ?: "미설치"} · ${it.required} 이상이 필요해요."))
        }
    }
}

@Composable
private fun IssuesScreen(graph: AppGraph, issues: List<Issue>) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("예외·미지원", style = Type.Label)
        Text(if (issues.isEmpty()) "확인할 문제가 없어요" else "안전하게 정차한 뒤 확인해 주세요", style = Type.Headline)
    }
    issues.forEach { issue ->
        Panel {
            Text(issue.title, style = Type.Title)
            Text(issue.detail, style = Type.Body)
        }
    }
    val voiceIssue by graph.voiceIssue.collectAsStateWithLifecycle()
    if (voiceIssue != null) SmallButton("음성 오류 확인함", { graph.voiceIssue.value = null })
    Panel {
        Text("지원하지 않는 매장·기능", style = Type.Title)
        Text("목록에 없는 매장은 예약·웨이팅 연동을 지원하지 않아요. 임의 대기시간이나 번호를 만들지 않아요.", style = Type.Body)
        Text("매장 전화 연결과 공식 예약 연동은 아직 지원하지 않아요 (계획).", style = Type.Body)
    }
    val context = LocalContext.current
    val versions = remember(context) { readServiceVersions(context) }
    Panel {
        Text("연결 환경", style = Type.Title)
        versions.forEach { Text("${it.label} SDK ${it.sdk} · 서비스 ${it.installed ?: "설치되지 않음"}", style = Type.Label) }
        Text("음성: Pleos Speech SDK 3.3.1 (TTS → STT) · Gleo App Schema 2개 등록", style = Type.Label)
    }
}

// ---------- 핵심 3. 주행 음성 흐름: 화면 없음. 주행 중 앱을 열면 상태만 보여 준다 ----------

@Composable
private fun DrivingScreen(journey: JourneyState, consented: Boolean) {
    val stop = journey.current
    Panel {
        if (consented && journey.active && stop != null) {
            Text(stop.store.name, style = Type.Headline)
            Text(journey.statusLine().substringAfter(" · "), style = Type.Title)
            Text("질문은 음성으로 드려요. \"응\" 또는 \"나중에\"로 답해 주세요.", style = Type.Body)
        } else {
            Text("차를 세운 뒤\n이어서 이용해 주세요", style = Type.Headline)
            Text("동의·인원·방문지 편집은 정차 중에만 할 수 있어요", style = Type.Body)
        }
        Text("안전을 위해 주행 중에는 편집과 상세 화면을 제한해요", style = Type.Label)
    }
}
