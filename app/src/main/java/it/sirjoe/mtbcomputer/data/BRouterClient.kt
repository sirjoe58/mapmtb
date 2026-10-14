package it.sirjoe.mtbcomputer.data

import it.sirjoe.mtbcomputer.BuildConfig
import it.sirjoe.mtbcomputer.util.Geo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale

/**
 * Calcolo dei percorsi tra due punti seguendo strade e sentieri,
 * con il server pubblico gratuito di BRouter (brouter.de), basato su OpenStreetMap.
 * Una richiesta per tratto (tre per il "più lungo"): uso moderato, come chiede il servizio.
 */
object BRouterClient {

    /**
     * Tipi di percorso. [profiles] sono i profili BRouter provati in ordine
     * (se il primo non è disponibile sul server si usa il successivo).
     */
    enum class Profile(val id: String, val label: String, val profiles: List<String>, val longest: Boolean = false) {
        OFFROAD("hiking-mountain", "Fuoristrada / MTB (sentieri e sterrati)", listOf("mtb-zossebart", "hiking-mountain")),
        TREKKING("trekking", "Equilibrato (sterrati e ciclabili)", listOf("trekking")),
        FAST("fastbike", "Il più veloce (strade scorrevoli)", listOf("fastbike", "trekking")),
        SHORTEST("shortest", "Il più corto", listOf("shortest")),
        LONG("long", "Il più lungo (giro più ampio)", listOf("trekking"), longest = true)
    }

    private const val SERVER = "https://brouter.de/brouter"

    private fun request(a: TrackPoint, b: TrackPoint, profile: String, alt: Int): List<TrackPoint> {
        val lonlats = String.format(Locale.US, "%.6f,%.6f|%.6f,%.6f", a.lon, a.lat, b.lon, b.lat)
        val url = SERVER + "?lonlats=" + URLEncoder.encode(lonlats, "UTF-8") +
            "&profile=" + profile + "&alternativeidx=" + alt + "&format=gpx"
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 15_000
        conn.readTimeout = 30_000
        conn.setRequestProperty("User-Agent", "SirJoeMTB/${BuildConfig.VERSION_NAME} (uso personale)")
        try {
            val code = conn.responseCode
            if (code != 200) {
                val err = conn.errorStream?.bufferedReader()?.use { it.readText() }?.take(200)
                throw IllegalStateException(err ?: "errore $code")
            }
            val bytes = conn.inputStream.use { it.readBytes() }
            return TrackIO.parse(bytes, "tratto.gpx").points.map { it.copy(time = null) }
        } finally {
            conn.disconnect()
        }
    }

    /** Un tratto da a a b. */
    suspend fun route(a: TrackPoint, b: TrackPoint, profile: Profile): List<TrackPoint> =
        withContext(Dispatchers.IO) {
            var lastError: Exception? = null
            for (p in profile.profiles) {
                try {
                    if (!profile.longest) return@withContext request(a, b, p, 0)
                    // "più lungo": tra le alternative proposte sceglie quella con più km
                    var best: List<TrackPoint>? = null
                    var bestLen = -1.0
                    for (alt in 0..2) {
                        try {
                            val r = request(a, b, p, alt)
                            val len = Geo.cumulative(r).lastOrNull() ?: 0.0
                            if (len > bestLen) {
                                best = r; bestLen = len
                            }
                        } catch (e: Exception) {
                            if (alt == 0) throw e
                        }
                    }
                    if (best != null) return@withContext best
                } catch (e: Exception) {
                    lastError = e
                }
            }
            throw IllegalStateException("BRouter: ${lastError?.message ?: "nessun percorso"}")
        }

    /**
     * Percorso che passa per tutti i punti in ordine, un tratto alla volta.
     * onLeg(i, n) viene chiamato dopo ogni tratto calcolato.
     */
    suspend fun routeThrough(
        points: List<TrackPoint>,
        profile: Profile,
        onLeg: (Int, Int) -> Unit = { _, _ -> }
    ): List<List<TrackPoint>> {
        val legs = ArrayList<List<TrackPoint>>()
        for (i in 0 until points.size - 1) {
            legs.add(route(points[i], points[i + 1], profile))
            onLeg(i + 1, points.size - 1)
        }
        return legs
    }
}
