package com.example.careway.ui

import android.content.Intent
import androidx.compose.ui.platform.LocalContext
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.careway.*
import com.example.careway.core.*
import com.example.careway.data.CareReports
import com.example.careway.vehicle.DemoScenario
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

private val Ink = Color(0xFF101A24)
private val Panel = Color(0xFF1A2835)
private val Mint = Color(0xFFA5E9C0)
private val Muted = Color(0xFFAFBDCA)
private val Amber = Color(0xFFFFC37C)
private val Outline = Color(0xFF344552)
private val shape = RoundedCornerShape(24.dp)

@Composable
fun CareWayApp(model: CareViewModel, onConnect: () -> Unit) {
    val s by model.state.collectAsStateWithLifecycle()
    var page by rememberSaveable { mutableStateOf(0) }
    var dismissedWarning by remember { mutableStateOf(false) }
    val selectPage: (Int) -> Unit = { selected ->
        if (selected == 1 && page != 1) model.prepareReaction()
        page = selected
    }
    LaunchedEffect(page) { if (page != 1) model.cancelReaction() }
    val urgent = s.plan.alerts.firstOrNull { it.urgent }.takeIf { s.enabled && s.foreground }
    LaunchedEffect(urgent?.id) { if (urgent == null) dismissedWarning = false }
    // Pedal input and a missing parking signal must not hide the measurement/result screen.
    // The evaluator still cancels immediately if any required safety condition is lost.
    LaunchedEffect(s.canConfigure, s.canViewReaction, s.demo) {
        if (!s.demo && ((page >= 2 && !s.canConfigure) || (page == 1 && !s.canViewReaction))) page = 0
    }
    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri -> uri?.let { model.saveReport(it) } }
    val csvExport = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri -> uri?.let { model.saveReport(it, csv = true) } }
    val context = LocalContext.current
    MaterialTheme(colorScheme = darkColorScheme(primary = Mint, onPrimary = Ink, background = Ink,
        surface = Panel, onSurface = Color.White, onSurfaceVariant = Muted, outline = Outline,
        secondary = Amber, error = Amber)) {
        Surface(Modifier.fillMaxSize(), color = Ink) {
            BoxWithConstraints(Modifier.fillMaxSize().safeDrawingPadding()) {
                val wide = maxWidth >= 850.dp
                Row(Modifier.fillMaxSize()) {
                    if (wide) Sidebar(page, selectPage, s.canConfigure, s.canViewReaction)
                    Column(Modifier.weight(1f).fillMaxHeight().padding(if (wide) 28.dp else 16.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
                        Header(s, onConnect, { model.switchMode(true) }, wide)
                        if (!wide) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf("주행", "반응 체크", "기록", "설정").forEachIndexed { i, title ->
                                FilterChip(page == i, { selectPage(i) }, label = { Text(title) }, enabled = i == 0 || (i == 1 && s.canViewReaction) || s.canConfigure)
                            }
                        }
                        if (s.message != null) Surface(color = Color(0xFF34443D), shape = RoundedCornerShape(14.dp)) {
                            Row(Modifier.fillMaxWidth().padding(start = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text(s.message!!, Modifier.weight(1f), fontSize = 16.sp)
                                TextButton({ model.message(null) }) { Text("닫기") }
                            }
                        }
                        Box(Modifier.weight(1f)) {
                            when (page) {
                                0 -> Dashboard(s, model, wide) { selectPage(1); model.startReaction() }
                                1 -> ReactionPage(s, model)
                                2 -> History(s, { export.launch("CareWay-${SimpleDateFormat("yyyyMMdd-HHmm", Locale.KOREA).format(Date())}.json") }, model::clearHistory,
                                    { csvExport.launch("Kkomjjakmacha-30days.csv") }, {
                                        if (s.canConfigure) runCatching { context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND)
                                            .setType("text/plain").putExtra(Intent.EXTRA_TEXT, model.shareReport()), "30일 리포트 공유")) }
                                            .onFailure { model.message("이 기기에 공유할 수 있는 앱이 없습니다") }
                                    })
                                3 -> SettingsPage(s, model)
                            }
                        }
                        Text(if (s.demo) "시뮬레이션 데이터 · 실제 차량에는 명령을 보내지 않습니다" else "앱 화면이 표시되는 동안 케어합니다 · 화면을 벗어나면 자동 제어를 해제하고 복원합니다",
                            color = if (s.demo) Amber else Muted, fontSize = 13.sp)
                    }
                }
            }
            if (s.startupPrompt && s.reactionReady && s.foreground) AlertDialog(
                onDismissRequest = model::dismissStartup, title = { Text("출발 전 반응을 확인할까요?") },
                text = { Text("정차 조건이 확인됐습니다. 화면에 동물이 나타나면 브레이크 페달로 반응하세요.") },
                confirmButton = { TextButton({ selectPage(1) }) { Text("반응 체크 열기") } },
                dismissButton = { TextButton(model::dismissStartup) { Text("나중에") } })
            if (urgent != null && !dismissedWarning) HeadlightWarning(urgent, s.demo,
                { dismissedWarning = true }, { model.demoHeadlights(true) })
        }
    }
}

@Composable
private fun Sidebar(selected: Int, onSelect: (Int) -> Unit, configurable: Boolean, canViewReaction: Boolean) {
    Column(Modifier.width(180.dp).fillMaxHeight().background(Color(0xFF0C151E)).padding(22.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Shield(Modifier.size(44.dp))
        Text("꼼짝마차", fontWeight = FontWeight.Bold, fontSize = 25.sp, letterSpacing = (-1).sp)
        Text("나를 위한 주행 케어", fontSize = 12.sp, color = Muted)
        Spacer(Modifier.height(30.dp))
        listOf("주행 케어", "세이프티 핏", "케어 기록", "설정").forEachIndexed { i, title ->
            val enabled = i == 0 || (i == 1 && canViewReaction) || configurable
            Surface(onClick = { onSelect(i) }, enabled = enabled,
                color = if (i == selected) Color(0xFF253F36) else Color.Transparent, shape = RoundedCornerShape(14.dp)) {
                Text(title, Modifier.fillMaxWidth().padding(vertical = 18.dp, horizontal = 14.dp),
                    color = if (i == selected) Mint else if (enabled) Muted else Outline,
                    fontSize = 17.sp, fontWeight = if (i == selected) FontWeight.Bold else FontWeight.Normal)
            }
        }
        Spacer(Modifier.weight(1f))
        Text("조작은 간단하게,\n주행은 여유롭게.", color = Muted, fontSize = 14.sp, lineHeight = 24.sp)
        Text("PLEOS CONNECT", color = Mint.copy(alpha = .7f), fontSize = 10.sp, letterSpacing = 2.sp)
    }
}

@Composable
private fun Header(s: CareUiState, onConnect: () -> Unit, onDemo: () -> Unit, wide: Boolean) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(Modifier.weight(1f)) {
            Text(if (wide) "DRIVE WITH CONFIDENCE" else "꼼짝마차", color = Mint, fontSize = if (wide) 11.sp else 23.sp, letterSpacing = if (wide) 2.sp else 0.sp)
            Text(if (s.demo) "시뮬레이션" else "차량 연결", fontSize = if (wide) 27.sp else 15.sp, fontWeight = FontWeight.Bold)
        }
        Badge(if (s.parked) "P · 정차 확인" else if (s.snapshot.stationaryInPark(s.now)) "P · 주차 브레이크 미확인"
            else if (s.snapshot.moving(s.now)) "주행 중" else "차량 신호 대기", if (s.parked) Mint else Amber)
        if (s.demo) OutlinedButton(onConnect, enabled = !s.busy, modifier = Modifier.testTag("connect_vehicle"), contentPadding = PaddingValues(18.dp)) { Text("차량 연결") }
        else OutlinedButton(onDemo, enabled = !s.busy && !s.snapshot.moving(s.now), modifier = Modifier.testTag("use_demo"), contentPadding = PaddingValues(18.dp)) { Text("시뮬레이션") }
    }
}

@Composable
private fun Dashboard(s: CareUiState, model: CareViewModel, wide: Boolean, onReaction: () -> Unit) {
    LazyColumn(verticalArrangement = Arrangement.spacedBy(18.dp)) {
        item {
            Surface(color = Color(0xFF20372F), shape = shape) {
                Row(Modifier.fillMaxWidth().padding(26.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Badge(if (!s.enabled) "케어 일시 정지" else if (s.enhanced) "전방 주의 알림 강화" else "꼼짝마차가 함께합니다", Mint)
                        Text(if (s.reactionReady) "출발 전,\n나의 반응을 확인해요." else if (s.snapshot.moving(s.now)) "주행에 집중하세요.\n주변 환경을 살필게요." else "차량의 신호를\n기다리고 있어요.",
                            fontSize = if (wide) 32.sp else 25.sp, fontWeight = FontWeight.Bold, lineHeight = if (wide) 42.sp else 34.sp)
                        Text(if (s.reactionReady) "짧은 브레이크 반응 체크로 오늘의 기록을 남겨보세요." else "야간 · 빗길 · 터널 상황에 맞춰 필요한 케어를 준비합니다.", color = Color(0xFFC8DBCF), fontSize = 15.sp)
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                            Button(onReaction, enabled = s.canViewReaction && !s.busy, modifier = Modifier.heightIn(min = 56.dp).testTag("open_start_reaction")) { Text("반응 체크 시작", fontSize = 17.sp) }
                            OutlinedButton({ model.enable(!s.enabled) }, enabled = s.enabled || s.parked,
                                modifier = Modifier.heightIn(min = 56.dp)) { Text(if (s.enabled) "케어 중지" else "케어 켜기") }
                        }
                    }
                    if (wide) RoadIllustration(s, Modifier.width(250.dp).height(225.dp))
                }
            }
        }
        if (s.plan.alerts.isNotEmpty() && s.enabled) item {
            val a = s.plan.alerts.first()
            Surface(color = Color(0xFF463522), shape = RoundedCornerShape(18.dp)) {
                Column(Modifier.padding(20.dp)) { Text(a.title, color = Amber, fontSize = 22.sp, fontWeight = FontWeight.Bold); Text(a.detail, color = Color(0xFFF2DDC3), fontSize = 16.sp) }
            }
        }
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("지금의 케어", fontSize = 22.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                val speed = s.snapshot.speedMps?.fresh(s.now)
                Text(if (speed == null) "차속 —" else "${(abs(speed) * 3.6).roundToInt()} km/h", color = Muted, fontSize = 18.sp)
            }
        }
        item {
            val tiles = listOf(
                listOf("01", "야간 시인성", "하향등 감시 · 실내 조명"),
                listOf("02", "빗길 시야", "김서림 제거 · 미러 열선"),
                listOf("03", "터널 공조", "진입 200m 전 공조 케어"),
                listOf("04", "눈부심 케어", if (s.demo) "출구 100m · 스마트 글래스" else "출구·역광 데이터 연동 대기"))
            val active = listOf(s.plan.nightActive, s.plan.raining, s.plan.tunnelActive, s.plan.glareActive)
            val controls = listOf(Control.AMBIENT, Control.DEFROST, Control.RECIRCULATION, Control.GLASS_FRONT)
            if (wide) Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                tiles.forEachIndexed { i, t -> CareTile(t[0], t[1], active[i] && s.enabled, t[2], s.statuses[controls[i]], Modifier.weight(1f)) }
            } else Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                tiles.forEachIndexed { i, t -> CareTile(t[0], t[1], active[i] && s.enabled, t[2], s.statuses[controls[i]], Modifier.fillMaxWidth()) }
            }
        }
        if (s.demo) item { DemoPanel(s, model) }
        if (!s.demo && s.problems.isNotEmpty()) item {
            CardBlock("연결 상태") {
                s.problems.forEach { (name, reason) -> Text("$name · $reason", color = Muted, fontSize = 14.sp) }
                OutlinedButton(model::refreshConnection) { Text("지원 기능 다시 확인") }
            }
        }
    }
}

@Composable
private fun CareTile(number: String, title: String, active: Boolean, subtitle: String, status: String?, modifier: Modifier) {
    Surface(shape = RoundedCornerShape(18.dp), color = Panel, modifier = modifier) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(number, color = Muted, fontSize = 12.sp, modifier = Modifier.weight(1f))
                Text(if (active) "감지 중" else "대기", color = if (active) Mint else Muted, fontSize = 12.sp)
            }
            Text(title, fontSize = 21.sp, fontWeight = FontWeight.Bold)
            Text(subtitle, fontSize = 12.sp, color = Muted, minLines = 2)
            Text(status?.substringBefore(" · ") ?: if (active) "조건 확인" else "주변 상황을 살펴요", color = if (active) Mint else Muted, fontSize = 13.sp)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DemoPanel(s: CareUiState, model: CareViewModel) {
    CardBlock("시나리오 스튜디오") {
        Text("상황을 선택하면 같은 판단 엔진으로 제어와 복원을 시연합니다.", color = Muted, fontSize = 14.sp)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            DemoScenario.entries.forEach { scenario -> FilterChip(s.scenario == scenario,
                { model.scenario(scenario) }, label = { Text(scenario.label) }, modifier = Modifier.testTag("scenario_${scenario.name}")) }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            TextButton({ model.demoHeadlights(s.snapshot.lowBeam?.value != true) }) { Text(if (s.snapshot.lowBeam?.value == true) "전조등 끄기" else "전조등 켜기") }
            TextButton(model::demoManualOverride) { Text("실내 조명 수동 변경") }
            TextButton({ model.demoFailure(true) }) { Text("제어 통신 실패") }
            TextButton({ model.demoFailure(false) }) { Text("통신 복구") }
        }
        Text(s.values.entries.joinToString("  ·  ") { "${it.key.label} ${it.value}" }, fontSize = 12.sp, color = Muted)
    }
}

@Composable
private fun ReactionPage(s: CareUiState, model: CareViewModel) {
    val active = s.reaction.phase in setOf(ReactionPhase.WAITING, ReactionPhase.STIMULUS)
    val blocker = if (s.busy) "연결과 설정 복원을 마치는 중입니다" else s.snapshot.reactionStartBlocker(s.now)
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(18.dp)) {
        Text("오늘의 세이프티 핏", fontSize = 30.sp, fontWeight = FontWeight.Bold)
        Text("숲길 옆에서 동물이 나옵니다. 도로에 나타나는 순간 브레이크 페달을 밟으세요.", color = Muted, fontSize = 17.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Badge(if (s.snapshot.gear?.fresh(s.now) == Gear.PARK) "P단 확인" else "P단 필요", if (s.snapshot.gear?.fresh(s.now) == Gear.PARK) Mint else Amber)
            Badge(if (s.snapshot.parkingBrake?.fresh(s.now) == true) "주차 브레이크 확인"
                else "주차 브레이크 필요",
                if (s.snapshot.parkingBrake?.fresh(s.now) == true) Mint else Amber)
            Badge(if (s.snapshot.stationaryInPark(s.now)) "완전 정차 확인" else "정차 확인 필요", if (s.snapshot.stationaryInPark(s.now)) Mint else Amber)
        }
        if (!active && blocker != null) Surface(color = Color(0xFF463522), shape = RoundedCornerShape(14.dp)) {
            Text("시작 대기 · $blocker", Modifier.fillMaxWidth().padding(18.dp).testTag("reaction_blocker"), color = Amber, fontSize = 18.sp)
        }
        if (!s.demo) {
            val pedal = s.snapshot.brake?.fresh(s.now)
            Surface(color = if (pedal == true) Color(0xFF304B3E) else Panel, shape = RoundedCornerShape(14.dp)) {
                Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(when (pedal) { true -> "차량 브레이크 · 밟힘"; false -> "차량 브레이크 · 뗌"; null -> "차량 브레이크 · 신호 대기" },
                        color = if (pedal == null) Amber else Mint, fontSize = 20.sp, modifier = Modifier.testTag("live_brake_state"))
                    Text("연결된 차량의 브레이크 이벤트로 측정합니다. 폴링 값만 수신되면 측정을 제한합니다.", color = Muted, fontSize = 14.sp)
                }
            }
        }
        AnimalCrossingScene(s.reaction, s.showStimulus, model::stimulusVisible)
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(model::startReaction, enabled = blocker == null && !active,
                modifier = Modifier.heightIn(min = 60.dp).testTag("start_reaction")) { Text("반응 체크", fontSize = 19.sp) }
            if (active) OutlinedButton(model::cancelReaction, modifier = Modifier.heightIn(min = 60.dp)) { Text("측정 취소") }
            if (s.demo) Button({ model.demoBrake(s.snapshot.brake?.value != true) },
                colors = ButtonDefaults.buttonColors(containerColor = Amber), modifier = Modifier.heightIn(min = 60.dp).testTag("demo_brake")) {
                Text(if (s.snapshot.brake?.value == true) "시연: 브레이크 떼기" else "시연: 브레이크 밟기", fontSize = 17.sp)
            }
        }
        Text(if (s.demo) "시연 버튼은 가상 페달입니다. 실차 모드에서는 실제 브레이크 센서로만 측정합니다."
            else "화면을 벗어나거나 기어·주차 브레이크·차속 조건이 바뀌면 자동으로 취소합니다.", color = Amber, fontSize = 14.sp)
        Text("반응 시간은 개인 참고 기록이며 운전 적합성이나 약물 영향을 진단하지 않습니다. 알림 강화 기준은 초기 1,000ms, 기록 3회 이후 개인 중앙값의 1.5배와 1,000ms 중 큰 값입니다.", color = Muted, fontSize = 13.sp)
    }
}

@Composable
private fun History(s: CareUiState, onExport: () -> Unit, onClear: () -> Unit, onCsv: () -> Unit, onShare: () -> Unit) {
    var confirmClear by remember { mutableStateOf(false) }
    val records = s.reactions.filter { it.demo == s.demo }
    val median = records.mapNotNull { it.durationMs }.sorted().let { it.getOrNull(it.size / 2) }
    val report = remember(s.reactions, s.events, s.demo) { CareReports.summarize(s.reactions, s.events, s.demo) }
    LazyColumn(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("나의 케어 기록", Modifier.weight(1f), fontSize = 30.sp, fontWeight = FontWeight.Bold)
                OutlinedButton(onExport) { Text("기록 저장") }
                TextButton({ confirmClear = true }) { Text("기록 삭제") }
            }
        }
        item { CardBlock(if (s.demo) "시뮬레이션 반응 기록" else "차량 반응 기록") {
            Text("${records.size}회 측정  ·  중앙값 ${median?.let { "$it ms" } ?: "—"}", fontSize = 25.sp, color = Mint)
            Text("최근 31일 기록을 이 기기에 보관합니다. 최대 반응 2,000건·케어 이벤트 20,000건입니다.", color = Muted)
        } }
        item { Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(onCsv) { Text("CSV 저장") }
            OutlinedButton(onShare) { Text("리포트 공유") }
        } }
        item { CardBlock("최근 30일 리포트") {
            Text("${report.attempts}회 체크  ·  평균 ${report.averageMs?.let { "$it ms" } ?: "—"}  ·  전조등 안내 ${report.headlightWarnings}회", fontSize = 20.sp)
            val max = report.days.mapNotNull { it.averageMs }.maxOrNull()?.coerceAtLeast(1) ?: 1
            Row(Modifier.fillMaxWidth().height(90.dp), horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.Bottom) {
                report.days.forEach { day ->
                    val average = day.averageMs
                    Box(Modifier.weight(1f).height(if (average == null) 3.dp else (80f * average / max).dp)
                        .clip(RoundedCornerShape(3.dp)).background(if (average == null) Outline else Mint))
                }
            }
            Text("일별 반응 평균 · 기록이 없는 날은 낮은 선으로 표시합니다. 저장된 최근 기록만 집계하며 건강 상태를 판단하지 않습니다.", color = Muted, fontSize = 12.sp)
        } }
        items(records.takeLast(10).reversed()) { r ->
            Row(Modifier.fillMaxWidth().padding(6.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(time(r.atMs), color = Muted)
                Text(r.durationMs?.let { "$it ms" } ?: "응답 없음", fontWeight = FontWeight.Bold)
                Text(if (r.delayed) "알림 강화" else "기록 완료", color = if (r.delayed) Amber else Mint)
            }
        }
        item { Text("케어 타임라인", fontSize = 22.sp, fontWeight = FontWeight.Bold) }
        val events = s.events.filter { it.demo == s.demo }.takeLast(50).reversed()
        if (events.isEmpty()) item { Text("아직 기록이 없어요. 반응 체크나 주행 케어를 시작해 보세요.", color = Muted) }
        items(events) { e -> CardBlock(e.title) {
            Text(e.detail, color = Muted)
            Text(time(e.atMs), color = Muted, fontSize = 12.sp)
        } }
    }
    if (confirmClear) AlertDialog(onDismissRequest = { confirmClear = false }, title = { Text("기록을 삭제할까요?") },
        text = { Text("현재 선택한 모드의 기록을 삭제합니다. 차량 설정 복원 기록은 유지됩니다.") },
        confirmButton = { TextButton({ onClear(); confirmClear = false }) { Text("삭제") } },
        dismissButton = { TextButton({ confirmClear = false }) { Text("취소") } })
}

@Composable
private fun SettingsPage(s: CareUiState, model: CareViewModel) {
    var level by remember(s.settings.glassLevel) { mutableStateOf(s.settings.glassLevel?.toString() ?: "") }
    var verified by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(18.dp)) {
        Text("나에게 맞는 케어", fontSize = 30.sp, fontWeight = FontWeight.Bold)
        CardBlock("자동 케어") {
            ToggleRow("야간 시인성", "하향등 미점등 안내와 운전석 실내 조명 감광", s.settings.night) { model.settings(s.settings.copy(night = it)) }
            ToggleRow("빗길 시야 확보", "앞유리 김서림 제거 · 외기 유입 · 양쪽 미러 열선", s.settings.rain) { model.settings(s.settings.copy(rain = it)) }
            ToggleRow("터널 케어", "진입 200m 전 내기 순환 · 빗길에는 시야 확보 우선", s.settings.tunnel) { model.settings(s.settings.copy(tunnel = it)) }
            ToggleRow("한국어 음성 안내", s.voiceStatus, s.settings.voice) { model.settings(s.settings.copy(voice = it)) }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(model::retryVoice) { Text("음성 다시 연결") }
                OutlinedButton(model::testVoice) { Text("음성 테스트") }
            }
        }
        CardBlock("스마트 글래스 · 차량별 설정") {
            Text(if (s.demo) "시뮬레이션에서는 0~5 중 2단계로 제어합니다." else "차량의 허용 투과 단계와 주행 중 사용 가능 여부를 확인한 값을 입력하세요. 앱을 다시 열면 확인을 새로 받습니다.", color = Muted)
            Text("현재 연동에서 출구 거리·역광 신호를 확인하지 못해 실제 모드의 자동 감광은 입력 연동 대기 상태입니다.", color = Amber, fontSize = 14.sp)
            if (!s.demo) {
                OutlinedTextField(level, { level = it.filter(Char::isDigit).take(3); verified = false }, label = { Text("검증된 단계") }, singleLine = true)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(verified, { verified = it })
                    Text("해당 차량에서 이 단계의 주행 사용을 확인했습니다", fontSize = 15.sp)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button({
                        val n = level.toIntOrNull()
                        val caps = listOfNotNull(s.capabilities[Control.GLASS_FRONT], s.capabilities[Control.GLASS_ROOF]).filter { it.supported }
                        if (n != null && caps.isNotEmpty() && caps.all { it.accepts(n) }) model.settings(s.settings.copy(glassLevel = n))
                        else model.message("차량이 지원하는 영역과 단계 범위를 먼저 확인해 주세요")
                    }, enabled = verified && s.parked) { Text("현재 연결에 적용") }
                    TextButton({ model.settings(s.settings.copy(glassLevel = null)); verified = false }) { Text("감광 설정 해제") }
                }
            }
        }
        CardBlock("차량 지원 기능") {
            Control.entries.forEach { c ->
                val cap = s.capabilities[c]
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(c.label, fontSize = 16.sp)
                    Text(if (cap == null) "확인 중" else if (cap.supported) "지원 · ${cap.min}~${cap.max}" else "사용 불가", color = if (cap?.supported == true) Mint else Amber)
                }
                if (cap != null && !cap.supported) Text(cap.reason, color = Muted, fontSize = 12.sp)
            }
            if (!s.demo) OutlinedButton(model::refreshConnection) { Text("지원 기능 다시 확인") }
        }
        Text("자동 케어는 제동·조향을 제어하지 않습니다. 운전자가 차량 설정을 변경하면 해당 자동 제어를 멈추고 수동 설정을 유지합니다.", color = Muted, fontSize = 14.sp)
    }
}

@Composable
private fun ToggleRow(title: String, description: String, checked: Boolean, changed: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) { Text(title, fontSize = 20.sp); Text(description, color = Muted, fontSize = 14.sp) }
        Switch(checked, changed)
    }
}

@Composable
private fun CardBlock(title: String, content: @Composable ColumnScope.() -> Unit) {
    Surface(shape = shape, color = Panel, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(22.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(title, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            content()
        }
    }
}

@Composable
private fun Badge(text: String, color: Color) {
    Text(text, color = color, fontSize = 12.sp, fontWeight = FontWeight.Medium,
        modifier = Modifier.clip(RoundedCornerShape(30.dp)).background(color.copy(alpha = .09f)).border(1.dp, color.copy(alpha = .25f), RoundedCornerShape(30.dp)).padding(horizontal = 12.dp, vertical = 8.dp))
}

@Composable
private fun HeadlightWarning(alert: CareAlert, demo: Boolean, dismiss: () -> Unit, fixDemo: () -> Unit) {
    val transition = rememberInfiniteTransition(label = "attention")
    val pulse by transition.animateFloat(.55f, 1f, infiniteRepeatable(tween(1200), RepeatMode.Reverse), label = "slow pulse")
    Dialog(onDismissRequest = dismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize().padding(30.dp), color = Color(0xFF34271C), shape = RoundedCornerShape(30.dp), border = androidx.compose.foundation.BorderStroke(3.dp, Amber.copy(alpha = pulse))) {
            Column(Modifier.padding(30.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                Text("!", color = Amber, fontSize = 76.sp, fontWeight = FontWeight.Bold)
                Text(alert.title, color = Amber, fontSize = 42.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center, modifier = Modifier.testTag("headlight_warning"))
                Spacer(Modifier.height(18.dp))
                Text(alert.detail, color = Color.White, fontSize = 24.sp, textAlign = TextAlign.Center)
                Spacer(Modifier.height(32.dp))
                Button(dismiss, modifier = Modifier.heightIn(min = 60.dp)) { Text("확인 · 주행 화면", fontSize = 20.sp) }
                if (demo) TextButton(fixDemo) { Text("시연: 전조등 켜기", color = Amber, fontSize = 18.sp) }
            }
        }
    }
}

@Composable
private fun Shield(modifier: Modifier) {
    Canvas(modifier) {
        val p = Path().apply { moveTo(size.width * .5f, 0f); lineTo(size.width * .92f, size.height * .17f); lineTo(size.width * .84f, size.height * .63f); quadraticTo(size.width * .7f, size.height * .88f, size.width * .5f, size.height); quadraticTo(size.width * .3f, size.height * .88f, size.width * .16f, size.height * .63f); lineTo(size.width * .08f, size.height * .17f); close() }
        drawPath(p, Mint)
        drawLine(Ink, Offset(size.width * .3f, size.height * .47f), Offset(size.width * .45f, size.height * .64f), size.width * .075f, StrokeCap.Round)
        drawLine(Ink, Offset(size.width * .45f, size.height * .64f), Offset(size.width * .72f, size.height * .32f), size.width * .075f, StrokeCap.Round)
    }
}

@Composable
private fun RoadIllustration(s: CareUiState, modifier: Modifier) {
    Canvas(modifier) {
        val w = size.width; val h = size.height
        drawCircle(Mint.copy(alpha = .045f), w * .48f, Offset(w * .5f, h * .5f))
        val road = Path().apply { moveTo(w * .34f, h * .12f); lineTo(w * .08f, h); moveTo(w * .66f, h * .12f); lineTo(w * .92f, h) }
        drawPath(road, Mint.copy(alpha = .22f), style = Stroke(2f))
        repeat(4) { i -> drawLine(Mint.copy(alpha = .22f), Offset(w * .5f, h * (.12f + i * .22f)), Offset(w * .5f, h * (.21f + i * .22f)), 3f) }
        drawRoundRect(Mint.copy(alpha = .06f), Offset(w * .25f, h * .3f), Size(w * .5f, h * .59f), CornerRadius(32f), style = Stroke(2f))
        drawRoundRect(Mint, Offset(w * .36f, h * .4f), Size(w * .28f, h * .42f), CornerRadius(20f))
        drawRoundRect(Ink, Offset(w * .39f, h * .46f), Size(w * .22f, h * .11f), CornerRadius(8f))
        drawRoundRect(Ink.copy(alpha = .7f), Offset(w * .4f, h * .69f), Size(w * .2f, h * .06f), CornerRadius(6f))
        if (s.plan.nightActive) {
            val beam = Path().apply { moveTo(w * .38f, h * .42f); lineTo(w * .25f, h * .1f); lineTo(w * .47f, h * .1f); close() }
            drawPath(beam, Brush.verticalGradient(listOf(Mint.copy(alpha = 0f), Mint.copy(alpha = .3f))))
        }
    }
}

private fun time(millis: Long) = SimpleDateFormat("MM.dd HH:mm:ss", Locale.KOREA).format(Date(millis))
