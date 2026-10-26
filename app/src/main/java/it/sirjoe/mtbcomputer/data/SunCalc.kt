package it.sirjoe.mtbcomputer.data

import kotlin.math.acos
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.roundToLong
import kotlin.math.sin

/**
 * Ora del tramonto (e dell'alba) calcolata sul telefono, senza Internet
 * (equazione dell'alba dell'NOAA semplificata, precisione di circa un minuto).
 */
object SunCalc {

    /** Sole 0,833° sotto l'orizzonte: tiene conto della rifrazione e del disco solare. */
    private const val ZENITH = 90.833

    /**
     * Istante (millisecondi UTC) del tramonto del giorno che contiene [dayMs] nel luogo indicato,
     * oppure null dove quel giorno il sole non tramonta o non sorge (zone polari).
     */
    fun sunset(dayMs: Long, lat: Double, lon: Double): Long? = event(dayMs, lat, lon, rising = false)

    fun sunrise(dayMs: Long, lat: Double, lon: Double): Long? = event(dayMs, lat, lon, rising = true)

    private fun event(dayMs: Long, lat: Double, lon: Double, rising: Boolean): Long? {
        val jd = dayMs / 86_400_000.0 + 2440587.5
        // giorno contato da J2000 riferito al mezzogiorno solare del luogo
        val n = (jd + lon / 360.0 - 2451545.0).roundToLong().toDouble()
        val jStar = n - lon / 360.0
        val m = (357.5291 + 0.98560028 * jStar).mod(360.0)
        val mr = Math.toRadians(m)
        val c = 1.9148 * sin(mr) + 0.0200 * sin(2 * mr) + 0.0003 * sin(3 * mr)
        val lambda = Math.toRadians((m + c + 180.0 + 102.9372).mod(360.0))
        val jTransit = 2451545.0 + jStar + 0.0053 * sin(mr) - 0.0069 * sin(2 * lambda)
        val decl = asin(sin(lambda) * sin(Math.toRadians(23.4397)))
        val latR = Math.toRadians(lat)
        val cosH = (cos(Math.toRadians(ZENITH)) - sin(latR) * sin(decl)) / (cos(latR) * cos(decl))
        if (cosH < -1.0 || cosH > 1.0) return null
        val h = Math.toDegrees(acos(cosH))
        val jEvent = if (rising) jTransit - h / 360.0 else jTransit + h / 360.0
        return ((jEvent - 2440587.5) * 86_400_000.0).toLong()
    }
}
