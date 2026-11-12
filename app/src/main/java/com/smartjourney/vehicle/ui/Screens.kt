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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
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
import com.smartjourney.vehicle.engine.needsEndConfirm
import com.smartjourney.vehicle.engine.partialArrival
import com.smartjourney.vehicle.engine.partialLine
import com.smartjourney.vehicle.engine.statusLine
import com.smartjourney.vehicle.engine.text
import com.smartjourney.vehicle.voice.VoiceIssue
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.flow
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
    // 탭을 바꾸거나 주행 화면으로 바뀌어도 홈에서 고른 방문지·인원을 잃지 않는다
    val tabStates = rememberSaveableStateHolder()
    val issues = rememberIssues(graph, journey)

    Surface(Modifier.fillMaxSize(), color = Palette.Background) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val wide = maxWidth >= 1400.dp
            val main: @Composable (Modifier) -> Unit = { modifier ->
                BoxWithConstraints(modifier) {
                CompositionLocalProvider(LocalCompact provides (maxWidth < 1000.dp)) {
                Column(Modifier.fillMaxSize()) {
                    TopBar(restricted, showDemo, onToggleDemo = { showDemo = !showDemo })
                    when {
                        restricted -> Page(Modifier.weight(1f)) { DrivingScreen(journey, consented) }
                        !consented -> Page(Modifier.weight(1f), scroll = false) { ConsentScreen(graph) }
                        else -> {
                            Tabs(tab, issues.size, onSelect = { tab = it })
                            when (tab) {
                                Tab.HOME -> tabStates.SaveableStateProvider(Tab.HOME) {
                                    HomeScreen(graph, journey, onShowStatus = { tab = Tab.STATUS }, modifier = Modifier.weight(1f))
                                }
                                Tab.STATUS -> Page(Modifier.weight(1f), scroll = false) { StatusScreen(graph, journey) }
                                Tab.ISSUES -> Page(Modifier.weight(1f)) { IssuesScreen(graph, issues) }
                            }
                        }
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
private fun Page(modifier: Modifier = Modifier, scroll: Boolean = true, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier.fillMaxWidth().then(if (scroll) Modifier.verticalScroll(rememberScrollState()) else Modifier).padding(PleosSpace.Page),
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
                Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("스마트 저니", style = Type.Title)
                    if (!LocalCompact.current) Text(if (restricted) "주행 중 · 음성으로 안내해요" else "방문 준비 · 결과 확인", style = Type.Caption)
                }
                Badge("시연용 데이터 · 실제 매장 미접수", Palette.Orange)
                if (restricted) Badge("주행 중 조작 제한", Palette.Orange)
                if (!restricted && !android.provider.Settings.canDrawOverlays(LocalContext.current)) {
                    val context = LocalContext.current
                    SmallButton("다른 앱 위 팝업 허용", { runCatching { context.startActivity(android.content.Intent(android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        android.net.Uri.parse("package:${context.packageName}"))) }.onFailure {
                            android.widget.Toast.makeText(context, "이 기기는 팝업 권한 설정을 제공하지 않아요. 지도 위 카드는 뜨지 않고 음성으로 안내해요.", android.widget.Toast.LENGTH_LONG).show()
                        } })
                }
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
            FlowRow(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
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

/** 값의 출처. 실제 차량·내비 값과 시연 입력을 화면에서 구분한다. */
@Composable
private fun SourceBadge(real: Boolean, label: String) =
    Badge(if (real) "실제 · $label" else "시연 입력 · $label", if (real) Palette.Green else Palette.Gray)

// ---------- 핵심 1. 동의 V-00 ----------

@Composable
private fun ConsentScreen(graph: AppGraph) {
    val support by graph.signals.support.collectAsStateWithLifecycle()
    var later by rememberSaveable { mutableStateOf(false) }
    Text("도착에 맞춰 방문을 준비해요", style = Type.Headline)
    Text("도착 예상과 대기를 비교하고, 승인한 접수만 요청해요.", style = Type.Body)
    Panel {
        Text("데이터 이용 안내", style = Type.Title)
        Text("항목: 방문 매장·도착 예상·방문 인원·접수 상태·하차 인원", style = Type.Body)
        Text("위치 좌표·이동 경로·차대번호는 보내지 않아요.", style = Type.Label)
        Text("수신처: 스마트 저니 서버·접수한 매장\n보관: ${RETENTION_DAYS}일 후 삭제", style = Type.Label)
        Text("현재는 시연용 접수이며 실제 매장에는 접수되지 않아요.", style = Type.Caption)
        Text("거부해도 내비는 이용할 수 있어요. 홈에서 동의를 철회할 수 있어요.", style = Type.Label)
    }
    Panel {
        Text("차량 기능", style = Type.Title)
        Text(support?.let { "좌석 ${if (it.seat) "지원" else "미지원"} · 도어 ${if (it.door) "지원" else "미지원"} · 주행 상태 ${if (it.drivingState) "지원" else "미지원"}" } ?: "차량 기능 확인 중", style = Type.Label)
        Text("좌석을 알 수 없으면 인원을 직접 입력해요. 주행 상태를 알 수 없으면 조작을 제한해요.", style = Type.Caption)
    }
    BigButton("동의하고 시작", { graph.setConsent(true) }, Modifier.fillMaxWidth())
    SmallButton("나중에", { later = true }, Modifier.fillMaxWidth())
    if (later) Text("동의 전에는 접수를 준비하지 않아요. 내비는 그대로 이용해 주세요.", style = Type.Label)
}

// ---------- 핵심 2. 홈(방문 계획) V-02 ----------

@OptIn(FlowPreview::class)
@Composable
private fun HomeScreen(graph: AppGraph, journey: JourneyState, onShowStatus: () -> Unit, modifier: Modifier = Modifier) {
    // 데모 패널에서 접수 백엔드(앱 안 데이터 ↔ 서버)를 바꾸면 매장 목록을 다시 읽는다. 서버 오류면 빈 목록이다.
    val useServer by graph.useServer.collectAsStateWithLifecycle()
    val stores by remember(useServer) {
        flow { emit(runCatching { graph.backend.stores() }.getOrDefault(emptyList())) }
    }.collectAsStateWithLifecycle(initialValue = emptyList())
    val useDemo by graph.demo.enabled.collectAsStateWithLifecycle()
    val support by graph.signals.support.collectAsStateWithLifecycle()
    val waitLimit by graph.waitLimitMin.collectAsStateWithLifecycle()
    // 좌석 점유가 5초 동안 같으면 제안값으로 쓴다
    val sensed by remember(graph) { graph.signals.occupied.debounce(5_000) }.collectAsStateWithLifecycle(initialValue = null)
    var manual by rememberSaveable { mutableStateOf<Int?>(null) }
    var picked by rememberSaveable { mutableStateOf(listOf<Long>()) }
    // 앱 범위의 진행 상태를 따른다. 탭을 바꿔도 시작 중에는 버튼이 다시 켜지지 않는다.
    val starting by graph.preparingJourney.collectAsStateWithLifecycle()
    val startError by graph.startError.collectAsStateWithLifecycle()
    val suggestion = sensed?.takeIf { it > 0 && (useDemo || support?.seat == true) }
    val party = manual ?: suggestion

    Column(modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("방문 계획", style = Type.Headline)
        if (journey.active) {
            CurrentVisitCard(graph, journey, onShowStatus)
            Text("방문 중에는 현황에서 결과와 다음 행동을 확인해 주세요.", style = Type.Label)
        } else {
            BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
                val settings: @Composable () -> Unit = {
                    Panel {
                        Text("방문 인원", style = Type.Title)
                        Text(suggestion?.let { "탑승 인원 제안 ${it}명 · 확인해 주세요" }
                            ?: if (sensed == 0) "감지 인원 없음 · 직접 입력해 주세요" else if (!useDemo && support?.seat == false) "좌석 미지원 · 직접 입력해 주세요" else "좌석 확인 중 · 직접 입력할 수 있어요", style = Type.Label)
                        PartyStepper(party, { manual = it })
                        SourceBadge(!useDemo, "좌석")
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("대기 ${waitLimit}분까지", Modifier.weight(1f), style = Type.Body)
                            SmallButton("−", { graph.setWaitLimit(waitLimit - 10) }, enabled = waitLimit > 10)
                            SmallButton("+", { graph.setWaitLimit(waitLimit + 10) }, enabled = waitLimit < 120)
                        }
                    }
                }
                val destinations: @Composable () -> Unit = {
                    Panel {
                        Text("방문지 · ${picked.size}곳 / 최대 3곳", style = Type.Title)
                        if (stores.isEmpty()) Text("방문지 정보를 불러오는 중이에요", style = Type.Label)
                        stores.forEach { store ->
                            val order = picked.indexOf(store.id)
                            StoreRow(graph, store, order, waitLimit, enabled = picked.size < 3 || order >= 0) {
                                picked = if (order >= 0) picked - store.id else picked + store.id
                            }
                        }
                    }
                }
                if (maxWidth >= 1000.dp) Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                    Column(Modifier.weight(0.85f)) { settings() }
                    Column(Modifier.weight(1.15f)) { destinations() }
                } else Column(verticalArrangement = Arrangement.spacedBy(12.dp)) { settings(); destinations() }
            }
            Surface(color = Palette.Surface, shape = RoundedCornerShape(20.dp)) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("${picked.size}곳 · ${party?.let { "${it}명" } ?: "인원 선택 필요"} · 웨이팅 데모 · 순정 내비 안내", style = Type.Label)
                    startError?.let { Text(it, style = Type.Caption, color = Palette.Red) }
                    BigButton(if (starting) "내비 연결 중…" else "방문 시작", {
                        val stops = picked.mapNotNull { id -> stores.find { it.id == id } }
                        graph.scope.launch {
                            val result = graph.startJourney(party ?: 1, stops)
                            if (result.isFailure && graph.startError.value == null) graph.startError.value = result.exceptionOrNull()?.message
                        }
                    }, Modifier.fillMaxWidth(), enabled = party != null && picked.isNotEmpty() && !starting)
                }
            }
        }
        TextButton({ graph.setConsent(false) }) { Text("데이터 전송 동의 철회", style = Type.Caption) }
    }
}

/**
 * "방문 끝내기". 순정 내비의 안내 종료는 SDK가 미리 알려 주지 않아 가로챌 수 없지만, 우리 앱의 종료 버튼은 항상 확인을 거친다.
 * 방문 추적만 끝내며 접수는 취소하지 않고 내비 경로도 건드리지 않는다.
 */
@Composable
private fun endVisitAction(graph: AppGraph, journey: JourneyState): () -> Unit {
    var confirm by remember { mutableStateOf(false) }
    val stop = journey.current
    if (confirm && stop.needsEndConfirm()) AlertDialog(
        onDismissRequest = { confirm = false },
        title = { Text("방문 안내를 종료할까요?", style = Type.Title) },
        text = {
            Text(if (stop?.submission == SubmissionStatus.CONFIRMED) "이미 웨이팅을 접수했어요. 방문 안내를 종료해도 접수는 유지돼요."
                else "웨이팅 접수 결과를 아직 확인하지 못했어요. 방문 안내를 종료해도 접수는 취소되지 않아요.", style = Type.Body)
        },
        confirmButton = { TextButton({ confirm = false; graph.engine.end() }) { Text("안내 종료", style = Type.Body) } },
        dismissButton = { TextButton({ confirm = false }) { Text("계속 안내", style = Type.Body) } },
    )
    return { if (stop.needsEndConfirm()) confirm = true else graph.engine.end() }
}

@Composable
private fun CurrentVisitCard(graph: AppGraph, journey: JourneyState, onShowStatus: () -> Unit) {
    val stop = journey.current ?: return
    val endVisit = endVisitAction(graph, journey)
    Panel {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Text("진행 중 · ${stop.store.name}", Modifier.weight(1f), style = Type.Title)
            SubmissionBadge(stop)
        }
        Text(journey.statusLine().substringAfter(" · "), style = Type.Body)
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            if (maxWidth < 600.dp) Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                BigButton("방문 현황 보기", onShowStatus, Modifier.fillMaxWidth())
                BigButton("방문 끝내기", endVisit, Modifier.fillMaxWidth(), primary = false)
            } else Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                BigButton("방문 현황 보기", onShowStatus, Modifier.weight(1f))
                BigButton("방문 끝내기", endVisit, Modifier.weight(1f), primary = false)
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
        Modifier.fillMaxWidth().toggleable(selected, enabled = enabled, role = Role.Checkbox) { onClick() },
        shape = RoundedCornerShape(16.dp), color = if (selected) Palette.Button else Palette.Surface,
        border = BorderStroke(2.dp, if (selected) Palette.Primary else Palette.Line),
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("${if (selected) "${order + 1}번째 · " else "○ "}${store.name}", style = Type.Title)
            Text("웨이팅 연동 · 데모 · 예약/전화 미지원", style = Type.Caption, color = Palette.Blue)
            Text(queue?.let { waitText(it) } ?: "대기정보 없음", style = Type.Label,
                color = if ((queue?.estimatedWaitMin ?: 0) > waitLimit) Palette.Orange else Palette.Muted)
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
    val useDemo by graph.demo.enabled.collectAsStateWithLifecycle()
    val stop = journey.current
    var expanded by rememberSaveable { mutableStateOf(false) }
    if (stop == null) {
        Panel {
            Text("진행 중인 방문이 없어요", style = Type.Title)
            Text("홈에서 방문지·인원을 고르고 시작해 주세요.", style = Type.Body)
        }
        return
    }
    val waiting = stop.waiting
    val waitMin = waiting?.estimatedWaitMin ?: journey.queue?.estimatedWaitMin
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(Modifier.weight(1f)) {
            Text(stop.store.name, style = Type.Title)
            Text("웨이팅 연동 · 시연용 접수", style = Type.Caption)
        }
        SubmissionBadge(stop)
    }
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val summary: @Composable () -> Unit = {
            Panel {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    TimeBlock(journey.remainingMin?.toString() ?: "–", "분", "도착 예상", Modifier.weight(1f))
                    TimeBlock(waiting?.ticketNo?.toString() ?: "–", "번", "접수 번호", Modifier.weight(1f))
                    TimeBlock((waiting?.aheadTeams ?: journey.queue?.aheadTeams)?.toString() ?: "–", "팀", "앞 팀", Modifier.weight(1f))
                }
                ProgressSteps(stop)
                Text("${waiting?.partySize ?: journey.partySize}명 · 예상 대기 ${waitMin?.let { "${it}분" } ?: "정보 없음"}", style = Type.Body)
                Text("허용 ${journey.waitLimitMin}분${waitMin?.let { if (it > journey.waitLimitMin) " · 설정한 시간보다 길어요" else " · 설정 범위 안" } ?: " · 비교할 정보 없음"}", style = Type.Label,
                    color = if (waitMin != null && waitMin > journey.waitLimitMin) Palette.Orange else Palette.Muted)
                ResultDetail(stop)
            }
        }
        val details: @Composable () -> Unit = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (journey.prompt == null) Panel {
                    Text("다음 행동", style = Type.Label)
                    Text(nextAction(stop), style = Type.Title)
                    Text(if (waiting != null) "접수 확인됨 · 실제 매장 미접수" else "아직 접수 전 · 승인한 경우에만 접수해요", style = Type.Caption)
                }
                if (stop.arrival != null || journey.alight?.let { it != AlightDetector.State.WAIT_ARRIVAL } == true) ArrivalPanel(graph, journey, stop)
                journey.prompt?.let { PromptCard(graph, it) }
                if (journey.active && stop.phase == StopPhase.PLANNED && stop.submission == SubmissionStatus.NOT_REQUESTED && journey.queue != null && journey.prompt == null) {
                    BigButton("접수 여부 확인", graph.engine::promptNow, Modifier.fillMaxWidth())
                }
            }
        }
        if (maxWidth >= 1000.dp) Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            Column(Modifier.weight(1f)) { summary() }
            Column(Modifier.weight(1f)) { details() }
        } else Column(verticalArrangement = Arrangement.spacedBy(12.dp)) { summary(); details() }
    }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        when (etaSource) {
            EtaSource.NAVI_LIVE -> SourceBadge(true, "내비 도착 예상")
            EtaSource.NAVI_REQUEST -> SourceBadge(true, "내비 도착 예상")
            EtaSource.DEMO -> SourceBadge(false, "도착 예상")
            null -> Badge("도착 예상 정보 없음", Palette.Gray)
        }
        SourceBadge(!useDemo, "좌석·도어")
    }
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        SmallButton(if (expanded) "기록 닫기" else "방문·기록 보기", { expanded = !expanded }, Modifier.weight(1f))
        if (journey.active) SmallButton("방문 끝내기", endVisitAction(graph, journey), Modifier.weight(1f))
    }
    if (expanded) Column(Modifier.heightIn(max = 240.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("방문을 끝내도 접수는 취소되지 않아요.", style = Type.Caption)
        journey.stops.forEachIndexed { i, item -> Text("${i + 1}번째 · ${item.store.name}", style = Type.Label) }
        journey.log.takeLast(8).forEach { Text(it, style = Type.Caption) }
    }
}

private fun nextAction(stop: StopState): String = when {
    stop.submission == SubmissionStatus.UNKNOWN -> "중복 접수하지 말고 결과를 확인해 주세요"
    stop.submission == SubmissionStatus.REQUESTING -> "접수 결과를 기다려 주세요"
    stop.submission == SubmissionStatus.REJECTED -> "매장 정책을 확인해 주세요"
    stop.partialArrival -> "일행 모두 도착한 뒤 매장 안내를 확인해 주세요"
    stop.phase == StopPhase.SEATED -> "착석 처리됐어요 · 다음 방문을 확인해 주세요"
    stop.phase == StopPhase.CANCELLED -> "접수가 취소됐어요 · 방문 계획을 확인해 주세요"
    stop.phase == StopPhase.CALLED -> "매장 호출을 확인하고 일행과 함께 이동해 주세요"
    stop.waiting != null -> "안전하게 주차한 뒤 매장 호출을 기다려 주세요"
    else -> "접수할지 확인하고 승인해 주세요"
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ProgressSteps(stop: StopState) {
    val current = when {
        stop.phase == StopPhase.SEATED -> 6
        stop.phase == StopPhase.CALLED -> 5
        stop.phase == StopPhase.ARRIVED -> 4
        stop.submission == SubmissionStatus.CONFIRMED -> 3
        stop.submission == SubmissionStatus.REQUESTING -> 2
        stop.phase == StopPhase.PROMPTED -> 1
        else -> 0
    }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        listOf("요청 전", "질문", "요청 중", "확정", "도착", "호출", "착석").forEachIndexed { i, text ->
            Text(if (i == current) "● $text" else text, style = Type.Caption,
                color = if (i == current) Palette.Blue else Palette.Muted)
        }
    }
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
        Text(if (prompt.kind == Prompt.Kind.PARTY_CHANGE) "다음 방문 인원" else "승인 확인", style = Type.Caption)
        Text(if (prompt.kind == Prompt.Kind.CALLED) "매장에서 호출했어요 · 일행 도착을 확인해 주세요" else text.title, style = Type.Body)
        Text(text.detail, style = Type.Label)
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            BigButton(text.primary, { graph.engine.approve(promptId = prompt.id) }, Modifier.weight(1f))
            text.secondary?.let { BigButton(it, { graph.engine.defer(prompt.id) }, Modifier.weight(1f), primary = false) }
        }
    }
}

/** V-10 도착 후 확인. 하차는 추정이며 매장 입장·신원 확인이 아니다. */
@Composable
private fun ArrivalPanel(graph: AppGraph, journey: JourneyState, stop: StopState) {
    val useDemo by graph.demo.enabled.collectAsStateWithLifecycle()
    val party = stop.waiting?.partySize ?: journey.partySize
    Panel {
        Text("도착 후 확인", style = Type.Label)
        Text("목적지 도착 감지", style = Type.Caption)
        val state = journey.alight
        when {
            stop.partialArrival -> {
                Text(stop.partialLine(party), style = Type.Title, color = Palette.Orange)
                Text("전체 도착 전이에요 · 입장 여부는 매장 안내를 확인해 주세요.", style = Type.Caption)
            }
            stop.arrival == ArrivalResult.CONFIRMED ->
                Text("차량 신호 기준 ${stop.alighted}/${party}명 하차 추정", style = Type.Title, color = Palette.Green)
            stop.arrival == ArrivalResult.MANUAL -> Text("도착을 직접 확인했어요", style = Type.Title)
            state == AlightDetector.State.MANUAL -> {
                Text("하차를 자동으로 확인하지 못했어요", style = Type.Body)
                BigButton("도착했어요", graph.engine::confirmManualArrival, Modifier.fillMaxWidth())
            }
            else -> Text("내리는 인원을 확인하고 있어요", style = Type.Body)
        }
        Text("도착 전송: ${if (stop.arrival != null) "시연 데이터 · 실제 매장 미전송" else "확인 전"}", style = Type.Caption)
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
            add(Issue("차량 소프트웨어 업데이트 필요", "내비·차량 기능 연결이 원활하지 않을 수 있어요. 차량 소프트웨어를 최신으로 업데이트해 주세요."))
        }
    }
}

@Composable
private fun IssuesScreen(graph: AppGraph, issues: List<Issue>) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("확인이 필요한 항목", style = Type.Label)
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
        Text("목록에 없는 매장은 접수를 지원하지 않아요.", style = Type.Body)
        Text("매장 전화 연결과 예약은 아직 지원하지 않아요.", style = Type.Body)
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
