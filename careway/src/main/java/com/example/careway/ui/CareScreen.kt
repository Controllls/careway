package com.example.careway.ui

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.View
import android.widget.*
import com.example.careway.*
import com.example.careway.core.*
import com.example.careway.data.CareReports
import com.example.careway.vehicle.DemoScenario
import java.text.SimpleDateFormat
import java.util.*

class CareScreen(private val activity: Activity, private val model: CareController,
    private val connect: () -> Unit, private val export: (Boolean) -> Unit) : LinearLayout(activity) {
    private val ink=Color.rgb(14,25,34); private val panel=Color.rgb(27,44,56)
    private val mint=Color.rgb(165,233,192); private val amber=Color.rgb(232,181,112)
    private val muted=Color.rgb(177,194,202)
    var page=0; private set
    private lateinit var body: LinearLayout
    private val bindings=mutableListOf<(CareUiState)->Unit>()
    private var mode: Boolean?=null
    private var historyKey: Pair<Int,Int>?=null
    private var warning: AlertDialog?=null
    private var startup: AlertDialog?=null
    private var dismissedWarning: String?=null
    private fun dp(n:Int)=(n*resources.displayMetrics.density).toInt()
    private fun bg(color:Int)=GradientDrawable().apply { setColor(color); cornerRadius=dp(20).toFloat() }
    private fun column()=LinearLayout(context).apply { orientation=VERTICAL }
    private fun row()=LinearLayout(context).apply { orientation=HORIZONTAL; gravity=android.view.Gravity.CENTER_VERTICAL }
    private fun text(value:String,size:Int=17,color:Int=Color.WHITE)=TextView(context).apply {
        text=value; textSize=size.toFloat(); setTextColor(color); setPadding(0,dp(6),0,dp(6))
        if(size>=25) setTypeface(null,Typeface.BOLD)
    }
    private fun label(parent:LinearLayout,size:Int=17,color:Int=Color.WHITE,tagName:String?=null, get:(CareUiState)->String):TextView {
        val v=text("",size,color); v.tag=tagName; parent.addView(v)
        bindings += { s -> val next=get(s); if(v.text.toString()!=next) v.text=next }
        return v
    }
    private fun button(parent:LinearLayout,title:String,tagName:String?=null,enabled:(CareUiState)->Boolean={true},click:()->Unit):Button {
        val v=Button(context).apply { text=title; isAllCaps=false; textSize=16f; tag=tagName
            setTextColor(ink); background=bg(mint); minHeight=dp(52); setPadding(dp(16),dp(8),dp(16),dp(8))
            setOnClickListener { click(); render(model.state.value) } }
        parent.addView(v,LayoutParams(LayoutParams.WRAP_CONTENT,LayoutParams.WRAP_CONTENT).apply { setMargins(0,dp(6),dp(10),dp(6)) })
        bindings += { v.isEnabled=enabled(it); v.alpha=if(v.isEnabled)1f else .4f }
        return v
    }
    private fun card(title:String):LinearLayout {
        val c=column().apply { background=bg(panel); setPadding(dp(22),dp(12),dp(22),dp(18)) }
        body.addView(c,LayoutParams(-1,-2).apply { bottomMargin=dp(16) }); c.addView(text(title,23)); return c
    }
    fun selectPage(value:Int) {
        val s=model.state.value
        if(value==1 && !s.canViewReaction || value>1 && !s.canConfigure) return
        if(value==1 && page!=1) model.prepareReaction()
        if(value!=1) model.cancelReaction()
        page=value; build(); render(model.state.value)
    }
    private fun build() {
        removeAllViews(); bindings.clear(); orientation=VERTICAL; setBackgroundColor(ink); setPadding(dp(24),dp(12),dp(24),dp(12))
        val top=row(); addView(top)
        top.addView(text("꼼짝마차",28,mint),LayoutParams(0,-2,1f))
        label(top,16,amber) { if(it.demo) "시뮬레이션" else "차량 연결" }
        button(top,"시뮬레이션", "use_demo", {!it.busy}) { model.switchMode(true) }
        button(top,"차량 연결", "connect_vehicle", {!it.busy}) { connect() }
        val nav=row(); addView(nav)
        listOf("주행 케어","세이프티 핏","케어 기록","설정").forEachIndexed { i,title ->
            button(nav,title,"page_$i", { i==0 || (i==1 && it.canViewReaction) || (i>1 && it.canConfigure) }) { selectPage(i) }
        }
        label(this,15,amber) { it.message.orEmpty() }.setOnClickListener { model.message(null) }
        val scroll=ScrollView(context); body=column(); scroll.addView(body); addView(scroll,LayoutParams(-1,0,1f))
        when(page) { 0->dashboard(); 1->reaction(); 2->history(); 3->settings() }
        label(this,13,amber) { if(it.demo) "시뮬레이션 데이터 · 실제 차량에는 명령을 보내지 않습니다" else "화면 표시 중 케어 · 화면 이탈 시 제어 해제 및 복원" }
        mode=model.state.value.demo
    }
    private fun dashboard() {
        val hero=card("출발 전 체크부터 주행 케어까지")
        label(hero,30,mint) { if(it.reactionReady) "출발 전, 나의 반응을 확인해요." else if(it.snapshot.moving(it.now)) "주행에 집중하세요. 주변 환경을 살필게요." else "차량의 신호를 기다리고 있어요." }
        label(hero,16,muted) { "속도 ${it.snapshot.speedMps?.fresh(it.now)?.let { v -> (v*3.6).toInt() } ?: "—"} km/h · ${it.snapshot.gear?.fresh(it.now) ?: "기어 미확인"} · ${if(it.enabled) "케어 중" else "케어 중지"}" }
        val controls=row(); hero.addView(controls)
        button(controls,"반응 체크 시작","open_start_reaction",{it.canViewReaction&&!it.busy}) { selectPage(1); model.startReaction() }
        val toggle=button(controls,"케어 중지","toggle_care",{it.enabled||it.parked||it.demo}) { model.enable(!model.state.value.enabled) }
        bindings += { toggle.text=if(it.enabled) "케어 중지" else "케어 켜기" }
        val status=card("지금의 케어")
        label(status,19,mint) { "야간 ${if(it.plan.nightActive) "감지" else "대기"}   ·   빗길 ${if(it.plan.raining) "감지" else "대기"}   ·   터널 ${if(it.plan.tunnelActive) "감지" else "대기"}" }
        label(status,17,amber) { it.plan.alerts.joinToString("\n") { a -> "${a.title} · ${a.detail}" } }
        label(status,15,muted) { it.statuses.entries.joinToString("\n") { e -> "${e.key.label}: ${e.value}" } }
        label(status,14,muted) { it.values.entries.joinToString(" · ") { e -> "${e.key.label} ${e.value}" } }
        label(status,15,amber) { it.problems.entries.joinToString("\n") { e -> "${e.key}: ${e.value}" } }
        if(model.state.value.demo) {
            val studio=card("시나리오 스튜디오")
            DemoScenario.entries.chunked(4).forEach { group -> val r=row(); studio.addView(r)
                group.forEach { scenario -> button(r,scenario.label,"scenario_${scenario.name}") { model.scenario(scenario) } } }
            val r=row(); studio.addView(r)
            button(r,"전조등 켜기") { model.demoHeadlights(true) }; button(r,"실내 조명 수동 변경") { model.demoManualOverride() }
            button(r,"통신 실패") { model.demoFailure(true) }; button(r,"통신 복구") { model.demoFailure(false) }
        }
    }
    private fun reaction() {
        body.addView(text("오늘의 세이프티 핏",30))
        label(body,16,mint) { "${if(it.snapshot.gear?.fresh(it.now)==Gear.PARK) "P단 확인" else "P단 필요"} · ${if(it.parked) "주차 브레이크·정차 확인" else "주차 조건 확인 필요"}" }
        label(body,17,amber,"reaction_blocker") { if(it.reaction.phase in listOf(ReactionPhase.WAITING,ReactionPhase.STIMULUS)) "" else if(it.busy) "연결과 복원을 기다려 주세요" else it.snapshot.reactionStartBlocker(it.now).orEmpty() }
        val scene=AnimalCrossingScene(context,model::stimulusVisible).apply { tag="animal_crossing_scene" }
        body.addView(scene,LayoutParams(-1,dp(310))); bindings += scene::render
        label(body,19,mint,"reaction_result") { if(it.reaction.phase==ReactionPhase.COMPLETE) "${it.reaction.reactionMs} ms · ${it.reaction.message}" else it.reaction.message }
        val r=row(); body.addView(r)
        button(r,"반응 체크","start_reaction",{ !it.busy && it.snapshot.reactionStartBlocker(it.now)==null && it.reaction.phase !in listOf(ReactionPhase.WAITING,ReactionPhase.STIMULUS) }) { model.startReaction() }
        if(model.state.value.demo) {
            val brake=button(r,"시연: 브레이크 밟기","demo_brake") { model.demoBrake(model.state.value.snapshot.brake?.value!=true) }
            bindings += { brake.text=if(it.snapshot.brake?.value==true) "시연: 브레이크 떼기" else "시연: 브레이크 밟기" }
        } else label(body,19,mint,"live_brake_state") { when(it.snapshot.brake?.fresh(it.now)) { true->"차량 브레이크 · 밟힘"; false->"차량 브레이크 · 뗌"; null->"차량 브레이크 · 신호 대기" } }
        body.addView(text("동물이 나타나면 브레이크를 밟으세요. 재측정 전에는 발을 떼세요.\n반응시간은 신호 전달 지연을 포함한 개인 참고 기록이며 건강·운전 적합성을 진단하지 않습니다.",14,muted))
    }
    private fun history() {
        val s=model.state.value; historyKey=s.events.size to s.reactions.size
        val report=CareReports.summarize(s.reactions,s.events,s.demo)
        val c=card(if(s.demo) "시뮬레이션 반응 기록" else "차량 반응 기록")
        c.addView(text("최근 30일 · ${report.attempts}회 체크 · 평균 ${report.averageMs ?: "—"} ms\n전조등 안내 ${report.headlightWarnings}회",24,mint))
        val r=row(); c.addView(r)
        button(r,"기록 저장") { export(false) }; button(r,"CSV 저장") { export(true) }
        button(r,"리포트 공유") {
            if(model.state.value.canConfigure) runCatching { activity.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND)
                .setType("text/plain").putExtra(Intent.EXTRA_TEXT,model.shareReport()),"30일 리포트 공유")) }.onFailure { model.message("공유할 앱이 없습니다") }
        }
        button(r,"기록 삭제") { AlertDialog.Builder(context).setTitle("현재 모드의 기록을 삭제할까요?")
            .setMessage("차량 설정 복원 기록은 유지합니다.").setNegativeButton("취소",null)
            .setPositiveButton("삭제") { _,_->model.clearHistory(); build(); render(model.state.value) }.show() }
        val daily=card("일별 반응 평균")
        report.days.filter { it.averageMs!=null }.forEach { daily.addView(text("${it.day}   ${it.averageMs} ms   (${it.attempts}회)",16,muted)) }
        s.reactions.filter { it.demo==s.demo }.takeLast(10).reversed().forEach { c.addView(text("${time(it.atMs)}   ${it.durationMs?.let { v -> "$v ms" } ?: "응답 없음"}   ${if(it.delayed) "알림 강화" else "기록 완료"}",16,muted)) }
        val timeline=card("케어 타임라인")
        s.events.filter { it.demo==s.demo }.takeLast(50).reversed().forEach { timeline.addView(text("${time(it.atMs)} · ${it.title}\n${it.detail}",16,muted)) }
    }
    private fun settings() {
        val c=card("나에게 맞는 케어")
        fun toggle(title:String,get:(CareSettings)->Boolean,set:(CareSettings,Boolean)->CareSettings) {
            val v=Switch(context).apply { text=title; textSize=20f; setTextColor(Color.WHITE); isChecked=get(model.state.value.settings); setPadding(0,dp(10),0,dp(10)) }
            c.addView(v); v.setOnCheckedChangeListener { _,checked -> model.settings(set(model.state.value.settings,checked)) }
            bindings += { v.isEnabled=it.canConfigure }
        }
        toggle("야간 시인성",{it.night},{s,b->s.copy(night=b)}); toggle("빗길 시야 확보",{it.rain},{s,b->s.copy(rain=b)})
        toggle("터널 케어",{it.tunnel},{s,b->s.copy(tunnel=b)}); toggle("한국어 음성 안내",{it.voice},{s,b->s.copy(voice=b)})
        label(c,16,muted) { it.voiceStatus }
        val r=row(); c.addView(r); button(r,"음성 다시 연결") { model.retryVoice() }; button(r,"음성 테스트") { model.testVoice() }
        val glass=card("스마트 글래스 · 차량별 설정")
        glass.addView(text("실제 출구·역광 입력 연동 대기. 시뮬레이션에서는 2단계로 동작합니다.",16,amber))
        if(!model.state.value.demo) {
            val input=EditText(context).apply { hint="검증된 단계"; inputType=android.text.InputType.TYPE_CLASS_NUMBER; setTextColor(Color.WHITE) }; glass.addView(input)
            val check=CheckBox(context).apply { text="이 차량에서 해당 단계의 주행 사용을 확인했습니다"; setTextColor(Color.WHITE) }; glass.addView(check)
            button(glass,"현재 연결에 적용",enabled={it.canConfigure}) {
                val s=model.state.value; val n=input.text.toString().toIntOrNull()
                val caps=listOfNotNull(s.capabilities[Control.GLASS_FRONT],s.capabilities[Control.GLASS_ROOF]).filter { it.supported }
                if(check.isChecked && n!=null && caps.isNotEmpty() && caps.all { it.accepts(n) }) model.settings(s.settings.copy(glassLevel=n)) else model.message("지원 범위와 주행 사용 확인이 필요합니다")
            }
            button(glass,"감광 설정 해제") { model.settings(model.state.value.settings.copy(glassLevel=null)) }
        }
        val caps=card("차량 지원 기능")
        Control.entries.forEach { control -> label(caps,16,muted) { val cap=it.capabilities[control]
            "${control.label}: ${if(cap==null) "확인 중" else if(cap.supported) "지원 ${cap.min}~${cap.max}" else cap.reason}" } }
        button(caps,"지원 기능 다시 확인") { model.refreshConnection() }
    }
    fun render(s:CareUiState) {
        if(mode==null || mode!=s.demo) build()
        if(page==1 && !s.canViewReaction || page>1 && !s.canConfigure) { page=0; model.cancelReaction(); build() }
        if(page==2 && historyKey!=(s.events.size to s.reactions.size)) build()
        bindings.toList().forEach { it(s) }
        val urgent=s.plan.alerts.firstOrNull { it.urgent }.takeIf { s.enabled&&s.foreground }
        if(urgent==null) { dismissedWarning=null; warning?.dismiss(); warning=null }
        else if(urgent.id!=dismissedWarning && warning==null) {
            warning=AlertDialog.Builder(context).setTitle(urgent.title).setMessage(urgent.detail)
                .setPositiveButton(if(s.demo) "시연: 전조등 켜기" else "확인") { _,_-> if(s.demo) model.demoHeadlights(true); dismissedWarning=urgent.id; warning=null }
                .setNegativeButton("확인 · 주행 화면") { _,_->dismissedWarning=urgent.id; warning=null }.setCancelable(false).show()
        }
        if(s.startupPrompt && s.reactionReady && s.foreground && startup==null) {
            startup=AlertDialog.Builder(context).setTitle("출발 전 반응을 확인할까요?").setMessage("정차 조건이 확인됐습니다.")
                .setPositiveButton("반응 체크 열기") { _,_->model.dismissStartup(); startup=null; selectPage(1) }
                .setNegativeButton("나중에") { _,_->model.dismissStartup(); startup=null }.setCancelable(false).show()
        } else if(!s.reactionReady || !s.foreground) { startup?.dismiss(); startup=null }
    }
    fun dismissDialogs() { warning?.dismiss(); warning=null; startup?.dismiss(); startup=null }
    private fun time(at:Long)=SimpleDateFormat("MM.dd HH:mm",Locale.KOREA).format(Date(at))
}
