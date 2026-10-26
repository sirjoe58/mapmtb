package it.sirjoe.mtbcomputer.data

import android.util.Xml
import it.sirjoe.mtbcomputer.util.Geo
import org.json.JSONArray
import org.json.JSONObject
import org.xmlpull.v1.XmlPullParser
import java.io.ByteArrayInputStream
import java.io.StringReader
import java.time.Instant
import java.time.OffsetDateTime
import java.util.Locale
import java.util.zip.ZipInputStream

/** Formati supportati per import ed export delle tracce. */
enum class TrackFormat(val ext: String, val mime: String, val label: String) {
    GPX("gpx", "application/gpx+xml", "GPX (universale: Komoot, Strava, Wikiloc...)"),
    FIT("fit", "application/vnd.ant.fit", "FIT (Garmin, Wahoo, Hammerhead)"),
    KML("kml", "application/vnd.google-earth.kml+xml", "KML (Google Earth)"),
    TCX("tcx", "application/vnd.garmin.tcx+xml", "TCX (Garmin)"),
    GEOJSON("geojson", "application/geo+json", "GeoJSON"),
    CSV("csv", "text/csv", "CSV (foglio di calcolo)")
}

/**
 * Lettura e scrittura delle tracce.
 * Import: GPX, FIT, KML, KMZ, TCX, GeoJSON, CSV. Export: GPX, FIT, KML, TCX, GeoJSON, CSV.
 */
object TrackIO {

    fun parse(bytes: ByteArray, fileName: String): Track {
        val lower = fileName.lowercase(Locale.ROOT)
        val base = fileName.substringBeforeLast('.').ifBlank { "Traccia" }

        if (lower.endsWith(".fit") || FitCodec.isFit(bytes)) return FitCodec.decode(bytes, base)

        if (lower.endsWith(".kmz") || isZip(bytes)) {
            val kml = unzipFirstKml(bytes)
                ?: throw IllegalArgumentException("Nel file KMZ non c'e' nessun KML")
            return parseXml(kml, base)
        }

        val text = String(bytes, Charsets.UTF_8).trimStart('﻿', ' ', '\n', '\r', '\t')
        return when {
            lower.endsWith(".geojson") || lower.endsWith(".json") || text.startsWith("{") ->
                parseGeoJson(text, base)
            lower.endsWith(".csv") || lower.endsWith(".txt") -> parseCsv(text, base)
            else -> parseXml(text, base)
        }
    }

    // ---------------------------------------------------------------- lettura

    private fun isZip(b: ByteArray) =
        b.size > 4 && b[0] == 'P'.code.toByte() && b[1] == 'K'.code.toByte()

    private fun unzipFirstKml(bytes: ByteArray): String? {
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                if (!entry.isDirectory && entry.name.lowercase(Locale.ROOT).endsWith(".kml")) {
                    return String(zip.readBytes(), Charsets.UTF_8)
                }
                entry = zip.nextEntry
            }
        }
        return null
    }

    private fun local(name: String): String = name.substringAfter(':')

    fun parseTime(t: String?): Long? {
        if (t.isNullOrBlank()) return null
        return try {
            Instant.parse(t).toEpochMilli()
        } catch (e: Exception) {
            try {
                OffsetDateTime.parse(t).toInstant().toEpochMilli()
            } catch (e2: Exception) {
                null
            }
        }
    }

    /** GPX, TCX e KML con un unico lettore XML. */
    private fun parseXml(text: String, base: String): Track {
        val parser = Xml.newPullParser()
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
        parser.setInput(StringReader(text))

        var name: String? = null
        val pts = ArrayList<TrackPoint>()
        val kmlLine = ArrayList<TrackPoint>()
        val gxCoords = ArrayList<TrackPoint>()
        val gxWhens = ArrayList<Long?>()

        var inPoint = false
        var inLine = false
        var inHeart = false
        var curLat: Double? = null
        var curLon: Double? = null
        var curEle: Double? = null
        var curTime: Long? = null
        var curHr: Int? = null
        var curCad: Int? = null
        var curPow: Int? = null
        val buf = StringBuilder()

        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> {
                    buf.setLength(0)
                    when (local(parser.name)) {
                        "trkpt", "rtept" -> {
                            inPoint = true
                            curLat = parser.getAttributeValue(null, "lat")?.toDoubleOrNull()
                            curLon = parser.getAttributeValue(null, "lon")?.toDoubleOrNull()
                            curEle = null
                            curTime = null
                            curHr = null; curCad = null; curPow = null
                        }
                        "Trackpoint" -> {
                            inPoint = true
                            curLat = null
                            curLon = null
                            curEle = null
                            curTime = null
                            curHr = null; curCad = null; curPow = null
                        }
                        "LineString" -> inLine = true
                        "HeartRateBpm" -> inHeart = true
                    }
                }
                XmlPullParser.TEXT -> buf.append(parser.text)
                XmlPullParser.END_TAG -> {
                    val t = buf.toString().trim()
                    when (local(parser.name)) {
                        "name", "Name" -> if (name == null && t.isNotEmpty()) name = t
                        "ele", "AltitudeMeters" -> if (inPoint) curEle = t.toDoubleOrNull()
                        "time", "Time" -> if (inPoint) curTime = parseTime(t)
                        "LatitudeDegrees" -> if (inPoint) curLat = t.toDoubleOrNull()
                        "LongitudeDegrees" -> if (inPoint) curLon = t.toDoubleOrNull()
                        "hr" -> if (inPoint) curHr = t.toDoubleOrNull()?.toInt()
                        "cad", "Cadence" -> if (inPoint) curCad = t.toDoubleOrNull()?.toInt()
                        "power", "Watts", "PowerInWatts" -> if (inPoint) curPow = t.toDoubleOrNull()?.toInt()
                        "Value" -> if (inPoint && curHr == null && inHeart) curHr = t.toDoubleOrNull()?.toInt()
                        "trkpt", "rtept", "Trackpoint" -> {
                            val la = curLat
                            val lo = curLon
                            if (la != null && lo != null) pts.add(TrackPoint(la, lo, curEle, curTime, curHr, curCad, curPow))
                            inPoint = false
                        }
                        "LineString" -> inLine = false
                        "HeartRateBpm" -> inHeart = false
                        "coordinates" -> if (inLine) parseKmlCoordinates(t, kmlLine)
                        "when" -> gxWhens.add(parseTime(t))
                        "coord" -> {
                            val p = t.split(Regex("\\s+"))
                            val lo = p.getOrNull(0)?.toDoubleOrNull()
                            val la = p.getOrNull(1)?.toDoubleOrNull()
                            if (la != null && lo != null) {
                                gxCoords.add(TrackPoint(la, lo, p.getOrNull(2)?.toDoubleOrNull()))
                            }
                        }
                    }
                    buf.setLength(0)
                }
            }
            event = parser.next()
        }

        val result: List<TrackPoint> = when {
            pts.isNotEmpty() -> pts
            gxCoords.isNotEmpty() -> gxCoords.mapIndexed { i, p -> p.copy(time = gxWhens.getOrNull(i)) }
            else -> kmlLine
        }
        if (result.isEmpty()) throw IllegalArgumentException("Nessun punto trovato nel file")
        return Track(name ?: base, result)
    }

    private fun parseKmlCoordinates(t: String, out: MutableList<TrackPoint>) {
        for (tuple in t.split(Regex("\\s+"))) {
            if (tuple.isBlank()) continue
            val p = tuple.split(",")
            val lo = p.getOrNull(0)?.toDoubleOrNull()
            val la = p.getOrNull(1)?.toDoubleOrNull()
            if (la != null && lo != null) out.add(TrackPoint(la, lo, p.getOrNull(2)?.toDoubleOrNull()))
        }
    }

    private fun parseGeoJson(text: String, base: String): Track {
        val root = JSONObject(text)
        var name: String? = root.optJSONObject("properties")?.optString("name")?.takeIf { it.isNotBlank() }
        val pts = ArrayList<TrackPoint>()

        fun addLine(coords: JSONArray?, times: JSONArray?) {
            if (coords == null) return
            for (i in 0 until coords.length()) {
                val c = coords.optJSONArray(i) ?: continue
                if (c.length() < 2) continue
                val ele = if (c.length() > 2) c.optDouble(2).takeIf { !it.isNaN() } else null
                val time = times?.optString(i)?.let { parseTime(it) }
                pts.add(TrackPoint(c.getDouble(1), c.getDouble(0), ele, time))
            }
        }

        fun addGeometry(g: JSONObject?, props: JSONObject?) {
            if (g == null) return
            val times = props?.optJSONArray("coordTimes")
            when (g.optString("type")) {
                "LineString" -> addLine(g.optJSONArray("coordinates"), times)
                "MultiLineString" -> {
                    val lines = g.optJSONArray("coordinates") ?: return
                    for (i in 0 until lines.length()) {
                        addLine(lines.optJSONArray(i), times?.optJSONArray(i))
                    }
                }
            }
            if (name == null) name = props?.optString("name")?.takeIf { it.isNotBlank() }
        }

        when (root.optString("type")) {
            "FeatureCollection" -> {
                val f = root.optJSONArray("features") ?: JSONArray()
                for (i in 0 until f.length()) {
                    val feat = f.optJSONObject(i) ?: continue
                    addGeometry(feat.optJSONObject("geometry"), feat.optJSONObject("properties"))
                }
            }
            "Feature" -> addGeometry(root.optJSONObject("geometry"), root.optJSONObject("properties"))
            else -> addGeometry(root, null)
        }
        if (pts.isEmpty()) throw IllegalArgumentException("Nessuna linea trovata nel GeoJSON")
        return Track(name ?: base, pts)
    }

    private fun parseCsv(text: String, base: String): Track {
        val lines = text.lines().filter { it.isNotBlank() }
        if (lines.isEmpty()) throw IllegalArgumentException("File CSV vuoto")
        val sep = if (lines[0].count { it == ';' } > lines[0].count { it == ',' }) ";" else ","
        val header = lines[0].split(sep).map { it.trim().lowercase(Locale.ROOT) }
        val hasHeader = header.any { it.contains("lat") }
        var iLat = 0
        var iLon = 1
        var iEle = 2
        var iTime = 3
        if (hasHeader) {
            iLat = header.indexOfFirst { it.contains("lat") }
            iLon = header.indexOfFirst { it.contains("lon") || it.contains("lng") }
            iEle = header.indexOfFirst { it.contains("ele") || it.contains("alt") }
            iTime = header.indexOfFirst { it.contains("time") || it.contains("ora") }
        }
        val pts = ArrayList<TrackPoint>()
        for (line in if (hasHeader) lines.drop(1) else lines) {
            val c = line.split(sep).map { it.trim() }
            val la = c.getOrNull(iLat)?.toDoubleOrNull() ?: continue
            val lo = c.getOrNull(iLon)?.toDoubleOrNull() ?: continue
            val ele = if (iEle >= 0) c.getOrNull(iEle)?.toDoubleOrNull() else null
            val time = if (iTime >= 0) parseTime(c.getOrNull(iTime)) else null
            pts.add(TrackPoint(la, lo, ele, time))
        }
        if (pts.isEmpty()) throw IllegalArgumentException("Nessun punto valido nel CSV")
        return Track(base, pts)
    }

    // ------------------------------------------------------- segnaposto GPX

    /** Legge i segnaposto (<wpt>) di un file GPX. */
    fun parseWaypoints(bytes: ByteArray): List<Placemark> {
        val text = String(bytes, Charsets.UTF_8).trimStart('\uFEFF', ' ', '\n', '\r', '\t')
        if (!text.startsWith("<")) return emptyList()
        val out = ArrayList<Placemark>()
        try {
            val parser = Xml.newPullParser()
            parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
            parser.setInput(StringReader(text))
            var inWpt = false
            var lat: Double? = null
            var lon: Double? = null
            var name: String? = null
            var note = ""
            var ele: Double? = null
            var type = PlaceType.GENERIC
            val buf = StringBuilder()
            var ev = parser.eventType
            while (ev != XmlPullParser.END_DOCUMENT) {
                when (ev) {
                    XmlPullParser.START_TAG -> {
                        buf.setLength(0)
                        if (local(parser.name) == "wpt") {
                            inWpt = true
                            lat = parser.getAttributeValue(null, "lat")?.toDoubleOrNull()
                            lon = parser.getAttributeValue(null, "lon")?.toDoubleOrNull()
                            name = null; note = ""; ele = null; type = PlaceType.GENERIC
                        }
                    }
                    XmlPullParser.TEXT -> buf.append(parser.text)
                    XmlPullParser.END_TAG -> {
                        val t = buf.toString().trim()
                        if (inWpt) when (local(parser.name)) {
                            "name" -> name = t
                            "desc", "cmt" -> if (note.isEmpty()) note = t
                            "ele" -> ele = t.toDoubleOrNull()
                            "type" -> type = PlaceType.of(t)
                            "wpt" -> {
                                val la = lat
                                val lo = lon
                                if (la != null && lo != null) {
                                    out.add(Placemark(name = name ?: "Segnaposto", note = note, lat = la, lon = lo, ele = ele, type = type))
                                }
                                inWpt = false
                            }
                        }
                        buf.setLength(0)
                    }
                }
                ev = parser.next()
            }
        } catch (_: Exception) {
        }
        return out
    }

    /** Scrive i segnaposto in un file GPX (leggibile da Komoot, Garmin, Locus, OsmAnd...). */
    fun writeWaypointsGpx(list: List<Placemark>): ByteArray {
        val sb = StringBuilder()
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
        sb.append("<gpx version=\"1.1\" creator=\"SirJoe MTB\" xmlns=\"http://www.topografix.com/GPX/1/1\">\n")
        for (p in list) {
            sb.append("  <wpt lat=\"").append(n(p.lat, 7)).append("\" lon=\"").append(n(p.lon, 7)).append("\">")
            p.ele?.let { sb.append("<ele>").append(n(it, 1)).append("</ele>") }
            sb.append("<name>").append(esc(p.name)).append("</name>")
            if (p.note.isNotBlank()) sb.append("<desc>").append(esc(p.note)).append("</desc>")
            sb.append("<type>").append(p.type.id).append("</type>")
            sb.append("</wpt>\n")
        }
        sb.append("</gpx>\n")
        return sb.toString().toByteArray()
    }

    // -------------------------------------------------------------- scrittura

    fun write(track: Track, format: TrackFormat): ByteArray = when (format) {
        TrackFormat.GPX -> writeGpx(track).toByteArray()
        TrackFormat.FIT -> FitCodec.encodeCourse(track)
        TrackFormat.KML -> writeKml(track).toByteArray()
        TrackFormat.TCX -> writeTcx(track).toByteArray()
        TrackFormat.GEOJSON -> writeGeoJson(track).toByteArray()
        TrackFormat.CSV -> writeCsv(track).toByteArray()
    }

    private fun n(v: Double, digits: Int) = String.format(Locale.US, "%.${digits}f", v)

    private fun iso(ms: Long): String = Instant.ofEpochMilli(ms).toString()

    private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;")
        .replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&apos;")

    private fun writeGpx(t: Track): String {
        val sb = StringBuilder()
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
        sb.append("<gpx version=\"1.1\" creator=\"SirJoe MTB\" xmlns=\"http://www.topografix.com/GPX/1/1\"")
        sb.append(" xmlns:gpxtpx=\"http://www.garmin.com/xmlschemas/TrackPointExtension/v1\">\n")
        sb.append("  <metadata><name>").append(esc(t.name)).append("</name></metadata>\n")
        // segnaposto salvati con la traccia (es. segnalazioni fatte durante l'uscita)
        for (w in t.waypoints) {
            sb.append("  <wpt lat=\"").append(n(w.lat, 7)).append("\" lon=\"").append(n(w.lon, 7)).append("\">")
            w.ele?.let { sb.append("<ele>").append(n(it, 1)).append("</ele>") }
            sb.append("<name>").append(esc(w.name)).append("</name>")
            if (w.note.isNotBlank()) sb.append("<desc>").append(esc(w.note)).append("</desc>")
            sb.append("<type>").append(esc(w.type.id)).append("</type>")
            sb.append("</wpt>\n")
        }
        sb.append("  <trk>\n    <name>").append(esc(t.name)).append("</name>\n    <trkseg>\n")
        for (p in t.points) {
            sb.append("      <trkpt lat=\"").append(n(p.lat, 7)).append("\" lon=\"").append(n(p.lon, 7)).append("\">")
            p.ele?.let { sb.append("<ele>").append(n(it, 1)).append("</ele>") }
            p.time?.let { sb.append("<time>").append(iso(it)).append("</time>") }
            // battito e cadenza nel formato Garmin (letto da Strava, Garmin Connect, Komoot...), potenza in <power>
            if (p.hr != null || p.cad != null || p.power != null) {
                sb.append("<extensions>")
                p.power?.let { sb.append("<power>").append(it).append("</power>") }
                if (p.hr != null || p.cad != null) {
                    sb.append("<gpxtpx:TrackPointExtension>")
                    p.hr?.let { sb.append("<gpxtpx:hr>").append(it).append("</gpxtpx:hr>") }
                    p.cad?.let { sb.append("<gpxtpx:cad>").append(it).append("</gpxtpx:cad>") }
                    sb.append("</gpxtpx:TrackPointExtension>")
                }
                sb.append("</extensions>")
            }
            sb.append("</trkpt>\n")
        }
        sb.append("    </trkseg>\n  </trk>\n</gpx>\n")
        return sb.toString()
    }

    private fun writeKml(t: Track): String {
        val sb = StringBuilder()
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
        sb.append("<kml xmlns=\"http://www.opengis.net/kml/2.2\">\n<Document>\n")
        sb.append("  <name>").append(esc(t.name)).append("</name>\n")
        sb.append("  <Style id=\"linea\"><LineStyle><color>ff006dff</color><width>4</width></LineStyle></Style>\n")
        sb.append("  <Placemark>\n    <name>").append(esc(t.name)).append("</name>\n")
        sb.append("    <styleUrl>#linea</styleUrl>\n    <LineString>\n      <tessellate>1</tessellate>\n      <coordinates>\n")
        for (p in t.points) {
            sb.append("        ").append(n(p.lon, 7)).append(',').append(n(p.lat, 7))
            p.ele?.let { sb.append(',').append(n(it, 1)) }
            sb.append('\n')
        }
        sb.append("      </coordinates>\n    </LineString>\n  </Placemark>\n</Document>\n</kml>\n")
        return sb.toString()
    }

    private fun writeTcx(t: Track): String {
        val cum = Geo.cumulative(t.points)
        val start = t.points.firstOrNull()?.time ?: System.currentTimeMillis()
        val sb = StringBuilder()
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
        sb.append("<TrainingCenterDatabase xmlns=\"http://www.garmin.com/xmlschemas/TrainingCenterDatabase/v2\">\n")
        sb.append("  <Courses>\n    <Course>\n      <Name>").append(esc(t.name.take(15))).append("</Name>\n")
        sb.append("      <Track>\n")
        t.points.forEachIndexed { i, p ->
            val time = p.time ?: (start + i * 1000L)
            sb.append("        <Trackpoint><Time>").append(iso(time)).append("</Time>")
            sb.append("<Position><LatitudeDegrees>").append(n(p.lat, 7)).append("</LatitudeDegrees>")
            sb.append("<LongitudeDegrees>").append(n(p.lon, 7)).append("</LongitudeDegrees></Position>")
            p.ele?.let { sb.append("<AltitudeMeters>").append(n(it, 1)).append("</AltitudeMeters>") }
            sb.append("<DistanceMeters>").append(n(cum[i], 1)).append("</DistanceMeters>")
            p.hr?.let { sb.append("<HeartRateBpm><Value>").append(it).append("</Value></HeartRateBpm>") }
            p.cad?.let { sb.append("<Cadence>").append(it.coerceAtMost(254)).append("</Cadence>") }
            sb.append("</Trackpoint>\n")
        }
        sb.append("      </Track>\n    </Course>\n  </Courses>\n</TrainingCenterDatabase>\n")
        return sb.toString()
    }

    private fun writeGeoJson(t: Track): String {
        val coords = JSONArray()
        val times = JSONArray()
        var hasTimes = false
        for (p in t.points) {
            val c = JSONArray()
            c.put(p.lon)
            c.put(p.lat)
            p.ele?.let { c.put(it) }
            coords.put(c)
            val tm = p.time
            if (tm != null) {
                hasTimes = true
                times.put(iso(tm))
            } else {
                times.put(JSONObject.NULL)
            }
        }
        val props = JSONObject().put("name", t.name)
        if (hasTimes) props.put("coordTimes", times)
        val geometry = JSONObject().put("type", "LineString").put("coordinates", coords)
        val feature = JSONObject().put("type", "Feature").put("properties", props).put("geometry", geometry)
        return JSONObject().put("type", "FeatureCollection")
            .put("features", JSONArray().put(feature)).toString(2)
    }

    private fun writeCsv(t: Track): String {
        val sb = StringBuilder("lat,lon,ele,time\n")
        for (p in t.points) {
            sb.append(n(p.lat, 7)).append(',').append(n(p.lon, 7)).append(',')
            p.ele?.let { sb.append(n(it, 1)) }
            sb.append(',')
            p.time?.let { sb.append(iso(it)) }
            sb.append('\n')
        }
        return sb.toString()
    }
}
