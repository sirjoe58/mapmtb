package it.sirjoe.mtbcomputer.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RouteAnalysisTest {

    /** Percorso verso Nord, un punto ogni ~10 m; [ele] dà la quota alla distanza d. */
    private fun route(lengthM: Int, ele: (Double) -> Double, secPerM: Double? = null): List<TrackPoint> {
        val step = 10.0
        val dLat = step / 111_195.0
        return (0..lengthM / 10).map { i ->
            val d = i * step
            TrackPoint(45.0 + i * dLat, 10.0, ele(d), secPerM?.let { (d * it * 1000).toLong() + 1_000_000L })
        }
    }

    @Test
    fun findsSingleClimb() {
        // piano 1 km, salita 1 km all'8%, piano 1 km
        val pts = route(3000, { d -> 200 + (d.coerceIn(1000.0, 2000.0) - 1000) * 0.08 })
        val c = RouteAnalysis.climbs(pts)
        assertEquals(1, c.size)
        val climb = c[0]
        assertTrue("inizio ${climb.start}", climb.start in 900.0..1100.0)
        assertTrue("fine ${climb.end}", climb.end in 1900.0..2150.0)
        assertTrue("dislivello ${climb.gain}", climb.gain in 70.0..82.0)
        assertTrue("media ${climb.avg}", climb.avg in 0.06..0.09)
    }

    @Test
    fun ignoresGentleOrShortRises() {
        // 3% costante: non è una salita da segnalare
        assertTrue(RouteAnalysis.climbs(route(3000, { d -> 100 + d * 0.03 })).isEmpty())
        // strappo di 150 m al 12%: troppo corto
        assertTrue(RouteAnalysis.climbs(route(2000, { d -> 100 + (d.coerceIn(500.0, 650.0) - 500) * 0.12 })).isEmpty())
    }

    @Test
    fun progressFollowsRoute() {
        val pts = route(2000, { 100.0 })
        val p = RouteProgress(pts)
        val d = p.update(pts[100].lat, pts[100].lon)
        assertEquals(1000.0, d, 15.0)
        assertTrue(p.offDistance < 1.0)
    }

    @Test
    fun ghostTiming() {
        // 2 km a 5 m/s (0,2 s al metro): 400 secondi
        val track = Track("prima", route(2000, { 100.0 }, secPerM = 0.2))
        assertTrue(Ghost.canUse(track))
        val g = Ghost(track)
        assertEquals(400_000L, g.durationMs)
        assertNear(200_000L, g.timeAtDistance(1000.0), 1_000L)
        val half = g.positionAt(200_000L)
        assertEquals(track.points[100].lat, half.lat, 1e-5)
        // un percorso disegnato (senza orari) non può fare da fantasma
        assertFalse(Ghost.canUse(Track("disegnato", route(2000, { 100.0 }))))
    }

    private fun assertNear(expected: Long, actual: Long, delta: Long) {
        assertTrue("atteso $expected, ottenuto $actual", kotlin.math.abs(expected - actual) <= delta)
    }
}
