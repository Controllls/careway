package com.example.careway.ui

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.app.Activity
import android.app.AlertDialog
import android.app.Dialog
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.*
import com.example.careway.*
import com.example.careway.core.*
import com.example.careway.data.CareReports
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
    private var warning: Dialog?=null
    private var startup: AlertDialog?=null
    private var dismissedWarning: String?=null
    private fun dp(n:Int)=(n*resources.displayMetrics.density).toInt()
    private fun bg(color:Int)=GradientDrawable().apply { setColor(color); cornerRadius=dp(20).toFloat() }
    private fun column()=LinearLayout(context).apply { orientation=VERTICAL }
    private fun row()=LinearLayout(context).apply { orientation=HORIZONTAL; gravity=Gravity.CENTER_VERTICAL }
    private fun text(value:String,size:Int=17,color:Int=Color.WHITE)=TextView(context).apply {
        text=value; textSize=size.toFloat(); setTextColor(color); setPadding(0,dp(6),0,dp(6))
        if(size>=25) setTypeface(null,Typeface.BOLD)
    }
    /** Bound text. An empty value collapses the view so unused banners take no space. */
    private fun label(parent:LinearLayout,size:Int=17,color:Int=Color.WHITE,tagName:String?=null,collapse:Boolean=true, get:(CareUiState)->String):TextView {
        val v=text("",size,color); v.tag=tagName; parent.addView(v)
        bindings += { s -> val next=get(s); if(v.text.toString()!=next) v.text=next
            v.visibility=if(next.isEmpty() && collapse) View.GONE else View.VISIBLE }
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
        if(value==4 && !BuildConfig.DEMO_TOOLS) return
        if(value==1 && page!=1) model.prepareReaction()
        if(value!=1) model.cancelReaction()
        page=value; build(); render(model.state.value)
    }
    private fun build() {
        removeAllViews(); bindings.clear(); orientation=VERTICAL; setBackgroundColor(ink); setPadding(dp(24),dp(12),dp(24),dp(12))
        val top=row(); addView(top)
        // No menu row: every page is reached from the home shortcuts, and "홈" brings the driver back.
        if(page!=0) button(top,"홈", "go_home") { selectPage(0) }
        top.addView(text("꼼짝마차",28,mint),LayoutParams(0,-2,1f))
        if(BuildConfig.DEMO_TOOLS) {
            label(top,16,amber) { if(it.demo) "시뮬레이션" else "차량 연결" }
            button(top,"시뮬레이션", "use_demo", {!it.busy}) { model.switchMode(true) }
            button(top,"차량 연결", "connect_vehicle", {!it.busy}) { connect() }
        }
        label(this,15,amber) { it.message.orEmpty() }.setOnClickListener { model.message(null) }
        label(this,16,amber,"situation_banner") { if(it.situation.active) "상황 유발 중 · ${it.situation.describe(it.now)}" else "" }
        label(this,21,amber,"care_banner") { if(it.enabled) it.plan.alerts.firstOrNull()?.let { a -> "${a.title} · ${a.detail}" }.orEmpty() else "" }
        val scroll=ScrollView(context); body=column(); scroll.addView(body); addView(scroll,LayoutParams(-1,0,1f))
        when(page) { 0->dashboard(); 1->reaction(); 2->history(); 3->settings(); 4->scenarios() }
        label(this,13,amber) { if(it.demo) "시뮬레이션 · 가상 차량에서 기능이 동작합니다. 실제 차량에는 명령을 보내지 않습니다" else "화면이 표시되는 동안 케어합니다 · 화면을 벗어나면 제어를 멈추고 설정을 복원합니다" }
        mode=model.state.value.demo
    }
    private fun kph(s:CareUiState)=s.snapshot.speedMps?.fresh(s.now)?.let { (it*3.6f+.5f).toInt().toString() } ?: "—"
    /** level: 0 off, 1 watching, 2 working, 3 warning */
    private class Service(val level:Int,val status:String,val detail:String)
    private val levelBg=intArrayOf(Color.rgb(20,32,41),Color.rgb(38,60,75),Color.rgb(24,78,62),Color.rgb(132,70,8))
    private val levelFg=intArrayOf(Color.rgb(112,130,140),Color.rgb(165,233,192),Color.rgb(190,255,214),Color.rgb(255,206,140))
    private fun stopped(s:CareUiState,on:Boolean)=when {
        !on -> Service(0,"꺼짐","설정에서 켤 수 있어요")
        !s.enabled -> Service(0,"케어 중지","케어를 켜면 동작합니다")
        else -> null
    }
    private fun beam(s:CareUiState)=when(s.snapshot.lowBeam?.fresh(s.now)) { true->"전조등 켜짐"; false->"전조등 꺼짐"; null->"전조등 미확인" }
    private fun wiper(s:CareUiState):String { val level=s.snapshot.wiperLevel?.fresh(s.now) ?: return "와이퍼 신호 대기"
        return "와이퍼 "+when { level=="OFF"->"꺼짐"; level=="AUTO"->"자동"; level.startsWith("CONTINUOUS_LEVEL_")->"연속 ${level.takeLast(1)}단"; else->level } }
    private val measuring=listOf(ReactionPhase.WAITING,ReactionPhase.STIMULUS,ReactionPhase.REST)
    private fun safetyService(s:CareUiState):Service { val last=s.reactions.lastOrNull { it.demo==s.demo }?.durationMs
        val average=s.reaction.reactionMs?.let { "평균 ${seconds(it)}초" }.orEmpty()
        return when {
            s.reaction.phase in measuring -> Service(2,"측정 중 ${s.reaction.trial}/3",s.reaction.message)
            s.reaction.phase==ReactionPhase.COMPLETE && s.reaction.condition==Condition.NORMAL -> Service(2,"컨디션 정상","$average · 눌러서 다시 확인")
            s.reaction.phase==ReactionPhase.COMPLETE && s.reaction.condition==Condition.CAUTION -> Service(3,"컨디션 주의","$average · 여유를 두고 운전하세요")
            s.reaction.phase==ReactionPhase.COMPLETE -> Service(3,"컨디션 느림","$average · 전방 주의 알림 강화 중")
            s.enhanced -> Service(3,"전방 주의 강화 중","반응이 기록되지 않아 경고 안내 간격을 줄였어요")
            s.reactionReady -> Service(1,"체크 가능",if(last!=null) "최근 평균 ${seconds(last)}초 · 눌러서 다시 확인" else "정차 중 · 눌러서 반응을 3번 확인하세요")
            s.snapshot.moving(s.now) -> Service(0,"주행 중에는 쉬어요",if(last!=null) "최근 평균 ${seconds(last)}초" else "정차하면 확인할 수 있어요")
            else -> Service(0,"조건 확인 중",s.snapshot.reactionStartBlocker(s.now) ?: "차량 신호를 기다리고 있어요")
        } }
    private fun nightService(s:CareUiState):Service=stopped(s,s.settings.night) ?: when {
        s.plan.alerts.any { it.id=="headlights" } -> Service(3,"전조등 경고 중","화면과 음성으로 알리고 있어요")
        s.plan.nightActive -> Service(2,"야간 케어 중","실내 조명 감광 · ${beam(s)}")
        else -> Service(1,"감시 중","${when(s.snapshot.dark?.fresh(s.now)) { false->"주간"; true->"야간"; null->"조도 신호 대기" }} · ${beam(s)}")
    }
    private fun rainService(s:CareUiState):Service=stopped(s,s.settings.rain) ?: when {
        s.plan.raining -> Service(2,if(s.snapshot.rain?.fresh(s.now)==true) "빗길 케어 중" else "비 그침 · 곧 복원","김서림 제거 · 외기 유입 · 미러 열선 · 조명 감광")
        else -> Service(1,"감시 중",wiper(s))
    }
    private fun tunnelService(s:CareUiState):Service=stopped(s,s.settings.tunnel) ?: run {
        val t=s.snapshot.tunnel?.fresh(s.now,10_000); val near=t?.phase==TunnelPhase.APPROACH
        val distance="터널 입구 ${t?.distanceM?.toInt() ?: 0}m 전"
        when {
            s.plan.tunnelActive && s.plan.raining -> Service(2,"터널 케어 중","빗길 우선 · 외기 유입 유지")
            s.plan.tunnelActive -> Service(2,"터널 케어 중",if(near) "$distance · 내기 순환" else "내기 순환 유지 · 곧 복원")
            else -> Service(1,"감시 중",when { near->distance; t!=null->"터널 안내 없음"; else->"내비 안내 대기" })
        } }
    private fun voiceService(s:CareUiState):Service { val v=s.voiceStatus
        return when {
            !s.settings.voice -> Service(0,"꺼짐","설정에서 켤 수 있어요")
            "합성 시작" in v || "요청 전송" in v -> Service(2,"안내 중",v)
            "준비됨" in v || "완료" in v -> Service(1,"준비됨",v)
            "준비 중" in v -> Service(0,"준비 중",v)
            else -> Service(0,"사용 불가",v)
        } }
    private fun service(parent:LinearLayout,kind:CareIcon.Kind,title:String,tagName:String,click:(()->Unit)?=null,get:(CareUiState)->Service) {
        val shape=bg(levelBg[1])
        val tile=column().apply { background=shape; setPadding(dp(22),dp(20),dp(22),dp(18)); tag=tagName
            if(click!=null) setOnClickListener { click() } }
        val icon=CareIcon(context,kind); tile.addView(icon,LayoutParams(dp(92),dp(92)))
        tile.addView(text(title,23).apply { setTypeface(null,Typeface.BOLD) })
        val status=text("",22,mint); tile.addView(status)
        val detail=text("",16,muted).apply { minLines=2; maxLines=2 }; tile.addView(detail)
        parent.addView(tile,LayoutParams(0,LayoutParams.MATCH_PARENT,1f).apply { setMargins(0,0,dp(12),0) })
        var level=-1
        bindings += { s -> val v=get(s)
            if(v.level!=level) { level=v.level; shape.setColor(levelBg[level]); icon.tint=levelFg[level]; status.setTextColor(levelFg[level]) }
            if(status.text.toString()!=v.status) status.text=v.status
            if(detail.text.toString()!=v.detail) detail.text=v.detail
            tile.contentDescription="$title · ${v.status}" }
    }
    /** One vehicle setting the care services may change: lit while a service is asking for it. */
    private fun chip(parent:LinearLayout,kind:CareIcon.Kind,c:Control,title:String,action:(Int)->String) {
        val shape=bg(levelBg[1]).apply { cornerRadius=dp(14).toFloat() }
        val chip=row().apply { background=shape; setPadding(dp(14),dp(12),dp(14),dp(12)); tag="control_${c.name}" }
        val icon=CareIcon(context,kind); chip.addView(icon,LayoutParams(dp(44),dp(44)).apply { rightMargin=dp(12) })
        val texts=column(); chip.addView(texts,LayoutParams(0,-2,1f))
        texts.addView(text(title,17).apply { setPadding(0,0,0,0) })
        val state=text("",15,muted).apply { setPadding(0,dp(3),0,0); maxLines=1 }; texts.addView(state)
        parent.addView(chip,LayoutParams(0,-2,1f).apply { setMargins(0,dp(6),dp(10),dp(6)) })
        var level=-1
        bindings += { s -> val cap=s.capabilities[c]; val wanted=s.plan.desired[c].takeIf { s.enabled }
            val short=s.statuses[c]?.substringBefore(" · ")
            val (next,line)=when {
                cap!=null && !cap.supported -> 0 to "이 차량 미지원"
                wanted!=null -> (if(short=="제어 보류") 3 else 2) to "${action(wanted)} · ${short ?: "요청 준비"}"
                else -> 1 to (short ?: "대기")
            }
            if(next!=level) { level=next; shape.setColor(levelBg[level]); icon.tint=levelFg[level]; state.setTextColor(if(level==1) muted else levelFg[level]) }
            if(state.text.toString()!=line) state.text=line }
    }
    private fun dashboard() {
        val services=card("지금 동작 중인 케어 서비스")
        label(services,16,muted,"home_summary") { "속도 ${kph(it)} km/h · ${it.snapshot.gear?.fresh(it.now) ?: "기어 미확인"} · ${if(it.enabled) "케어 켜짐" else "케어 중지 · 켜면 아래 서비스가 동작합니다"}" }
        val tiles=row(); services.addView(tiles,LayoutParams(-1,-2).apply { topMargin=dp(8) })
        service(tiles,CareIcon.Kind.PEDAL,"세이프티 핏","service_safety",{ selectPage(1) },::safetyService)
        service(tiles,CareIcon.Kind.HEADLIGHT,"야간 시인성","service_night",null,::nightService)
        service(tiles,CareIcon.Kind.RAIN,"빗길 시야","service_rain",null,::rainService)
        service(tiles,CareIcon.Kind.TUNNEL,"터널 공조","service_tunnel",null,::tunnelService)
        service(tiles,CareIcon.Kind.VOICE,"음성 안내","service_voice",null,::voiceService)
        val vehicle=card("차량 제어 현황")
        vehicle.addView(text("케어 서비스가 차량에 요청한 설정입니다. 상황이 끝나면 원래 값으로 되돌립니다.",14,muted))
        val chips=row(); vehicle.addView(chips)
        chip(chips,CareIcon.Kind.FAN,Control.HVAC_POWER,"공조 전원") { "켜기" }
        chip(chips,CareIcon.Kind.DEFROST,Control.DEFROST,"김서림 제거") { "켜기" }
        chip(chips,CareIcon.Kind.AIR,Control.RECIRCULATION,"내·외기") { if(it==1) "내기 순환" else "외기 유입" }
        chip(chips,CareIcon.Kind.MIRROR,Control.MIRROR_LEFT,"왼쪽 미러 열선") { "켜기" }
        chip(chips,CareIcon.Kind.MIRROR,Control.MIRROR_RIGHT,"오른쪽 미러 열선") { "켜기" }
        chip(chips,CareIcon.Kind.LAMP,Control.AMBIENT,"실내 조명") { "감광" }
        label(vehicle,15,amber) { it.problems.entries.joinToString("\n") { e -> "${e.key}: ${e.value}" } }
        val actions=card("바로 가기")
        val controls=row(); actions.addView(controls)
        button(controls,"반응 체크","open_start_reaction",{it.canViewReaction&&!it.busy}) { selectPage(1); model.startReaction() }
        val toggle=button(controls,"케어 중지","toggle_care",{!it.busy}) { model.enable(!model.state.value.enabled) }
        bindings += { toggle.text=if(it.enabled) "케어 중지" else "케어 켜기" }
        button(controls,"설정","open_settings",{it.canConfigure}) { selectPage(3) }
        if(BuildConfig.DEMO_TOOLS) button(controls,"시나리오 시연","open_scenarios",{it.canConfigure}) { selectPage(4) }
    }
    private fun trigger(parent:LinearLayout,t:Trigger)=button(parent,t.label,"trigger_${t.name}",{!it.busy}) { model.trigger(t) }
    private fun step(parent:LinearLayout,tagName:String?=null,get:(CareUiState)->Pair<Boolean,String>) {
        val v=text("",16,muted); v.tag=tagName; parent.addView(v)
        bindings += { s -> val (on,line)=get(s); val next=(if(on) "● " else "○ ")+line
            if(v.text.toString()!=next) v.text=next
            v.setTextColor(if(on) mint else muted) }
    }
    /** One line per vehicle control: what the plan wants and what the read-back reported. */
    private fun control(s:CareUiState,c:Control,action:String,target:Int?=null):Pair<Boolean,String> {
        val wanted=s.plan.desired[c].takeIf { target==null || it==target }
        val status=s.statuses[c]
        val value=s.values[c]?.let { " (현재 값 $it)" }.orEmpty()
        val cap=s.capabilities[c]
        if(cap!=null && !cap.supported) return false to "${c.label} · 이 차량에서 지원하지 않아 안내만 제공합니다"
        return (wanted!=null) to when {
            wanted!=null && !s.enabled -> "${c.label} → $action · 케어가 꺼져 있어 요청하지 않습니다"
            wanted!=null -> "${c.label} → $action · ${status ?: "요청 준비 중"}$value"
            status!=null -> "${c.label} · $status$value"
            else -> "${c.label} · 대기$value"
        }
    }
    private fun scenarios() {
        val intro=card("시나리오 상황 유발")
        intro.addView(text("버튼은 주행 상황(차속·야간·전조등·와이퍼·터널 안내)만 유발합니다. 이후의 감지, 경고 화면, 음성 안내, 차량 제어와 복원은 앱의 기능이 그대로 실행됩니다. 브레이크 페달과 정차 인터락은 유발하지 않고 차량 신호만 사용합니다.",15,muted))
        label(intro,18,amber,"situation_now") { if(it.situation.active) "현재 상황 · ${it.situation.describe(it.now)}" else "현재 상황 · 유발 없음 (차량 신호 사용)" }
        label(intro,15,amber) { if(!it.enabled) "케어가 꺼져 있습니다. 상황을 유발하면 케어를 함께 켭니다." else "" }
        val reset=row(); intro.addView(reset); trigger(reset,Trigger.RESET)

        val s1=card("시나리오 1 · 출발 전 안전 진단")
        s1.addView(text("시동 직후 P단 정차 → ‘오늘의 반응성 체크’ 팝업 → 브레이크 페달로 3번 측정 → 평균으로 컨디션 판정(0.5초 이하 정상, 1초 이상 느림) → 느리면 음성 안내와 환기",15,muted))
        val r1=row(); s1.addView(r1); trigger(r1,Trigger.IGNITION)
        step(s1,"step_interlock") { s -> val blocker=s.snapshot.reactionStartBlocker(s.now)
            (blocker==null) to "정차 인터락 · ${blocker ?: "P단 · 차속 0 · 화면 조작 허용 · 브레이크 페달 이벤트 확인"}" }
        step(s1,"step_reaction") { s -> (s.reaction.phase==ReactionPhase.COMPLETE) to "브레이크 반응 · ${s.reaction.message}" }
        step(s1) { s -> s.enhanced to if(s.enhanced) "전방 주의 알림 강화 중 · 경고 음성 간격을 줄였습니다" else "반응이 지연되면 전방 주의 알림을 강화합니다" }
        step(s1) { s -> control(s,Control.HVAC_POWER,"환기 가동") }

        val s2=card("시나리오 2 · 스텔스 주행 방지")
        s2.addView(text("야간 도로에서 전조등을 켜지 않고 주행 → 화면 전체 주황 경고 + “전조등을 켜주세요” 음성 → 실내 조명 감광",15,muted))
        val r2=row(); s2.addView(r2); trigger(r2,Trigger.NIGHT_DRIVE); trigger(r2,Trigger.HEADLIGHT_ON)
        step(s2,"step_stealth") { s -> val dark=s.snapshot.dark?.fresh(s.now); val beam=s.snapshot.lowBeam?.fresh(s.now)
            (s.snapshot.moving(s.now) && dark==true && beam==false) to "감지 · 차속 ${kph(s)} km/h · ${
                when(dark) { true->"야간"; false->"주간"; null->"조도 미확인" }} · 하향등 ${
                when(beam) { true->"켜짐"; false->"꺼짐"; null->"미확인" }}" }
        step(s2,"step_headlight_alert") { s -> val on=s.plan.alerts.any { it.id=="headlights" }
            on to if(on) "경고 · 화면 전체 주황 경고 표시 + 음성 “전조등을 켜주세요”" else "경고 · 주행 중 하향등이 꺼져 있으면 화면 전체 경고와 음성으로 알립니다" }
        step(s2) { s -> control(s,Control.AMBIENT,"최소 밝기") }
        step(s2) { s -> s.settings.voice to "음성 · ${if(s.settings.voice) s.voiceStatus else "설정에서 꺼져 있습니다"}" }

        val s3=card("시나리오 3 · 빗길 시야 확보 오토 튠")
        s3.addView(text("와이퍼 연속 작동 감지 → 김서림 제거, 외기 유입, 양쪽 미러 열선, 실내 조명 감광 → 비가 그치면 10초 뒤 원래 설정 복원",15,muted))
        val r3=row(); s3.addView(r3); trigger(r3,Trigger.RAIN_START); trigger(r3,Trigger.RAIN_STOP)
        step(s3,"step_rain") { s -> s.plan.raining to "감지 · 와이퍼 ${s.snapshot.wiperLevel?.fresh(s.now) ?: "미확인"} → ${
            if(!s.plan.raining) "대기" else if(s.snapshot.rain?.fresh(s.now)==true) "빗길 주행 판정" else "비 그침 · 10초 유지 후 복원"}" }
        step(s3) { s -> control(s,Control.HVAC_POWER,"켜기") }
        step(s3) { s -> control(s,Control.DEFROST,"켜기") }
        step(s3) { s -> control(s,Control.RECIRCULATION,"외기 유입으로 전환",0) }
        step(s3) { s -> control(s,Control.MIRROR_LEFT,"켜기") }
        step(s3) { s -> control(s,Control.MIRROR_RIGHT,"켜기") }
        step(s3) { s -> control(s,Control.AMBIENT,"최소 밝기") }

        val s4=card("시나리오 4 · 터널 진입 공조 케어")
        s4.addView(text("내비 안내의 터널 입구 거리 확인 → 입구 100m 전 내기 순환 → 입구를 지난 뒤 30초 유지하고 원래 설정 복원. 시속 80km로 입구 250m 전부터 자동 진행합니다.",15,muted))
        val r4=row(); s4.addView(r4); trigger(r4,Trigger.TUNNEL_DRIVE)
        step(s4,"step_tunnel") { s -> val t=s.snapshot.tunnel?.fresh(s.now,10_000)
            val near=t?.phase==TunnelPhase.APPROACH
            val guide=when(t?.phase) { TunnelPhase.APPROACH->"입구 ${t.distanceM?.toInt() ?: 0}m 전"; TunnelPhase.CLEAR->"없음"; null->"수신 대기" }
            s.plan.tunnelActive to "감지 · 내비 터널 안내 $guide${
                if(!s.plan.tunnelActive) (if(near) " · 100m 전부터 케어" else "") else if(near) " → 터널 케어" else " → 입구 통과 · 30초 유지 후 복원"}" }
        step(s4) { s -> control(s,Control.RECIRCULATION,"내기 순환으로 전환",1) }

        if(model.state.value.demo) {
            val virtual=card("가상 차량 조작")
            virtual.addView(text("운전자 수동 조작 우선과 통신 실패 처리를 확인합니다.",15,muted))
            val r=row(); virtual.addView(r)
            button(r,"실내 조명 수동 변경") { model.demoManualOverride() }
            button(r,"통신 실패") { model.demoFailure(true) }; button(r,"통신 복구") { model.demoFailure(false) }
        }
    }
    private fun reaction() {
        // Nothing on this page may move while a check is running: every line keeps its place.
        body.addView(text("오늘의 세이프티 핏",30))
        label(body,16,mint) { "${if(it.snapshot.gear?.fresh(it.now)==Gear.PARK) "P단 확인" else "P단 필요"} · ${if(it.snapshot.stationaryInPark(it.now)) "정차 확인" else "정차 확인 필요"}" }
        label(body,17,amber,"reaction_blocker",collapse=false) { if(it.reaction.phase in measuring) "" else if(it.busy) "연결과 복원을 기다려 주세요" else it.snapshot.reactionStartBlocker(it.now).orEmpty() }
        val scene=AnimalCrossingScene(context,model::stimulusVisible).apply { tag="animal_crossing_scene" }
        body.addView(scene,LayoutParams(-1,dp(310))); bindings += scene::render
        val r=row(); body.addView(r)
        button(r,"반응 체크","start_reaction",{ !it.busy && it.snapshot.reactionStartBlocker(it.now)==null && it.reaction.phase !in measuring }) { model.startReaction() }
        if(model.state.value.demo) {
            val brake=button(r,"시연: 브레이크 밟기","demo_brake") { model.demoBrake(model.state.value.snapshot.brake?.value!=true) }
            bindings += { brake.text=if(it.snapshot.brake?.value==true) "시연: 브레이크 떼기" else "시연: 브레이크 밟기" }
        } else label(r,19,mint,"live_brake_state") { when(it.snapshot.brake?.fresh(it.now)) { true->"차량 브레이크 · 밟힘"; false->"차량 브레이크 · 뗌"; null->"차량 브레이크 · 신호 대기" } }
        label(body,21,mint,"reaction_trials") { s -> (1..3).joinToString("        ") { i -> "${i}회 ${s.reaction.samples.getOrNull(i-1)?.let { v -> seconds(v)+"초" } ?: "—"}" } }
        label(body,19,mint,"reaction_result") { it.reaction.message }
        val verdict=text("",38,mint).apply { tag="reaction_condition" }; body.addView(verdict)
        bindings += { s -> val c=s.reaction.condition.takeIf { s.reaction.phase==ReactionPhase.COMPLETE }
            val next=if(c!=null) "오늘의 컨디션 · ${c.label}   (평균 ${seconds(s.reaction.reactionMs ?: 0)}초)" else ""
            if(verdict.text.toString()!=next) verdict.text=next
            verdict.visibility=if(next.isEmpty()) View.INVISIBLE else View.VISIBLE
            verdict.setTextColor(when(c) { Condition.CAUTION->amber; Condition.SLOW->Color.rgb(255,138,0); else->mint }) }
        label(body,16,amber) { control(it,Control.HVAC_POWER,"환기 가동").let { (on,line) -> if(on) line else "" } }
        body.addView(text("동물이 나타날 때마다 브레이크를 밟으세요. 3번 측정한 평균으로 컨디션을 보여 줍니다(0.5초 이하 정상, 1초 이상 느림). 밟은 뒤에는 발을 떼야 다음 측정이 시작됩니다.\n반응시간은 신호 전달 지연을 포함한 개인 참고 기록이며 건강·운전 적합성을 진단하지 않습니다.",14,muted))
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
        s.reactions.filter { it.demo==s.demo }.takeLast(10).reversed().forEach { c.addView(text("${time(it.atMs)}   ${it.durationMs?.let { v -> "평균 ${seconds(v)}초 · 컨디션 ${Condition.of(v).label}" } ?: "응답 없음"}   ${if(it.delayed) "알림 강화" else "기록 완료"}",16,muted)) }
        val timeline=card("케어 타임라인")
        s.events.filter { it.demo==s.demo }.takeLast(50).reversed().forEach { timeline.addView(text("${time(it.atMs)} · ${it.title}\n${it.detail}",16,muted)) }
    }
    private fun settings() {
        val records=card("케어 기록")
        records.addView(text("최근 30일 반응 체크 평균과 전조등 안내 횟수를 확인하고 저장·공유합니다.",15,muted))
        button(records,"케어 기록 보기","open_history",{it.canConfigure}) { selectPage(2) }
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
        val caps=card("차량 지원 기능")
        Control.entries.forEach { control -> label(caps,16,muted) { val cap=it.capabilities[control]
            "${control.label}: ${if(cap==null) "확인 중" else if(cap.supported) "지원 ${cap.min}~${cap.max}" else cap.reason}" } }
        button(caps,"지원 기능 다시 확인") { model.refreshConnection() }
    }
    /** Planning document: a high-contrast orange card that fills the screen and blinks. */
    private fun showWarning(alert:CareAlert,s:CareUiState):Dialog {
        val dialog=Dialog(context,android.R.style.Theme_Material_NoActionBar_Fullscreen)
        val root=column().apply { gravity=Gravity.CENTER; setBackgroundColor(Color.BLACK); setPadding(dp(28),dp(28),dp(28),dp(28)); tag="headlight_warning" }
        val sign=column().apply { gravity=Gravity.CENTER; background=bg(Color.rgb(255,138,0)); setPadding(dp(40),dp(28),dp(40),dp(28)) }
        root.addView(sign,LayoutParams(-1,0,1f))
        fun line(value:String,size:Int,bold:Boolean)=TextView(context).apply { text=value; textSize=size.toFloat(); setTextColor(ink)
            gravity=Gravity.CENTER; if(bold) setTypeface(null,Typeface.BOLD); setPadding(0,dp(8),0,dp(8)) }
        sign.addView(line("⚠",72,true)); sign.addView(line(alert.title,56,true)); sign.addView(line(alert.detail,26,false))
        val blink=ObjectAnimator.ofFloat(sign,"alpha",1f,.55f).apply { duration=450; repeatMode=ValueAnimator.REVERSE; repeatCount=ValueAnimator.INFINITE; start() }
        val actions=row().apply { gravity=Gravity.CENTER }; root.addView(actions,LayoutParams(-1,-2).apply { topMargin=dp(16) })
        fun action(title:String,tagName:String,click:()->Unit) {
            actions.addView(Button(context).apply { text=title; isAllCaps=false; textSize=20f; tag=tagName
                setTextColor(ink); background=bg(mint); minHeight=dp(64); setPadding(dp(28),dp(10),dp(28),dp(10))
                setOnClickListener { click(); dismissedWarning=alert.id; warning=null; dialog.dismiss(); render(model.state.value) } },
                LayoutParams(-2,-2).apply { setMargins(dp(10),0,dp(10),0) })
        }
        if(s.situation.active) action("전조등 켜기","warning_headlight_on") { model.trigger(Trigger.HEADLIGHT_ON) }
        else if(s.demo) action("시연: 전조등 켜기","warning_headlight_on") { model.demoHeadlights(true) }
        action("확인 · 주행 화면","warning_confirm") {}
        dialog.setContentView(root); dialog.setCancelable(false); dialog.setOnDismissListener { blink.cancel() }
        dialog.window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        dialog.show(); return dialog
    }
    val warningView: View? get()=warning?.findViewById<View>(android.R.id.content)
    fun render(s:CareUiState) {
        if(mode==null || mode!=s.demo) build()
        if(page==1 && !s.canViewReaction || page>1 && !s.canConfigure) { page=0; model.cancelReaction(); build() }
        if(page==2 && historyKey!=(s.events.size to s.reactions.size)) build()
        bindings.toList().forEach { it(s) }
        val urgent=s.plan.alerts.firstOrNull { it.urgent }.takeIf { s.enabled&&s.foreground }
        if(urgent==null) { dismissedWarning=null; warning?.dismiss(); warning=null }
        else if(urgent.id!=dismissedWarning && warning==null) warning=showWarning(urgent,s)
        if(s.startupPrompt && s.reactionReady && s.foreground && startup==null) {
            startup=AlertDialog.Builder(context).setTitle("오늘의 반응성 체크").setMessage("정차 조건이 확인됐습니다. 화면에 동물이 나타나면 브레이크 페달을 밟아 주세요.")
                .setPositiveButton("지금 시작") { _,_->model.dismissStartup(); startup=null; selectPage(1); model.startReaction(); render(model.state.value) }
                .setNegativeButton("나중에") { _,_->model.dismissStartup(); startup=null }.setCancelable(false).show()
        } else if(!s.reactionReady || !s.foreground) { startup?.dismiss(); startup=null }
    }
    val startupDialog: AlertDialog? get()=startup
    fun dismissDialogs() { warning?.dismiss(); warning=null; startup?.dismiss(); startup=null }
    private fun time(at:Long)=SimpleDateFormat("MM.dd HH:mm",Locale.KOREA).format(Date(at))
}
