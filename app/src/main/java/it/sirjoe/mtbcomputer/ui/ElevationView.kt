package it.sirjoe.mtbcomputer.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.View
import it.sirjoe.mtbcomputer.data.TrackPoint
import it.sirjoe.mtbcomputer.util.Geo

/** Profilo altimetrico del percorso caricato, con la posizione attuale. */
class ElevationView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private var dist = DoubleArray(0)
    private var ele = DoubleArray(0)
    private var current: Double? = null

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#55FF6D00")
        style = Paint.Style.FILL
    }
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FF6D00")
        style = Paint.Style.STROKE
        strokeWidth = 4f
    }
    private val pos = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#7B1FA2")
        strokeWidth = 5f
    }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.DKGRAY
        textSize = 28f
    }

    fun setTrack(points: List<TrackPoint>?) {
        val withEle = points?.filter { it.ele != null } ?: emptyList()
        if (withEle.size < 2) {
            dist = DoubleArray(0)
            ele = DoubleArray(0)
        } else {
            dist = Geo.cumulative(withEle)
            ele = DoubleArray(withEle.size) { withEle[it].ele ?: 0.0 }
        }
        invalidate()
    }

    fun setCurrentDistance(d: Double?) {
        current = d
        invalidate()
    }

    fun hasData() = ele.size >= 2

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (ele.size < 2) {
            canvas.drawText("Nessun profilo: carica un percorso con quota", 16f, height / 2f, text)
            return
        }
        val pad = 8f
        val w = width - 2 * pad
        val h = height - 2 * pad - 30f
        val minE = ele.min()
        val maxE = ele.max()
        val range = (maxE - minE).coerceAtLeast(10.0)
        val total = dist.last().coerceAtLeast(1.0)
        fun x(d: Double) = pad + (d / total * w).toFloat()
        fun y(e: Double) = pad + 30f + (h - ((e - minE) / range * h)).toFloat()

        val p = Path()
        p.moveTo(x(0.0), y(minE))
        for (i in ele.indices) p.lineTo(x(dist[i]), y(ele[i]))
        p.lineTo(x(total), y(minE))
        p.close()
        canvas.drawPath(p, fill)

        val l = Path()
        l.moveTo(x(dist[0]), y(ele[0]))
        for (i in 1 until ele.size) l.lineTo(x(dist[i]), y(ele[i]))
        canvas.drawPath(l, line)

        canvas.drawText("max ${maxE.toInt()} m  -  min ${minE.toInt()} m", pad, 26f, text)
        current?.let {
            val cx = x(it.coerceIn(0.0, total))
            canvas.drawLine(cx, pad + 30f, cx, height - pad, pos)
        }
    }
}
