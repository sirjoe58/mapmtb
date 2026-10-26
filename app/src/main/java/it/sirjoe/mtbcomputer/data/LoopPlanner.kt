package it.sirjoe.mtbcomputer.data

import it.sirjoe.mtbcomputer.util.Geo
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/**
 * Giro ad anello automatico: dalla partenza sceglie due punti di passaggio a forma di
 * triangolo in una direzione, chiede il percorso a BRouter (partenza → A → B → partenza)
 * e corregge la dimensione finché la lunghezza si avvicina a quella voluta.
 * Prova più direzioni e propone gli anelli migliori.
 *
 * La parte geometrica è qui, senza Android (testabile); il calcolo dei percorsi
 * viene passato da fuori.
 */
object LoopPlanner {

    data class Request(
        val start: TrackPoint,
        val lengthM: Double,
        /** Dislivello voluto in metri, null = indifferente. */
        val ascentM: Double? = null,
        /** Direzione di partenza preferita (gradi), null = prova attorno. */
        val bearing: Double? = null,
        val candidates: Int = 3
    )

    data class Loop(
        val bearing: Double,
        val points: List<TrackPoint>,
        val lengthM: Double,
        val ascentM: Double?,
        val via: List<TrackPoint>
    ) {
        fun score(req: Request): Double {
            val dErr = abs(lengthM - req.lengthM) / req.lengthM
            val aErr = if (req.ascentM != null && ascentM != null && req.ascentM > 0)
                abs(ascentM - req.ascentM) / maxOf(req.ascentM, 200.0) else 0.0
            return dErr + aErr * 0.7
        }
    }

    /** Fattore medio tra la lunghezza del triangolo in linea d'aria e quella sui sentieri. */
    const val DETOUR = 1.35

    /** Punto a [distM] metri in direzione [bearingDeg] da [p]. */
    fun destination(p: TrackPoint, bearingDeg: Double, distM: Double): TrackPoint {
        val r = 6371000.0
        val d = distM / r
        val b = Math.toRadians(bearingDeg)
        val la1 = Math.toRadians(p.lat)
        val lo1 = Math.toRadians(p.lon)
        val la2 = asin(sin(la1) * cos(d) + cos(la1) * sin(d) * cos(b))
        val lo2 = lo1 + atan2(sin(b) * sin(d) * cos(la1), cos(d) - sin(la1) * sin(la2))
        return TrackPoint(Math.toDegrees(la2), Math.toDegrees(lo2))
    }

    /**
     * Punti di passaggio di un triangolo equilatero con un vertice nella partenza:
     * gli altri due vertici a distanza [side] a ±30° attorno a [bearing].
     * Il perimetro in linea d'aria è 3 × side.
     */
    fun triangle(start: TrackPoint, bearing: Double, side: Double): List<TrackPoint> = listOf(
        start,
        destination(start, bearing - 30.0, side),
        destination(start, bearing + 30.0, side),
        start
    )

    /** Lato iniziale per ottenere circa [lengthM] metri sui sentieri. */
    fun initialSide(lengthM: Double) = lengthM / (3.0 * DETOUR)

    /** Direzioni da provare. */
    fun bearings(req: Request): List<Double> {
        val n = req.candidates.coerceIn(1, 6)
        val base = req.bearing ?: 0.0
        return if (req.bearing != null) {
            // attorno alla direzione chiesta
            listOf(0.0, -40.0, 40.0, -80.0, 80.0, 180.0).take(n).map { ((base + it) % 360 + 360) % 360 }
        } else {
            (0 until n).map { (base + it * 360.0 / n) % 360 }
        }
    }

    fun ascentOf(points: List<TrackPoint>): Double? = RouteSummary.of(points).ascentM

    /**
     * Calcola gli anelli. [route] restituisce il percorso che passa per i punti dati
     * (una sola richiesta al server). Al massimo due tentativi per direzione.
     */
    suspend fun plan(
        req: Request,
        route: suspend (List<TrackPoint>) -> List<TrackPoint>,
        onProgress: (Int, Int) -> Unit = { _, _ -> }
    ): List<Loop> {
        val dirs = bearings(req)
        val out = ArrayList<Loop>()
        var step = 0
        val total = dirs.size * 2
        var lastError: Exception? = null
        for (b in dirs) {
            var side = initialSide(req.lengthM)
            var best: Loop? = null
            for (attempt in 0 until 2) {
                step++
                onProgress(step, total)
                val via = triangle(req.start, b, side)
                val pts = try {
                    route(via)
                } catch (e: Exception) {
                    lastError = e
                    break
                }
                if (pts.size < 2) break
                val len = Geo.cumulative(pts).last()
                val loop = Loop(b, pts, len, ascentOf(pts), via)
                if (best == null || loop.score(req) < best.score(req)) best = loop
                val err = abs(len - req.lengthM) / req.lengthM
                if (err < 0.12) break
                // correzione proporzionale della dimensione del triangolo
                side *= (req.lengthM / len).coerceIn(0.5, 1.8)
            }
            best?.let { out.add(it) }
        }
        if (out.isEmpty()) throw IllegalStateException(lastError?.message ?: "nessun anello trovato")
        return out.sortedBy { it.score(req) }
    }
}
