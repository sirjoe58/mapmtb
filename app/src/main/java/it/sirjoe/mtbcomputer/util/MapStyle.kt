package it.sirjoe.mtbcomputer.util

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.widget.LinearLayout
import android.widget.Toast
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import it.sirjoe.mtbcomputer.data.OfflineMaps
import it.sirjoe.mtbcomputer.data.Prefs
import org.mapsforge.map.model.DisplayModel
import org.mapsforge.map.rendertheme.ExternalRenderTheme
import org.mapsforge.map.rendertheme.XmlRenderTheme
import org.mapsforge.map.rendertheme.XmlRenderThemeMenuCallback
import org.mapsforge.map.rendertheme.XmlRenderThemeStyleMenu
import org.osmdroid.mapsforge.MapsForgeTileProvider
import org.osmdroid.mapsforge.MapsForgeTileSource
import org.osmdroid.tileprovider.MapTileProviderBasic
import org.osmdroid.tileprovider.modules.OfflineTileProvider
import org.osmdroid.tileprovider.tilesource.FileBasedTileSource
import org.osmdroid.tileprovider.tilesource.ITileSource
import org.osmdroid.tileprovider.tilesource.OnlineTileSourceBase
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.tileprovider.tilesource.XYTileSource
import org.osmdroid.tileprovider.util.SimpleRegisterReceiver
import org.osmdroid.util.MapTileIndex
import org.osmdroid.views.CustomZoomButtonsController
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.CopyrightOverlay
import org.osmdroid.views.overlay.TilesOverlay
import org.osmdroid.views.overlay.gestures.RotationGestureOverlay
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/** Impostazione comune delle mappe (pagina Mappa e pagina Crea). */
object MapStyle {

    /** Sovrapposizione degli itinerari MTB segnati (dati OpenStreetMap). */
    private val MTB_ROUTES = XYTileSource(
        "WaymarkedTrailsMTB", 0, 18, 256, ".png",
        arrayOf("https://tile.waymarkedtrails.org/mtb/"),
        "Itinerari MTB: waymarkedtrails.org, dati (c) OpenStreetMap contributors"
    )

    // ------------------------------------------------------------ satellite

    private const val MAPTILER_ATTR = "© MapTiler © OpenStreetMap contributors"
    private const val ESRI_ATTR = "Immagini © Esri, Maxar, Earthstar Geographics"
    const val MAPTILER_KEYS_PAGE = "https://cloud.maptiler.com/account/keys/"

    /**
     * Satellite MapTiler (piano gratuito con chiave personale). Riquadri da 256 px:
     * ogni riquadro conta come 1 richiesta nel limite mensile del piano gratuito.
     * Il nome non contiene la chiave: cambiandola, la cache resta valida.
     */
    private class MapTilerSatellite(private val key: String) : OnlineTileSourceBase(
        "MapTilerSatellite", 0, 19, 256, ".jpg",
        arrayOf("https://api.maptiler.com/maps/satellite/256/"), MAPTILER_ATTR
    ) {
        override fun getTileURLString(index: Long): String =
            baseUrl + MapTileIndex.getZoom(index) + "/" + MapTileIndex.getX(index) + "/" +
                MapTileIndex.getY(index) + ".jpg?key=" + key
    }

    /** Satellite Esri World Imagery (stessa fonte del Planner per Windows; ordine z/y/x). */
    private val ESRI_SAT = object : OnlineTileSourceBase(
        "EsriWorldImagery", 0, 19, 256, ".jpg",
        arrayOf("https://server.arcgisonline.com/ArcGIS/rest/services/World_Imagery/MapServer/tile/"), ESRI_ATTR
    ) {
        override fun getTileURLString(index: Long): String =
            baseUrl + MapTileIndex.getZoom(index) + "/" + MapTileIndex.getY(index) + "/" + MapTileIndex.getX(index)
    }

    /** Attribuzione sulla mappa (obbligatoria per MapTiler ed Esri): in alto a destra. */
    private fun attribution(ctx: Context) = CopyrightOverlay(ctx).apply {
        setAlignBottom(false)
        setAlignRight(true)
        setTextColor(Color.WHITE)
        setTextSize(10)
        val d = ctx.resources.displayMetrics.density
        setOffset((6 * d).toInt(), (6 * d).toInt())
    }

    /** Rotazione con due dita; onRotate viene chiamato quando l'utente gira la mappa. */
    class Rotation(map: MapView, private val callback: () -> Unit) : RotationGestureOverlay(map) {
        override fun onRotate(deltaAngle: Float) {
            super.onRotate(deltaAngle)
            callback()
        }
    }

    fun setup(map: MapView, onUserRotate: () -> Unit = {}) {
        map.setMultiTouchControls(true)
        map.overlays.add(Rotation(map, onUserRotate).apply { isEnabled = true })
        map.zoomController.setVisibility(CustomZoomButtonsController.Visibility.NEVER)
        map.setTilesScaledToDpi(true)
        map.setMinZoomLevel(4.0)
        map.setMaxZoomLevel(19.0)
    }

    /** Nello stile Elevate (e simili) attiva la variante "MTB" se presente. */
    private object MtbStyleMenu : XmlRenderThemeMenuCallback {
        override fun getCategories(menu: XmlRenderThemeStyleMenu): Set<String> {
            val id = menu.layers.keys.firstOrNull { it.contains("mtb", ignoreCase = true) } ?: menu.defaultValue
            val layer = menu.getLayer(id) ?: return emptySet()
            val cats = HashSet<String>(layer.categories)
            for (o in layer.overlays) if (o.isEnabled) cats.addAll(o.categories)
            return cats
        }
    }

    private fun theme(ctx: Context): XmlRenderTheme? {
        val f = OfflineMaps.themeFile(ctx) ?: return null
        return try {
            ExternalRenderTheme(f, MtbStyleMenu)
        } catch (e: Exception) {
            null
        }
    }

    private fun applyVector(ctx: Context, map: MapView, files: List<File>): Boolean = try {
        // Mapsforge disegna già testi e simboli alla densità dello schermo:
        // niente ingrandimento aggiuntivo dei riquadri, altrimenti la mappa viene sfocata.
        val scale = Prefs.vectorScale(ctx)
        DisplayModel.setDefaultUserScaleFactor(scale)
        val t = theme(ctx)
        val src = if (t != null) {
            MapsForgeTileSource.createFromFiles(files.toTypedArray(), t, "sirjoe-tema-$scale")
        } else {
            MapsForgeTileSource.createFromFiles(files.toTypedArray())
        }
        map.setTileProvider(MapsForgeTileProvider(SimpleRegisterReceiver(ctx), src, null))
        map.setTilesScaledToDpi(false)
        map.setMaxZoomLevel(21.0)
        map.setUseDataConnection(false)
        true
    } catch (e: Throwable) {
        Toast.makeText(ctx, "Mappa offline non leggibile: ${e.message}", Toast.LENGTH_LONG).show()
        false
    }

    fun apply(ctx: Context, map: MapView) {
        val base = Prefs.baseMap(ctx)
        val offlineFile = Prefs.offlineMapPath(ctx)?.let { File(it) }?.takeIf { it.exists() }
        val vectorMaps = OfflineMaps.vectorMaps(ctx)
        var online = false
        map.setTilesScaledToDpi(true)
        map.setMaxZoomLevel(19.0)

        if (base == Prefs.BASE_VECTOR && vectorMaps.isNotEmpty() && applyVector(ctx, map, vectorMaps)) {
            // mappa OpenAndroMaps attiva
        } else if (base == Prefs.BASE_OFFLINE && offlineFile != null) {
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
            val key = Prefs.maptilerKey(ctx)
            val src: ITileSource = when {
                base == Prefs.BASE_SAT_MAPTILER && key != null -> MapTilerSatellite(key)
                base == Prefs.BASE_SAT_ESRI -> ESRI_SAT
                base == Prefs.BASE_OSM -> TileSourceFactory.MAPNIK
                else -> TileSourceFactory.OpenTopo
            }
            map.setTileProvider(MapTileProviderBasic(ctx, src))
            map.setTileSource(src)
            map.setUseDataConnection(true)
            online = true
        }

        // attribuzione visibile solo sulle mappe satellitari (le altre sono nella pagina Info)
        map.overlays.filterIsInstance<CopyrightOverlay>().forEach { map.overlays.remove(it) }
        if (online && Prefs.isSatellite(base) && (base != Prefs.BASE_SAT_MAPTILER || Prefs.maptilerKey(ctx) != null)) {
            map.overlays.add(attribution(ctx))
        }

        for (o in map.overlays.filterIsInstance<TilesOverlay>()) {
            map.overlays.remove(o)
            o.onDetach(map)
        }
        if (Prefs.mtbOverlay(ctx) && online) {
            val o = TilesOverlay(MapTileProviderBasic(ctx, MTB_ROUTES), ctx)
            o.setLoadingBackgroundColor(Color.TRANSPARENT)
            o.setLoadingLineColor(Color.TRANSPARENT)
            map.overlays.add(0, o)
        }
        map.invalidate()
    }

    /** Finestra di scelta della mappa; onChanged viene chiamato dopo la scelta. */
    fun choose(ctx: Context, onChanged: () -> Unit) {
        val hasOffline = Prefs.offlineMapPath(ctx)?.let { File(it).exists() } == true
        val hasVector = OfflineMaps.vectorMaps(ctx).isNotEmpty()
        val mtbOn = Prefs.mtbOverlay(ctx)
        val items = mutableListOf(
            "OpenTopoMap (sentieri e rilievo)", "OpenStreetMap standard",
            "Satellite (MapTiler)", "Satellite Esri (senza chiave)"
        )
        if (hasVector) items.add("Offline: OpenAndroMaps")
        if (hasOffline) items.add("Mappa offline importata (MBTiles)")
        items.add(if (mtbOn) "Nascondi itinerari MTB" else "Mostra itinerari MTB")
        val ridesOn = Prefs.showRides(ctx)
        items.add(if (ridesOn) "Nascondi le mie uscite registrate" else "Mostra le mie uscite registrate")
        if (hasVector) items.add("Dimensione testi mappa offline…")
        items.add(if (Prefs.maptilerKey(ctx) == null) "Chiave MapTiler per il satellite…" else "Chiave MapTiler (cambia)…")

        MaterialAlertDialogBuilder(ctx)
            .setTitle("Mappa")
            .setItems(items.toTypedArray()) { _, which ->
                val label = items[which]
                when {
                    label.startsWith("OpenTopo") -> Prefs.setBaseMap(ctx, Prefs.BASE_TOPO)
                    label.startsWith("OpenStreetMap") -> Prefs.setBaseMap(ctx, Prefs.BASE_OSM)
                    label.startsWith("Satellite Esri") -> {
                        Prefs.setBaseMap(ctx, Prefs.BASE_SAT_ESRI)
                        satelliteNotice(ctx)
                    }
                    label.startsWith("Satellite") -> {
                        if (Prefs.maptilerKey(ctx) == null) {
                            // prima volta: chiede la chiave, poi attiva il satellite
                            askMaptilerKey(ctx) {
                                if (Prefs.maptilerKey(ctx) != null) Prefs.setBaseMap(ctx, Prefs.BASE_SAT_MAPTILER)
                                onChanged()
                            }
                            return@setItems
                        }
                        Prefs.setBaseMap(ctx, Prefs.BASE_SAT_MAPTILER)
                        satelliteNotice(ctx)
                    }
                    label.startsWith("Chiave MapTiler") -> {
                        askMaptilerKey(ctx, onChanged)
                        return@setItems
                    }
                    label.startsWith("Dimensione") -> {
                        chooseScale(ctx, onChanged)
                        return@setItems
                    }
                    label.startsWith("Offline:") -> Prefs.setBaseMap(ctx, Prefs.BASE_VECTOR)
                    label.startsWith("Mappa offline") -> Prefs.setBaseMap(ctx, Prefs.BASE_OFFLINE)
                    label.contains("uscite registrate") -> Prefs.setShowRides(ctx, !ridesOn)
                    else -> {
                        Prefs.setMtbOverlay(ctx, !mtbOn)
                        if (!mtbOn) Toast.makeText(ctx, "Itinerari MTB visibili (serve Internet)", Toast.LENGTH_SHORT).show()
                    }
                }
                onChanged()
            }
            .show()
    }

    private fun chooseScale(ctx: Context, onChanged: () -> Unit) {
        val values = floatArrayOf(0.8f, 1.0f, 1.25f, 1.5f)
        val labels = arrayOf("Piccoli (più dettaglio)", "Normali", "Grandi", "Molto grandi")
        val cur = Prefs.vectorScale(ctx)
        val sel = values.indexOfFirst { kotlin.math.abs(it - cur) < 0.01f }.coerceAtLeast(1)
        MaterialAlertDialogBuilder(ctx)
            .setTitle("Testi e simboli della mappa offline")
            .setSingleChoiceItems(labels, sel) { d, which ->
                Prefs.setVectorScale(ctx, values[which])
                d.dismiss()
                onChanged()
            }
            .show()
    }

    private fun satelliteNotice(ctx: Context) {
        val offline = if (Prefs.mtbOverlay(ctx)) " con itinerari MTB" else ""
        Toast.makeText(
            ctx,
            "Satellite$offline (serve Internet). Le zone già viste restano in memoria anche senza segnale.",
            Toast.LENGTH_LONG
        ).show()
    }

    /** Finestra per inserire la chiave MapTiler; onSaved viene chiamato dopo il salvataggio. */
    fun askMaptilerKey(ctx: Context, onSaved: () -> Unit) {
        val d = ctx.resources.displayMetrics.density
        fun dp(v: Int) = (v * d).toInt()

        val steps = android.widget.TextView(ctx).apply {
            text = "1. Tocca \"Apri il sito\" e registrati su MapTiler (piano Free, gratis)\n" +
                "2. Nella pagina API keys copia la chiave (tieni premuto → Copia)\n" +
                "3. Torna qui e incollala nel riquadro qui sotto, poi tocca Salva"
            textSize = 14f
        }
        val field = com.google.android.material.textfield.TextInputLayout(
            ctx, null, com.google.android.material.R.attr.textInputOutlinedStyle
        ).apply { hint = "Chiave MapTiler" }
        val input = com.google.android.material.textfield.TextInputEditText(field.context).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            isSingleLine = true
            setText(Prefs.maptilerKey(ctx) ?: "")
        }
        field.addView(input)
        val paste = com.google.android.material.button.MaterialButton(
            ctx, null, com.google.android.material.R.attr.materialButtonOutlinedStyle
        ).apply {
            text = "Incolla la chiave copiata"
            setOnClickListener {
                val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as? android.content.ClipboardManager
                val txt = cm?.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(ctx)?.toString()?.trim()
                if (txt.isNullOrEmpty()) {
                    Toast.makeText(ctx, "Negli appunti non c'è niente: copia prima la chiave dal sito", Toast.LENGTH_LONG).show()
                } else {
                    input.setText(txt)
                    input.setSelection(txt.length)
                }
            }
        }
        val note = android.widget.TextView(ctx).apply {
            text = "La chiave resta solo sul telefono. Il piano gratuito ha un limite mensile di richieste: " +
                "le zone già viste restano in memoria e non si richiedono di nuovo."
            textSize = 12f
            alpha = 0.75f
        }
        val box = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(8), dp(24), 0)
            val full = LinearLayout.LayoutParams.MATCH_PARENT
            val wrap = LinearLayout.LayoutParams.WRAP_CONTENT
            addView(steps, LinearLayout.LayoutParams(full, wrap))
            addView(field, LinearLayout.LayoutParams(full, wrap).apply { topMargin = dp(16) })
            addView(paste, LinearLayout.LayoutParams(full, wrap).apply { topMargin = dp(8) })
            addView(note, LinearLayout.LayoutParams(full, wrap).apply { topMargin = dp(12) })
        }
        val scroll = android.widget.ScrollView(ctx).apply { addView(box) }

        MaterialAlertDialogBuilder(ctx)
            .setTitle("Chiave MapTiler per il satellite")
            .setView(scroll)
            .setPositiveButton("Salva") { _, _ ->
                val key = input.text?.toString()?.trim().orEmpty()
                if (key.isEmpty()) {
                    Prefs.setMaptilerKey(ctx, null)
                    if (Prefs.baseMap(ctx) == Prefs.BASE_SAT_MAPTILER) Prefs.setBaseMap(ctx, Prefs.BASE_TOPO)
                    Toast.makeText(ctx, "Chiave MapTiler rimossa", Toast.LENGTH_SHORT).show()
                    onSaved()
                    return@setPositiveButton
                }
                Prefs.setMaptilerKey(ctx, key)
                checkMaptilerKey(ctx, key)
                onSaved()
            }
            .setNeutralButton("Apri il sito") { _, _ ->
                try {
                    ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(MAPTILER_KEYS_PAGE)))
                    Toast.makeText(ctx, "Copiata la chiave, torna qui: Info → Mappa satellitare", Toast.LENGTH_LONG).show()
                } catch (_: Exception) {
                    Toast.makeText(ctx, "Apri nel browser: $MAPTILER_KEYS_PAGE", Toast.LENGTH_LONG).show()
                }
            }
            .setNegativeButton("Annulla", null)
            .show()
    }

    /** Prova la chiave scaricando un riquadro e mostra l'esito. */
    private fun checkMaptilerKey(ctx: Context, key: String) {
        val app = ctx.applicationContext
        val main = Handler(Looper.getMainLooper())
        Thread {
            val msg = try {
                val c = URL("https://api.maptiler.com/maps/satellite/256/0/0/0.jpg?key=$key").openConnection() as HttpURLConnection
                c.connectTimeout = 10_000
                c.readTimeout = 10_000
                c.setRequestProperty("User-Agent", org.osmdroid.config.Configuration.getInstance().userAgentValue)
                val code = c.responseCode
                c.disconnect()
                when (code) {
                    200 -> "Chiave MapTiler valida: satellite pronto"
                    401, 403 -> "Chiave MapTiler non valida: controlla di averla copiata tutta"
                    429 -> "Limite mensile MapTiler raggiunto: il satellite tornerà il mese prossimo"
                    else -> "Risposta MapTiler inattesa ($code): riprova più tardi"
                }
            } catch (e: Exception) {
                "Impossibile verificare la chiave (serve Internet). Salvata comunque."
            }
            main.post { Toast.makeText(app, msg, Toast.LENGTH_LONG).show() }
        }.start()
    }
}
