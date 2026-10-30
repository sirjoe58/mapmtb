package it.sirjoe.training.data

/**
 * Allenatore: segue il programma secondo per secondo, confronta battito e cadenza
 * con l'obiettivo della fase e decide quali avvisi dare (voce, bip, vibrazione, colore).
 * Nessuna dipendenza da Android (testabile): il servizio chiama [tick] una volta al secondo.
 */
class Coach(
    val workout: Workout?,
    private val maxHr: Int,
    /** in uscita libera: avvisa se si supera questa zona (0 = mai) */
    private val freeMaxZone: Int = 0
) {

    enum class Kind { INFO, UP, DOWN, WARN, STEP, DONE }

    data class Cue(val kind: Kind, val text: String)

    enum class Status { OK, LOW, HIGH, NONE }

    // ---------------------------------------------------------------- stato del programma
    var stepIndex = 0
        private set
    var stepElapsed = 0
        private set
    var finished = workout == null
        private set

    val step: Step? get() = workout?.steps?.getOrNull(stepIndex)
    val nextStep: Step? get() = workout?.steps?.getOrNull(stepIndex + 1)
    val stepRemaining: Int get() = step?.let { (it.seconds - stepElapsed).coerceAtLeast(0) } ?: 0
    val totalRemaining: Int
        get() = workout?.let { w -> stepRemaining + w.steps.drop(stepIndex + 1).sumOf { it.seconds } } ?: 0

    var hrStatus = Status.NONE
        private set
    var cadStatus = Status.NONE
        private set

    // ---------------------------------------------------------------- contatori avvisi
    private var hrHigh = 0
    private var hrLow = 0
    private var cadHigh = 0
    private var cadLow = 0
    private var danger = 0
    private var lastHrCue = -1_000
    private var lastCadCue = -1_000
    private var lastDangerCue = -1_000
    private var hrCorrected = false
    private var cadCorrected = false
    private var clock = 0

    /** Avviso iniziale da dare alla partenza. */
    fun startCue(): Cue? {
        val s = step ?: return null
        return Cue(Kind.STEP, "Si parte: ${s.label}, ${minutes(s.seconds)}. Obiettivo ${s.target(maxHr)}." + tipText(s))
    }

    /** Passa alla fase successiva (pulsante "Salta fase"). */
    fun skip(): Cue? {
        if (finished || workout == null) return null
        return advance()
    }

    /**
     * Un secondo di allenamento. [hr] e [cad] sono null se il sensore non manda dati;
     * cadenza 0 = non stai pedalando (discesa), non conta come "troppo bassa".
     */
    fun tick(hr: Int?, cad: Int?, paused: Boolean = false): List<Cue> {
        val out = ArrayList<Cue>()
        if (paused) return out
        clock++

        // ---- sicurezza: sempre attiva, anche senza programma
        if (hr != null && hr >= Zones.dangerBpm(maxHr)) danger++ else danger = 0
        if (danger >= 5 && clock - lastDangerCue >= 30) {
            lastDangerCue = clock
            out += Cue(Kind.WARN, "Attenzione, battito molto alto: $hr. Rallenta e respira.")
        }

        val s = step
        if (s == null || finished) {
            freeRide(hr, out)
            return out
        }

        // ---- avanzamento fase
        stepElapsed++
        if (stepElapsed == s.seconds - 30 && s.seconds >= 90) {
            nextStep?.let { n -> out += Cue(Kind.INFO, "Tra 30 secondi: ${n.label}, ${n.target(maxHr)}.") }
        }
        if (stepElapsed >= s.seconds) {
            advance()?.let { out += it }
            return out
        }

        // ---- battito (il cuore reagisce in ritardo: primi 40 secondi di ogni fase senza correzioni)
        if (s.hasHr && hr != null) {
            val lo = Zones.bpmLo(s.zoneLo, maxHr)
            val hi = Zones.bpmHi(s.zoneHi, maxHr)
            hrStatus = when {
                hr > hi -> Status.HIGH
                hr < lo -> Status.LOW
                else -> Status.OK
            }
            if (hrStatus == Status.HIGH) hrHigh++ else hrHigh = 0
            if (hrStatus == Status.LOW) hrLow++ else hrLow = 0
            val z = Zones.zoneOf(hr, maxHr)
            if (stepElapsed > 40 && clock - lastHrCue >= 40) {
                if (hrHigh >= 15) {
                    lastHrCue = clock; hrCorrected = true
                    out += Cue(Kind.DOWN, "Rallenta: sei in zona $z, devi stare in ${zonesText(s)}.")
                } else if (hrLow >= 20) {
                    lastHrCue = clock; hrCorrected = true
                    out += Cue(Kind.UP, "Spingi un po' di più: sei in zona $z, l'obiettivo è ${zonesText(s)}.")
                }
            }
            if (hrStatus == Status.OK && hrCorrected) {
                hrCorrected = false
                out += Cue(Kind.INFO, "Bene, battito nella zona giusta.")
            }
        } else {
            hrStatus = Status.NONE
        }

        // ---- cadenza
        if (s.hasCad && cad != null && cad > 0) {
            cadStatus = when {
                cad > s.cadHi + 3 -> Status.HIGH
                cad < s.cadLo - 3 -> Status.LOW
                else -> Status.OK
            }
            if (cadStatus == Status.HIGH) cadHigh++ else cadHigh = 0
            if (cadStatus == Status.LOW) cadLow++ else cadLow = 0
            if (stepElapsed > 10 && clock - lastCadCue >= 45 && clock - lastHrCue >= 8) {
                if (cadLow >= 12) {
                    lastCadCue = clock; cadCorrected = true
                    out += Cue(Kind.UP, "Cadenza $cad: pedala più agile, scala a un rapporto più leggero. Obiettivo ${s.cadLo}–${s.cadHi}.")
                } else if (cadHigh >= 12) {
                    lastCadCue = clock; cadCorrected = true
                    out += Cue(Kind.DOWN, "Cadenza $cad, troppo alta: metti un rapporto più duro. Obiettivo ${s.cadLo}–${s.cadHi}.")
                }
            }
            if (cadStatus == Status.OK && cadCorrected) {
                cadCorrected = false
                out += Cue(Kind.INFO, "Cadenza giusta.")
            }
        } else {
            cadStatus = if (s.hasCad) cadStatus else Status.NONE
            cadHigh = 0; cadLow = 0
        }
        return out
    }

    private fun freeRide(hr: Int?, out: MutableList<Cue>) {
        hrStatus = Status.NONE
        cadStatus = Status.NONE
        if (freeMaxZone !in 1..4 || hr == null) return
        val limit = Zones.bpmHi(freeMaxZone, maxHr)
        if (hr > limit) hrHigh++ else hrHigh = 0
        if (hrHigh >= 15 && clock - lastHrCue >= 60) {
            lastHrCue = clock
            out += Cue(Kind.DOWN, "Sei in zona ${Zones.zoneOf(hr, maxHr)}: rallenta, il limite che hai scelto è la zona $freeMaxZone.")
        }
    }

    private fun advance(): Cue? {
        val w = workout ?: return null
        stepIndex++
        stepElapsed = 0
        hrHigh = 0; hrLow = 0; cadHigh = 0; cadLow = 0
        hrCorrected = false; cadCorrected = false
        hrStatus = Status.NONE; cadStatus = Status.NONE
        if (stepIndex >= w.steps.size) {
            finished = true
            stepIndex = w.steps.size
            return Cue(Kind.DONE, "Allenamento completato, bravo! Ora pedala tranquillo in zona 1 per qualche minuto.")
        }
        val s = w.steps[stepIndex]
        return Cue(Kind.STEP, "${s.label}, ${minutes(s.seconds)}. Obiettivo ${s.target(maxHr)}." + tipText(s))
    }

    private fun zonesText(s: Step) = if (s.zoneLo == s.zoneHi) "zona ${s.zoneLo}" else "zona ${s.zoneLo} o ${s.zoneHi}"

    private fun tipText(s: Step) = if (s.tip.isBlank()) "" else " " + s.tip.trim().let { if (it.endsWith(".")) it else "$it." }

    companion object {
        /** "10 minuti", "1 minuto e 30", "45 secondi" (per la voce). */
        fun minutes(sec: Int): String {
            val m = sec / 60
            val s = sec % 60
            return when {
                m == 0 -> "$s secondi"
                s == 0 -> if (m == 1) "1 minuto" else "$m minuti"
                else -> (if (m == 1) "1 minuto" else "$m minuti") + " e $s"
            }
        }

        /** "12:05" o "1:02:05" */
        fun clock(sec: Int): String {
            val h = sec / 3600
            val m = (sec % 3600) / 60
            val s = sec % 60
            return if (h > 0) String.format(java.util.Locale.ITALY, "%d:%02d:%02d", h, m, s)
            else String.format(java.util.Locale.ITALY, "%d:%02d", m, s)
        }
    }
}
