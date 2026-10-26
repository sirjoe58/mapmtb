package it.sirjoe.mtbcomputer.data

import kotlin.math.abs

/**
 * Riconoscimento di una probabile caduta con il telefono sul manubrio.
 *
 * 1. Urto: l'accelerazione supera la soglia scelta (in g) mentre stavi pedalando
 *    (velocità sopra ~4 km/h negli ultimi 15 secondi).
 * 2. Immobilità: nei [stillMs] successivi la velocità GPS resta quasi zero e il telefono
 *    è quasi fermo (se rialzi la bici e riparti, l'allarme non parte).
 * 3. Allora [onAccel]/[onSpeed] restituiscono true una volta sola: parte il conto alla rovescia.
 *
 * Nessuna dipendenza da Android: i tempi sono passati da fuori (testabile).
 */
class FallDetector(
    thresholdG: Double = Sensitivity.MEDIUM.g,
    private val stillMs: Long = 25_000L
) {
    enum class Sensitivity(val id: String, val label: String, val g: Double) {
        HIGH("alta", "Alta (scatta anche con urti medi)", 4.5),
        MEDIUM("media", "Media (consigliata)", 6.0),
        LOW("bassa", "Bassa (solo urti molto forti)", 7.5);

        companion object {
            fun of(id: String?) = values().firstOrNull { it.id == id } ?: MEDIUM
        }
    }

    private val threshold = thresholdG * G
    private var lastMovingAt = Long.MIN_VALUE / 2
    private var impactAt: Long? = null
    private var shakes = 0
    private var fired = false

    /** Momento dell'urto in attesa di conferma (null se nessuno). */
    val pendingImpactAt: Long? get() = impactAt

    /** Nuovo campione dell'accelerometro (modulo in m/s², gravità compresa). */
    fun onAccel(magnitude: Double, now: Long): Boolean {
        val imp = impactAt
        if (imp == null) {
            if (magnitude >= threshold && now - lastMovingAt <= 15_000L) {
                impactAt = now
                shakes = 0
                fired = false
            }
            return false
        }
        // dopo i primi secondi, molti scossoni = qualcuno sta maneggiando la bici
        if (now - imp > 5_000L && abs(magnitude - G) > 3.0) shakes++
        if (shakes > 40) {
            reset()
            return false
        }
        return check(now)
    }

    /** Nuova velocità GPS in m/s. */
    fun onSpeed(speedMs: Double, now: Long): Boolean {
        if (speedMs >= 1.2) lastMovingAt = now
        val imp = impactAt ?: return false
        // ripartito: era solo una buca o un salto
        if (now - imp > 4_000L && speedMs >= 2.5) {
            reset()
            return false
        }
        return check(now)
    }

    /** Da chiamare anche ogni secondo: se il GPS tace, conta come fermo. */
    fun onTick(now: Long): Boolean = check(now)

    private fun check(now: Long): Boolean {
        val imp = impactAt ?: return false
        if (!fired && now - imp >= stillMs) {
            fired = true
            impactAt = null
            return true
        }
        return false
    }

    fun reset() {
        impactAt = null
        shakes = 0
    }

    companion object {
        const val G = 9.80665
    }
}
