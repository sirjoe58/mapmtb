package it.sirjoe.mtbcomputer.util

import android.content.Context
import android.graphics.Color
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
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.tileprovider.tilesource.XYTileSource
import org.osmdroid.tileprovider.util.SimpleRegisterReceiver
import org.osmdroid.views.CustomZoomButtonsController
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.TilesOverlay
import org.osmdroid.views.overlay.gestures.RotationGestureOverlay
import java.io.File

/** Impostazione comune delle mappe (pagina Mappa e pagina Crea). */
object MapStyle {

    /** Sovrapposizione degli itinerari MTB segnati (dati OpenStreetMap). */
    private val MTB_ROUTES = XYTileSource(
        "WaymarkedTrailsMTB", 0, 18, 256, ".png",
        arrayOf("https://tile.waymarkedtrails.org/mtb/"),
        "Itinerari MTB: waymarkedtrails.org, dati (c) OpenStreetMap contributors"
    )

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
            val src = if (base == Prefs.BASE_OSM) TileSourceFactory.MAPNIK else TileSourceFactory.OpenTopo
            map.setTileProvider(MapTileProviderBasic(ctx, src))
            map.setTileSource(src)
            map.setUseDataConnection(true)
            online = true
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
        val items = mutableListOf("OpenTopoMap (sentieri e rilievo)", "OpenStreetMap standard")
        if (hasVector) items.add("Offline: OpenAndroMaps")
        if (hasOffline) items.add("Mappa offline importata (MBTiles)")
        items.add(if (mtbOn) "Nascondi itinerari MTB" else "Mostra itinerari MTB")
        if (hasVector) items.add("Dimensione testi mappa offline…")

        MaterialAlertDialogBuilder(ctx)
            .setTitle("Mappa")
            .setItems(items.toTypedArray()) { _, which ->
                val label = items[which]
                when {
                    label.startsWith("OpenTopo") -> Prefs.setBaseMap(ctx, Prefs.BASE_TOPO)
                    label.startsWith("OpenStreetMap") -> Prefs.setBaseMap(ctx, Prefs.BASE_OSM)
                    label.startsWith("Dimensione") -> {
                        chooseScale(ctx, onChanged)
                        return@setItems
                    }
                    label.startsWith("Offline:") -> Prefs.setBaseMap(ctx, Prefs.BASE_VECTOR)
                    label.startsWith("Mappa offline") -> Prefs.setBaseMap(ctx, Prefs.BASE_OFFLINE)
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
}
