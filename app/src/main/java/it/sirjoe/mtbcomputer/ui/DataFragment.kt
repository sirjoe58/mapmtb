package it.sirjoe.mtbcomputer.ui

import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import it.sirjoe.mtbcomputer.data.RideRepository
import it.sirjoe.mtbcomputer.util.Format
import it.sirjoe.mtbcomputer.util.Geo
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Pagina "Dati": tutti i valori classici del ciclocomputer, a riquadri grandi. */
class DataFragment : Fragment() {

    private val values = HashMap<String, TextView>()
    private lateinit var elevation: ElevationView
    private val clockFmt = SimpleDateFormat("HH:mm:ss", Locale.ITALY)

    private val fields = listOf(
        "speed" to "Velocità (km/h)",
        "avg" to "Media (km/h)",
        "max" to "Massima (km/h)",
        "dist" to "Distanza (km)",
        "moving" to "Tempo in movimento",
        "elapsed" to "Tempo totale",
        "clock" to "Ora",
        "alt" to "Quota (m)",
        "asc" to "Dislivello + (m)",
        "desc" to "Dislivello - (m)",
        "slope" to "Pendenza",
        "dir" to "Direzione",
        "togo" to "Al traguardo (km)",
        "gps" to "Precisione GPS (m)"
    )

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val ctx = requireContext()
        val dp = resources.displayMetrics.density
        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((8 * dp).toInt(), (8 * dp).toInt(), (8 * dp).toInt(), (8 * dp).toInt())
        }

        var row: LinearLayout? = null
        fields.forEachIndexed { i, (key, label) ->
            if (i % 2 == 0) {
                row = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
                root.addView(row)
            }
            val cell = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setBackgroundColor(Color.parseColor("#F3F3F3"))
                setPadding(0, (10 * dp).toInt(), 0, (10 * dp).toInt())
            }
            val lp = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            lp.setMargins((4 * dp).toInt(), (4 * dp).toInt(), (4 * dp).toInt(), (4 * dp).toInt())
            cell.layoutParams = lp
            cell.addView(TextView(ctx).apply {
                text = label
                textSize = 13f
                setTextColor(Color.DKGRAY)
                gravity = Gravity.CENTER
            })
            val v = TextView(ctx).apply {
                text = "--"
                textSize = 30f
                typeface = Typeface.create("sans-serif-condensed", Typeface.BOLD)
                setTextColor(Color.BLACK)
                gravity = Gravity.CENTER
            }
            cell.addView(v)
            values[key] = v
            row?.addView(cell)
        }

        root.addView(TextView(ctx).apply {
            text = "Profilo altimetrico del percorso"
            textSize = 15f
            setTypeface(typeface, Typeface.BOLD)
            setPadding((4 * dp).toInt(), (16 * dp).toInt(), 0, (4 * dp).toInt())
        })
        elevation = ElevationView(ctx)
        root.addView(elevation, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (160 * dp).toInt()))

        return ScrollView(ctx).apply { addView(root) }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { RideRepository.route.collect { elevation.setTrack(it?.points) } }
                launch {
                    while (true) {
                        refresh()
                        delay(1000)
                    }
                }
            }
        }
    }

    private fun set(key: String, v: String) {
        values[key]?.text = v
    }

    private fun refresh() {
        val s = RideRepository.stats.value
        val loc = RideRepository.location.value
        val speed = if (s.recording) s.currentSpeedMs else (loc?.takeIf { it.hasSpeed() }?.speed?.toDouble() ?: 0.0)
        set("speed", Format.speedKmh(speed))
        set("avg", Format.speedKmh(s.avgSpeedMs))
        set("max", Format.speedKmh(s.maxSpeedMs))
        set("dist", Format.distanceKm(s.distanceM))
        set("moving", Format.duration(s.movingMs))
        set("elapsed", Format.duration(s.elapsedMs))
        set("clock", clockFmt.format(Date()))
        set("alt", Format.meters(s.altitudeM ?: loc?.takeIf { it.hasAltitude() }?.altitude))
        set("asc", Format.meters(s.ascentM))
        set("desc", Format.meters(s.descentM))
        set("slope", Format.percent(s.slopePct))
        set("dir", if (loc != null && loc.hasBearing()) Format.cardinal(loc.bearing) else "--")
        set("gps", if (loc != null && loc.hasAccuracy()) Format.meters(loc.accuracy.toDouble()) else "--")

        val route = RideRepository.route.value
        if (route != null && loc != null && route.points.size >= 2) {
            val cum = Geo.cumulative(route.points)
            var best = Double.MAX_VALUE
            var idx = 0
            route.points.forEachIndexed { i, p ->
                val d = Geo.distance(loc.latitude, loc.longitude, p.lat, p.lon)
                if (d < best) {
                    best = d
                    idx = i
                }
            }
            set("togo", Format.distanceKm(cum.last() - cum[idx]))
            if (elevation.hasData()) {
                val withEle = route.points.filter { it.ele != null }
                var bestE = Double.MAX_VALUE
                var idxE = 0
                withEle.forEachIndexed { i, p ->
                    val d = Geo.distance(loc.latitude, loc.longitude, p.lat, p.lon)
                    if (d < bestE) {
                        bestE = d
                        idxE = i
                    }
                }
                elevation.setCurrentDistance(Geo.cumulative(withEle)[idxE])
            }
        } else {
            set("togo", "--")
            elevation.setCurrentDistance(null)
        }
    }
}
