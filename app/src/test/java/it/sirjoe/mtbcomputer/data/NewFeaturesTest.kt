package it.sirjoe.mtbcomputer.data

import it.sirjoe.mtbcomputer.util.Geo
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneOffset

class NewFeaturesTest {

    private fun utc(s: String) = LocalDateTime.parse(s).toInstant(ZoneOffset.UTC).toEpochMilli()

    // ------------------------------------------------------------ tramonto

    @Test
    fun sunsetRome() {
        // Roma, 26/10/2026: tramonto verso le 16:13 UTC (17:13 ora italiana)
        val s = SunCalc.sunset(utc("2026-10-26T10:00:00"), 41.9, 12.5)!!
        val expected = utc("2026-10-26T16:13:00")
        assertTrue("scarto ${(s - expected) / 60000} min", kotlin.math.abs(s - expected) < 5 * 60_000)
        // 21 giugno: verso le 18:48 UTC (20:48 ora legale)
        val j = SunCalc.sunset(utc("2026-06-21T08:00:00"), 41.9, 12.5)!!
        assertTrue(kotlin.math.abs(j - utc("2026-06-21T18:48:00")) < 5 * 60_000)
        // alba prima del tramonto
        assertTrue(SunCalc.sunrise(utc("2026-10-26T10:00:00"), 41.9, 12.5)!! < s)
    }

    @Test
    fun sunsetSameDayAcrossTimes() {
        val a = SunCalc.sunset(utc("2026-10-26T00:30:00"), 46.0, 11.0)!!
        val b = SunCalc.sunset(utc("2026-10-26T20:30:00"), 46.0, 11.0)!!
        assertTrue(kotlin.math.abs(a - b) < 60_000)
    }

    @Test
    fun polarNightHasNoSunset() {
        assertNull(SunCalc.sunset(utc("2026-12-21T12:00:00"), 80.0, 15.0))
    }

    // ------------------------------------------------------------ caduta

    private fun riding(d: FallDetector, until: Long) {
        var t = 0L
        while (t < until) {
            d.onSpeed(5.0, t); d.onAccel(9.8, t); t += 1000
        }
    }

    @Test
    fun fallDetectedAfterImpactAndStillness() {
        val d = FallDetector(6.0, stillMs = 25_000)
        riding(d, 30_000)
        assertFalse(d.onAccel(70.0, 30_000))
        var fired = false
        var t = 31_000L
        while (t < 60_000) {
            if (d.onSpeed(0.2, t) || d.onAccel(9.8, t) || d.onTick(t)) fired = true
            t += 1000
        }
        assertTrue(fired)
    }

    @Test
    fun noAlarmIfRiderContinues() {
        val d = FallDetector(6.0)
        riding(d, 30_000)
        d.onAccel(70.0, 30_000)
        var fired = false
        var t = 31_000L
        while (t < 70_000) {
            val v = if (t > 36_000) 5.0 else 0.5
            if (d.onSpeed(v, t) || d.onTick(t)) fired = true
            t += 1000
        }
        assertFalse(fired)
    }

    @Test
    fun noAlarmWhenNotRiding() {
        val d = FallDetector(6.0)
        // fermo da tempo: il telefono cade dal tavolo
        assertFalse(d.onAccel(80.0, 100_000))
        assertNull(d.pendingImpactAt)
    }

    @Test
    fun softBumpsIgnored() {
        val d = FallDetector(FallDetector.Sensitivity.MEDIUM.g)
        riding(d, 10_000)
        d.onAccel(4.0 * FallDetector.G, 10_000)
        assertNull(d.pendingImpactAt)
    }

    // ------------------------------------------------------------ anello

    @Test
    fun triangleGeometry() {
        val start = TrackPoint(45.0, 10.0)
        val tri = LoopPlanner.triangle(start, 0.0, 3000.0)
        assertEquals(4, tri.size)
        val a = Geo.distance(start, tri[1])
        val ab = Geo.distance(tri[1], tri[2])
        assertEquals(3000.0, a, 5.0)
        assertEquals(3000.0, ab, 30.0) // equilatero
        assertTrue(tri[1].lat > start.lat && tri[2].lat > start.lat) // verso Nord
    }

    @Test
    fun loopPlannerConvergesOnLength() = runBlocking {
        // "server" finto: il percorso è il triangolo stesso con il 35% in più (giri dei sentieri)
        val fake: suspend (List<TrackPoint>) -> List<TrackPoint> = { via ->
            val pts = ArrayList<TrackPoint>()
            for (i in 0 until via.size - 1) {
                for (k in 0..20) {
                    val f = k / 20.0
                    pts.add(TrackPoint(via[i].lat + (via[i + 1].lat - via[i].lat) * f, via[i].lon + (via[i + 1].lon - via[i].lon) * f, 300.0 + k))
                }
            }
            // allunga in modo artificiale: ripete un tratto
            pts
        }
        val req = LoopPlanner.Request(TrackPoint(45.0, 10.0), 30_000.0, candidates = 3)
        val loops = LoopPlanner.plan(req, fake)
        assertEquals(3, loops.size)
        for (l in loops) {
            assertTrue("lunghezza ${l.lengthM}", kotlin.math.abs(l.lengthM - 30_000) / 30_000 < 0.15)
            assertEquals(l.points.first().lat, l.points.last().lat, 1e-9)
        }
    }

    // ------------------------------------------------------------ sensori

    @Test
    fun heartRateParsing() {
        assertEquals(72, BleParse.heartRate(byteArrayOf(0x00, 72)))
        assertNull(BleParse.heartRate(byteArrayOf(0x01, 0x2C, 0x01))) // 300 bpm: fuori scala
        assertEquals(150, BleParse.heartRate(byteArrayOf(0x01, 150.toByte(), 0x00)))
        assertNull(BleParse.heartRate(byteArrayOf(0x00)))
    }

    @Test
    fun cadenceFromCsc() {
        // solo dati pedivella: flags 0x02, giri 100, tempo 1024 (=1 s)
        val a = BleParse.cscCrank(byteArrayOf(0x02, 100, 0, 0x00, 0x04))!!
        // 1,5 giri non esistono: 1 giro in 0,666 s = 90 rpm -> tempo +683
        val b = BleParse.cscCrank(byteArrayOf(0x02, 101, 0, (1024 + 683 and 0xFF).toByte(), ((1024 + 683) shr 8).toByte()))!!
        assertEquals(89, BleParse.cadence(a, b))
        // con anche i dati ruota (flags 0x03): la pedivella viene dopo 6 byte
        val c = BleParse.cscCrank(byteArrayOf(0x03, 1, 0, 0, 0, 10, 0, 50, 0, 0x00, 0x08))!!
        assertEquals(50, c.revs)
        assertEquals(2048, c.eventTime)
        // contatore che riparte da zero
        val d1 = BleParse.Crank(65535, 65000)
        val d2 = BleParse.Crank(0, (65000 + 683) and 0xFFFF)
        assertEquals(89, BleParse.cadence(d1, d2))
    }

    @Test
    fun powerParsing() {
        // flags 0x0000, potenza 250 W
        assertEquals(250, BleParse.power(byteArrayOf(0, 0, 250.toByte(), 0)))
        // flags 0x0020 (crank presente): giri 10, tempo 512
        val cr = BleParse.powerCrank(byteArrayOf(0x20, 0, 200.toByte(), 0, 10, 0, 0, 2))!!
        assertEquals(10, cr.revs)
        assertEquals(512, cr.eventTime)
    }

    @Test
    fun zones() {
        assertEquals(1, BleParse.zone(100, 180))
        assertEquals(3, BleParse.zone(135, 180))
        assertEquals(5, BleParse.zone(170, 180))
    }

    // ------------------------------------------------------------ punti utili

    private fun northRoute(): List<TrackPoint> = (0..100).map { TrackPoint(45.0 + it * 0.0009, 10.0) } // ~10 km

    @Test
    fun overpassQueryContainsTypes() {
        val q = Pois.query(northRoute(), setOf(PoiType.WATER, PoiType.HUT))
        assertTrue(q.contains("drinking_water"))
        assertTrue(q.contains("alpine_hut"))
        assertFalse(q.contains("viewpoint"))
        assertTrue(q.startsWith("[out:json]"))
    }

    @Test
    fun overpassParsingAndOrder() {
        val json = """
            {"elements":[
              {"type":"node","id":1,"lat":45.0450,"lon":10.0005,"tags":{"amenity":"drinking_water"}},
              {"type":"way","id":2,"center":{"lat":45.0100,"lon":10.0010},"tags":{"tourism":"alpine_hut","name":"Rifugio Alto"}},
              {"type":"node","id":3,"lat":45.0451,"lon":10.0005,"tags":{"amenity":"drinking_water"}},
              {"type":"node","id":4,"lat":45.0300,"lon":10.0500,"tags":{"amenity":"drinking_water"}},
              {"type":"node","id":5,"lat":45.0200,"lon":10.0001,"tags":{"amenity":"bench"}}
            ]}
        """.trimIndent()
        val route = northRoute()
        val list = Pois.parse(json, route, PoiType.values().toSet())
        assertEquals(2, list.size) // doppione unito, lontano e panchina scartati
        assertEquals(PoiType.HUT, list[0].type)
        assertEquals("Rifugio Alto", list[0].name)
        assertEquals(PoiType.WATER, list[1].type)
        assertTrue(list[1].along in 4900.0..5100.0)

        val next = Pois.nextAhead(list, 2000.0, 10_000.0, reverse = false)
        assertNotNull(next)
        assertEquals(PoiType.WATER, next!!.first.type)
        assertEquals(3000.0, next.second, 120.0)
        val back = Pois.nextAhead(list, 8000.0, 10_000.0, reverse = true)
        assertEquals(PoiType.WATER, back!!.first.type)
    }

    // ------------------------------------------------------------ protocollo SOS

    @Test
    fun sosProtocolRoundTrip() {
        val m = SosProtocol.Msg(SosProtocol.Type.SOS, 45.123456, 10.654321, 8f, 1234L, "caduta | forte")
        val line = SosProtocol.encode(m)
        assertTrue(line.endsWith("\n"))
        val d = SosProtocol.decode(line)!!
        assertEquals(SosProtocol.Type.SOS, d.type)
        assertEquals(45.123456, d.lat!!, 1e-6)
        assertEquals(10.654321, d.lon!!, 1e-6)
        assertEquals(1234L, d.time)
        assertEquals("caduta / forte", d.text)
        assertEquals(SosProtocol.Type.PING, SosProtocol.decode("PING")!!.type)
        assertNull(SosProtocol.decode("BOH|1"))
        val sms = SosProtocol.smsText("Sergio", "possibile caduta", 45.1, 10.2, 7f, "15:32")
        assertTrue(sms.contains("https://maps.google.com/?q=45.100000,10.200000"))
        assertTrue(sms.contains("112"))
    }
}
