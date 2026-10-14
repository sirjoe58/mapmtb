package it.sirjoe.mtbcomputer.share

import android.content.Context
import android.net.Uri
import it.sirjoe.mtbcomputer.data.Prefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * SirJoe Link: collegamento Wi-Fi diretto con il Planner per Windows.
 * Il QR mostrato dal PC contiene indirizzo, porta e codice segreto:
 * sirjoemtb://link?h=192.168.1.10&p=47891&t=CODICE&n=NOME-PC
 * Tutto resta nella rete di casa, senza cloud né account.
 */
object LinkClient {

    data class Pc(val host: String, val port: Int, val token: String, val name: String) {
        val base get() = "http://$host:$port"
        fun toUri(): String = Uri.Builder().scheme("sirjoemtb").authority("link")
            .appendQueryParameter("h", host).appendQueryParameter("p", port.toString())
            .appendQueryParameter("t", token).appendQueryParameter("n", name).build().toString()
    }

    data class Offer(val id: String, val name: String, val km: String)

    fun isLink(uri: Uri?) = uri != null && uri.scheme == "sirjoemtb" && uri.host == "link"

    /** Indirizzo della rete locale (casa, hotspot del telefono). */
    fun isLocal(host: String) = host.startsWith("192.168.") || host.startsWith("10.") ||
        Regex("^172\\.(1[6-9]|2[0-9]|3[01])\\.").containsMatchIn(host) || host.endsWith(".local")

    /** Errore leggibile: rete irraggiungibile (firewall/Wi-Fi) oppure codice rifiutato. */
    class LinkError(message: String, val unreachable: Boolean) : Exception(message)

    fun parse(text: String?): Pc? {
        if (text.isNullOrBlank()) return null
        return try {
            val u = Uri.parse(text.trim())
            if (!isLink(u)) return null
            val host = u.getQueryParameter("h") ?: return null
            val port = u.getQueryParameter("p")?.toIntOrNull() ?: return null
            val token = u.getQueryParameter("t") ?: return null
            // solo indirizzi della rete locale
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
                // nessuna risposta: Wi-Fi diverso, PC spento o firewall di Windows
                throw LinkError(e.javaClass.simpleName + ": " + (e.message ?: ""), unreachable = true)
            }
            val stream = if (code in 200..299) c.inputStream else c.errorStream
            val bytes = stream?.use { it.readBytes() } ?: ByteArray(0)
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

    /** Verifica il collegamento: restituisce il nome del PC. */
    suspend fun hello(pc: Pc): String = withContext(Dispatchers.IO) {
        JSONObject(String(readBody(open(pc, "/info")))).optString("name", pc.name)
    }

    suspend fun offers(pc: Pc): List<Offer> = withContext(Dispatchers.IO) {
        val arr = JSONArray(String(readBody(open(pc, "/outbox"))))
        (0 until arr.length()).map {
            val o = arr.getJSONObject(it)
            Offer(o.getString("id"), o.optString("name", "Percorso"), o.optString("km", ""))
        }
    }

    suspend fun download(pc: Pc, id: String): ByteArray = withContext(Dispatchers.IO) {
        readBody(open(pc, "/outbox/" + URLEncoder.encode(id, "UTF-8") + ".gpx"))
    }

    suspend fun upload(pc: Pc, name: String, gpx: ByteArray): String = withContext(Dispatchers.IO) {
        val c = open(pc, "/inbox?name=" + URLEncoder.encode(name, "UTF-8"), "POST")
        c.doOutput = true
        c.setRequestProperty("Content-Type", "application/gpx+xml")
        c.setFixedLengthStreamingMode(gpx.size)
        c.outputStream.use { it.write(gpx) }
        JSONObject(String(readBody(c))).optString("name", name)
    }
}
