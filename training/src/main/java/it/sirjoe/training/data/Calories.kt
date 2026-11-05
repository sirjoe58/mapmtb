package it.sirjoe.training.data

/**
 * Stima delle calorie consumate, secondo per secondo.
 * Con il battito: formula di Keytel et al. (2005), che usa battito, peso, età e sesso.
 * Senza fascia: dispendio medio del ciclismo in base alla velocità (MET, Compendium of Physical Activities).
 * È una stima (±20 % circa): serve per confrontare le uscite tra loro.
 * Nessuna dipendenza da Android (testabile).
 */
object Calories {

    /** kcal consumate in un secondo. [moving] = in movimento o pedalando (da fermi conta solo il battito). */
    fun perSecond(hr: Int?, kmh: Float?, moving: Boolean, weightKg: Int, age: Int, male: Boolean): Double {
        val w = weightKg.coerceIn(30, 250).toDouble()
        val a = (if (age in 10..100) age else 50).toDouble()
        if (hr != null && hr >= 80) {
            val kj = if (male) -55.0969 + 0.6309 * hr + 0.1988 * w + 0.2017 * a
            else -20.4022 + 0.4472 * hr - 0.1263 * w + 0.074 * a
            // kJ al minuto → kcal al secondo; mai sotto il consumo da seduti
            return maxOf(kj / 4.184, rest(w)) / 60.0
        }
        if (!moving) return rest(w) / 60.0
        val v = kmh ?: 15f
        val met = when {
            v < 16f -> 4.0
            v < 19f -> 6.8
            v < 22f -> 8.0
            v < 25f -> 10.0
            else -> 12.0
        }
        return met * 3.5 * w / 200.0 / 60.0
    }

    /** kcal al minuto da seduti (1,2 MET). */
    private fun rest(w: Double) = 1.2 * 3.5 * w / 200.0
}
