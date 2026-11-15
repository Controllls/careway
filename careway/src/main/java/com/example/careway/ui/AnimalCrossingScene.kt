package com.example.careway.ui

import android.content.Context
import android.graphics.*
import android.os.SystemClock
import android.view.View
import com.example.careway.CareUiState
import com.example.careway.core.ReactionPhase

class AnimalCrossingScene(context: Context, private val visible: (Long) -> Unit) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var state = CareUiState()
    private var acknowledged = false
    private var appearedAt = 0L
    fun render(value: CareUiState) {
        if (value.reaction.phase !in listOf(ReactionPhase.WAITING,ReactionPhase.STIMULUS)) acknowledged = false
        if (!value.showStimulus && value.reaction.phase == ReactionPhase.WAITING) { acknowledged = false; appearedAt = 0 }
        state = value
        contentDescription = if (value.showStimulus || value.reaction.phase == ReactionPhase.COMPLETE) "동물 표시" else "동물 대기"
        invalidate()
    }
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w=width.toFloat(); val h=height.toFloat()
        fun color(v: Long) { paint.color=v.toInt(); paint.style=Paint.Style.FILL }
        color(0xFFDCEBE3); canvas.drawRoundRect(0f,0f,w,h,24f,24f,paint)
        color(0xFF93B29B); canvas.drawRect(0f,h*.30f,w,h,paint)
        color(0xFF53696A)
        canvas.drawPath(Path().apply { moveTo(w*.45f,h*.3f); lineTo(w*.55f,h*.3f); lineTo(w*.9f,h); lineTo(w*.1f,h); close() },paint)
        color(0xFFF2E7C1); paint.strokeWidth=4f
        for (i in 0..4) { val y=h*(.35f+i*.14f); canvas.drawLine(w*.5f,y,w*.5f,y+h*.05f,paint) }
        for (i in 0..7) {
            val x=if(i%2==0) w*(.04f+(i/2)*.07f) else w*(.72f+(i/2)*.07f)
            val y=h*(.28f+(i%3)*.17f)
            color(0xFF47745E)
            canvas.drawPath(Path().apply { moveTo(x,y); lineTo(x-w*.025f,y+h*.22f); lineTo(x+w*.025f,y+h*.22f); close() },paint)
        }
        if(state.showStimulus || state.reaction.phase==ReactionPhase.COMPLETE) {
            if(appearedAt==0L) appearedAt=SystemClock.elapsedRealtime()
            val progress=((SystemClock.elapsedRealtime()-appearedAt)/600f).coerceIn(0f,1f)
            canvas.save(); canvas.translate(w*(.69f-.19f*progress),h*.68f); canvas.scale(h/260f,h/260f)
            color(0xFFC99457); canvas.drawOval(-38f,-15f,44f,22f,paint)
            canvas.drawOval(-50f,-55f,-19f,5f,paint); canvas.drawOval(-64f,-65f,-20f,-35f,paint); canvas.drawOval(-45f,-80f,-33f,-50f,paint)
            paint.strokeWidth=8f; canvas.drawLine(-25f,10f,-29f,57f,paint); canvas.drawLine(26f,10f,30f,57f,paint)
            color(0xFF795B3A); paint.strokeWidth=4f
            canvas.drawLine(-35f,-60f,-38f,-95f,paint); canvas.drawLine(-38f,-82f,-51f,-94f,paint); canvas.drawLine(-38f,-86f,-27f,-97f,paint)
            color(0xFFEDD7AE); for(i in 0..3) canvas.drawCircle(-13f+i*12f,-5f+(i%2)*8f,3f,paint)
            color(0xFF253A32); canvas.drawCircle(-48f,-52f,3f,paint); canvas.drawCircle(-63f,-47f,4f,paint)
            canvas.restore()
            if(state.reaction.phase==ReactionPhase.WAITING && !acknowledged) {
                acknowledged=true
                val drawn=SystemClock.elapsedRealtime()
                post { if(isAttachedToWindow) visible(drawn) }
            }
            if(progress<1) postInvalidateOnAnimation()
        }
        val caption=when(state.reaction.phase) {
            ReactionPhase.IDLE -> "아래 ‘반응 체크’를 누르면 시작합니다"
            ReactionPhase.WAITING -> "측정 ${state.reaction.trial}/3 · 잠시 후 동물이 나타납니다"
            ReactionPhase.STIMULUS -> "지금 브레이크를 밟으세요"
            ReactionPhase.COMPLETE -> "평균 ${state.reaction.reactionMs?.let { com.example.careway.core.seconds(it) }}초 · 컨디션 ${state.reaction.condition?.label}"
            else -> state.reaction.message
        }
        color(0xFF152C32); canvas.drawRoundRect(w*.08f,h-56f,w*.92f,h-8f,16f,16f,paint)
        color(0xFFFFFFFF); paint.textSize=18f*resources.displayMetrics.scaledDensity; paint.textAlign=Paint.Align.CENTER
        canvas.drawText(caption,w/2,h-26f,paint)
    }
}
