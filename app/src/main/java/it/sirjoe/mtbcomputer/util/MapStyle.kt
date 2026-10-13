package it.sirjoe.mtbcomputer.util

import android.content.Context
import android.graphics.Color
import android.widget.Toast
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import it.sirjoe.mtbcomputer.data.OfflineMaps
import it.sirjoe.mtbcomputer.data.Prefs
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
import java.io.File

/** Impostazione comune delle mappe (pagina Mappa e pagina Crea). */
object MapStyle {

    /** Sovrapposizione degli itinerari MTB segnati (dati OpenStreetMap). */
    private val MTB_ROUTES = XYTileSource(
        "WaymarkedTrailsMTB", 0, 18, 256, ".png",
        arrayOf("https://tile.waymarkedtrails.org/mtb/"),
        "Itinerari MTB: waymarkedtrails.org, dati (c) OpenStreetMap contributors"
    )

    fun setup(map: MapView) {
        map.setMultiTouchControls(true)
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
        val t = theme(ctx)
        val src = if (t != null) {
            MapsForgeTileSource.createFromFiles(files.toTypedArray(), t, "sirjoe-tema")
        } else {
            MapsForgeTileSource.createFromFiles(files.toTypedArray())
        }
        map.setTileProvider(MapsForgeTileProvider(SimpleRegisterReceiver(ctx), src, null))
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

        MaterialAlertDialogBuilder(ctx)
            .setTitle("Mappa")
            .setItems(items.toTypedArray()) { _, which ->
                val label = items[which]
                when {
                    label.startsWith("OpenTopo") -> Prefs.setBaseMap(ctx, Prefs.BASE_TOPO)
                    label.startsWith("OpenStreetMap") -> Prefs.setBaseMap(ctx, Prefs.BASE_OSM)
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
}
