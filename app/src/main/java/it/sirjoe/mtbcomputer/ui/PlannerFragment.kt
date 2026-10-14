package it.sirjoe.mtbcomputer.ui

import android.graphics.Color
import android.graphics.Paint
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import it.sirjoe.mtbcomputer.MainActivity
import it.sirjoe.mtbcomputer.R
import it.sirjoe.mtbcomputer.data.BRouterClient
import it.sirjoe.mtbcomputer.data.Placemark
import it.sirjoe.mtbcomputer.data.Placemarks
import it.sirjoe.mtbcomputer.data.PlannerState
import it.sirjoe.mtbcomputer.data.Prefs
import it.sirjoe.mtbcomputer.data.RideRepository
import it.sirjoe.mtbcomputer.data.RouteSummary
import it.sirjoe.mtbcomputer.data.TrackIO
import it.sirjoe.mtbcomputer.data.TrackPoint
import it.sirjoe.mtbcomputer.data.TrackStorage
import it.sirjoe.mtbcomputer.share.ShareHelper
import it.sirjoe.mtbcomputer.share.TrackActions
import it.sirjoe.mtbcomputer.util.Format
import it.sirjoe.mtbcomputer.util.MapStyle
import kotlinx.coroutines.launch
import org.osmdroid.events.MapEventsReceiver
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.MapEventsOverlay
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Pagina "Crea": percorsi personalizzati.
 * Tocca la mappa (o usa "+" col mirino) per aggiungere punti di passaggio.
 * In modalità "segui sentieri" ogni tratto viene calcolato su strade e sentieri reali.
 */
class PlannerFragment : Fragment(R.layout.fragment_planner) {

    private lateinit var map: MapView
    private lateinit var summary: TextView
    private lateinit var modeTv: TextView
    private lateinit var busy: ProgressBar
    private val line = Polyline()
    private val wpMarkers = ArrayList<Marker>()
    private val placeMarkers = ArrayList<Marker>()
    private var working = false
    private var centered = false

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        map = view.findViewById(R.id.planMap)
        summary = view.findViewById(R.id.planSummary)
        modeTv = view.findViewById(R.id.planMode)
        busy = view.findViewById(R.id.planBusy)

        MapStyle.setup(map)
        map.controller.setZoom(14.0)
        val here = RideRepository.location.value
        map.controller.setCenter(if (here != null) GeoPoint(here.latitude, here.longitude) else GeoPoint(42.5, 12.5))
        if (here == null) map.controller.setZoom(6.0) else centered = true

        line.outlinePaint.color = Color.parseColor("#E07B1FA2")
        line.outlinePaint.strokeWidth = 12f
        line.outlinePaint.strokeCap = Paint.Cap.ROUND
        map.overlays.add(MapEventsOverlay(object : MapEventsReceiver {
            override fun singleTapConfirmedHelper(p: GeoPoint): Boolean {
                addPoint(p)
                return true
            }

            // tocco lungo sulla mappa: nuovo segnaposto
            override fun longPressHelper(p: GeoPoint): Boolean {
                newPlacemark(p)
                return true
            }
        }))
        map.overlays.add(line)
        MapStyle.apply(requireContext(), map)

        view.findViewById<View>(R.id.planAdd).setOnClickListener { addPoint(map.mapCenter as GeoPoint) }
        view.findViewById<View>(R.id.planLayers).setOnClickListener {
            MapStyle.choose(requireContext()) { MapStyle.apply(requireContext(), map) }
        }
        view.findViewById<View>(R.id.planMyPos).setOnClickListener {
            val l = RideRepository.location.value
            map.mapOrientation = 0f
            if (l == null) toast("Posizione non ancora disponibile") else {
                map.controller.setZoom(15.0)
                map.controller.animateTo(GeoPoint(l.latitude, l.longitude))
            }
        }
        view.findViewById<View>(R.id.planPlaces).setOnClickListener { placesMenu() }
        view.findViewById<View>(R.id.planUndo).setOnClickListener { if (!working) PlannerState.undo() }
        view.findViewById<View>(R.id.planModeBtn).setOnClickListener { chooseMode() }
        view.findViewById<View>(R.id.planLoop).setOnClickListener {
            val first = PlannerState.firstWaypoint()
            if (first == null || PlannerState.waypoints().size < 2) toast("Aggiungi almeno due punti")
            else addPoint(GeoPoint(first.lat, first.lon))
        }
        view.findViewById<View>(R.id.planReverse).setOnClickListener { PlannerState.reverse() }
        view.findViewById<View>(R.id.planSave).setOnClickListener { save(followAfter = false) }
        view.findViewById<View>(R.id.planGo).setOnClickListener { follow() }
        view.findViewById<View>(R.id.planShare).setOnClickListener {
            val act = activity as? MainActivity ?: return@setOnClickListener
            TrackActions.shareMenu(act, PlannerState.toTrack(PlannerState.name ?: defaultName()))
        }
        view.findViewById<View>(R.id.planClear).setOnClickListener {
            if (PlannerState.waypoints().isEmpty()) return@setOnClickListener
            MaterialAlertDialogBuilder(requireContext())
                .setTitle("Cancellare il percorso in costruzione?")
                .setPositiveButton("Cancella") { _, _ -> PlannerState.clear() }
                .setNegativeButton(R.string.cancel, null)
                .show()
        }

        updateModeText()
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { PlannerState.version.collect { redraw() } }
                launch { Placemarks.items.collect { redrawPlaces() } }
            }
        }
        Placemarks.load(requireContext())
    }

    // ---------------------------------------------------------- segnaposto

    private fun newPlacemark(g: GeoPoint) {
        val ctx = requireContext()
        val p = Placemark(name = Placemarks.nextName(ctx), lat = g.latitude, lon = g.longitude)
        PlacemarkUi.edit(ctx, p, isNew = true) { Placemarks.add(ctx, it) }
    }

    private fun redrawPlaces() {
        if (!isAdded || view == null) return
        val ctx = requireContext()
        for (m in placeMarkers) map.overlays.remove(m)
        placeMarkers.clear()
        for (p in Placemarks.load(ctx)) {
            val m = PlacemarkUi.marker(map, ctx, p)
            // tieni premuto il segnaposto per spostarlo
            m.isDraggable = true
            m.setOnMarkerDragListener(object : Marker.OnMarkerDragListener {
                override fun onMarkerDrag(marker: Marker) {}
                override fun onMarkerDragStart(marker: Marker) {}
                override fun onMarkerDragEnd(marker: Marker) {
                    Placemarks.get(ctx, p.id)?.let {
                        Placemarks.update(ctx, it.copy(lat = marker.position.latitude, lon = marker.position.longitude, ele = null))
                    }
                }
            })
            m.setOnMarkerClickListener { _, _ ->
                placeActions(p.id)
                true
            }
            map.overlays.add(m)
            placeMarkers.add(m)
        }
        map.invalidate()
    }

    private fun placeActions(id: String) {
        val ctx = requireContext()
        val act = activity as? MainActivity ?: return
        val p = Placemarks.get(ctx, id) ?: return
        val items = arrayOf(
            "Naviga qui dalla mia posizione",
            "Aggiungi al percorso in costruzione",
            "Modifica nome, nota e tipo",
            "Elimina"
        )
        MaterialAlertDialogBuilder(ctx)
            .setTitle(p.name + PlacemarkUi.distanceText(p) + if (p.note.isNotBlank()) "\n" + p.note else "")
            .setItems(items) { _, which ->
                when (which) {
                    0 -> PlacemarkUi.navigateTo(act, p)
                    1 -> addPoint(GeoPoint(p.lat, p.lon))
                    2 -> PlacemarkUi.edit(ctx, p, isNew = false) { Placemarks.update(ctx, it) }
                    3 -> MaterialAlertDialogBuilder(ctx)
                        .setTitle("Eliminare \"${p.name}\"?")
                        .setPositiveButton("Elimina") { _, _ -> Placemarks.remove(ctx, p.id) }
                        .setNegativeButton(R.string.cancel, null)
                        .show()
                }
            }
            .show()
    }

    private fun placesMenu() {
        val ctx = requireContext()
        val act = activity as? MainActivity ?: return
        val n = Placemarks.load(ctx).size
        val items = arrayOf(
            "Nuovo segnaposto al centro (mirino)",
            "Elenco segnaposto ($n)",
            "Percorso automatico tra segnaposto…",
            "Esporta / condividi segnaposto (GPX)",
            "Elimina tutti i segnaposto"
        )
        MaterialAlertDialogBuilder(ctx)
            .setTitle("Segnaposto")
            .setItems(items) { _, which ->
                when (which) {
                    0 -> newPlacemark(map.mapCenter as GeoPoint)
                    1 -> placesList()
                    2 -> routeBetweenPlaces()
                    3 -> {
                        val list = Placemarks.load(ctx)
                        if (list.isEmpty()) toast("Nessun segnaposto") else
                            MaterialAlertDialogBuilder(ctx)
                                .setTitle("Segnaposto in GPX")
                                .setPositiveButton("Condividi") { _, _ -> ShareHelper.shareWaypoints(ctx, list) }
                                .setNegativeButton("Salva su file") { _, _ ->
                                    act.saveToFile("Segnaposto SirJoe MTB.gpx", TrackIO.writeWaypointsGpx(list))
                                }
                                .show()
                    }
                    4 -> if (n > 0) MaterialAlertDialogBuilder(ctx)
                        .setTitle("Eliminare tutti i $n segnaposto?")
                        .setPositiveButton("Elimina") { _, _ -> Placemarks.clear(ctx) }
                        .setNegativeButton(R.string.cancel, null)
                        .show()
                }
            }
            .show()
        toast("Suggerimento: tocco lungo sulla mappa = nuovo segnaposto; tieni premuto un segnaposto per spostarlo")
    }

    private fun placesList() {
        val ctx = requireContext()
        val list = Placemarks.load(ctx)
        if (list.isEmpty()) {
            toast("Nessun segnaposto: fai un tocco lungo sulla mappa per crearne uno")
            return
        }
        MaterialAlertDialogBuilder(ctx)
            .setTitle("Segnaposto")
            .setItems(list.map { it.name + " (" + it.type.label + ")" + PlacemarkUi.distanceText(it) }.toTypedArray()) { _, which ->
                val p = list[which]
                map.controller.setZoom(16.0)
                map.controller.animateTo(GeoPoint(p.lat, p.lon))
                placeActions(p.id)
            }
            .show()
    }

    /** Percorso automatico che passa per i segnaposto scelti, nell'ordine dell'elenco. */
    private fun routeBetweenPlaces() {
        val ctx = requireContext()
        val list = Placemarks.load(ctx)
        if (list.isEmpty()) {
            toast("Crea prima almeno un segnaposto (tocco lungo sulla mappa)")
            return
        }
        val labels = arrayOf("La mia posizione (partenza)") + list.map { it.name }.toTypedArray() +
            arrayOf("Ritorno alla partenza (anello)")
        val checked = BooleanArray(labels.size)
        checked[0] = RideRepository.location.value != null
        MaterialAlertDialogBuilder(ctx)
            .setTitle("Punti da collegare (in ordine)")
            .setMultiChoiceItems(labels, checked) { _, which, isChecked -> checked[which] = isChecked }
            .setPositiveButton("Avanti") { _, _ ->
                val pts = ArrayList<TrackPoint>()
                val names = ArrayList<String>()
                if (checked[0]) {
                    val l = RideRepository.location.value
                    if (l == null) toast("Posizione non disponibile: parto dal primo segnaposto") else {
                        pts.add(TrackPoint(l.latitude, l.longitude)); names.add("Posizione")
                    }
                }
                list.forEachIndexed { i, p -> if (checked[i + 1]) { pts.add(p.toPoint()); names.add(p.name) } }
                if (checked.last() && pts.size >= 2) {
                    pts.add(pts.first()); names.add(names.first())
                }
                if (pts.size < 2) {
                    toast("Scegli almeno due punti")
                    return@setPositiveButton
                }
                PlacemarkUi.chooseRouteType(ctx) { profile -> buildRoute(pts, names, profile) }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun buildRoute(pts: List<TrackPoint>, names: List<String>, profile: BRouterClient.Profile) {
        if (working) return
        working = true
        busy.visibility = View.VISIBLE
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val legs = BRouterClient.routeThrough(pts, profile) { i, n ->
                    modeTv.text = "Calcolo tratto $i di $n…"
                }
                PlannerState.clear()
                PlannerState.addFirst(pts.first())
                legs.forEachIndexed { i, leg ->
                    PlannerState.addSegment(leg.lastOrNull() ?: pts[i + 1], if (leg.size >= 2) leg else listOf(pts[i], pts[i + 1]))
                }
                PlannerState.name = names.first() + " → " + names.last()
                zoomToRoute()
                val s = RouteSummary.of(PlannerState.allPoints())
                MaterialAlertDialogBuilder(requireContext())
                    .setTitle("Percorso pronto")
                    .setMessage(
                        "${profile.label}\n${Format.distanceKm(s.distanceM)} km" +
                            (s.ascentM?.let { "  ·  ↗ ${Format.meters(it)} m" } ?: "") +
                            "\n\nPuoi modificarlo, salvarlo o seguirlo subito."
                    )
                    .setPositiveButton("Segui ora") { _, _ -> startFollowing() }
                    .setNeutralButton("Salva") { _, _ -> save(followAfter = false) }
                    .setNegativeButton("Chiudi", null)
                    .show()
            } catch (e: Exception) {
                toast("Percorso non calcolato: ${e.message}")
            } finally {
                working = false
                busy.visibility = View.GONE
                updateModeText()
            }
        }
    }

    fun applyBaseMap() {
        if (isAdded && view != null) MapStyle.apply(requireContext(), map)
    }

    override fun onResume() {
        super.onResume()
        map.onResume()
        if (!centered) {
            RideRepository.location.value?.let {
                centered = true
                map.controller.setZoom(14.0)
                map.controller.setCenter(GeoPoint(it.latitude, it.longitude))
            }
        }
    }

    override fun onPause() {
        map.onPause()
        super.onPause()
    }

    private fun toast(msg: String) {
        if (isAdded) Toast.makeText(requireContext(), msg, Toast.LENGTH_SHORT).show()
    }

    private fun defaultName() = "Percorso " + SimpleDateFormat("yyyy-MM-dd HH-mm", Locale.ITALY).format(Date())

    // -------------------------------------------------------------- punti

    private fun addPoint(g: GeoPoint) {
        if (working) {
            toast("Attendi il calcolo del tratto precedente")
            return
        }
        val p = TrackPoint(g.latitude, g.longitude)
        val last = PlannerState.lastWaypoint()
        if (last == null) {
            PlannerState.addFirst(p)
            return
        }
        if (!Prefs.plannerRouting(requireContext())) {
            PlannerState.addSegment(p, listOf(last, p))
            return
        }
        working = true
        busy.visibility = View.VISIBLE
        val profile = Prefs.plannerProfile(requireContext())
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val seg = BRouterClient.route(last, p, profile)
                // il tratto termina dove finisce la strada/sentiero calcolato
                PlannerState.addSegment(seg.lastOrNull() ?: p, if (seg.size >= 2) seg else listOf(last, p))
            } catch (e: Exception) {
                toast("Calcolo non riuscito (${e.message}). Aggiunto tratto in linea retta.")
                PlannerState.addSegment(p, listOf(last, p))
            } finally {
                working = false
                busy.visibility = View.GONE
            }
        }
    }

    private fun redraw() {
        if (!isAdded) return
        val pts = PlannerState.allPoints()
        line.setPoints(pts.map { GeoPoint(it.lat, it.lon) })

        for (m in wpMarkers) map.overlays.remove(m)
        wpMarkers.clear()
        val wps = PlannerState.waypoints()
        wps.forEachIndexed { i, w ->
            val m = Marker(map)
            m.position = GeoPoint(w.lat, w.lon)
            m.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
            m.icon = ContextCompat.getDrawable(
                requireContext(),
                when {
                    i == 0 -> R.drawable.wp_start
                    i == wps.lastIndex -> R.drawable.wp_end
                    else -> R.drawable.wp_dot
                }
            )
            m.title = if (i == 0) "Partenza" else "Punto ${i + 1}"
            map.overlays.add(m)
            wpMarkers.add(m)
        }

        val s = RouteSummary.of(pts)
        val sb = StringBuilder(Format.distanceKm(s.distanceM) + " km")
        s.ascentM?.let { sb.append("   ↗ ").append(Format.meters(it)).append(" m") }
        s.descentM?.let { sb.append("   ↘ ").append(Format.meters(it)).append(" m") }
        summary.text = sb.toString()
        if (wps.isEmpty()) modeTv.text = "Tocca la mappa per mettere la partenza"
        else updateModeText()
        map.invalidate()
    }

    private fun updateModeText() {
        val ctx = requireContext()
        modeTv.text = if (Prefs.plannerRouting(ctx)) {
            "Segue strade e sentieri: " + Prefs.plannerProfile(ctx).label
        } else {
            "Linea retta tra i punti"
        }
    }

    private fun chooseMode() {
        val ctx = requireContext()
        val profiles = BRouterClient.Profile.values()
        val items = profiles.map { "Segue la mappa – " + it.label } + "Linea retta (senza Internet)"
        MaterialAlertDialogBuilder(ctx)
            .setTitle("Come collegare i punti")
            .setItems(items.toTypedArray()) { _, which ->
                if (which < profiles.size) {
                    Prefs.setPlannerRouting(ctx, true)
                    Prefs.setPlannerProfile(ctx, profiles[which])
                } else {
                    Prefs.setPlannerRouting(ctx, false)
                }
                updateModeText()
            }
            .show()
    }

    // --------------------------------------------------------- salva / segui

    private fun save(followAfter: Boolean) {
        if (PlannerState.allPoints().size < 2) {
            toast("Aggiungi almeno due punti")
            return
        }
        val input = EditText(requireContext()).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            setText(PlannerState.name ?: defaultName())
            selectAll()
        }
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Nome del percorso")
            .setView(input)
            .setPositiveButton("Salva") { _, _ ->
                val name = input.text.toString().trim().ifBlank { defaultName() }
                PlannerState.name = name
                val track = PlannerState.toTrack(name)
                val f = TrackStorage.save(requireContext(), track)
                toast("Salvato in Percorsi: ${f.nameWithoutExtension}")
                if (followAfter) startFollowing()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun follow() {
        if (PlannerState.allPoints().size < 2) {
            toast("Aggiungi almeno due punti")
            return
        }
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Seguire il percorso")
            .setMessage("Vuoi salvarlo prima di partire?")
            .setPositiveButton("Salva e segui") { _, _ -> save(followAfter = true) }
            .setNeutralButton("Segui senza salvare") { _, _ -> startFollowing() }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun startFollowing() {
        RideRepository.setRoute(PlannerState.toTrack(PlannerState.name ?: "Percorso"))
        (activity as? MainActivity)?.selectTab(R.id.nav_map)
    }

    /** Mostra tutto il percorso caricato (es. da "Modifica" nella pagina Percorsi). */
    fun zoomToRoute() {
        if (!isAdded) return
        val pts = PlannerState.allPoints()
        if (pts.size < 2) return
        val box = BoundingBox(pts.maxOf { it.lat }, pts.maxOf { it.lon }, pts.minOf { it.lat }, pts.minOf { it.lon })
        map.post { map.zoomToBoundingBox(box, true, 100) }
        centered = true
    }
}
