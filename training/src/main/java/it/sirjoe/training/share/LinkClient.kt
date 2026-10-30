package it.sirjoe.training.share

import android.content.Context
import android.net.Uri
import it.sirjoe.training.data.Prefs
import it.sirjoe.training.data.Workout
import it.sirjoe.training.data.WorkoutJson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Collegamento Wi-Fi con SirJoe MTB Planner sul PC (stesso QR di SirJoe Link):
 * sirjoemtb://link?h=192.168.1.10&p=47891&t=CODICE&n=NOME-PC
 * Scarica i programmi di allenamento e invia le uscite. Tutto resta nella rete di casa.
 */
object LinkClient {

    data class Pc(val host: String, val port: Int, val token: String, val name: String) {
        val base get() = "http://$host:$port"
        fun toUri(): String = Uri.Builder().scheme("sirjoemtb").authority("link")
            .appendQueryParameter("h", host).appendQueryParameter("p", port.toString())
            .appendQueryParameter("t", token).appendQueryParameter("n", name).build().toString()
    }

    data class FromPc(val workouts: List<Workout>, val maxHr: Int, val age: Int)

    class LinkError(message: String, val unreachable: Boolean) : Exception(message)

    fun isLocal(host: String) = host.startsWith("192.168.") || host.startsWith("10.") ||
        Regex("^172\\.(1[6-9]|2[0-9]|3[01])\\.").containsMatchIn(host) || host.endsWith(".local")

    fun parse(text: String?): Pc? {
        if (text.isNullOrBlank()) return null
        return try {
            val u = Uri.parse(text.trim())
            if (u.scheme != "sirjoemtb" || u.host != "link") return null
            val host = u.getQueryParameter("h") ?: return null
            val port = u.getQueryParameter("p")?.toIntOrNull() ?: return null
            val token = u.getQueryParameter("t") ?: return null
            if (!isLocal(host)) return null
            Pc(host, port, token, u.getQueryParameter("n") ?: "PC")
        } catch (e: Exception) {
            null
        }
    }

    fun saveLast(ctx: Context, pc: Pc) = Prefs.putStr(ctx, "link_pc", pc.toUri())
    fun last(ctx: Context): Pc? = parse(Prefs.str(ctx, "link_pc"))

    private fun open(pc: Pc, path: String, method: String = "GET"): HttpURLConnection {
        val c = URL(pc.base + path).openConnection() as HttpURLConnection
        c.requestMethod = method
        c.connectTimeout = 5_000
        c.readTimeout = 30_000
        c.setRequestProperty("X-SirJoe-Token", pc.token)
        return c
    }

    private fun readBody(c: HttpURLConnection): ByteArray {
        try {
            val code = try {
                c.responseCode
            } catch (e: java.io.IOException) {
                throw LinkError("il PC non risponde (" + (e.message ?: e.javaClass.simpleName) + ")", unreachable = true)
            }
            val stream = if (code in 200..299) c.inputStream else c.errorStream
            val bytes = stream?.use { it.readBytes() } ?: ByteArray(0)
            if (code == 404) throw LinkError("il Planner sul PC è vecchio: aggiornalo alla versione 1.7 o successiva", false)
            if (code !in 200..299) {
                val msg = try {
                    JSONObject(String(bytes)).optString("error")
                } catch (e: Exception) {
                    ""
                }
                throw LinkError(msg.ifBlank { "errore $code" }, unreachable = false)
            }
            return bytes
        } finally {
            c.disconnect()
        }
    }

    /** Collegamento a mano: indirizzo "192.168.1.10:47891" e PIN di 6 cifre mostrati sul PC. */
    suspend fun pair(address: String, pin: String): Pc = withContext(Dispatchers.IO) {
        val a = address.trim().removePrefix("http://").trimEnd('/')
        val host = a.substringBefore(':').trim()
        val port = a.substringAfter(':', "47891").trim().toIntOrNull() ?: 47891
        if (host.isEmpty() || !isLocal(host)) throw LinkError("Indirizzo non valido: scrivilo come sul PC (es. 192.168.1.10:47891)", false)
        val probe = Pc(host, port, "", "PC")
        val j = JSONObject(String(readBody(open(probe, "/pair?pin=" + URLEncoder.encode(pin.trim(), "UTF-8")))))
        Pc(host, port, j.getString("token"), j.optString("name", "PC"))
    }

    suspend fun hello(pc: Pc): String = withContext(Dispatchers.IO) {
        JSONObject(String(readBody(open(pc, "/info")))).optString("name", pc.name)
    }

    /** Programmi di allenamento e profilo (FC max) preparati sul PC. */
    suspend fun training(pc: Pc): FromPc = withContext(Dispatchers.IO) {
        val j = JSONObject(String(readBody(open(pc, "/training"))))
        FromPc(
            workouts = WorkoutJson.listFrom(j.optJSONArray("workouts"), fromPc = true),
            maxHr = j.optInt("maxHr", 0),
            age = j.optInt("age", 0)
        )
    }

    /** Invia al PC i programmi creati o modificati sul telefono (finiscono nella scheda Training). */
    suspend fun uploadWorkouts(pc: Pc, list: List<Workout>): Int = withContext(Dispatchers.IO) {
        val bytes = JSONObject().put("workouts", WorkoutJson.listTo(list)).toString().toByteArray(Charsets.UTF_8)
        val c = open(pc, "/workouts", "POST")
        c.doOutput = true
        c.setRequestProperty("Content-Type", "application/json; charset=utf-8")
        c.setFixedLengthStreamingMode(bytes.size)
        c.outputStream.use { it.write(bytes) }
        JSONObject(String(readBody(c))).optInt("count", list.size)
    }

    /** Invia un'uscita completa (JSON con battito e cadenza secondo per secondo). */
    suspend fun uploadSession(pc: Pc, json: String): String = withContext(Dispatchers.IO) {
        val bytes = json.toByteArray(Charsets.UTF_8)
        val c = open(pc, "/sessions", "POST")
        c.doOutput = true
        c.setRequestProperty("Content-Type", "application/json; charset=utf-8")
        c.setFixedLengthStreamingMode(bytes.size)
        c.outputStream.use { it.write(bytes) }
        JSONObject(String(readBody(c))).optString("name", "")
    }

    /** Invia anche la traccia GPX (con battito e cadenza) alle Uscite del Planner. */
    suspend fun uploadGpx(pc: Pc, name: String, gpx: String): String = withContext(Dispatchers.IO) {
        val bytes = gpx.toByteArray(Charsets.UTF_8)
        val c = open(pc, "/inbox?name=" + URLEncoder.encode(name, "UTF-8"), "POST")
        c.doOutput = true
        c.setRequestProperty("Content-Type", "application/gpx+xml")
        c.setFixedLengthStreamingMode(bytes.size)
        c.outputStream.use { it.write(bytes) }
        JSONObject(String(readBody(c))).optString("name", name)
    }
}
