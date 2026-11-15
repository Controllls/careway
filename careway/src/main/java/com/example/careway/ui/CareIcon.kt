package com.example.careway.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.View

/** Simple line icons drawn on a 24x24 grid, so the home screen needs no image assets. */
class CareIcon(context: Context, private val kind: Kind) : View(context) {
    enum class Kind { PEDAL, HEADLIGHT, RAIN, TUNNEL, VOICE, FAN, DEFROST, AIR, MIRROR, LAMP }

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
    private val path = Path()
    private val box = RectF()
    var tint: Int = Color.WHITE
        set(value) { if (field != value) { field = value; invalidate() } }

    private fun stroke(width: Float = 1.7f) { paint.style = Paint.Style.STROKE; paint.strokeWidth = width }
    private fun fill() { paint.style = Paint.Style.FILL }

    override fun onDraw(c: Canvas) {
        super.onDraw(c)
        val size = minOf(width, height).toFloat()
        c.save(); c.translate((width - size) / 2, (height - size) / 2); c.scale(size / 24f, size / 24f)
        paint.color = tint
        when (kind) {
            Kind.PEDAL -> { // brake warning symbol
                stroke(); c.drawCircle(12f, 12f, 6f, paint)
                box.set(2.5f, 2.5f, 21.5f, 21.5f)
                c.drawArc(box, 135f, 90f, false, paint); c.drawArc(box, -45f, 90f, false, paint)
                c.drawLine(12f, 8.5f, 12f, 12.5f, paint)
                fill(); c.drawCircle(12f, 15.2f, 1f, paint)
            }
            Kind.HEADLIGHT -> { // low beam: lamp with downward rays
                stroke(); path.reset(); path.moveTo(13f, 5.5f); path.cubicTo(22f, 6.5f, 22f, 17.5f, 13f, 18.5f); path.close()
                c.drawPath(path, paint)
                for (y in floatArrayOf(6.5f, 10.2f, 13.9f, 17.6f)) c.drawLine(10f, y, 4f, y + 1.6f, paint)
            }
            Kind.RAIN -> { // cloud with drops
                fill(); c.drawRoundRect(5f, 8.5f, 19f, 14f, 2.7f, 2.7f, paint)
                c.drawCircle(9.5f, 8.8f, 3.3f, paint); c.drawCircle(14.3f, 8f, 4f, paint)
                stroke(); for (x in floatArrayOf(8.5f, 12.5f, 16.5f)) c.drawLine(x, 16.5f, x - 1.2f, 20f, paint)
            }
            Kind.TUNNEL -> { // arch with a lane
                stroke(); path.reset(); path.moveTo(3.5f, 20f); path.lineTo(3.5f, 12f)
                box.set(3.5f, 3.5f, 20.5f, 20.5f); path.arcTo(box, 180f, 180f); path.lineTo(20.5f, 20f)
                c.drawPath(path, paint)
                path.reset(); path.moveTo(8f, 20f); path.lineTo(8f, 13f)
                box.set(8f, 9f, 16f, 17f); path.arcTo(box, 180f, 180f); path.lineTo(16f, 20f)
                c.drawPath(path, paint)
                c.drawLine(12f, 20f, 12f, 18f, paint); c.drawLine(12f, 16f, 12f, 14.5f, paint)
            }
            Kind.VOICE -> { // speaker with sound waves
                fill(); path.reset(); path.moveTo(4f, 10f); path.lineTo(8f, 10f); path.lineTo(12.5f, 6f)
                path.lineTo(12.5f, 18f); path.lineTo(8f, 14f); path.lineTo(4f, 14f); path.close()
                c.drawPath(path, paint)
                stroke(); box.set(9f, 7.5f, 18f, 16.5f); c.drawArc(box, -50f, 100f, false, paint)
                box.set(6.5f, 4.5f, 21.5f, 19.5f); c.drawArc(box, -50f, 100f, false, paint)
            }
            Kind.FAN -> { // three blades
                stroke(); c.drawCircle(12f, 12f, 1.6f, paint)
                for (i in 0..2) { c.save(); c.rotate(i * 120f, 12f, 12f); box.set(10.2f, 3f, 13.8f, 10f); c.drawOval(box, paint); c.restore() }
            }
            Kind.DEFROST -> { // windshield with rising heat
                stroke(); path.reset(); path.moveTo(3f, 9.5f); path.quadTo(12f, 4.5f, 21f, 9.5f)
                path.lineTo(18.5f, 17.5f); path.quadTo(12f, 15.5f, 5.5f, 17.5f); path.close()
                c.drawPath(path, paint)
                stroke(1.4f)
                for (x in floatArrayOf(8f, 12f, 16f)) {
                    path.reset(); path.moveTo(x, 21.5f); path.cubicTo(x - 1.8f, 19f, x + 1.8f, 16.5f, x, 13.5f)
                    c.drawPath(path, paint)
                }
            }
            Kind.AIR -> { // circulating arrow
                stroke(); box.set(5f, 5f, 19f, 19f); c.drawArc(box, -50f, 290f, false, paint)
                c.drawLine(8.5f, 5.94f, 5.5f, 5.7f, paint); c.drawLine(8.5f, 5.94f, 7.2f, 8.7f, paint)
            }
            Kind.MIRROR -> { // side mirror
                stroke(); path.reset(); path.moveTo(5f, 8.5f); path.lineTo(16.5f, 7f); path.lineTo(19f, 15f)
                path.lineTo(6.5f, 17f); path.close()
                c.drawPath(path, paint)
                c.drawLine(17.5f, 16f, 21f, 19f, paint); c.drawLine(8.5f, 11f, 11.5f, 10.6f, paint)
            }
            Kind.LAMP -> { // cabin light
                stroke(); c.drawCircle(12f, 9.5f, 5f, paint)
                c.drawLine(10f, 16.5f, 14f, 16.5f, paint); c.drawLine(10.7f, 19f, 13.3f, 19f, paint)
                c.drawLine(3.5f, 9.5f, 5.2f, 9.5f, paint); c.drawLine(18.8f, 9.5f, 20.5f, 9.5f, paint)
                c.drawLine(5.6f, 3.6f, 6.8f, 4.8f, paint); c.drawLine(18.4f, 3.6f, 17.2f, 4.8f, paint)
            }
        }
        c.restore()
    }
}
