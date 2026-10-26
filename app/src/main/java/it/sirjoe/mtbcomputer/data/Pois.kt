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

    /** Riquadro (sud, ovest, nord, est) attorno a un tratto, allargato di circa [BUFFER_M] + margine. */
    data class Box(val s: Double, val w: Double, val n: Double, val e: Double)

    /**
     * Il percorso viene diviso in tratti di circa [chunkM] metri e per ogni tratto si cerca nel suo riquadro:
     * le ricerche per riquadro sono velocissime sul server; i punti troppo lontani dalla linea
     * vengono scartati dopo, sul telefono.
     */
    fun boxes(points: List<TrackPoint>, chunkM: Double = 8000.0): List<Box> {
        if (points.isEmpty()) return emptyList()
        val cum = Geo.cumulative(points)
        val out = ArrayList<Box>()
        var from = 0
        while (from < points.size) {
            var to = from
            while (to < points.lastIndex && cum[to + 1] - cum[from] <= chunkM) to++
            if (to == from && to < points.lastIndex) to++
            val seg = points.subList(from, to + 1)
            val midLat = seg.sumOf { it.lat } / seg.size
            val dLat = (BUFFER_M + 150) / 111_000.0
            val dLon = (BUFFER_M + 150) / (111_000.0 * kotlin.math.cos(Math.toRadians(midLat)).coerceAtLeast(0.2))
            out.add(Box(seg.minOf { it.lat } - dLat, seg.minOf { it.lon } - dLon, seg.maxOf { it.lat } + dLat, seg.maxOf { it.lon } + dLon))
            if (to >= points.lastIndex) break
            from = to
        }
        return out
    }

    /** Testo della richiesta Overpass QL. */
    fun query(points: List<TrackPoint>, types: Set<PoiType>): String {
        val filters = ArrayList<String>()
        if (PoiType.WATER in types) {
            filters.add("node[\"amenity\"=\"drinking_water\"]")
            filters.add("node[\"amenity\"=\"fountain\"][\"drinking_water\"=\"yes\"]")
            filters.add("node[\"man_made\"=\"water_tap\"][\"drinking_water\"!=\"no\"]")
        }
        if (PoiType.SPRING in types) filters.add("node[\"natural\"=\"spring\"]")
        if (PoiType.HUT in types) {
            filters.add("nwr[\"tourism\"=\"alpine_hut\"]")
            filters.add("nwr[\"tourism\"=\"wilderness_hut\"]")
            filters.add("nwr[\"amenity\"=\"shelter\"]")
        }
        if (PoiType.FOOD in types) {
            for (a in listOf("cafe", "bar", "restaurant", "pub")) filters.add("nwr[\"amenity\"=\"$a\"]")
        }
        if (PoiType.BIKE in types) {
            filters.add("nwr[\"shop\"=\"bicycle\"]")
            filters.add("node[\"amenity\"=\"bicycle_repair_station\"]")
        }
        if (PoiType.VIEW in types) filters.add("node[\"tourism\"=\"viewpoint\"]")
        val sb = StringBuilder("[out:json][timeout:90];(")
        for (b in boxes(points)) {
            val bbox = String.format(Locale.US, "(%.5f,%.5f,%.5f,%.5f)", b.s, b.w, b.n, b.e)
            for (f in filters) sb.append(f).append(bbox).append(';')
        }
        return sb.append(");out center tags;").toString()
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
            if (off > BUFFER_M) continue
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

    /**
     * Distanza lungo il percorso del punto della linea più vicino e distanza dalla linea
     * (calcolata sui segmenti, quindi giusta anche per i percorsi in linea retta con pochi punti).
     */
    fun project(route: List<TrackPoint>, cum: DoubleArray, lat: Double, lon: Double): Pair<Double, Double> {
        if (route.isEmpty()) return 0.0 to Double.MAX_VALUE
        if (route.size == 1) return 0.0 to Geo.distance(lat, lon, route[0].lat, route[0].lon)
        val k = kotlin.math.cos(Math.toRadians(lat)) * 111_320.0
        val m = 110_540.0
        var bestAlong = 0.0
        var bestD = Double.MAX_VALUE
        for (i in 0 until route.size - 1) {
            val ax = (route[i].lon - lon) * k
            val ay = (route[i].lat - lat) * m
            val bx = (route[i + 1].lon - lon) * k
            val by = (route[i + 1].lat - lat) * m
            val dx = bx - ax
            val dy = by - ay
            val len2 = dx * dx + dy * dy
            val t = if (len2 > 0) ((-ax * dx - ay * dy) / len2).coerceIn(0.0, 1.0) else 0.0
            val px = ax + t * dx
            val py = ay + t * dy
            val d = kotlin.math.sqrt(px * px + py * py)
            if (d < bestD) {
                bestD = d
                bestAlong = cum[i] + t * (cum[i + 1] - cum[i])
            }
        }
        return bestAlong to bestD
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

    /** Server Overpass pubblici: se il primo è occupato si prova il successivo. */
    private val SERVERS = listOf(
        "https://overpass-api.de/api/interpreter",
        "https://overpass.kumi.systems/api/interpreter",
        "https://overpass.private.coffee/api/interpreter"
    )

    suspend fun fetch(route: List<TrackPoint>, types: Set<PoiType>): List<Poi> = withContext(Dispatchers.IO) {
        if (route.size < 2) throw IllegalStateException("percorso troppo corto")
        val body = "data=" + URLEncoder.encode(query(route, types), "UTF-8")
        var lastError = "nessun server raggiungibile"
        for (server in SERVERS) {
            val conn = URL(server).openConnection() as HttpURLConnection
            try {
                conn.requestMethod = "POST"
                conn.doOutput = true
                conn.connectTimeout = 20_000
                conn.readTimeout = 100_000
                conn.setRequestProperty("User-Agent", "SirJoeMTB/${BuildConfig.VERSION_NAME} (app personale; github.com/sirjoe58/mapmtb)")
                conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
                conn.setRequestProperty("Accept", "application/json")
                conn.outputStream.use { it.write(body.toByteArray()) }
                val code = conn.responseCode
                if (code == 200) {
                    val json = conn.inputStream.bufferedReader().use { it.readText() }
                    // Overpass può rispondere 200 con un errore nel campo "remark"
                    val remark = try {
                        JSONObject(json).optString("remark")
                    } catch (_: Exception) {
                        throw IllegalStateException("risposta non valida dal server")
                    }
                    if (remark.contains("error", ignoreCase = true) || remark.contains("timed out", ignoreCase = true)) {
                        lastError = "server occupato (" + remark.take(80) + ")"
                        continue
                    }
                    return@withContext parse(json, route, types)
                }
                lastError = when (code) {
                    429 -> "troppe richieste al server, riprova tra qualche minuto"
                    504, 503, 502 -> "server OpenStreetMap occupato"
                    400 -> "richiesta rifiutata (400)"
                    else -> "errore $code"
                }
            } catch (e: java.net.UnknownHostException) {
                throw IllegalStateException("nessuna connessione a Internet")
            } catch (e: Exception) {
                lastError = e.message ?: e.javaClass.simpleName
            } finally {
                conn.disconnect()
            }
        }
        throw IllegalStateException(lastError)
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
