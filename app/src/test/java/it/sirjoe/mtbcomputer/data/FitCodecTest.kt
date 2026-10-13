package it.sirjoe.mtbcomputer.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class FitCodecTest {

    private fun sample(withTimes: Boolean): Track {
        val t0 = 1_760_000_000_000L
        val pts = (0 until 60).map { i ->
            TrackPoint(
                lat = 45.5 + i * 0.0003,
                lon = 9.2 + i * 0.0002,
                ele = 300.0 + i,
                time = if (withTimes) t0 + i * 5000L else null
            )
        }
        return Track("Giro di prova MTB", pts)
    }

    @Test
    fun roundTripCourse() {
        for (withTimes in listOf(true, false)) {
            val track = sample(withTimes)
            val bytes = FitCodec.encodeCourse(track)
            assertTrue(FitCodec.isFit(bytes))
            val back = FitCodec.decode(bytes, "x")
            assertEquals(track.points.size, back.points.size)
            assertEquals("Giro di prova M", back.name)
            for (i in track.points.indices) {
                assertEquals(track.points[i].lat, back.points[i].lat, 1e-6)
                assertEquals(track.points[i].lon, back.points[i].lon, 1e-6)
                assertEquals(track.points[i].ele!!, back.points[i].ele!!, 0.3)
                assertTrue(back.points[i].time != null)
            }
            if (withTimes) assertEquals(track.points.last().time, back.points.last().time)
            // file per la verifica esterna con fitparse (vedi workflow)
            val out = File("build/test-fit").apply { mkdirs() }
            File(out, "course-${if (withTimes) "tempi" else "senza-tempi"}.fit").writeBytes(bytes)
        }
    }
}
