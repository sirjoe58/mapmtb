package it.sirjoe.mtbcomputer.share

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import androidx.core.content.res.ResourcesCompat
import it.sirjoe.mtbcomputer.R
import it.sirjoe.mtbcomputer.data.RouteSummary
import it.sirjoe.mtbcomputer.data.Track
import it.sirjoe.mtbcomputer.util.Format
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.PI
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.tan

/** Immagine 1080x1350 da pubblicare sui social: forma del percorso, numeri e logo. */
object ShareImage {

    fun render(ctx: Context, track: Track): Bitmap {
        val w = 1080
        val h = 1350
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val russo: Typeface = ResourcesCompat.getFont(ctx, R.font.russo_one) ?: Typeface.DEFAULT_BOLD

        val bg = Paint()
        bg.shader = LinearGradient(0f, 0f, 0f, h.toFloat(), Color.parseColor("#22442F"), Color.parseColor("#0C1A12"), Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), bg)

        val title = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            typeface = russo
            textSize = 58f
        }
        var name = track.name
        while (title.measureText(name) > w - 120 && name.length > 4) name = name.dropLast(2)
        if (name != track.name) name = name.trimEnd() + "…"
        c.drawText(name, 60f, 120f, title)

        val sub = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#B0C4B8")
            textSize = 36f
        }
        track.points.firstOrNull()?.time?.let {
            c.drawText(SimpleDateFormat("EEEE d MMMM yyyy", Locale.ITALY).format(Date(it)), 60f, 172f, sub)
        }

        drawRoute(c, track, RectF(90f, 230f, w - 90f, 960f))

        // numeri
        val sum = RouteSummary.of(track.points)
        val stats = ArrayList<Pair<String, String>>()
        stats.add(Format.distanceKm(sum.distanceM) to "km")
        sum.ascentM?.let { stats.add("+" + Format.meters(it) to "m dislivello") }
        val t0 = track.points.firstOrNull()?.time
        val t1 = track.points.lastOrNull()?.time
        if (t0 != null && t1 != null && t1 > t0) {
            stats.add(Format.duration(t1 - t0) to "tempo")
            stats.add(Format.speedKmh(sum.distanceM / ((t1 - t0) / 1000.0)) to "km/h media")
        }
        val value = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            typeface = russo
            textAlign = Paint.Align.CENTER
            textSize = if (stats.size > 3) 56f else 68f
        }
        val label = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#FFB074")
            textAlign = Paint.Align.CENTER
            textSize = 32f
        }
        val col = (w - 80f) / stats.size
        stats.forEachIndexed { i, (v, l) ->
            val x = 40f + col * i + col / 2
            c.drawText(v, x, 1080f, value)
            c.drawText(l, x, 1128f, label)
        }

        // logo
        val logo = BitmapFactory.decodeResource(ctx.resources, R.drawable.logo_sirjoe)
        val lw = 460f
        val lh = lw * logo.height / logo.width
        c.drawBitmap(logo, null, RectF((w - lw) / 2, 1190f, (w + lw) / 2, 1190f + lh), Paint(Paint.FILTER_BITMAP_FLAG))
        return bmp
    }

    private fun drawRoute(c: Canvas, track: Track, box: RectF) {
        val pts = track.points
        if (pts.size < 2) return
        // proiezione di Mercatore, adattata al riquadro mantenendo le proporzioni
        fun mx(lon: Double) = lon / 360.0 + 0.5
        fun my(lat: Double): Double {
            val r = Math.toRadians(lat.coerceIn(-85.0, 85.0))
            return 0.5 - ln(tan(PI / 4 + r / 2)) / (2 * PI)
        }
        var minX = Double.MAX_VALUE
        var maxX = -Double.MAX_VALUE
        var minY = Double.MAX_VALUE
        var maxY = -Double.MAX_VALUE
        for (p in pts) {
            val x = mx(p.lon)
            val y = my(p.lat)
            minX = min(minX, x); maxX = max(maxX, x); minY = min(minY, y); maxY = max(maxY, y)
        }
        val span = max(maxX - minX, maxY - minY).coerceAtLeast(1e-9)
        val scale = min(box.width(), box.height()) / span
        val ox = box.centerX() - ((minX + maxX) / 2 * scale).toFloat()
        val oy = box.centerY() - ((minY + maxY) / 2 * scale).toFloat()

        val path = Path()
        pts.forEachIndexed { i, p ->
            val x = (mx(p.lon) * scale).toFloat() + ox
            val y = (my(p.lat) * scale).toFloat() + oy
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        val glow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 26f
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
            color = Color.parseColor("#33FFFFFF")
        }
        val line = Paint(glow).apply {
            strokeWidth = 12f
            color = Color.parseColor("#FF6D00")
        }
        c.drawPath(path, glow)
        c.drawPath(path, line)

        val dot = Paint(Paint.ANTI_ALIAS_FLAG)
        val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; strokeWidth = 6f; color = Color.WHITE
        }
        fun mark(i: Int, color: Int) {
            val x = (mx(pts[i].lon) * scale).toFloat() + ox
            val y = (my(pts[i].lat) * scale).toFloat() + oy
            dot.color = color
            c.drawCircle(x, y, 18f, dot)
            c.drawCircle(x, y, 18f, ring)
        }
        mark(pts.lastIndex, Color.parseColor("#C62828"))
        mark(0, Color.parseColor("#2E7D32"))
    }
}
