package it.sirjoe.training.data

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TrainingTest {

    // ------------------------------------------------------------ zone

    @Test
    fun zone_dalla_fc_max() {
        assertEquals(160, Zones.estimateMax(68))
        assertEquals(96, Zones.bpmLo(2, 160))
        assertEquals(112, Zones.bpmHi(2, 160))
        assertEquals(1, Zones.zoneOf(80, 160))
        assertEquals(2, Zones.zoneOf(100, 160))
        assertEquals(3, Zones.zoneOf(115, 160))
        assertEquals(4, Zones.zoneOf(135, 160))
        assertEquals(5, Zones.zoneOf(150, 160))
        assertEquals("zone 1-2 (80–112 battiti)", Zones.describe(1, 2, 160))
        assertEquals(152, Zones.dangerBpm(160))
    }

    // ------------------------------------------------------------ formato programmi (uguale sul PC)

    @Test
    fun programma_json_andata_e_ritorno() {
        val json = """{"id":"p1","name":"Prova","note":"n","steps":[
            {"label":"Riscaldamento","sec":600,"zLo":1,"zHi":2,"cLo":80,"cHi":90,"tip":"piano"},
            {"label":"Libero","sec":300}]}"""
        val w = WorkoutJson.from(JSONObject(json), fromPc = true)!!
        assertEquals(2, w.steps.size)
        assertEquals(900, w.totalSeconds)
        assertTrue(w.steps[0].hasHr)
        assertTrue(w.steps[0].hasCad)
        assertFalse(w.steps[1].hasHr)
        assertFalse(w.steps[1].hasCad)
        val back = WorkoutJson.from(WorkoutJson.to(w))!!
        assertEquals(w.steps, back.steps)
        assertEquals("ritmo libero", w.steps[1].target(160))
    }

    @Test
    fun programma_senza_fasi_scartato() {
        assertNull(WorkoutJson.from(JSONObject("""{"name":"vuoto","steps":[]}""")))
    }

    @Test
    fun programmi_pronti_validi() {
        for (w in BuiltinWorkouts.ALL) {
            assertTrue(w.name, w.steps.isNotEmpty())
            for (s in w.steps) assertTrue(w.name + " " + s.label, s.hasHr)
        }
    }

    // ------------------------------------------------------------ allenatore

    private fun prova() = Workout(
        "t", "Test", "", listOf(
            Step("A", 120, 2, 2, 80, 90),
            Step("B", 120, 3, 3)
        )
    )

    @Test
    fun allenatore_avanza_le_fasi_e_finisce() {
        val c = Coach(prova(), 160)
        assertNotNull(c.startCue())
        val cues = ArrayList<Coach.Cue>()
        repeat(240) { cues += c.tick(105, 85) }
        assertTrue(c.finished)
        assertTrue(cues.any { it.kind == Coach.Kind.STEP && it.text.startsWith("B") })
        assertTrue(cues.any { it.kind == Coach.Kind.DONE })
        // avviso 30 secondi prima del cambio fase
        assertTrue(cues.any { it.text.startsWith("Tra 30 secondi: B") })
    }

    @Test
    fun allenatore_dice_di_rallentare_e_poi_conferma() {
        val c = Coach(prova(), 160)
        val cues = ArrayList<Coach.Cue>()
        // battito in zona 4 (140) mentre la fase chiede zona 2
        repeat(70) { cues += c.tick(140, 85) }
        assertEquals(Coach.Status.HIGH, c.hrStatus)
        val down = cues.filter { it.kind == Coach.Kind.DOWN }
        assertEquals(1, down.size)
        assertTrue(down[0].text.contains("Rallenta"))
        // rientra in zona: un solo "bene"
        val after = ArrayList<Coach.Cue>()
        repeat(5) { after += c.tick(105, 85) }
        assertEquals(1, after.count { it.text.startsWith("Bene") })
    }

    @Test
    fun nessuna_correzione_nei_primi_40_secondi() {
        val c = Coach(prova(), 160)
        val cues = ArrayList<Coach.Cue>()
        repeat(40) { cues += c.tick(70, 85) }
        assertTrue(cues.none { it.kind == Coach.Kind.UP || it.kind == Coach.Kind.DOWN })
    }

    @Test
    fun cadenza_bassa_e_zero_in_discesa() {
        val c = Coach(prova(), 160)
        val low = ArrayList<Coach.Cue>()
        repeat(30) { low += c.tick(105, 60) }
        assertTrue(low.any { it.kind == Coach.Kind.UP && it.text.contains("agile") })
        val c2 = Coach(prova(), 160)
        val coast = ArrayList<Coach.Cue>()
        repeat(30) { coast += c2.tick(105, 0) }
        assertTrue(coast.none { it.text.contains("Cadenza") })
    }

    @Test
    fun sicurezza_battito_troppo_alto_anche_senza_programma() {
        val c = Coach(null, 160)
        val cues = ArrayList<Coach.Cue>()
        repeat(6) { cues += c.tick(155, 80) }
        assertEquals(1, cues.count { it.kind == Coach.Kind.WARN })
    }

    @Test
    fun pausa_ferma_il_tempo() {
        val c = Coach(prova(), 160)
        repeat(10) { c.tick(105, 85) }
        repeat(50) { c.tick(105, 85, paused = true) }
        assertEquals(10, c.stepElapsed)
    }

    @Test
    fun salta_fase() {
        val c = Coach(prova(), 160)
        val cue = c.skip()
        assertEquals(1, c.stepIndex)
        assertEquals(Coach.Kind.STEP, cue!!.kind)
    }

    @Test
    fun orologio() {
        assertEquals("0:45", Coach.clock(45))
        assertEquals("12:05", Coach.clock(725))
        assertEquals("1:02:05", Coach.clock(3725))
        assertEquals("1 minuto e 30", Coach.minutes(90))
        assertEquals("10 minuti", Coach.minutes(600))
    }

    // ------------------------------------------------------------ uscita

    @Test
    fun uscita_json_e_gpx() {
        val t0 = 1_761_800_000_000L
        val samples = (0 until 60).map { i ->
            Sample(t0 + i * 1000L, 43.55 + i * 1e-5, 10.31, 12.0, if (i < 30) 100 else 130, 85, 20f)
        } + Sample(t0 + 61_000L, null, null, null, 120, 0, null)
        val s = Session("Prova", "Test", 160, samples, 500.0, 61)
        val sum = s.summary()
        assertEquals(30, sum.zoneSeconds[1])  // 100 bpm = zona 2
        assertEquals(30, sum.zoneSeconds[3])  // 130 bpm = zona 4
        assertEquals(1, sum.zoneSeconds[2])   // 120 bpm = zona 3
        assertEquals(85, sum.avgCad)          // lo 0 (discesa) non conta
        assertEquals(130, sum.maxHrSeen)

        val back = Session.fromJson(JSONObject(s.toJson().toString()))
        assertEquals(samples.size, back.samples.size)
        assertNull(back.samples.last().lat)
        assertEquals(120, back.samples.last().hr)
        assertEquals("Test", back.workoutName)

        val gpx = s.toGpx()
        assertTrue(gpx.contains("<gpxtpx:hr>100</gpxtpx:hr>"))
        assertTrue(gpx.contains("<gpxtpx:cad>85</gpxtpx:cad>"))
        assertEquals(60, Regex("<trkpt ").findAll(gpx).count())
        assertTrue(s.hasGps)
    }

    // ------------------------------------------------------------ sensori Bluetooth

    @Test
    fun cadenza_da_bluetooth() {
        // flags 0x02 = solo dati pedivella: 10 giri, tempo 5120/1024 s
        val a = BleParse.cscCrank(byteArrayOf(0x02, 10, 0, 0, 0))!!
        val b = BleParse.cscCrank(byteArrayOf(0x02, 17, 0, 0x00, 0x14))!!  // +7 giri in 5 s
        assertEquals(84, BleParse.cadence(a, b))
        assertEquals(72, BleParse.heartRate(byteArrayOf(0, 72)))
    }
}
