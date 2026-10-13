package it.sirjoe.mtbcomputer.data

import it.sirjoe.mtbcomputer.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale

/**
 * Calcolo del tratto tra due punti seguendo strade e sentieri,
 * con il server pubblico gratuito di BRouter (brouter.de), basato su OpenStreetMap.
 * Una sola richiesta per ogni punto aggiunto: uso moderato, come chiede il servizio.
 */
object BRouterClient {

    /** Profili standard del server BRouter. */
    enum class Profile(val id: String, val label: String) {
        TREKKING("trekking", "Bici su sterrato e ciclabili"),
        MOUNTAIN("hiking-mountain", "Sentieri di montagna"),
        SHORTEST("shortest", "Il più breve")
    }

    private const val SERVER = "https://brouter.de/brouter"

    suspend fun route(a: TrackPoint, b: TrackPoint, profile: Profile): List<TrackPoint> =
        withContext(Dispatchers.IO) {
            val lonlats = String.format(Locale.US, "%.6f,%.6f|%.6f,%.6f", a.lon, a.lat, b.lon, b.lat)
            val url = SERVER + "?lonlats=" + URLEncoder.encode(lonlats, "UTF-8") +
                "&profile=" + profile.id + "&alternativeidx=0&format=gpx"
            val conn = URL(url).openConnection() as HttpURLConnection
            conn.connectTimeout = 15_000
            conn.readTimeout = 30_000
            conn.setRequestProperty("User-Agent", "SirJoeMTB/${BuildConfig.VERSION_NAME} (uso personale)")
            try {
                val code = conn.responseCode
                if (code != 200) {
                    val err = conn.errorStream?.bufferedReader()?.use { it.readText() }?.take(200)
                    throw IllegalStateException("BRouter: ${err ?: "errore $code"}")
                }
                val bytes = conn.inputStream.use { it.readBytes() }
                TrackIO.parse(bytes, "tratto.gpx").points.map { it.copy(time = null) }
            } finally {
                conn.disconnect()
            }
        }
}
