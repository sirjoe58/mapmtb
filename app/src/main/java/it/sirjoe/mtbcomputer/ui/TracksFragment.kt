package it.sirjoe.mtbcomputer.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.FileProvider
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import it.sirjoe.mtbcomputer.MainActivity
import it.sirjoe.mtbcomputer.R
import it.sirjoe.mtbcomputer.data.Prefs
import it.sirjoe.mtbcomputer.data.RideRepository
import it.sirjoe.mtbcomputer.data.Track
import it.sirjoe.mtbcomputer.data.TrackFormat
import it.sirjoe.mtbcomputer.data.TrackIO
import it.sirjoe.mtbcomputer.data.TrackStorage
import it.sirjoe.mtbcomputer.util.Format
import it.sirjoe.mtbcomputer.util.Geo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

/** Pagina "Percorsi": archivio tracce, import/export in tutti i formati, mappe offline. */
class TracksFragment : Fragment() {

    private lateinit var list: ListView
    private lateinit var empty: TextView
    private var files: List<File> = emptyList()
    private var pendingExport: String? = null

    private val importTrack = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) importTrackFrom(uri)
    }
    private val importMap = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) importMapFrom(uri)
    }
    private val saveFile = registerForActivityResult(ActivityResultContracts.CreateDocument("*/*")) { uri ->
        val text = pendingExport
        pendingExport = null
        if (uri != null && text != null) {
            try {
                requireContext().contentResolver.openOutputStream(uri)?.use { it.write(text.toByteArray()) }
                toast("File salvato")
            } catch (e: Exception) {
                toast("Errore nel salvataggio: ${e.message}")
            }
        }
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
        root.addView(button("Importa traccia (GPX, KML, KMZ, TCX, GeoJSON, CSV)") {
            importTrack.launch(arrayOf("*/*"))
        })
        root.addView(button("Importa mappa offline (MBTiles)") {
            importMap.launch(arrayOf("*/*"))
        })
        root.addView(button("Rimuovi percorso attivo dalla mappa") {
            RideRepository.setRoute(null)
            toast("Percorso rimosso")
        })
        root.addView(TextView(ctx).apply {
            text = "Le mie tracce"
            textSize = 16f
            setPadding(0, (12 * dp).toInt(), 0, (4 * dp).toInt())
        })
        empty = TextView(ctx).apply {
            text = "Nessuna traccia. Registra un'uscita o importa un file."
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

    private fun refresh() {
        if (!isAdded) return
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

    private fun displayName(uri: Uri): String {
        requireContext().contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { c -> if (c.moveToFirst()) return c.getString(0) ?: "traccia" }
        return uri.lastPathSegment ?: "traccia"
    }

    private fun importTrackFrom(uri: Uri) {
        val ctx = requireContext().applicationContext
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val saved = withContext(Dispatchers.IO) {
                    val name = displayName(uri)
                    val bytes = ctx.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                        ?: throw IllegalArgumentException("Impossibile leggere il file")
                    if (bytes.size > 50 * 1024 * 1024) throw IllegalArgumentException("File troppo grande (max 50 MB)")
                    val track = TrackIO.parse(bytes, name)
                    TrackStorage.save(ctx, track)
                    track
                }
                toast("Importata: ${saved.name} (${saved.points.size} punti)")
                refresh()
            } catch (e: Exception) {
                toast("Import non riuscito: ${e.message}")
            }
        }
    }

    private fun importMapFrom(uri: Uri) {
        val ctx = requireContext().applicationContext
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val dest = withContext(Dispatchers.IO) {
                    val name = displayName(uri)
                    val ext = name.substringAfterLast('.', "").lowercase(Locale.ROOT)
                    if (ext !in listOf("mbtiles", "sqlite", "zip", "gemf")) {
                        throw IllegalArgumentException("Formato non supportato: usa un file .mbtiles, .sqlite, .zip o .gemf")
                    }
                    val dir = File(ctx.filesDir, "maps").apply { mkdirs() }
                    dir.listFiles()?.forEach { it.delete() }
                    val out = File(dir, name)
                    ctx.contentResolver.openInputStream(uri)?.use { input ->
                        out.outputStream().use { input.copyTo(it) }
                    } ?: throw IllegalArgumentException("Impossibile leggere il file")
                    out
                }
                Prefs.setOfflineMapPath(ctx, dest.absolutePath)
                Prefs.setBaseMap(ctx, Prefs.BASE_OFFLINE)
                (activity as? MainActivity)?.onBaseMapChanged()
                toast("Mappa offline attiva: ${dest.name}")
            } catch (e: Exception) {
                toast("Import mappa non riuscito: ${e.message}")
            }
        }
    }

    // --------------------------------------------------------------- azioni

    private fun onTrackClick(file: File) {
        val track = try {
            TrackStorage.load(file)
        } catch (e: Exception) {
            toast("File non leggibile: ${e.message}")
            return
        }
        val km = Format.distanceKm(Geo.cumulative(track.points).lastOrNull() ?: 0.0)
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(track.name)
            .setMessage("$km km  -  ${track.points.size} punti")
            .setItems(arrayOf("Mostra e segui sulla mappa", "Esporta / condividi", "Elimina")) { _, which ->
                when (which) {
                    0 -> {
                        RideRepository.setRoute(track)
                        (activity as? MainActivity)?.selectTab(R.id.nav_map)
                    }
                    1 -> chooseFormat(track)
                    2 -> confirmDelete(file)
                }
            }
            .show()
    }

    private fun chooseFormat(track: Track) {
        val formats = TrackFormat.values()
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Formato")
            .setItems(formats.map { it.label }.toTypedArray()) { _, which ->
                val f = formats[which]
                val text = TrackIO.write(track, f)
                MaterialAlertDialogBuilder(requireContext())
                    .setTitle(track.name + "." + f.ext)
                    .setPositiveButton("Condividi") { _, _ -> share(track.name, f, text) }
                    .setNegativeButton("Salva su file") { _, _ ->
                        pendingExport = text
                        saveFile.launch(track.name + "." + f.ext)
                    }
                    .show()
            }
            .show()
    }

    private fun share(name: String, format: TrackFormat, text: String) {
        val ctx = requireContext()
        val dir = File(ctx.cacheDir, "export").apply { mkdirs() }
        val safe = name.replace(Regex("[\\\\/:*?\"<>|]"), "_")
        val f = File(dir, "$safe.${format.ext}")
        f.writeText(text)
        val uri = FileProvider.getUriForFile(ctx, ctx.packageName + ".fileprovider", f)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = format.mime
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(send, "Condividi traccia"))
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
