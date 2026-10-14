package it.sirjoe.mtbcomputer.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/** Tipi di segnaposto, ognuno con il suo colore sulla mappa. */
enum class PlaceType(val id: String, val label: String, val color: Int) {
    GENERIC("generic", "Generico", 0xFF1565C0.toInt()),
    WATER("water", "Fontana / acqua", 0xFF00ACC1.toInt()),
    FOOD("food", "Rifugio / ristoro", 0xFF6D4C41.toInt()),
    VIEW("view", "Panorama", 0xFF2E7D32.toInt()),
    BIKE("bike", "Officina / bici", 0xFFFF6D00.toInt()),
    PARKING("parking", "Parcheggio / partenza", 0xFF546E7A.toInt()),
    DANGER("danger", "Pericolo / attenzione", 0xFFC62828.toInt());

    companion object {
        fun of(id: String?) = values().firstOrNull { it.id == id } ?: GENERIC
    }
}

/** Segnaposto (waypoint) modificabile. */
data class Placemark(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val note: String = "",
    val lat: Double,
    val lon: Double,
    val ele: Double? = null,
    val type: PlaceType = PlaceType.GENERIC
) {
    fun toPoint() = TrackPoint(lat, lon, ele)
}

/** Archivio dei segnaposto sul telefono (file JSON privato dell'app). */
object Placemarks {
    private val _items = MutableStateFlow<List<Placemark>>(emptyList())
    val items: StateFlow<List<Placemark>> = _items.asStateFlow()
    private var loaded = false

    private fun file(ctx: Context) = File(ctx.filesDir, "segnaposto.json")

    @Synchronized
    fun load(ctx: Context): List<Placemark> {
        if (!loaded) {
            loaded = true
            val f = file(ctx)
            if (f.exists()) {
                try {
                    val arr = JSONArray(f.readText())
                    val list = ArrayList<Placemark>()
                    for (i in 0 until arr.length()) {
                        val o = arr.getJSONObject(i)
                        list.add(
                            Placemark(
                                id = o.optString("id", UUID.randomUUID().toString()),
                                name = o.optString("name", "Segnaposto"),
                                note = o.optString("note", ""),
                                lat = o.getDouble("lat"),
                                lon = o.getDouble("lon"),
                                ele = if (o.has("ele")) o.getDouble("ele") else null,
                                type = PlaceType.of(o.optString("type"))
                            )
                        )
                    }
                    _items.value = list
                } catch (_: Exception) {
                }
            }
        }
        return _items.value
    }

    @Synchronized
    private fun save(ctx: Context, list: List<Placemark>) {
        _items.value = list
        val arr = JSONArray()
        for (p in list) {
            val o = JSONObject()
                .put("id", p.id).put("name", p.name).put("note", p.note)
                .put("lat", p.lat).put("lon", p.lon).put("type", p.type.id)
            p.ele?.let { o.put("ele", it) }
            arr.put(o)
        }
        file(ctx).writeText(arr.toString(1))
    }

    fun nextName(ctx: Context): String = "Segnaposto " + (load(ctx).size + 1)

    fun add(ctx: Context, p: Placemark) = save(ctx, load(ctx) + p)

    fun addAll(ctx: Context, ps: List<Placemark>) = save(ctx, load(ctx) + ps)

    fun update(ctx: Context, p: Placemark) = save(ctx, load(ctx).map { if (it.id == p.id) p else it })

    fun remove(ctx: Context, id: String) = save(ctx, load(ctx).filterNot { it.id == id })

    fun clear(ctx: Context) = save(ctx, emptyList())

    fun get(ctx: Context, id: String) = load(ctx).firstOrNull { it.id == id }
}
