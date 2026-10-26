package it.sirjoe.mtbcomputer.ui

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.drawable.BitmapDrawable
import android.net.Uri
import android.text.InputType
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.core.content.FileProvider
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import it.sirjoe.mtbcomputer.BuildConfig
import it.sirjoe.mtbcomputer.MainActivity
import it.sirjoe.mtbcomputer.R
import it.sirjoe.mtbcomputer.data.BleParse
import it.sirjoe.mtbcomputer.data.BRouterClient
import it.sirjoe.mtbcomputer.data.Poi
import it.sirjoe.mtbcomputer.data.PoiType
import it.sirjoe.mtbcomputer.data.Pois
import it.sirjoe.mtbcomputer.data.Prefs
import it.sirjoe.mtbcomputer.data.Report
import it.sirjoe.mtbcomputer.data.ReportType
import it.sirjoe.mtbcomputer.data.Reports
import it.sirjoe.mtbcomputer.data.RideRepository
import it.sirjoe.mtbcomputer.data.SosProtocol
import it.sirjoe.mtbcomputer.data.Track
import it.sirjoe.mtbcomputer.data.TrackPoint
import it.sirjoe.mtbcomputer.service.SosBike
import it.sirjoe.mtbcomputer.util.Format
import it.sirjoe.mtbcomputer.util.Geo
import kotlinx.coroutines.launch
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.FolderOverlay
import org.osmdroid.views.overlay.Marker
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Pulsante ⋯ della mappa: SOS manuale, ritorno alla partenza, segnalazioni con foto,
 * punti utili lungo il percorso. Disegna anche segnalazioni e punti utili sulla mappa.
 */
class MapTools(
    private val f: Fragment,
    private val map: MapView,
    private val takePhoto: ActivityResultLauncher<Uri>?,
    private val belowCursor: () -> Int
) {
    private val ctx: Context get() = f.requireContext()
    private fun toast(t: String) = Toast.makeText(ctx, t, Toast.LENGTH_LONG).show()

    fun menu() {
        val items = arrayOf(
            "🆘  Invia SOS ora",
            "↩  Riportami alla partenza",
            "📸  Segnala un problema sul sentiero",
            "📋  Segnalazioni salvate (${Reports.load(ctx).size})",
            "💧  Punti utili lungo il percorso",
            "📡  Stato SOS e sensori"
        )
        MaterialAlertDialogBuilder(ctx)
            .setTitle("Strumenti")
            .setItems(items) { _, w ->
                when (w) {
                    0 -> manualSos()
                    1 -> backToStart()
                    2 -> newReport()
                    3 -> reportsList()
                    4 -> poisMenu()
                    5 -> status()
                }
            }
            .show()
    }

    // ------------------------------------------------------------ SOS

    private fun manualSos() {
        if (Prefs.sosRole(ctx) != Prefs.SOS_ROLE_BIKE || Prefs.sosPeer(ctx) == null) {
            MaterialAlertDialogBuilder(ctx)
                .setTitle("SOS non configurato")
                .setMessage("Per mandare l'SOS dal telefono in tasca impostalo nella pagina Info → SOS.\n\nIn emergenza puoi chiamare subito il 112 (funziona anche senza SIM).")
                .setPositiveButton("Chiama il 112") { _, _ -> dial112() }
                .setNeutralButton("Vai a Info") { _, _ -> (f.activity as? MainActivity)?.selectTab(R.id.nav_info) }
                .setNegativeButton("Chiudi", null)
                .show()
            return
        }
        MaterialAlertDialogBuilder(ctx)
            .setTitle("Inviare la richiesta d'aiuto?")
            .setMessage("Parte un conto alla rovescia di 10 secondi (puoi ancora annullare), poi il telefono in tasca manda l'SMS con la tua posizione.")
            .setPositiveButton("Invia SOS") { _, _ ->
                SosBike.start(ctx)
                SosBike.trigger(ctx, "SOS manuale", 10)
            }
            .setNeutralButton("Chiama il 112") { _, _ -> dial112() }
            .setNegativeButton("Annulla", null)
            .show()
    }

    private fun dial112() {
        try {
            ctx.startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:112")))
        } catch (_: Exception) {
            toast("Componi 112")
        }
    }

    private fun status() {
        val s = RideRepository.sos.value
        val v = RideRepository.sensors.value
        val sos = when (Prefs.sosRole(ctx)) {
            Prefs.SOS_ROLE_BIKE -> if (s.connected) "Collegato a ${Prefs.sosPeerName(ctx)}" else (s.message ?: "Si collega quando avvii la registrazione")
            Prefs.SOS_ROLE_POCKET -> "Questo è il telefono in tasca"
            else -> "Spento (pagina Info)"
        }
        val sensors = BleParse.Kind.values().joinToString("\n") { k ->
            val name = Prefs.sensorName(ctx, k) ?: return@joinToString "${k.label}: nessuno"
            "${k.label}: $name ${v.status[k.id]?.let { "($it)" } ?: ""}"
        }
        val values = listOfNotNull(
            v.hr?.let { "♥ $it bpm" }, v.cadence?.let { "⟳ $it rpm" }, v.power?.let { "⚡ $it W" }
        ).joinToString("   ")
        MaterialAlertDialogBuilder(ctx)
            .setTitle("Stato")
            .setMessage("SOS: $sos\n\n$sensors" + if (values.isNotEmpty()) "\n\n$values" else "")
            .setPositiveButton("Chiudi", null)
            .show()
    }

    // ------------------------------------------------------------ ritorno

    private fun backToStart() {
        val stats = RideRepository.stats.value
        val recorded = RideRepository.snapshotPoints()
        val route = RideRepository.route.value
        val here = RideRepository.location.value
        val start: TrackPoint? = when {
            stats.recording && recorded.size >= 2 -> recorded.first()
            route != null && route.points.isNotEmpty() -> route.points.first()
            else -> null
        }
        if (start == null) {
            toast("Nessuna partenza: avvia la registrazione all'inizio del giro"); return
        }
        val items = ArrayList<String>()
        val actions = ArrayList<() -> Unit>()
        if (stats.recording && recorded.size >= 2) {
            items.add("Ripercorri al contrario la strada fatta (senza Internet)")
            actions.add { followBack(recorded) }
        }
        if (here != null) {
            items.add("Strada più breve sui sentieri (serve Internet)")
            actions.add { shortestBack(TrackPoint(here.latitude, here.longitude), start) }
        }
        val dist = here?.let { Geo.distance(it.latitude, it.longitude, start.lat, start.lon) }
        MaterialAlertDialogBuilder(ctx)
            .setTitle("Riportami alla partenza" + (dist?.let { " (in linea d'aria ${Format.distanceKm(it)} km)" } ?: ""))
            .setItems(items.toTypedArray()) { _, w -> actions[w]() }
            .setNegativeButton("Annulla", null)
            .show()
    }

    private fun followBack(recorded: List<TrackPoint>) {
        // al contrario e alleggerita (un punto ogni ~5 m basta per guidarti)
        val back = ArrayList<TrackPoint>()
        var last: TrackPoint? = null
        for (p in recorded.asReversed()) {
            if (last == null || Geo.distance(last, p) >= 5.0) {
                back.add(p.copy(time = null, hr = null, cad = null, power = null)); last = p
            }
        }
        if (back.size < 2) {
            toast("Strada fatta troppo corta"); return
        }
        RideRepository.setRoute(Track("Ritorno alla partenza", back))
        toast("Segui la linea viola: è la strada che hai fatto, al contrario")
    }

    private fun shortestBack(from: TrackPoint, to: TrackPoint) {
        toast("Calcolo il ritorno…")
        f.viewLifecycleOwner.lifecycleScope.launch {
            try {
                val pts = BRouterClient.route(from, to, BRouterClient.Profile.OFFROAD)
                RideRepository.setRoute(Track("Ritorno alla partenza", pts))
                toast("Ritorno: ${Format.distanceKm(Geo.cumulative(pts).last())} km")
            } catch (e: Exception) {
                toast("Ritorno non calcolato (serve Internet): ${e.message}\nUsa \"Ripercorri al contrario\".")
            }
        }
    }

    // ------------------------------------------------------------ segnalazioni

    private var pendingType: ReportType? = null
    private var pendingFile: File? = null
    private var pendingLat = 0.0
    private var pendingLon = 0.0

    private fun newReport() {
        val l = RideRepository.location.value
        if (l == null) {
            toast("Posizione non disponibile: attendi il GPS"); return
        }
        val types = ReportType.values()
        MaterialAlertDialogBuilder(ctx)
            .setTitle("Che cosa segnali?")
            .setItems(types.map { it.emoji + "  " + it.label }.toTypedArray()) { _, w ->
                pendingType = types[w]
                pendingLat = l.latitude; pendingLon = l.longitude
                MaterialAlertDialogBuilder(ctx)
                    .setTitle(types[w].label)
                    .setMessage("Vuoi scattare una foto?")
                    .setPositiveButton("Scatta foto") { _, _ -> photo() }
                    .setNeutralButton("Senza foto") { _, _ -> askNote(null) }
                    .setNegativeButton("Annulla", null)
                    .show()
            }
            .show()
    }

    private fun photo() {
        val file = Reports.newPhotoFile(ctx)
        pendingFile = file
        val uri = FileProvider.getUriForFile(ctx, BuildConfig.APPLICATION_ID + ".fileprovider", file)
        try {
            takePhoto!!.launch(uri)
        } catch (_: Exception) {
            toast("Fotocamera non disponibile")
            askNote(null)
        }
    }

    /** Ritorno dalla fotocamera. */
    fun onPhoto(ok: Boolean) {
        val file = pendingFile
        pendingFile = null
        askNote(if (ok && file != null && file.length() > 0) file.name else {
            file?.delete(); null
        })
    }

    private fun askNote(photo: String?) {
        val type = pendingType ?: return
        val input = EditText(ctx).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            hint = "Nota (facoltativa)"
        }
        val box = LinearLayout(ctx).apply {
            val d = ctx.resources.displayMetrics.density
            setPadding((20 * d).toInt(), (8 * d).toInt(), (20 * d).toInt(), 0)
            addView(input)
        }
        MaterialAlertDialogBuilder(ctx)
            .setTitle(type.emoji + " " + type.label)
            .setView(box)
            .setPositiveButton("Salva") { _, _ ->
                Reports.add(ctx, Report(type = type, note = input.text.toString().trim(), lat = pendingLat, lon = pendingLon,
                    time = System.currentTimeMillis(), photo = photo))
                toast("Segnalazione salvata" + if (RideRepository.stats.value.recording) ": sarà anche nella traccia dell'uscita" else "")
                pendingType = null
            }
            .setNegativeButton("Annulla") { _, _ ->
                photo?.let { File(Reports.dir(ctx), it).delete() }
                pendingType = null
            }
            .show()
    }

    private fun reportsList() {
        val list = Reports.load(ctx).sortedByDescending { it.time }
        if (list.isEmpty()) {
            toast("Nessuna segnalazione: usa \"Segnala un problema sul sentiero\""); return
        }
        val fmt = SimpleDateFormat("dd/MM HH:mm", Locale.ITALY)
        val here = RideRepository.location.value
        MaterialAlertDialogBuilder(ctx)
            .setTitle("Segnalazioni")
            .setItems(list.map { r ->
                r.title + " · " + fmt.format(Date(r.time)) +
                    (here?.let { " · " + Format.distanceKm(Geo.distance(it.latitude, it.longitude, r.lat, r.lon)) + " km" } ?: "") +
                    (if (r.photo != null) " 📷" else "")
            }.toTypedArray()) { _, w -> showReport(list[w]) }
            .setNegativeButton("Chiudi", null)
            .show()
    }

    fun showReport(r: Report) {
        val d = ctx.resources.displayMetrics.density
        val box = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((20 * d).toInt(), (8 * d).toInt(), (20 * d).toInt(), 0)
        }
        Reports.photoFile(ctx, r)?.let { file ->
            val opts = BitmapFactory.Options().apply { inSampleSize = 4 }
            BitmapFactory.decodeFile(file.absolutePath, opts)?.let { bmp ->
                box.addView(ImageView(ctx).apply {
                    setImageBitmap(bmp)
                    adjustViewBounds = true
                }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, (220 * d).toInt()))
            }
        }
        box.addView(TextView(ctx).apply {
            text = (if (r.note.isNotBlank()) r.note + "\n" else "") +
                SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.ITALY).format(Date(r.time))
            setPadding(0, (8 * d).toInt(), 0, 0)
        })
        MaterialAlertDialogBuilder(ctx)
            .setTitle(r.title)
            .setView(box)
            .setPositiveButton("Condividi") { _, _ -> share(r) }
            .setNeutralButton("Elimina") { _, _ ->
                MaterialAlertDialogBuilder(ctx)
                    .setTitle("Eliminare la segnalazione?")
                    .setPositiveButton("Elimina") { _, _ -> Reports.remove(ctx, r) }
                    .setNegativeButton("Annulla", null)
                    .show()
            }
            .setNegativeButton("Mostra sulla mappa") { _, _ ->
                map.controller.setZoom(17.0)
                map.controller.animateTo(GeoPoint(r.lat, r.lon))
            }
            .show()
    }

    private fun share(r: Report) {
        val text = r.title + (if (r.note.isNotBlank()) ": " + r.note else "") + "\n" +
            SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.ITALY).format(Date(r.time)) + "\n" +
            SosProtocol.mapLink(r.lat, r.lon) + "\n(segnalazione SirJoe MTB)"
        val i = Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_TEXT, text)
        val file = Reports.photoFile(ctx, r)
        if (file != null) {
            val uri = FileProvider.getUriForFile(ctx, BuildConfig.APPLICATION_ID + ".fileprovider", file)
            i.type = "image/jpeg"
            i.putExtra(Intent.EXTRA_STREAM, uri)
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } else i.type = "text/plain"
        ctx.startActivity(Intent.createChooser(i, "Condividi la segnalazione"))
    }

    // ------------------------------------------------------------ punti utili

    private fun poisMenu() {
        val route = RideRepository.route.value
        if (route == null || route.points.size < 2) {
            toast("Carica prima un percorso da seguire (pagina Percorsi o Crea)"); return
        }
        val have = RideRepository.pois.value
        if (have.isNotEmpty()) {
            MaterialAlertDialogBuilder(ctx)
                .setTitle("Punti utili (${have.size})")
                .setItems(have.map { it.type.emoji + " " + it.name + " · km " + Format.distanceKm(it.along) }.toTypedArray()) { _, w ->
                    map.controller.setZoom(16.5)
                    map.controller.animateTo(GeoPoint(have[w].lat, have[w].lon))
                }
                .setPositiveButton("Cerca di nuovo") { _, _ -> askPoiTypes(route) }
                .setNegativeButton("Chiudi", null)
                .show()
        } else askPoiTypes(route)
    }

    fun askPoiTypes(route: Track, onDone: ((List<Poi>) -> Unit)? = null) {
        val types = PoiType.values()
        val checked = BooleanArray(types.size) { true }
        MaterialAlertDialogBuilder(ctx)
            .setTitle("Cosa cerco lungo il percorso? (serve Internet)")
            .setMultiChoiceItems(types.map { it.emoji + " " + it.label }.toTypedArray(), checked) { _, w, v -> checked[w] = v }
            .setPositiveButton("Cerca") { _, _ ->
                val sel = types.filterIndexed { i, _ -> checked[i] }.toSet()
                if (sel.isEmpty()) return@setPositiveButton
                toast("Cerco i punti utili su OpenStreetMap…")
                f.viewLifecycleOwner.lifecycleScope.launch {
                    try {
                        val list = Pois.fetch(route.points, sel)
                        Pois.save(ctx, route.points, list)
                        if (RideRepository.route.value?.points === route.points) RideRepository.setPois(list)
                        val counts = list.groupBy { it.type }.entries.joinToString(", ") { "${it.value.size} ${it.key.emoji}" }
                        toast(if (list.isEmpty()) "Nessun punto utile trovato vicino al percorso"
                        else "Trovati ${list.size} punti utili ($counts): salvati anche per l'uso senza Internet")
                        onDone?.invoke(list)
                    } catch (e: Exception) {
                        toast("Ricerca non riuscita: ${e.message}")
                    }
                }
            }
            .setNegativeButton("Annulla", null)
            .show()
    }

    // ------------------------------------------------------------ disegno

    private var poiLayer: FolderOverlay? = null
    private var reportLayer: FolderOverlay? = null
    private val iconCache = HashMap<String, BitmapDrawable>()

    private fun icon(emoji: String, color: Int): BitmapDrawable = iconCache.getOrPut(emoji + color) {
        val d = ctx.resources.displayMetrics.density
        val size = (30 * d).toInt()
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color }
        val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = Color.WHITE; style = Paint.Style.STROKE; strokeWidth = 2 * d }
        c.drawCircle(size / 2f, size / 2f, size / 2f - 2 * d, fill)
        c.drawCircle(size / 2f, size / 2f, size / 2f - 2 * d, ring)
        val txt = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 15 * d; textAlign = Paint.Align.CENTER }
        c.drawText(emoji, size / 2f, size / 2f + 5.5f * d, txt)
        BitmapDrawable(ctx.resources, bmp)
    }

    fun drawPois(list: List<Poi>) {
        poiLayer?.let { map.overlays.remove(it) }
        poiLayer = null
        if (list.isNotEmpty()) {
            val folder = FolderOverlay()
            for (p in list) {
                val m = Marker(map)
                m.position = GeoPoint(p.lat, p.lon)
                m.icon = icon(p.type.emoji, p.type.color)
                m.setAnchor(0.5f, 0.5f)
                m.setOnMarkerClickListener { _, _ ->
                    toast(p.type.emoji + " " + p.name + " · km " + Format.distanceKm(p.along) + " del percorso")
                    true
                }
                folder.add(m)
            }
            poiLayer = folder
            map.overlays.add(belowCursor().coerceIn(0, map.overlays.size), folder)
        }
        map.invalidate()
    }

    fun drawReports(list: List<Report>) {
        reportLayer?.let { map.overlays.remove(it) }
        reportLayer = null
        if (list.isNotEmpty()) {
            val folder = FolderOverlay()
            for (r in list) {
                val m = Marker(map)
                m.position = GeoPoint(r.lat, r.lon)
                m.icon = icon(r.type.emoji, r.type.color)
                m.setAnchor(0.5f, 0.5f)
                m.setOnMarkerClickListener { _, _ -> showReport(r); true }
                folder.add(m)
            }
            reportLayer = folder
            map.overlays.add(belowCursor().coerceIn(0, map.overlays.size), folder)
        }
        map.invalidate()
    }
}
