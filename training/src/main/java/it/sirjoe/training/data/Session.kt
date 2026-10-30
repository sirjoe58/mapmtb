package it.sirjoe.training.data

import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt

/** Un campione al secondo: tempo, posizione (se c'è il GPS), battito, cadenza, velocità. */
data class Sample(
    val t: Long,
    val lat: Double? = null,
    val lon: Double? = null,
    val ele: Double? = null,
    val hr: Int? = null,
    val cad: Int? = null,
    val kmh: Float? = null
)

/** Riepilogo di un'uscita/allenamento. */
data class Summary(
    val start: Long,
    val seconds: Int,
    val distanceM: Double,
    val avgHr: Int?,
    val maxHrSeen: Int?,
    val avgCad: Int?,
    val avgKmh: Double,
    val zoneSeconds: IntArray
) {
    fun zonePct(z: Int): Int {
        val tot = zoneSeconds.sum()
        return if (tot == 0) 0 else (zoneSeconds[z - 1] * 100.0 / tot).roundToInt()
    }
}

/**
 * Uscita registrata: si salva in JSON (dati completi per il PC) e in GPX con battito e
 * cadenza (estensioni Garmin, leggibili da Strava, Garmin Connect e dal Planner).
 * Nessuna dipendenza da Android (testabile).
 */
class Session(
    val name: String,
    val workoutName: String?,
    val maxHr: Int,
    val samples: List<Sample>,
    val distanceM: Double,
    /** secondi effettivi (senza le pause) */
    val activeSeconds: Int
) {

    fun summary(): Summary {
        val hrs = samples.mapNotNull { it.hr }
        val cads = samples.mapNotNull { it.cad }.filter { it > 0 }
        val zones = IntArray(5)
        for (h in hrs) zones[Zones.zoneOf(h, maxHr) - 1]++
        val sec = activeSeconds
        return Summary(
            start = samples.firstOrNull()?.t ?: 0L,
            seconds = sec,
            distanceM = distanceM,
            avgHr = if (hrs.isEmpty()) null else hrs.average().roundToInt(),
            maxHrSeen = hrs.maxOrNull(),
            avgCad = if (cads.isEmpty()) null else cads.average().roundToInt(),
            avgKmh = if (sec > 0) distanceM / sec * 3.6 else 0.0,
            zoneSeconds = zones
        )
    }

    val hasGps get() = samples.count { it.lat != null && it.lon != null } >= 2

    fun toJson(): JSONObject {
        val s = summary()
        val arr = JSONArray()
        for (p in samples) {
            arr.put(JSONArray().apply {
                put(p.t)
                put(p.hr ?: JSONObject.NULL)
                put(p.cad ?: JSONObject.NULL)
                put(p.kmh?.let { (it * 10).roundToInt() / 10.0 } ?: JSONObject.NULL)
                put(p.lat ?: JSONObject.NULL)
                put(p.lon ?: JSONObject.NULL)
                put(p.ele?.let { (it * 10).roundToInt() / 10.0 } ?: JSONObject.NULL)
            })
        }
        return JSONObject()
            .put("app", "SirJoe Training")
            .put("format", 1)
            .put("name", name)
            .put("workout", workoutName ?: JSONObject.NULL)
            .put("maxHr", maxHr)
            .put("start", s.start)
            .put("seconds", s.seconds)
            .put("distanceM", s.distanceM.roundToInt())
            .put("avgHr", s.avgHr ?: JSONObject.NULL)
            .put("maxHrSeen", s.maxHrSeen ?: JSONObject.NULL)
            .put("avgCad", s.avgCad ?: JSONObject.NULL)
            .put("zoneSeconds", JSONArray().apply { s.zoneSeconds.forEach { put(it) } })
            .put("cols", JSONArray(listOf("t", "hr", "cad", "kmh", "lat", "lon", "ele")))
            .put("samples", arr)
    }

    fun toGpx(): String {
        val sb = StringBuilder()
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
        sb.append("<gpx version=\"1.1\" creator=\"SirJoe Training\" xmlns=\"http://www.topografix.com/GPX/1/1\" ")
        sb.append("xmlns:gpxtpx=\"http://www.garmin.com/xmlschemas/TrackPointExtension/v1\">\n")
        sb.append("  <metadata><name>").append(esc(name)).append("</name></metadata>\n")
        sb.append("  <trk><name>").append(esc(name)).append("</name><type>cycling</type><trkseg>\n")
        for (p in samples) {
            if (p.lat == null || p.lon == null) continue
            sb.append("    <trkpt lat=\"").append(n7(p.lat)).append("\" lon=\"").append(n7(p.lon)).append("\">")
            p.ele?.let { sb.append("<ele>").append(String.format(Locale.US, "%.1f", it)).append("</ele>") }
            sb.append("<time>").append(Instant.ofEpochMilli(p.t).toString()).append("</time>")
            if (p.hr != null || p.cad != null) {
                sb.append("<extensions><gpxtpx:TrackPointExtension>")
                p.hr?.let { sb.append("<gpxtpx:hr>").append(it).append("</gpxtpx:hr>") }
                p.cad?.let { sb.append("<gpxtpx:cad>").append(it).append("</gpxtpx:cad>") }
                sb.append("</gpxtpx:TrackPointExtension></extensions>")
            }
            sb.append("</trkpt>\n")
        }
        sb.append("  </trkseg></trk>\n</gpx>\n")
        return sb.toString()
    }

    companion object {
        private fun n7(v: Double) = String.format(Locale.US, "%.7f", v)
        private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

        /** Nome predefinito: "Allenamento 30-10-2026 09:15" */
        fun defaultName(start: Long, workout: String?): String {
            val d = DateTimeFormatter.ofPattern("dd-MM-yyyy HH:mm", Locale.ITALY).withZone(ZoneId.systemDefault())
                .format(Instant.ofEpochMilli(start))
            return (workout ?: "Uscita libera") + " " + d
        }

        fun fromJson(o: JSONObject): Session {
            val arr = o.optJSONArray("samples") ?: JSONArray()
            val list = ArrayList<Sample>(arr.length())
            fun JSONArray.optIntOrNull(i: Int) = if (isNull(i) || i >= length()) null else optInt(i)
            fun JSONArray.optDoubleOrNull(i: Int) = if (i >= length() || isNull(i)) null else optDouble(i)
            for (i in 0 until arr.length()) {
                val r = arr.optJSONArray(i) ?: continue
                list += Sample(
                    t = r.optLong(0),
                    hr = r.optIntOrNull(1),
                    cad = r.optIntOrNull(2),
                    kmh = r.optDoubleOrNull(3)?.toFloat(),
                    lat = r.optDoubleOrNull(4),
                    lon = r.optDoubleOrNull(5),
                    ele = r.optDoubleOrNull(6)
                )
            }
            return Session(
                name = o.optString("name", "Uscita"),
                workoutName = if (o.isNull("workout")) null else o.optString("workout"),
                maxHr = o.optInt("maxHr", 160),
                samples = list,
                distanceM = o.optDouble("distanceM", 0.0),
                activeSeconds = o.optInt("seconds", list.size)
            )
        }
    }
}
