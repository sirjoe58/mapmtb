package it.sirjoe.training.data

import kotlin.math.roundToInt

/**
 * Zone cardio calcolate sulla frequenza cardiaca massima (FC max).
 * Zona 1 = 50-60 %, 2 = 60-70 %, 3 = 70-80 %, 4 = 80-90 %, 5 = 90-100 %.
 * Nessuna dipendenza da Android (testabile).
 */
object Zones {

    data class Zone(
        val n: Int,
        val name: String,
        val loPct: Int,
        val hiPct: Int,
        val color: Int,
        val feel: String,
        val advice: String
    )

    val ALL = listOf(
        Zone(1, "Recupero", 50, 60, 0xFF78909C.toInt(),
            "Respiro tranquillo, parli senza fatica.",
            "Pedala sciolto con un rapporto leggero. Va bene per riscaldamento, defaticamento e giorni di recupero."),
        Zone(2, "Fondo lento", 60, 70, 0xFF43A047.toInt(),
            "Riesci a chiacchierare.",
            "È la zona della resistenza: puoi restarci a lungo. Tieni il ritmo regolare anche in salita, scala marcia prima di affannarti."),
        Zone(3, "Fondo medio", 70, 80, 0xFFFDD835.toInt(),
            "Parli a frasi brevi.",
            "Ritmo sostenuto ma controllato. Buona per salite regolari: non forzare sui tratti ripidi."),
        Zone(4, "Soglia", 80, 90, 0xFFFB8C00.toInt(),
            "Riesci a dire solo poche parole.",
            "Sforzo impegnativo: solo per tratti brevi e se ti senti bene. Al termine recupera in zona 1-2."),
        Zone(5, "Massimale", 90, 100, 0xFFE53935.toInt(),
            "Fiato corto, non riesci a parlare.",
            "Sforzo massimo: rallenta appena puoi e lascia scendere il battito.")
    )

    fun zone(n: Int): Zone = ALL[(n.coerceIn(1, 5)) - 1]

    /** Stima della FC max dall'età (formula di Tanaka: 208 - 0,7 × età). */
    fun estimateMax(age: Int): Int = (208 - 0.7 * age).roundToInt()

    /** Battiti di inizio e fine di una zona. */
    fun bpmLo(n: Int, maxHr: Int): Int = (maxHr * zone(n).loPct / 100.0).roundToInt()
    fun bpmHi(n: Int, maxHr: Int): Int = (maxHr * zone(n).hiPct / 100.0).roundToInt()

    /** Zona 1..5 del battito (sotto il 60 % è comunque zona 1). */
    fun zoneOf(bpm: Int, maxHr: Int): Int {
        if (maxHr <= 0) return 1
        val p = bpm * 100.0 / maxHr
        return when {
            p < 60 -> 1
            p < 70 -> 2
            p < 80 -> 3
            p < 90 -> 4
            else -> 5
        }
    }

    /** Percentuale della FC max. */
    fun pct(bpm: Int, maxHr: Int): Int = if (maxHr <= 0) 0 else (bpm * 100.0 / maxHr).roundToInt()

    /** "zona 2 (99–116 battiti)" oppure "zone 2-3 (99–132 battiti)". */
    fun describe(lo: Int, hi: Int, maxHr: Int): String {
        val a = bpmLo(lo, maxHr)
        val b = bpmHi(hi, maxHr)
        return if (lo == hi) "zona $lo ($a–$b battiti)" else "zone $lo-$hi ($a–$b battiti)"
    }

    /** Soglia di sicurezza: oltre il 95 % della FC max l'app avvisa sempre di rallentare. */
    fun dangerBpm(maxHr: Int): Int = (maxHr * 0.95).roundToInt()
}
