package it.sirjoe.mtbcomputer.share

import android.content.Context
import android.content.Intent
import android.net.Uri
import it.sirjoe.mtbcomputer.data.Prefs
import it.sirjoe.mtbcomputer.data.Track
import it.sirjoe.mtbcomputer.data.TrackFormat
import it.sirjoe.mtbcomputer.data.TrackIO
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Caricamento delle uscite su Strava con l'API ufficiale.
 * Ogni utente usa la propria "applicazione API" Strava (gratuita, per uso personale):
 * Client ID e Client Secret restano solo sul telefono.
 */
object Strava {

    const val REDIRECT = "sirjoemtb://localhost"
    private const val K_ID = "strava_client_id"
    private const val K_SECRET = "strava_client_secret"
    private const val K_ACCESS = "strava_access"
    private const val K_REFRESH = "strava_refresh"
    private const val K_EXPIRES = "strava_expires"
    private const val K_ATHLETE = "strava_athlete"

    fun hasApp(ctx: Context) = !Prefs.str(ctx, K_ID).isNullOrBlank() && !Prefs.str(ctx, K_SECRET).isNullOrBlank()
    fun isConnected(ctx: Context) = !Prefs.str(ctx, K_REFRESH).isNullOrBlank()
    fun athlete(ctx: Context): String? = Prefs.str(ctx, K_ATHLETE)
    fun clientId(ctx: Context): String = Prefs.str(ctx, K_ID) ?: ""

    fun saveApp(ctx: Context, id: String, secret: String) {
        Prefs.putStr(ctx, K_ID, id.trim())
        Prefs.putStr(ctx, K_SECRET, secret.trim())
    }

    fun disconnect(ctx: Context) {
        for (k in listOf(K_ACCESS, K_REFRESH, K_ATHLETE)) Prefs.putStr(ctx, k, null)
        Prefs.putLong(ctx, K_EXPIRES, 0L)
    }

    /** Apre la pagina (o l'app) Strava per autorizzare il caricamento delle attività. */
    fun startAuth(ctx: Context) {
        val uri = Uri.parse("https://www.strava.com/oauth/mobile/authorize").buildUpon()
            .appendQueryParameter("client_id", clientId(ctx))
            .appendQueryParameter("redirect_uri", REDIRECT)
            .appendQueryParameter("response_type", "code")
            .appendQueryParameter("approval_prompt", "auto")
            .appendQueryParameter("scope", "read,activity:write")
            .build()
        ctx.startActivity(Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    fun isAuthRedirect(uri: Uri?) = uri != null && uri.scheme == "sirjoemtb" && uri.host == "localhost"

    // ------------------------------------------------------------- token

    private fun postForm(url: String, params: Map<String, String>): JSONObject {
        val body = params.entries.joinToString("&") {
            URLEncoder.encode(it.key, "UTF-8") + "=" + URLEncoder.encode(it.value, "UTF-8")
        }
        val c = URL(url).openConnection() as HttpURLConnection
        c.requestMethod = "POST"
        c.doOutput = true
        c.connectTimeout = 15_000
        c.readTimeout = 30_000
        c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
        c.outputStream.use { it.write(body.toByteArray()) }
        return readJson(c)
    }

    private fun readJson(c: HttpURLConnection): JSONObject {
        try {
            val code = c.responseCode
            val text = (if (code in 200..299) c.inputStream else c.errorStream)
                ?.bufferedReader()?.use { it.readText() } ?: "{}"
            val json = try {
                JSONObject(text)
            } catch (e: Exception) {
                JSONObject().put("message", text.take(200))
            }
            if (code !in 200..299) {
                throw IllegalStateException("Strava ($code): " + json.optString("message", "errore"))
            }
            return json
        } finally {
            c.disconnect()
        }
    }

    private fun storeTokens(ctx: Context, j: JSONObject) {
        Prefs.putStr(ctx, K_ACCESS, j.getString("access_token"))
        Prefs.putStr(ctx, K_REFRESH, j.getString("refresh_token"))
        Prefs.putLong(ctx, K_EXPIRES, j.getLong("expires_at"))
        j.optJSONObject("athlete")?.let {
            val n = (it.optString("firstname") + " " + it.optString("lastname")).trim()
            if (n.isNotEmpty()) Prefs.putStr(ctx, K_ATHLETE, n)
        }
    }

    /** Completa il collegamento con il codice ricevuto da Strava. */
    suspend fun finishAuth(ctx: Context, uri: Uri): String = withContext(Dispatchers.IO) {
        uri.getQueryParameter("error")?.let { throw IllegalStateException("Autorizzazione negata") }
        val code = uri.getQueryParameter("code") ?: throw IllegalStateException("Codice mancante")
        val scope = uri.getQueryParameter("scope") ?: ""
        if (!scope.contains("activity:write")) {
            throw IllegalStateException("Serve il permesso di caricare attività: riprova e lascia la spunta attiva")
        }
        val j = postForm(
            "https://www.strava.com/oauth/token",
            mapOf(
                "client_id" to clientId(ctx),
                "client_secret" to (Prefs.str(ctx, K_SECRET) ?: ""),
                "code" to code,
                "grant_type" to "authorization_code"
            )
        )
        storeTokens(ctx, j)
        athlete(ctx) ?: "account Strava"
    }

    private fun token(ctx: Context): String {
        val access = Prefs.str(ctx, K_ACCESS)
        val expires = Prefs.long(ctx, K_EXPIRES)
        if (access != null && expires > System.currentTimeMillis() / 1000 + 120) return access
        val refresh = Prefs.str(ctx, K_REFRESH) ?: throw IllegalStateException("Collega prima Strava (pagina Info)")
        val j = postForm(
            "https://www.strava.com/oauth/token",
            mapOf(
                "client_id" to clientId(ctx),
                "client_secret" to (Prefs.str(ctx, K_SECRET) ?: ""),
                "refresh_token" to refresh,
                "grant_type" to "refresh_token"
            )
        )
        storeTokens(ctx, j)
        return j.getString("access_token")
    }

    // ---------------------------------------------------------- caricamento

    /** Strava accetta come attività solo tracce registrate, cioè con gli orari. */
    fun canUpload(track: Track) = track.points.size >= 2 && track.points.all { it.time != null }

    /** Carica l'uscita e restituisce un messaggio da mostrare. */
    suspend fun upload(ctx: Context, track: Track, description: String): String = withContext(Dispatchers.IO) {
        require(canUpload(track)) { "Strava accetta solo uscite registrate (con orari)" }
        val tok = token(ctx)
        val file = TrackIO.write(track, TrackFormat.GPX)
        val boundary = "----SirJoeMTB" + System.currentTimeMillis()
        val body = ByteArrayOutputStream()
        fun field(name: String, value: String) {
            body.write("--$boundary\r\nContent-Disposition: form-data; name=\"$name\"\r\n\r\n$value\r\n".toByteArray())
        }
        field("data_type", "gpx")
        field("name", track.name)
        field("description", description)
        field("external_id", "sirjoemtb-" + track.points.first().time + ".gpx")
        body.write(
            ("--$boundary\r\nContent-Disposition: form-data; name=\"file\"; filename=\"uscita.gpx\"\r\n" +
                "Content-Type: application/gpx+xml\r\n\r\n").toByteArray()
        )
        body.write(file)
        body.write("\r\n--$boundary--\r\n".toByteArray())

        val c = URL("https://www.strava.com/api/v3/uploads").openConnection() as HttpURLConnection
        c.requestMethod = "POST"
        c.doOutput = true
        c.connectTimeout = 15_000
        c.readTimeout = 60_000
        c.setRequestProperty("Authorization", "Bearer $tok")
        c.setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
        c.outputStream.use { it.write(body.toByteArray()) }
        var j = readJson(c)
        val id = j.optLong("id")

        // Strava elabora il file in pochi secondi: controlla l'esito
        repeat(8) {
            if (!j.isNull("error") && j.optString("error").isNotBlank()) {
                return@withContext "Strava: " + j.optString("error")
            }
            if (!j.isNull("activity_id") && j.optLong("activity_id") > 0) {
                return@withContext "Caricata su Strava!"
            }
            delay(2500)
            val s = URL("https://www.strava.com/api/v3/uploads/$id").openConnection() as HttpURLConnection
            s.setRequestProperty("Authorization", "Bearer $tok")
            s.connectTimeout = 15_000
            s.readTimeout = 30_000
            j = readJson(s)
        }
        "Inviata a Strava: comparirà tra poco nel tuo profilo"
    }
}
