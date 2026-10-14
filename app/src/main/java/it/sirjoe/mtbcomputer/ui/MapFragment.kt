package it.sirjoe.mtbcomputer.ui

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.drawable.BitmapDrawable
import android.location.Location
import android.os.Bundle
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.view.MotionEvent
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.floatingactionbutton.FloatingActionButton
import it.sirjoe.mtbcomputer.MainActivity
import it.sirjoe.mtbcomputer.R
import it.sirjoe.mtbcomputer.data.Prefs
import it.sirjoe.mtbcomputer.data.RideRepository
import it.sirjoe.mtbcomputer.data.Track
import it.sirjoe.mtbcomputer.service.RecordingService
import it.sirjoe.mtbcomputer.util.Format
import it.sirjoe.mtbcomputer.util.Geo
import it.sirjoe.mtbcomputer.util.MapStyle
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline
import org.osmdroid.views.overlay.ScaleBarOverlay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Pagina principale: mappa a tutto schermo, cursore "bici vista da dietro"
 * con freccia davanti che indica dove andare, barra dati discreta in basso.
 */
class MapFragment : Fragment(R.layout.fragment_map) {

    companion object {
        private const val OFF_ROUTE_M = 60.0
        private const val BACK_ON_ROUTE_M = 35.0
        /** La freccia punta al punto del percorso che si trova circa 40 m più avanti. */
        private const val LOOK_AHEAD_M = 40.0
        /** Dimensione del disegno del cursore (unità 160x160). */
        private const val ICON_UNITS = 160f
    }

    private lateinit var map: MapView
    private lateinit var marker: Marker
    private val trackLine = Polyline()
    private val routeLine = Polyline()
    private lateinit var fused: FusedLocationProviderClient

    private lateinit var speedTv: TextView
    private lateinit var distanceTv: TextView
    private lateinit var clockTv: TextView
    private lateinit var bannerTv: TextView
    private lateinit var extraRow: View
    private lateinit var extraTime: TextView
    private lateinit var extraAscent: TextView
    private lateinit var extraAlt: TextView
    private lateinit var btnRecord: FloatingActionButton
    private lateinit var btnStop: FloatingActionButton
    private lateinit var btnHeading: FloatingActionButton

    private var follow = true
    /** L'utente ha girato la mappa con due dita: l'orientamento automatico si sospende. */
    private var userRotated = false
    private var downX = 0f
    private var downY = 0f
    private var firstFix = true
    private var lastBearing = 0f
    private var offRoute = false
    private var routePoints: List<GeoPoint> = emptyList()
    private var routeCum: DoubleArray? = null
    private var drawnPoints = 0
    private val iconCache = HashMap<Int, BitmapDrawable>()
    private val clockFmt = SimpleDateFormat("HH:mm", Locale.ITALY)

    private val callback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            // Quando si registra, la posizione arriva gia' dal servizio
            if (!RideRepository.stats.value.recording) {
                result.lastLocation?.let { RideRepository.updateLocation(it) }
            }
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        fused = LocationServices.getFusedLocationProviderClient(requireContext())

        map = view.findViewById(R.id.map)
        speedTv = view.findViewById(R.id.speed)
        distanceTv = view.findViewById(R.id.distance)
        clockTv = view.findViewById(R.id.clock)
        bannerTv = view.findViewById(R.id.routeBanner)
        extraRow = view.findViewById(R.id.extraRow)
        extraTime = view.findViewById(R.id.extraTime)
        extraAscent = view.findViewById(R.id.extraAscent)
        extraAlt = view.findViewById(R.id.extraAlt)
        btnRecord = view.findViewById(R.id.btnRecord)
        btnStop = view.findViewById(R.id.btnStop)

        MapStyle.setup(map) {
            userRotated = true
            updateCompass()
        }
        map.controller.setZoom(6.0)
        map.controller.setCenter(GeoPoint(42.5, 12.5))

        routeLine.outlinePaint.color = Color.parseColor("#C07B1FA2")
        routeLine.outlinePaint.strokeWidth = 14f
        routeLine.outlinePaint.strokeCap = Paint.Cap.ROUND
        trackLine.outlinePaint.color = Color.parseColor("#FF6D00")
        trackLine.outlinePaint.strokeWidth = 9f
        trackLine.outlinePaint.strokeCap = Paint.Cap.ROUND

        map.overlays.add(routeLine)
        map.overlays.add(trackLine)
        map.overlays.add(ScaleBarOverlay(map).apply { setAlignBottom(false) })

        marker = Marker(map)
        // la posizione corrisponde al punto d'appoggio della ruota posteriore
        marker.setAnchor(0.5f, 0.8f)
        marker.setOnMarkerClickListener { _, _ -> true }
        marker.icon = navIcon(0)

        applyBaseMap()

        // Trascinando con un dito la mappa smette di seguire la posizione
        // (lo zoom con due dita invece non la sgancia).
        map.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.x; downY = e.y
                }
                MotionEvent.ACTION_MOVE -> {
                    val moved = Math.hypot((e.x - downX).toDouble(), (e.y - downY).toDouble())
                    if (e.pointerCount == 1 && moved > 24 * resources.displayMetrics.density && follow) {
                        follow = false
                        map.setMapCenterOffset(0, 0)
                    }
                }
            }
            false
        }

        view.findViewById<View>(R.id.btnCenter).setOnClickListener {
            follow = true
            userRotated = false
            val l = RideRepository.location.value
            if (l != null) onLocation(l) else Toast.makeText(requireContext(), "In attesa del GPS…", Toast.LENGTH_SHORT).show()
        }
        view.findViewById<View>(R.id.btnLayers).setOnClickListener {
            MapStyle.choose(requireContext()) { applyBaseMap() }
        }
        btnHeading = view.findViewById(R.id.btnHeading)
        btnHeading.setOnClickListener { chooseOrientation() }
        // tocco lungo sulla bussola: Nord in alto subito
        btnHeading.setOnLongClickListener {
            Prefs.setOrientation(requireContext(), Prefs.ORIENT_NORTH)
            userRotated = false
            map.mapOrientation = 0f
            RideRepository.location.value?.let { onLocation(it) }
            updateCompass()
            true
        }
        btnRecord.setOnClickListener { onRecordClick() }
        btnStop.setOnClickListener { confirmStop() }
        view.findViewById<View>(R.id.dataBar).setOnClickListener {
            extraRow.visibility = if (extraRow.visibility == View.VISIBLE) View.GONE else View.VISIBLE
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { RideRepository.location.collect { l -> if (l != null) onLocation(l) } }
                launch { RideRepository.stats.collect { updateData() } }
                launch { RideRepository.pointCount.collect { updateTrackLine(it) } }
                launch { RideRepository.route.collect { showRoute(it) } }
                launch {
                    while (true) {
                        clockTv.text = clockFmt.format(Date())
                        delay(1000)
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        map.onResume()
        startLocationUpdates()
    }

    override fun onPause() {
        fused.removeLocationUpdates(callback)
        map.onPause()
        super.onPause()
    }

    fun applyBaseMap() {
        if (isAdded) MapStyle.apply(requireContext(), map)
    }

    // ------------------------------------------------------------- posizione

    @SuppressLint("MissingPermission")
    fun startLocationUpdates() {
        if (!hasLocationPermission()) return
        val req = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 1000L).build()
        try {
            fused.requestLocationUpdates(req, callback, Looper.getMainLooper())
        } catch (_: SecurityException) {
        }
    }

    private fun hasLocationPermission() = ContextCompat.checkSelfPermission(
        requireContext(), Manifest.permission.ACCESS_FINE_LOCATION
    ) == PackageManager.PERMISSION_GRANTED

    private fun onLocation(loc: Location) {
        if (!isAdded) return
        val gp = GeoPoint(loc.latitude, loc.longitude)
        if (loc.hasBearing() && loc.hasSpeed() && loc.speed > 1.0f) lastBearing = loc.bearing

        val mode = Prefs.orientation(requireContext())
        val auto = !userRotated && mode != Prefs.ORIENT_FREE && (follow || mode == Prefs.ORIENT_NORTH)
        if (auto) map.mapOrientation = if (mode == Prefs.ORIENT_HEADING) -lastBearing else 0f
        val headingUp = auto && mode == Prefs.ORIENT_HEADING

        // In modalità "direzione di marcia" la posizione sta nella parte bassa dello schermo
        if (map.height > 0) {
            map.setMapCenterOffset(0, if (headingUp && follow) (map.height * 0.22f).toInt() else 0)
        }
        updateCompass()

        // direzione da seguire: verso il percorso, oppure la direzione di marcia
        val target = updateRouteProgress(loc)
        val wanted = target ?: lastBearing.toDouble()
        val arrow = (((wanted + map.mapOrientation) % 360) + 360) % 360
        marker.icon = navIcon(arrow.roundToInt())
        marker.position = gp

        if (firstFix) {
            firstFix = false
            if (!map.overlays.contains(marker)) map.overlays.add(marker)
            if (follow) {
                map.controller.setZoom(17.0)
                map.controller.setCenter(gp)
            }
        } else if (follow) {
            map.controller.animateTo(gp)
        }

        updateData()
        map.invalidate()
    }

    /** La freccia del pulsante bussola indica sempre il Nord sullo schermo. */
    private fun updateCompass() {
        if (::btnHeading.isInitialized) btnHeading.rotation = map.mapOrientation
    }

    private fun chooseOrientation() {
        val ctx = requireContext()
        val modes = arrayOf(Prefs.ORIENT_HEADING, Prefs.ORIENT_NORTH, Prefs.ORIENT_FREE)
        val labels = arrayOf(
            "Direzione di marcia (la mappa gira con te)",
            "Nord in alto",
            "Libera (la giri tu con due dita)"
        )
        val sel = modes.indexOf(Prefs.orientation(ctx)).coerceAtLeast(0)
        MaterialAlertDialogBuilder(ctx)
            .setTitle("Orientamento della mappa")
            .setSingleChoiceItems(labels, sel) { d, which ->
                Prefs.setOrientation(ctx, modes[which])
                userRotated = false
                if (modes[which] == Prefs.ORIENT_NORTH) map.mapOrientation = 0f
                d.dismiss()
                RideRepository.location.value?.let { onLocation(it) }
                updateCompass()
                map.invalidate()
            }
            .setNegativeButton("Chiudi", null)
            .show()
        Toast.makeText(
            ctx,
            "Suggerimento: ruota la mappa con due dita; il pulsante ◎ riporta l'orientamento scelto",
            Toast.LENGTH_LONG
        ).show()
    }

    /**
     * Cursore: bici MTB vista da dietro, con una freccia davanti che ruota
     * verso la direzione da prendere. In cache ogni 5 gradi.
     */
    private fun navIcon(arrowDeg: Int): BitmapDrawable {
        val key = (((arrowDeg / 5f).roundToInt() * 5) % 360 + 360) % 360
        return iconCache.getOrPut(key) {
            val size = (80 * resources.displayMetrics.density).toInt()
            val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
            val c = Canvas(bmp)
            val bike = ContextCompat.getDrawable(requireContext(), R.drawable.ic_mtb_rear)!!
            bike.setBounds(0, 0, size, size)
            bike.draw(c)

            val k = size / ICON_UNITS
            c.save()
            c.scale(k, k)
            c.rotate(key.toFloat(), 80f, 17f)
            val p = Path().apply {
                moveTo(80f, 0f); lineTo(98f, 30f); lineTo(80f, 22f); lineTo(62f, 30f); close()
            }
            val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#FF6D00") }
            val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeWidth = 3.5f
                strokeJoin = Paint.Join.ROUND
                color = Color.WHITE
            }
            c.drawPath(p, fill)
            c.drawPath(p, stroke)
            c.restore()
            BitmapDrawable(resources, bmp)
        }
    }

    // ------------------------------------------------------------------ dati

    private fun updateData() {
        if (!isAdded) return
        val s = RideRepository.stats.value
        val speedMs = if (s.recording) s.currentSpeedMs
        else (RideRepository.location.value?.takeIf { it.hasSpeed() }?.speed?.toDouble() ?: 0.0)
        speedTv.text = Format.speedKmh(speedMs)
        distanceTv.text = getString(R.string.unit_km_value, Format.distanceKm(s.distanceM)) +
            if (s.sleeping) "  · sosta" else ""
        extraTime.text = "In movimento " + Format.duration(s.movingMs)
        extraAscent.text = "Salita " + Format.meters(s.ascentM) + " m"
        val alt = s.altitudeM ?: RideRepository.location.value?.takeIf { it.hasAltitude() }?.altitude
        extraAlt.text = "Quota " + Format.meters(alt) + " m"

        when {
            !s.recording -> {
                btnRecord.setImageResource(R.drawable.ic_play)
                btnStop.visibility = View.GONE
            }
            s.paused -> {
                btnRecord.setImageResource(R.drawable.ic_play)
                btnStop.visibility = View.VISIBLE
            }
            else -> {
                btnRecord.setImageResource(R.drawable.ic_pause)
                btnStop.visibility = View.VISIBLE
            }
        }
    }

    private fun updateTrackLine(count: Int) {
        if (count == 0) {
            trackLine.setPoints(emptyList())
            drawnPoints = 0
        } else if (count > drawnPoints) {
            val pts = RideRepository.snapshotPoints()
            for (i in drawnPoints until pts.size) trackLine.addPoint(GeoPoint(pts[i].lat, pts[i].lon))
            drawnPoints = pts.size
        }
        map.invalidate()
    }

    // -------------------------------------------------------- registrazione

    private fun onRecordClick() {
        val s = RideRepository.stats.value
        val ctx = requireContext()
        when {
            !s.recording -> {
                if (!hasLocationPermission()) {
                    (activity as? MainActivity)?.askPermissions()
                    Toast.makeText(ctx, R.string.need_location, Toast.LENGTH_LONG).show()
                    return
                }
                follow = true
                RecordingService.send(ctx, RecordingService.ACTION_START)
            }
            s.paused -> RecordingService.send(ctx, RecordingService.ACTION_RESUME)
            else -> RecordingService.send(ctx, RecordingService.ACTION_PAUSE)
        }
    }

    private fun confirmStop() {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.stop_title)
            .setMessage(R.string.stop_msg)
            .setPositiveButton(R.string.yes_stop) { _, _ ->
                RecordingService.send(requireContext(), RecordingService.ACTION_STOP)
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    // -------------------------------------------------------------- percorso

    private fun showRoute(track: Track?) {
        offRoute = false
        if (track == null || track.points.isEmpty()) {
            routeLine.setPoints(emptyList())
            routePoints = emptyList()
            routeCum = null
            bannerTv.visibility = View.GONE
            map.invalidate()
            return
        }
        routePoints = track.points.map { GeoPoint(it.lat, it.lon) }
        routeCum = Geo.cumulative(track.points)
        routeLine.setPoints(routePoints)

        var n = -90.0
        var s = 90.0
        var e = -180.0
        var w = 180.0
        for (p in track.points) {
            if (p.lat > n) n = p.lat
            if (p.lat < s) s = p.lat
            if (p.lon > e) e = p.lon
            if (p.lon < w) w = p.lon
        }
        follow = false
        map.post {
            map.mapOrientation = 0f
            map.setMapCenterOffset(0, 0)
            map.zoomToBoundingBox(BoundingBox(n, e, s, w), true, 80)
        }
        bannerTv.setBackgroundResource(R.drawable.bg_banner)
        bannerTv.text = track.name + " - " + Format.distanceKm(routeCum!!.last()) + " km"
        bannerTv.visibility = View.VISIBLE
        Toast.makeText(requireContext(), "Tocca il pulsante \"centra\" per partire", Toast.LENGTH_LONG).show()
        map.invalidate()
    }

    /**
     * Aggiorna distanza al traguardo e avviso "fuori percorso".
     * Restituisce la direzione (gradi da Nord) in cui andare, o null senza percorso.
     */
    private fun updateRouteProgress(loc: Location): Double? {
        val pts = routePoints
        val cum = routeCum ?: return null
        if (pts.isEmpty()) return null
        var best = Double.MAX_VALUE
        var bestIdx = 0
        for (i in pts.indices) {
            val d = Geo.distance(loc.latitude, loc.longitude, pts[i].latitude, pts[i].longitude)
            if (d < best) {
                best = d
                bestIdx = i
            }
        }
        val remaining = cum.last() - cum[bestIdx]
        if (!offRoute && best > OFF_ROUTE_M) {
            offRoute = true
            vibrate()
        } else if (offRoute && best < BACK_ON_ROUTE_M) {
            offRoute = false
        }
        bannerTv.visibility = View.VISIBLE
        if (offRoute) {
            bannerTv.text = "Fuori percorso: " + best.roundToInt() + " m"
            bannerTv.setBackgroundColor(ContextCompat.getColor(requireContext(), R.color.sj_red))
        } else {
            bannerTv.text = "Al traguardo: " + Format.distanceKm(remaining) + " km"
            bannerTv.setBackgroundResource(R.drawable.bg_banner)
        }

        // Fuori percorso: la freccia indica il punto più vicino per rientrare.
        // Sul percorso: indica il punto qualche decina di metri più avanti.
        var j = bestIdx
        if (!offRoute) {
            while (j < pts.lastIndex && cum[j] - cum[bestIdx] < LOOK_AHEAD_M) j++
        }
        if (Geo.distance(loc.latitude, loc.longitude, pts[j].latitude, pts[j].longitude) < 3.0) return null
        return Geo.bearing(loc.latitude, loc.longitude, pts[j].latitude, pts[j].longitude)
    }

    private fun vibrate() {
        val v = requireContext().getSystemService(Vibrator::class.java) ?: return
        v.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 300, 150, 300), -1))
    }
}
