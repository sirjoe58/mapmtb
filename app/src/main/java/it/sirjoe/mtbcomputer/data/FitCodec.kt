package it.sirjoe.mtbcomputer.data

import it.sirjoe.mtbcomputer.util.Geo
import java.io.ByteArrayOutputStream

/**
 * Lettura e scrittura minima del formato FIT (Garmin, Wahoo, Hammerhead...).
 * - Scrittura: file "Course" (percorso da seguire sul ciclocomputer).
 * - Lettura: punti GPS dei messaggi "record" di attivita' e percorsi.
 */
object FitCodec {

    /** Secondi tra 1970-01-01 e l'epoca FIT 1989-12-31. */
    private const val FIT_EPOCH = 631_065_600L
    private const val SEMI = 2147483648.0 / 180.0

    private const val T_ENUM = 0x00
    private const val T_STRING = 0x07
    private const val T_UINT16 = 0x84
    private const val T_SINT32 = 0x85
    private const val T_UINT32 = 0x86

    private val CRC_TABLE = intArrayOf(
        0x0000, 0xCC01, 0xD801, 0x1400, 0xF001, 0x3C00, 0x2800, 0xE401,
        0xA001, 0x6C00, 0x7800, 0xB401, 0x5000, 0x9C01, 0x8801, 0x4400
    )

    fun isFit(b: ByteArray): Boolean =
        b.size > 12 && b[8] == '.'.code.toByte() && b[9] == 'F'.code.toByte() &&
            b[10] == 'I'.code.toByte() && b[11] == 'T'.code.toByte()

    private fun crc(data: ByteArray, from: Int = 0, to: Int = data.size): Int {
        var crc = 0
        for (i in from until to) {
            val byte = data[i].toInt() and 0xFF
            var tmp = CRC_TABLE[crc and 0xF]
            crc = (crc shr 4) and 0x0FFF
            crc = crc xor tmp xor CRC_TABLE[byte and 0xF]
            tmp = CRC_TABLE[crc and 0xF]
            crc = (crc shr 4) and 0x0FFF
            crc = crc xor tmp xor CRC_TABLE[(byte shr 4) and 0xF]
        }
        return crc
    }

    // ----------------------------------------------------------- scrittura

    private class Out {
        val buf = ByteArrayOutputStream()
        fun u8(v: Int) = buf.write(v and 0xFF)
        fun u16(v: Int) {
            u8(v); u8(v shr 8)
        }
        fun u32(v: Long) {
            u8(v.toInt()); u8((v shr 8).toInt()); u8((v shr 16).toInt()); u8((v shr 24).toInt())
        }
        fun bytes(b: ByteArray) = buf.write(b)

        /** Messaggio di definizione: campi = (numero, dimensione, tipo base). */
        fun def(local: Int, global: Int, fields: List<Triple<Int, Int, Int>>) {
            u8(0x40 or local); u8(0); u8(0); u16(global); u8(fields.size)
            for ((num, size, type) in fields) {
                u8(num); u8(size); u8(type)
            }
        }
    }

    private fun fitTime(ms: Long) = ms / 1000 - FIT_EPOCH
    private fun semi(deg: Double) = Math.round(deg * SEMI).toInt().toLong() and 0xFFFFFFFFL

    fun encodeCourse(track: Track): ByteArray {
        val pts = track.points
        require(pts.size >= 2) { "Percorso troppo corto" }
        val cum = Geo.cumulative(pts)

        // Orari: quelli della traccia, oppure simulati a 16 km/h per i percorsi disegnati
        val hasTimes = pts.all { it.time != null }
        val start = pts.first().time ?: System.currentTimeMillis()
        val times = LongArray(pts.size) { i ->
            if (hasTimes) pts[i].time!! else start + (cum[i] / 4.44 * 1000).toLong()
        }
        for (i in 1 until times.size) if (times[i] <= times[i - 1]) times[i] = times[i - 1] + 1000

        val o = Out()

        // file_id
        o.def(0, 0, listOf(Triple(0, 1, T_ENUM), Triple(1, 2, T_UINT16), Triple(2, 2, T_UINT16), Triple(4, 4, T_UINT32)))
        o.u8(0); o.u8(6); o.u16(255); o.u16(0); o.u32(fitTime(start))

        // course: sport ciclismo + nome (max 15 caratteri)
        o.def(1, 31, listOf(Triple(4, 1, T_ENUM), Triple(5, 16, T_STRING)))
        o.u8(1); o.u8(2)
        val name = ByteArray(16)
        val nb = track.name.take(15).toByteArray(Charsets.UTF_8)
        System.arraycopy(nb, 0, name, 0, minOf(nb.size, 15))
        o.bytes(name)

        // lap riassuntivo
        val first = pts.first()
        val last = pts.last()
        val elapsed = (times.last() - times.first())
        o.def(
            2, 19, listOf(
                Triple(253, 4, T_UINT32), Triple(2, 4, T_UINT32),
                Triple(3, 4, T_SINT32), Triple(4, 4, T_SINT32),
                Triple(5, 4, T_SINT32), Triple(6, 4, T_SINT32),
                Triple(7, 4, T_UINT32), Triple(8, 4, T_UINT32), Triple(9, 4, T_UINT32)
            )
        )
        o.u8(2)
        o.u32(fitTime(times.last())); o.u32(fitTime(times.first()))
        o.u32(semi(first.lat)); o.u32(semi(first.lon)); o.u32(semi(last.lat)); o.u32(semi(last.lon))
        o.u32(elapsed); o.u32(elapsed); o.u32((cum.last() * 100).toLong())

        // evento di partenza
        o.def(3, 21, listOf(Triple(253, 4, T_UINT32), Triple(0, 1, T_ENUM), Triple(1, 1, T_ENUM)))
        o.u8(3); o.u32(fitTime(times.first())); o.u8(0); o.u8(0)

        // punti
        o.def(
            4, 20, listOf(
                Triple(253, 4, T_UINT32), Triple(0, 4, T_SINT32), Triple(1, 4, T_SINT32),
                Triple(2, 2, T_UINT16), Triple(5, 4, T_UINT32)
            )
        )
        pts.forEachIndexed { i, p ->
            o.u8(4)
            o.u32(fitTime(times[i]))
            o.u32(semi(p.lat)); o.u32(semi(p.lon))
            val alt = p.ele
            o.u16(if (alt == null) 0xFFFF else ((alt + 500.0) * 5.0).toInt().coerceIn(0, 0xFFFE))
            o.u32((cum[i] * 100).toLong())
        }

        // evento di fine (stop_disable_all)
        o.u8(3); o.u32(fitTime(times.last())); o.u8(0); o.u8(9)

        val data = o.buf.toByteArray()
        val header = ByteArray(14)
        header[0] = 14
        header[1] = 0x10
        header[2] = (2132 and 0xFF).toByte(); header[3] = (2132 shr 8).toByte()
        for (k in 0..3) header[4 + k] = (data.size shr (8 * k)).toByte()
        header[8] = '.'.code.toByte(); header[9] = 'F'.code.toByte()
        header[10] = 'I'.code.toByte(); header[11] = 'T'.code.toByte()
        val hc = crc(header, 0, 12)
        header[12] = hc.toByte(); header[13] = (hc shr 8).toByte()

        val all = header + data
        val fc = crc(all)
        return all + byteArrayOf(fc.toByte(), (fc shr 8).toByte())
    }

    // ------------------------------------------------------------- lettura

    private class Field(val num: Int, val size: Int, val type: Int)
    private class Def(val global: Int, val big: Boolean, val fields: List<Field>, val devSize: Int)

    private fun readInt(b: ByteArray, pos: Int, size: Int, big: Boolean): Long {
        var v = 0L
        for (k in 0 until size) {
            val byte = (b[pos + k].toLong() and 0xFF)
            v = if (big) (v shl 8) or byte else v or (byte shl (8 * k))
        }
        return v
    }

    fun decode(b: ByteArray, fallbackName: String): Track {
        require(isFit(b)) { "Non e' un file FIT valido" }
        val headerSize = b[0].toInt() and 0xFF
        val dataSize = readInt(b, 4, 4, false).toInt()
        val end = minOf(headerSize + dataSize, b.size)

        val defs = arrayOfNulls<Def>(16)
        val pts = ArrayList<TrackPoint>()
        var name: String? = null
        var lastTs = 0L
        var pos = headerSize

        while (pos < end) {
            val h = b[pos++].toInt() and 0xFF
            var forcedTs: Long? = null
            val local: Int
            if (h and 0x80 != 0) {
                local = (h shr 5) and 0x3
                val off = (h and 0x1F).toLong()
                var ts = (lastTs and 0x1FL.inv()) + off
                if (off < (lastTs and 0x1F)) ts += 0x20
                forcedTs = ts
                lastTs = ts
            } else if (h and 0x40 != 0) {
                val dev = h and 0x20 != 0
                val l = h and 0x0F
                pos++ // riservato
                val big = b[pos++].toInt() == 1
                val global = readInt(b, pos, 2, big).toInt()
                pos += 2
                val n = b[pos++].toInt() and 0xFF
                val fields = ArrayList<Field>(n)
                repeat(n) {
                    fields.add(Field(b[pos].toInt() and 0xFF, b[pos + 1].toInt() and 0xFF, b[pos + 2].toInt() and 0xFF))
                    pos += 3
                }
                var devSize = 0
                if (dev) {
                    val nd = b[pos++].toInt() and 0xFF
                    repeat(nd) {
                        devSize += b[pos + 1].toInt() and 0xFF
                        pos += 3
                    }
                }
                defs[l] = Def(global, big, fields, devSize)
                continue
            } else {
                local = h and 0x0F
            }

            val def = defs[local] ?: throw IllegalArgumentException("File FIT danneggiato")
            var lat: Long? = null
            var lon: Long? = null
            var alt: Double? = null
            var ts: Long? = forcedTs
            for (f in def.fields) {
                if (pos + f.size > b.size) break
                if (f.size == 1 || f.size == 2 || f.size == 4) {
                    val raw = readInt(b, pos, f.size, def.big)
                    when {
                        f.num == 253 && f.size == 4 && raw != 0xFFFFFFFFL -> {
                            ts = raw; lastTs = raw
                        }
                        def.global == 20 && f.num == 0 && raw != 0x7FFFFFFFL -> lat = raw
                        def.global == 20 && f.num == 1 && raw != 0x7FFFFFFFL -> lon = raw
                        def.global == 20 && f.num == 2 && f.size == 2 && raw != 0xFFFFL ->
                            if (alt == null) alt = raw / 5.0 - 500.0
                        def.global == 20 && f.num == 78 && f.size == 4 && raw != 0xFFFFFFFFL ->
                            alt = raw / 5.0 - 500.0
                    }
                } else if (def.global == 31 && f.num == 5) {
                    val s = String(b, pos, f.size, Charsets.UTF_8).substringBefore('\u0000').trim()
                    if (s.isNotEmpty()) name = s
                }
                pos += f.size
            }
            pos += def.devSize

            val la = lat
            val lo = lon
            if (def.global == 20 && la != null && lo != null) {
                val latDeg = la.toInt() / SEMI
                val lonDeg = lo.toInt() / SEMI
                if (latDeg in -90.0..90.0 && lonDeg in -180.0..180.0) {
                    pts.add(TrackPoint(latDeg, lonDeg, alt, ts?.let { (it + FIT_EPOCH) * 1000 }))
                }
            }
        }
        if (pts.isEmpty()) throw IllegalArgumentException("Nessun punto GPS nel file FIT")
        return Track(name ?: fallbackName, pts)
    }
}
