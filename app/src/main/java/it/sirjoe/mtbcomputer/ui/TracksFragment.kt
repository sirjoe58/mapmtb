package it.sirjoe.mtbcomputer.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.text.InputType
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import it.sirjoe.mtbcomputer.MainActivity
import it.sirjoe.mtbcomputer.data.OfflineMaps
import it.sirjoe.mtbcomputer.R
import it.sirjoe.mtbcomputer.data.PlannerState
import it.sirjoe.mtbcomputer.data.Prefs
import it.sirjoe.mtbcomputer.data.RideRepository
import it.sirjoe.mtbcomputer.data.RouteSummary
import it.sirjoe.mtbcomputer.data.TrackStorage
import it.sirjoe.mtbcomputer.share.ShareHelper
import it.sirjoe.mtbcomputer.share.TrackActions
import it.sirjoe.mtbcomputer.util.Format
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** Pagina "Percorsi": archivio tracce, import/export in tutti i formati, condivisione, mappe offline. */
class TracksFragment : Fragment() {

    private lateinit var list: ListView
    private lateinit var empty: TextView
    private var files: List<File> = emptyList()

    private val importTrack = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        for (u in uris) importTrackFrom(u)
    }
    private val importMap = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) importMapFrom(uri)
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val ctx = requireContext()
        val dp = resources.displayMetrics.density
        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((12 * dp).toInt(), (12 * dp).toInt(), (12 * dp).toInt(), 0)
        }
        fun button(label: String, onClick: () -> Unit) = MaterialButton(ctx).apply {
            text = label
            setOnClickListener { onClick() }
        }
        root.addView(button("Importa tracce (GPX, FIT, KML, KMZ, TCX, GeoJSON, CSV, ZIP)") {
            importTrack.launch(arrayOf("*/*"))
        })
        root.addView(button("Esporta tutte le tracce (ZIP)") {
            if (!ShareHelper.shareAll(requireContext())) toast("Nessuna traccia da esportare")
        })
        root.addView(button("Mappe offline (OpenAndroMaps)…") { offlineMenu() })
        root.addView(button("Rimuovi percorso attivo dalla mappa") {
            RideRepository.setRoute(null)
            toast("Percorso rimosso")
        })
        root.addView(TextView(ctx).apply {
            text = "Le mie tracce  (tocca per aprire)"
            textSize = 16f
            setPadding(0, (12 * dp).toInt(), 0, (4 * dp).toInt())
        })
        empty = TextView(ctx).apply {
            text = "Nessuna traccia. Registra un'uscita, crea un percorso o importa un file."
            setPadding(0, (8 * dp).toInt(), 0, 0)
        }
        root.addView(empty)
        list = ListView(ctx)
        root.addView(list, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        list.setOnItemClickListener { _, _, pos, _ -> onTrackClick(files[pos]) }
        return root
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    override fun onHiddenChanged(hidden: Boolean) {
        super.onHiddenChanged(hidden)
        if (!hidden) refresh()
    }

    fun refresh() {
        if (!isAdded || view == null) return
        files = TrackStorage.list(requireContext())
        list.adapter = ArrayAdapter(
            requireContext(), android.R.layout.simple_list_item_1,
            files.map { it.nameWithoutExtension }
        )
        empty.visibility = if (files.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun toast(msg: String) {
        if (isAdded) Toast.makeText(requireContext(), msg, Toast.LENGTH_LONG).show()
    }

    // ---------------------------------------------------------------- import

    private fun importTrackFrom(uri: Uri) {
        val ctx = requireContext().applicationContext
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val tracks = withContext(Dispatchers.IO) { ShareHelper.importUri(ctx, uri) }
                toast(
                    if (tracks.size == 1) "Importata: ${tracks[0].name} (${tracks[0].points.size} punti)"
                    else "Importate ${tracks.size} tracce"
                )
                refresh()
            } catch (e: Exception) {
                toast("Import non riuscito: ${e.message}")
            }
        }
    }

    private fun importMapFrom(uri: Uri) {
        val ctx = requireContext().applicationContext
        toast("Copia della mappa in corso… può richiedere qualche minuto")
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val msg = withContext(Dispatchers.IO) { OfflineMaps.import(ctx, uri) }
                (activity as? MainActivity)?.onBaseMapChanged()
                toast(msg)
            } catch (e: Exception) {
                toast("Import mappa non riuscito: ${e.message}")
            }
        }
    }

    private fun offlineMenu() {
        val ctx = requireContext()
        val items = arrayOf(
            "Scarica mappe e stile da openandromaps.org",
            "Importa mappa (.map o .zip) o stile Elevate (.zip)",
            "Elimina mappe offline",
            "Elimina stile (torna allo stile standard)"
        )
        MaterialAlertDialogBuilder(ctx)
            .setTitle("Mappe offline")
            .setItems(items) { _, which ->
                when (which) {
                    0 -> howToDownload()
                    1 -> importMap.launch(arrayOf("*/*"))
                    2 -> MaterialAlertDialogBuilder(ctx)
                        .setTitle("Eliminare tutte le mappe offline?")
                        .setMessage(OfflineMaps.describe(ctx))
                        .setPositiveButton("Elimina") { _, _ ->
                            OfflineMaps.deleteVector(ctx)
                            OfflineMaps.deleteRaster(ctx)
                            Prefs.setBaseMap(ctx, Prefs.BASE_TOPO)
                            (activity as? MainActivity)?.onBaseMapChanged()
                            toast("Mappe offline eliminate")
                        }
                        .setNegativeButton(R.string.cancel, null)
                        .show()
                    3 -> {
                        OfflineMaps.deleteTheme(ctx)
                        (activity as? MainActivity)?.onBaseMapChanged()
                        toast("Stile standard")
                    }
                }
            }
            .show()
    }

    private fun howToDownload() {
        val ctx = requireContext()
        MaterialAlertDialogBuilder(ctx)
            .setTitle("Mappe OpenAndroMaps")
            .setMessage(
                "1. Sul sito vai su Download → Europa → Italia e scarica la tua zona " +
                    "(file .zip della versione \"Android/Mapsforge\").\n\n" +
                    "2. Nella sezione dei temi scarica lo stile \"Elevate\" (file .zip).\n\n" +
                    "3. Torna qui: Mappe offline → Importa, prima la mappa e poi lo stile.\n\n" +
                    "Lo stile si imposta da solo sulla variante MTB. Scarica col Wi-Fi: " +
                    "le mappe regionali pesano centinaia di MB.\n\n" +
                    "Ora installate:\n" + OfflineMaps.describe(ctx)
            )
            .setPositiveButton("Apri il sito") { _, _ ->
                try {
                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.openandromaps.org")))
                } catch (e: Exception) {
                    toast("Nessun browser disponibile")
                }
            }
            .setNegativeButton("Chiudi", null)
            .show()
    }

    // --------------------------------------------------------------- azioni

    private fun onTrackClick(file: File) {
        val act = activity as? MainActivity ?: return
        val track = try {
            TrackStorage.load(file)
        } catch (e: Exception) {
            toast("File non leggibile: ${e.message}")
            return
        }
        val s = RouteSummary.of(track.points)
        val info = StringBuilder("${Format.distanceKm(s.distanceM)} km")
        s.ascentM?.let { info.append("  ·  ↗ ${Format.meters(it)} m") }
        val t0 = track.points.first().time
        val t1 = track.points.last().time
        if (t0 != null && t1 != null && t1 > t0) info.append("  ·  ${Format.duration(t1 - t0)}")

        val items = arrayOf(
            "Mostra e segui sulla mappa",
            "Modifica / allunga nella pagina Crea",
            "Condividi (Strava, social, app…)",
            "Rinomina",
            "Elimina"
        )
        MaterialAlertDialogBuilder(act)
            .setTitle("${track.name}\n$info")
            .setItems(items) { _, which ->
                when (which) {
                    0 -> {
                        RideRepository.setRoute(track)
                        act.selectTab(R.id.nav_map)
                    }
                    1 -> {
                        PlannerState.loadFrom(track)
                        act.openPlannerOnRoute()
                    }
                    2 -> TrackActions.shareMenu(act, track)
                    3 -> rename(file)
                    4 -> confirmDelete(file)
                }
            }
            .show()
    }

    private fun rename(file: File) {
        val input = EditText(requireContext()).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            setText(file.nameWithoutExtension)
            selectAll()
        }
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Nuovo nome")
            .setView(input)
            .setPositiveButton("OK") { _, _ ->
                TrackStorage.rename(file, input.text.toString())
                refresh()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun confirmDelete(file: File) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Eliminare ${file.nameWithoutExtension}?")
            .setPositiveButton("Elimina") { _, _ ->
                file.delete()
                refresh()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }
}
