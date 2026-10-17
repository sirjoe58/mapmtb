package it.sirjoe.mtbcomputer.data

import it.sirjoe.mtbcomputer.util.Geo
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max

/** Salita: distanze in metri dall'inizio del percorso, pendenze come frazione (0.08 = 8%). */
data class Climb(val start: Double, val end: Double, val length: Double, val gain: Double, val avg: Double, val max: Double)

object RouteAnalysis {

    /**
     * Salite: tratti di almeno 300 m con pendenza media >= 5% e almeno 25 m di dislivello.
     * Stesso metodo del Planner per Windows.
     */
    fun climbs(points: List<TrackPoint>): List<Climb> {
        val p = points.filter { it.ele != null }
        if (p.size < 2) return emptyList()
        val cum = Geo.cumulative(p)
        val total = cum.last()
        fun eleAt(d: Double): Double {
            if (d <= 0) return p.first().ele!!
            if (d >= total) return p.last().ele!!
            var lo = 0
            var hi = cum.size - 1
            while (hi - lo > 1) {
                val m = (lo + hi) / 2
                if (cum[m] < d) lo = m else hi = m
            }
            val f = (d - cum[lo]) / ((cum[hi] - cum[lo]).takeIf { it > 0 } ?: 1.0)
            return p[lo].ele!! + f * (p[hi].ele!! - p[lo].ele!!)
        }

        val out = ArrayList<Climb>()
        var start = -1.0
        var end = 0.0
        var maxG = 0.0
        var low = 0
        fun finish() {
            val length = end - start
            val gain = eleAt(end) - eleAt(start)
            val avg = gain / length
            if (length >= 300 && gain >= 25 && avg >= 0.05) out.add(Climb(start, end, length, gain, avg, maxG))
            start = -1.0
        }
        var s = 0.0
        while (s + 100 <= total) {
            val g = (eleAt(s + 100) - eleAt(s)) / 100
            if (g >= 0.05) {
                if (start < 0) {
                    start = s; maxG = g
                }
                end = s + 100
                maxG = max(maxG, g)
                low = 0
            } else if (start >= 0) {
                low++
                if (g < 0.02 || low >= 3) {
                    finish(); low = 0
                }
            }
            s += 50
        }
        if (start >= 0) finish()
        return out
    }
}

/**
 * Avanzamento lungo un percorso, in qualsiasi senso di marcia e partendo da qualsiasi punto.
 * Cerca il punto più vicino attorno all'ultimo trovato (così nei tratti che si incrociano
 * non "salta" all'altro passaggio) e capisce da solo se lo stai percorrendo al contrario.
 */
class RouteProgress(val points: List<TrackPoint>) {
    val cum: DoubleArray = Geo.cumulative(points)
    val total: Double get() = if (cum.isEmpty()) 0.0 else cum.last()
    private var idx = -1
    var index = 0
        private set
    var offDistance = 0.0
        private set
    /** true se stai seguendo il percorso dalla fine verso l'inizio. */
    var reverse = false
        private set
    private var anchor = -1.0

    private fun dist(lat: Double, lon: Double, i: Int): Double {
        val p = points[i]
        val k = cos(Math.toRadians(lat))
        return hypot((p.lon - lon) * 111320 * k, (p.lat - lat) * 110540)
    }

    private fun nearest(lat: Double, lon: Double, from: Int, to: Int): Pair<Int, Double> {
        var best = from
        var bestD = Double.MAX_VALUE
        for (i in from..to) {
            val d = dist(lat, lon, i)
            if (d < bestD) {
                bestD = d; best = i
            }
        }
        return best to bestD
    }

    /**
     * Aggiorna la posizione; [bearing] è la direzione di marcia in gradi (null se fermo).
     * Restituisce la distanza dall'inizio del percorso, in metri.
     */
    fun update(lat: Double, lon: Double, bearing: Float? = null): Double {
        if (points.isEmpty()) return 0.0
        var (best, bestD) = if (idx < 0) nearest(lat, lon, 0, points.lastIndex)
        else nearest(lat, lon, max(0, idx - 400), minOf(points.lastIndex, idx + 400))
        // perso il filo (es. ripartito da un altro punto): ricerca su tutto il percorso
        if (bestD > 150) {
            val g = nearest(lat, lon, 0, points.lastIndex)
            if (g.second < bestD) {
                if (kotlin.math.abs(cum[g.first] - cum[best]) > 300) anchor = -1.0
                best = g.first; bestD = g.second
            }
        }
        idx = best
        index = best
        offDistance = bestD
        val done = cum[best]

        // senso di marcia: dalla direzione del GPS quando ti muovi...
        if (bearing != null && bestD < 60 && points.size >= 2) {
            val a = points[if (best == points.lastIndex) best - 1 else best]
            val b = points[if (best == points.lastIndex) best else best + 1]
            val seg = Geo.bearing(a.lat, a.lon, b.lat, b.lon)
            val diff = kotlin.math.abs(((bearing - seg) % 360 + 540) % 360 - 180)
            if (diff > 120) reverse = true else if (diff < 60) reverse = false
        }
        // ...e comunque dall'avanzamento lungo il percorso
        if (anchor < 0) anchor = done
        else if (kotlin.math.abs(done - anchor) > 40) {
            reverse = done < anchor
            anchor = done
        }
        return done
    }

    /** Metri che mancano alla fine, nel senso in cui lo stai percorrendo. */
    fun remaining(): Double = if (reverse) cum[index] else total - cum[index]

    /** Punto [meters] metri più avanti, nel senso di marcia. */
    fun aheadIndex(meters: Double): Int {
        var j = index
        if (reverse) {
            while (j > 0 && cum[index] - cum[j] < meters) j--
        } else {
            while (j < points.lastIndex && cum[j] - cum[index] < meters) j++
        }
        return j
    }
}

/** Fantasma: la tua uscita precedente sullo stesso percorso, riprodotta nel tempo. */
class Ghost(track: Track) {
    private val pts = track.points
    private val cum = Geo.cumulative(pts)
    private val t0 = pts.first().time ?: 0L
    private val rel = LongArray(pts.size) { (pts[it].time ?: t0) - t0 }

    val durationMs: Long = rel.last()

    companion object {
        fun canUse(track: Track?) = track != null && track.points.size >= 2 && track.points.all { it.time != null } &&
            (track.points.last().time!! - track.points.first().time!!) > 60_000
    }

    /** Posizione del fantasma dopo [elapsedMs] dalla partenza. */
    fun positionAt(elapsedMs: Long): TrackPoint {
        if (elapsedMs <= 0) return pts.first()
        if (elapsedMs >= durationMs) return pts.last()
        var lo = 0
        var hi = rel.size - 1
        while (hi - lo > 1) {
            val m = (lo + hi) / 2
            if (rel[m] < elapsedMs) lo = m else hi = m
        }
        val span = (rel[hi] - rel[lo]).coerceAtLeast(1)
        val f = (elapsedMs - rel[lo]).toDouble() / span
        val a = pts[lo]
        val b = pts[hi]
        return TrackPoint(a.lat + (b.lat - a.lat) * f, a.lon + (b.lon - a.lon) * f)
    }

    /** Tempo impiegato dal fantasma per arrivare a [distance] metri. */
    fun timeAtDistance(distance: Double): Long {
        if (distance <= 0) return 0
        if (distance >= cum.last()) return durationMs
        var lo = 0
        var hi = cum.size - 1
        while (hi - lo > 1) {
            val m = (lo + hi) / 2
            if (cum[m] < distance) lo = m else hi = m
        }
        val span = (cum[hi] - cum[lo]).takeIf { it > 0 } ?: 1.0
        return rel[lo] + ((distance - cum[lo]) / span * (rel[hi] - rel[lo])).toLong()
    }
}

/** Indicazioni durante l'uscita: posizione del fantasma, distacco e messaggio sulle salite. */
data class CoachState(
    val ghostLat: Double? = null,
    val ghostLon: Double? = null,
    val ghostDiffSec: Int? = null,
    val climbText: String? = null
)
