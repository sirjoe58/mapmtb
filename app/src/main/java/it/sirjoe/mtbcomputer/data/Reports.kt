package it.sirjoe.mtbcomputer.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/** Tipi di segnalazione sul sentiero. */
enum class ReportType(val id: String, val label: String, val emoji: String, val color: Int) {
    TREE("albero", "Albero caduto", "🌲", 0xFF2E7D32.toInt()),
    LANDSLIDE("frana", "Frana / smottamento", "🪨", 0xFF795548.toInt()),
    MUD("fango", "Fango / acqua", "💦", 0xFF5D4037.toInt()),
    CLOSED("chiuso", "Sentiero chiuso / divieto", "⛔", 0xFFC62828.toInt()),
    DANGER("pericolo", "Pericolo", "⚠️", 0xFFEF6C00.toInt()),
    OTHER("altro", "Altro", "📍", 0xFF1565C0.toInt());

    companion object {
        fun of(id: String?) = values().firstOrNull { it.id == id } ?: OTHER
    }
}

/** Segnalazione con foto, attaccata a un punto del sentiero. */
data class Report(
    val id: String = UUID.randomUUID().toString(),
    val type: ReportType,
    val note: String,
    val lat: Double,
    val lon: Double,
    val time: Long,
    /** Nome del file della foto nella cartella delle segnalazioni (null = senza foto). */
    val photo: String? = null
) {
    val title: String get() = type.emoji + " " + type.label
}

/** Archivio delle segnalazioni sul telefono (JSON + foto in una cartella privata). */
object Reports {
    private val _items = MutableStateFlow<List<Report>>(emptyList())
    val items: StateFlow<List<Report>> = _items.asStateFlow()
    private var loaded = false

    fun dir(ctx: Context): File = File(ctx.filesDir, "segnalazioni").apply { mkdirs() }
    private fun file(ctx: Context) = File(dir(ctx), "segnalazioni.json")

    fun photoFile(ctx: Context, r: Report): File? = r.photo?.let { File(dir(ctx), it) }?.takeIf { it.exists() }

    /** File nuovo in cui la fotocamera salva la foto. */
    fun newPhotoFile(ctx: Context): File = File(dir(ctx), "foto_" + System.currentTimeMillis() + ".jpg")

    @Synchronized
    fun load(ctx: Context): List<Report> {
        if (!loaded) {
            loaded = true
            try {
                val f = file(ctx)
                if (f.exists()) {
                    val arr = JSONArray(f.readText())
                    _items.value = (0 until arr.length()).map { i ->
                        val o = arr.getJSONObject(i)
                        Report(
                            id = o.optString("id", UUID.randomUUID().toString()),
                            type = ReportType.of(o.optString("type")),
                            note = o.optString("note"),
                            lat = o.getDouble("lat"),
                            lon = o.getDouble("lon"),
                            time = o.optLong("time"),
                            photo = o.optString("photo").ifBlank { null }
                        )
                    }
                }
            } catch (_: Exception) {
            }
        }
        return _items.value
    }

    @Synchronized
    private fun save(ctx: Context, list: List<Report>) {
        _items.value = list
        val arr = JSONArray()
        for (r in list) {
            val o = JSONObject().put("id", r.id).put("type", r.type.id).put("note", r.note)
                .put("lat", r.lat).put("lon", r.lon).put("time", r.time)
            r.photo?.let { o.put("photo", it) }
            arr.put(o)
        }
        file(ctx).writeText(arr.toString(1))
    }

    fun add(ctx: Context, r: Report) = save(ctx, load(ctx) + r)

    fun remove(ctx: Context, r: Report) {
        photoFile(ctx, r)?.delete()
        save(ctx, load(ctx).filterNot { it.id == r.id })
    }

    /** Segnalazioni fatte tra [from] e [to] (ms), per allegarle a un'uscita registrata. */
    fun between(ctx: Context, from: Long, to: Long) = load(ctx).filter { it.time in from..to }

    /** Come segnaposto GPX (<wpt>) da salvare insieme all'uscita. */
    fun asPlacemarks(list: List<Report>): List<Placemark> = list.map {
        Placemark(
            name = it.title,
            note = (it.note + if (it.photo != null) " [foto: ${it.photo}]" else "").trim(),
            lat = it.lat, lon = it.lon,
            type = if (it.type == ReportType.DANGER || it.type == ReportType.CLOSED || it.type == ReportType.LANDSLIDE)
                PlaceType.DANGER else PlaceType.GENERIC
        )
    }
}
