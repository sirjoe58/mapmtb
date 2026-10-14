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
 * Avanzamento lungo un percorso: cerca il punto più vicino soprattutto in avanti,
 * così nei tratti che si incrociano o tornano indietro non "salta" all'altro passaggio.
 */
class RouteProgress(val points: List<TrackPoint>) {
    val cum: DoubleArray = Geo.cumulative(points)
    private var idx = 0
    var offDistance = 0.0
        private set

    private fun dist(lat: Double, lon: Double, i: Int): Double {
        val p = points[i]
        val k = cos(Math.toRadians(lat))
        return hypot((p.lon - lon) * 111320 * k, (p.lat - lat) * 110540)
    }

    /** Distanza percorsa lungo il percorso, in metri. */
    fun update(lat: Double, lon: Double): Double {
        if (points.isEmpty()) return 0.0
        var best = idx
        var bestD = Double.MAX_VALUE
        val from = max(0, idx - 30)
        val to = minOf(points.size - 1, idx + 400)
        for (i in from..to) {
            val d = dist(lat, lon, i)
            if (d < bestD) {
                bestD = d; best = i
            }
        }
        // perso il filo (es. ripartito da un altro punto): ricerca su tutto il percorso
        if (bestD > 150) {
            for (i in points.indices) {
                val d = dist(lat, lon, i)
                if (d < bestD) {
                    bestD = d; best = i
                }
            }
        }
        idx = best
        offDistance = bestD
        return cum[best]
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
