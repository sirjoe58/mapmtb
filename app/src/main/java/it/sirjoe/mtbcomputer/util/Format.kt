package it.sirjoe.mtbcomputer.util

import it.sirjoe.mtbcomputer.data.TrackPoint
import java.util.Locale
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

object Format {
    fun speedKmh(ms: Double): String = String.format(Locale.ITALY, "%.1f", ms * 3.6)

    fun distanceKm(m: Double): String = String.format(Locale.ITALY, "%.2f", m / 1000.0)

    fun duration(ms: Long): String {
        val s = ms / 1000
        val h = s / 3600
        val m = (s % 3600) / 60
        val sec = s % 60
        return String.format(Locale.ITALY, "%d:%02d:%02d", h, m, sec)
    }

    fun meters(m: Double?): String =
        if (m == null) "--" else String.format(Locale.ITALY, "%.0f", m)

    fun percent(p: Double?): String =
        if (p == null) "--" else String.format(Locale.ITALY, "%.0f%%", p)

    fun cardinal(bearing: Float): String {
        val names = arrayOf("N", "NE", "E", "SE", "S", "SO", "O", "NO")
        val idx = (((bearing % 360) + 360) % 360 / 45f + 0.5f).toInt() % 8
        return names[idx]
    }
}

object Geo {
    private const val R = 6371000.0

    fun distance(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2) * sin(dLat / 2) +
            cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) *
            sin(dLon / 2) * sin(dLon / 2)
        return 2 * R * atan2(sqrt(a), sqrt(1 - a))
    }

    fun distance(a: TrackPoint, b: TrackPoint): Double = distance(a.lat, a.lon, b.lat, b.lon)

    /** Distanze cumulative dal primo punto, in metri. */
    fun cumulative(points: List<TrackPoint>): DoubleArray {
        val out = DoubleArray(points.size)
        for (i in 1 until points.size) {
            out[i] = out[i - 1] + distance(points[i - 1], points[i])
        }
        return out
    }
}
