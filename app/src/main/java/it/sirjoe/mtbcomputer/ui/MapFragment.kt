package it.sirjoe.mtbcomputer.ui

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.osmdroid.tileprovider.MapTileProviderBasic
import org.osmdroid.tileprovider.modules.OfflineTileProvider
import org.osmdroid.tileprovider.tilesource.FileBasedTileSource
import org.osmdroid.tileprovider.tilesource.ITileSource
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.tileprovider.tilesource.XYTileSource
import org.osmdroid.tileprovider.util.SimpleRegisterReceiver
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.CustomZoomButtonsController
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline
import org.osmdroid.views.overlay.ScaleBarOverlay
import org.osmdroid.views.overlay.TilesOverlay
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

/** Pagina principale: mappa a tutto schermo con cursore MTB e barra dati discreta. */
class MapFragment : Fragment(R.layout.fragment_map) {

    companion object {
        /** Sovrapposizione degli itinerari MTB segnati (dati OpenStreetMap). */
        private val MTB_ROUTES = XYTileSource(
            "WaymarkedTrailsMTB", 0, 18, 256, ".png",
            arrayOf("https://tile.waymarkedtrails.org/mtb/"),
            "Itinerari MTB: waymarkedtrails.org, dati (c) OpenStreetMap contributors"
        )
        private const val OFF_ROUTE_M = 60.0
        private const val BACK_ON_ROUTE_M = 35.0
    }

    private lateinit var map: MapView
    private lateinit var marker: Marker
    private val trackLine = Polyline()
    private val routeLine = Polyline()
    private var mtbOverlay: TilesOverlay? = null
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

    private var follow = true
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

        map.setMultiTouchControls(true)
        map.zoomController.setVisibility(CustomZoomButtonsController.Visibility.NEVER)
        map.setTilesScaledToDpi(true)
        map.setMinZoomLevel(4.0)
        map.setMaxZoomLevel(19.0)
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
        marker.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
        marker.setOnMarkerClickListener { _, _ -> true }
        marker.icon = bikeIcon(0f)

        applyBaseMap()

        // Se l'utente sposta la mappa, smette di seguire la posizione
        map.setOnTouchListener { _, e ->
            if (e.action == MotionEvent.ACTION_MOVE) follow = false
            false
        }

        view.findViewById<View>(R.id.btnCenter).setOnClickListener {
            follow = true
            RideRepository.location.value?.let {
                map.controller.animateTo(GeoPoint(it.latitude, it.longitude))
            }
        }
        view.findViewById<View>(R.id.btnLayers).setOnClickListener { chooseMap() }
        view.findViewById<View>(R.id.btnHeading).setOnClickListener {
            val v = !Prefs.headingUp(requireContext())
            Prefs.setHeadingUp(requireContext(), v)
            Toast.makeText(
                requireContext(),
                if (v) "Mappa orientata nella direzione di marcia" else "Mappa orientata a Nord",
                Toast.LENGTH_SHORT
            ).show()
            if (!v) map.mapOrientation = 0f
            RideRepository.location.value?.let { onLocation(it) }
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
        val gp = GeoPoint(loc.latitude, loc.longitude)
        if (loc.hasBearing() && loc.hasSpeed() && loc.speed > 1.0f) lastBearing = loc.bearing

        if (Prefs.headingUp(requireContext())) {
            map.mapOrientation = -lastBearing
            marker.icon = bikeIcon(0f)
        } else {
            if (map.mapOrientation != 0f) map.mapOrientation = 0f
            marker.icon = bikeIcon(lastBearing)
        }
        marker.position = gp

        if (firstFix) {
            firstFix = false
            if (!map.overlays.contains(marker)) map.overlays.add(marker)
            if (follow) {
                map.controller.setZoom(16.0)
                map.controller.setCenter(gp)
            }
        } else if (follow) {
            map.controller.animateTo(gp)
        }

        updateRouteProgress(loc)
        updateData()
        map.invalidate()
    }

    /** Icona MTB ruotata (in modalita' Nord in alto) e messa in cache ogni 5 gradi. */
    private fun bikeIcon(bearing: Float): BitmapDrawable {
        val key = (((bearing / 5f).roundToInt() * 5) % 360 + 360) % 360
        return iconCache.getOrPut(key) {
            val d = ContextCompat.getDrawable(requireContext(), R.drawable.ic_mtb_marker)!!
            val size = (56 * resources.displayMetrics.density).toInt()
            val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
            val c = Canvas(bmp)
            c.rotate(key.toFloat(), size / 2f, size / 2f)
            d.setBounds(0, 0, size, size)
            d.draw(c)
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
        distanceTv.text = getString(R.string.unit_km_value, Format.distanceKm(s.distanceM))
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

    // ---------------------------------------------------------------- mappe

    fun applyBaseMap() {
        val ctx = requireContext()
        val base = Prefs.baseMap(ctx)
        val offlineFile = Prefs.offlineMapPath(ctx)?.let { File(it) }?.takeIf { it.exists() }

        if (base == Prefs.BASE_OFFLINE && offlineFile != null) {
            val provider = OfflineTileProvider(SimpleRegisterReceiver(ctx), arrayOf(offlineFile))
            map.setTileProvider(provider)
            val names = provider.archives.firstOrNull()?.tileSources
            val src: ITileSource = if (!names.isNullOrEmpty()) {
                FileBasedTileSource.getSource(names.first())
            } else {
                TileSourceFactory.OpenTopo
            }
            map.setTileSource(src)
            map.setUseDataConnection(false)
        } else {
            val src = if (base == Prefs.BASE_OSM) TileSourceFactory.MAPNIK else TileSourceFactory.OpenTopo
            map.setTileProvider(MapTileProviderBasic(ctx, src))
            map.setTileSource(src)
            map.setUseDataConnection(true)
        }

        mtbOverlay?.let {
            map.overlays.remove(it)
            it.onDetach(map)
        }
        mtbOverlay = null
        if (Prefs.mtbOverlay(ctx) && base != Prefs.BASE_OFFLINE) {
            val o = TilesOverlay(MapTileProviderBasic(ctx, MTB_ROUTES), ctx)
            o.setLoadingBackgroundColor(Color.TRANSPARENT)
            o.setLoadingLineColor(Color.TRANSPARENT)
            map.overlays.add(0, o)
            mtbOverlay = o
        }
        map.invalidate()
    }

    private fun chooseMap() {
        val ctx = requireContext()
        val hasOffline = Prefs.offlineMapPath(ctx)?.let { File(it).exists() } == true
        val mtbOn = Prefs.mtbOverlay(ctx)
        val items = mutableListOf(
            "OpenTopoMap (sentieri e rilievo)",
            "OpenStreetMap standard"
        )
        if (hasOffline) items.add("Mappa offline importata")
        items.add(if (mtbOn) "Nascondi itinerari MTB" else "Mostra itinerari MTB")

        MaterialAlertDialogBuilder(ctx)
            .setTitle("Mappa")
            .setItems(items.toTypedArray()) { _, which ->
                val label = items[which]
                when {
                    label.startsWith("OpenTopo") -> Prefs.setBaseMap(ctx, Prefs.BASE_TOPO)
                    label.startsWith("OpenStreetMap") -> Prefs.setBaseMap(ctx, Prefs.BASE_OSM)
                    label.startsWith("Mappa offline") -> Prefs.setBaseMap(ctx, Prefs.BASE_OFFLINE)
                    else -> Prefs.setMtbOverlay(ctx, !mtbOn)
                }
                applyBaseMap()
            }
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
            map.zoomToBoundingBox(BoundingBox(n, e, s, w), true, 80)
        }
        bannerTv.text = track.name + " - " + Format.distanceKm(routeCum!!.last()) + " km"
        bannerTv.visibility = View.VISIBLE
        map.invalidate()
    }

    /** Distanza al traguardo e avviso "fuori percorso". */
    private fun updateRouteProgress(loc: Location) {
        val pts = routePoints
        val cum = routeCum ?: return
        if (pts.isEmpty()) return
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
    }

    private fun vibrate() {
        val v = requireContext().getSystemService(Vibrator::class.java) ?: return
        v.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 300, 150, 300), -1))
    }
}
