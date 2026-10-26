package it.sirjoe.mtbcomputer.data

import android.content.Context
import it.sirjoe.mtbcomputer.BuildConfig
import it.sirjoe.mtbcomputer.util.Geo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale

/** Tipi di punti utili lungo il percorso (dati OpenStreetMap). */
enum class PoiType(val id: String, val label: String, val emoji: String, val color: Int, val spoken: String) {
    WATER("water", "Fontanella / acqua potabile", "💧", 0xFF00ACC1.toInt(), "Fontanella"),
    SPRING("spring", "Sorgente (verifica se è potabile)", "🌊", 0xFF4DD0E1.toInt(), "Sorgente"),
    HUT("hut", "Rifugio / bivacco", "🏠", 0xFF6D4C41.toInt(), "Rifugio"),
    FOOD("food", "Bar / ristoro", "☕", 0xFF8D6E63.toInt(), "Bar"),
    BIKE("bike", "Officina bici / punto riparazione", "🔧", 0xFFFF6D00.toInt(), "Officina bici"),
    VIEW("view", "Punto panoramico", "📷", 0xFF2E7D32.toInt(), "Panorama");

    companion object {
        fun of(id: String?) = values().firstOrNull { it.id == id }
    }
}

/** Punto utile: [along] è la distanza in metri dall'inizio del percorso (proiezione sul percorso). */
data class Poi(
    val type: PoiType,
    val name: String,
    val lat: Double,
    val lon: Double,
    val along: Double,
    val offM: Double
)

/**
 * Punti utili da OpenStreetMap (servizio Overpass), cercati una volta lungo il percorso
 * e salvati sul telefono: dopo si vedono anche senza Internet.
 */
object Pois {

    private const val SERVER = "https://overpass-api.de/api/interpreter"
    /** Distanza massima dal percorso, in metri. */
    const val BUFFER_M = 250

    // ------------------------------------------------------------ pura logica (testabile)

    /** Percorso semplificato (un punto ogni ~[stepM] m, al massimo [max]) per la richiesta. */
    fun simplify(points: List<TrackPoint>, stepM: Double = 400.0, max: Int = 200): List<TrackPoint> {
        if (points.size <= 2) return points
        val cum = Geo.cumulative(points)
        val total = cum.last()
        val step = maxOf(stepM, total / max)
        val out = ArrayList<TrackPoint>()
        var next = 0.0
        for (i in points.indices) {
            if (cum[i] >= next) {
                out.add(points[i]); next = cum[i] + step
            }
        }
        if (out.last() != points.last()) out.add(points.last())
        return out
    }

    /** Testo della richiesta Overpass QL: punti utili entro [BUFFER_M] m dalla linea. */
    fun query(points: List<TrackPoint>, types: Set<PoiType>): String {
        val line = simplify(points).joinToString(",") { String.format(Locale.US, "%.5f,%.5f", it.lat, it.lon) }
        val around = "(around:$BUFFER_M,$line)"
        val parts = ArrayList<String>()
        if (PoiType.WATER in types) {
            parts.add("node[\"amenity\"=\"drinking_water\"]$around;")
            parts.add("node[\"amenity\"=\"fountain\"][\"drinking_water\"=\"yes\"]$around;")
            parts.add("node[\"man_made\"=\"water_tap\"][\"drinking_water\"!=\"no\"]$around;")
        }
        if (PoiType.SPRING in types) parts.add("node[\"natural\"=\"spring\"]$around;")
        if (PoiType.HUT in types) {
            parts.add("nwr[\"tourism\"~\"^(alpine_hut|wilderness_hut)$\"]$around;")
            parts.add("nwr[\"amenity\"=\"shelter\"]$around;")
        }
        if (PoiType.FOOD in types) parts.add("nwr[\"amenity\"~\"^(cafe|bar|restaurant|pub)$\"]$around;")
        if (PoiType.BIKE in types) {
            parts.add("nwr[\"shop\"=\"bicycle\"]$around;")
            parts.add("node[\"amenity\"=\"bicycle_repair_station\"]$around;")
        }
        if (PoiType.VIEW in types) parts.add("node[\"tourism\"=\"viewpoint\"]$around;")
        return "[out:json][timeout:60];(" + parts.joinToString("") + ");out center tags 400;"
    }

    fun typeOf(tags: JSONObject): PoiType? {
        val amenity = tags.optString("amenity")
        val tourism = tags.optString("tourism")
        return when {
            amenity == "drinking_water" || tags.optString("man_made") == "water_tap" ||
                (amenity == "fountain" && tags.optString("drinking_water") == "yes") -> PoiType.WATER
            tags.optString("natural") == "spring" -> PoiType.SPRING
            tourism == "alpine_hut" || tourism == "wilderness_hut" || amenity == "shelter" -> PoiType.HUT
            amenity in setOf("cafe", "bar", "restaurant", "pub") -> PoiType.FOOD
            tags.optString("shop") == "bicycle" || amenity == "bicycle_repair_station" -> PoiType.BIKE
            tourism == "viewpoint" -> PoiType.VIEW
            else -> null
        }
    }

    /** Legge la risposta di Overpass e posiziona i punti lungo il percorso, in ordine. */
    fun parse(json: String, route: List<TrackPoint>, types: Set<PoiType>): List<Poi> {
        val els = JSONObject(json).optJSONArray("elements") ?: return emptyList()
        val cum = Geo.cumulative(route)
        val out = ArrayList<Poi>()
        for (i in 0 until els.length()) {
            val e = els.getJSONObject(i)
            val tags = e.optJSONObject("tags") ?: continue
            val type = typeOf(tags) ?: continue
            if (type !in types) continue
            val center = e.optJSONObject("center")
            val lat = if (e.has("lat")) e.getDouble("lat") else center?.optDouble("lat") ?: continue
            val lon = if (e.has("lon")) e.getDouble("lon") else center?.optDouble("lon") ?: continue
            val (along, off) = project(route, cum, lat, lon)
            if (off > BUFFER_M * 1.5) continue
            val name = tags.optString("name").ifBlank { type.label.substringBefore(" (") }
            out.add(Poi(type, name, lat, lon, along, off))
        }
        // niente doppioni vicini dello stesso tipo
        val dedup = ArrayList<Poi>()
        for (p in out.sortedBy { it.along }) {
            if (dedup.none { it.type == p.type && Geo.distance(it.lat, it.lon, p.lat, p.lon) < 40 }) dedup.add(p)
        }
        return dedup
    }

    /** Distanza lungo il percorso del punto più vicino e distanza dal percorso. */
    fun project(route: List<TrackPoint>, cum: DoubleArray, lat: Double, lon: Double): Pair<Double, Double> {
        var best = 0
        var bestD = Double.MAX_VALUE
        for (i in route.indices) {
            val d = Geo.distance(lat, lon, route[i].lat, route[i].lon)
            if (d < bestD) {
                bestD = d; best = i
            }
        }
        return cum.getOrElse(best) { 0.0 } to bestD
    }

    /** Prossimo punto utile nel senso di marcia, entro [maxAheadM]. */
    fun nextAhead(list: List<Poi>, done: Double, total: Double, reverse: Boolean, maxAheadM: Double = 5000.0): Pair<Poi, Double>? {
        var best: Pair<Poi, Double>? = null
        for (p in list) {
            val ahead = if (reverse) done - p.along else p.along - done
            if (ahead < -30 || ahead > maxAheadM) continue
            val a = ahead.coerceAtLeast(0.0)
            if (best == null || a < best.second) best = p to a
        }
        return best
    }

    /** Chiave del percorso per ritrovare i suoi punti utili anche dopo averlo salvato e riaperto. */
    fun keyOf(points: List<TrackPoint>): String {
        if (points.isEmpty()) return "vuoto"
        val a = points.first()
        val b = points.last()
        val total = Geo.cumulative(points).last()
        return String.format(Locale.US, "%.4f_%.4f_%.4f_%.4f_%d", a.lat, a.lon, b.lat, b.lon, (total / 50).toInt())
            .replace('-', 'm').replace('.', 'p')
    }

    // ------------------------------------------------------------ rete e archivio

    suspend fun fetch(route: List<TrackPoint>, types: Set<PoiType>): List<Poi> = withContext(Dispatchers.IO) {
        val body = "data=" + URLEncoder.encode(query(route, types), "UTF-8")
        val conn = URL(SERVER).openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.doOutput = true
        conn.connectTimeout = 20_000
        conn.readTimeout = 90_000
        conn.setRequestProperty("User-Agent", "SirJoeMTB/${BuildConfig.VERSION_NAME} (uso personale)")
        conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
        try {
            conn.outputStream.use { it.write(body.toByteArray()) }
            val code = conn.responseCode
            if (code == 429) throw IllegalStateException("server OpenStreetMap occupato: riprova tra qualche minuto")
            if (code != 200) throw IllegalStateException("errore $code dal server OpenStreetMap")
            val json = conn.inputStream.bufferedReader().use { it.readText() }
            parse(json, route, types)
        } finally {
            conn.disconnect()
        }
    }

    private fun dir(ctx: Context) = File(ctx.filesDir, "punti_utili").apply { mkdirs() }

    fun save(ctx: Context, route: List<TrackPoint>, list: List<Poi>) {
        val arr = JSONArray()
        for (p in list) {
            arr.put(
                JSONObject().put("type", p.type.id).put("name", p.name).put("lat", p.lat).put("lon", p.lon)
                    .put("along", p.along).put("off", p.offM)
            )
        }
        File(dir(ctx), keyOf(route) + ".json").writeText(arr.toString())
    }

    fun load(ctx: Context, route: List<TrackPoint>): List<Poi> {
        val f = File(dir(ctx), keyOf(route) + ".json")
        if (!f.exists()) return emptyList()
        return try {
            val arr = JSONArray(f.readText())
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.getJSONObject(i)
                val t = PoiType.of(o.optString("type")) ?: return@mapNotNull null
                Poi(t, o.optString("name"), o.getDouble("lat"), o.getDouble("lon"), o.getDouble("along"), o.optDouble("off", 0.0))
            }
        } catch (_: Exception) {
            emptyList()
        }
    }
}
