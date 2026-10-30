package it.sirjoe.training.service

import android.os.Handler
import android.os.Looper
import it.sirjoe.training.data.Coach
import it.sirjoe.training.data.Step
import it.sirjoe.training.data.Workout

/** Fotografia dell'uscita in corso, aggiornata ogni secondo dal servizio e letta dalle pagine. */
data class Snapshot(
    val running: Boolean = false,
    val paused: Boolean = false,
    val seconds: Int = 0,
    val distanceM: Double = 0.0,
    val kmh: Float? = null,
    val avgKmh: Double = 0.0,
    val hr: Int? = null,
    val cad: Int? = null,
    val maxHr: Int = 160,
    val gpsOk: Boolean = false,
    val workout: Workout? = null,
    val stepIndex: Int = 0,
    val step: Step? = null,
    val nextStep: Step? = null,
    val stepRemaining: Int = 0,
    val totalRemaining: Int = 0,
    val finished: Boolean = false,
    val hrStatus: Coach.Status = Coach.Status.NONE,
    val cadStatus: Coach.Status = Coach.Status.NONE,
    val lastCue: Coach.Cue? = null,
    val lastCueAt: Long = 0L,
    val zoneSeconds: IntArray = IntArray(5)
)

object RideState {
    private val main = Handler(Looper.getMainLooper())
    private val listeners = ArrayList<(Snapshot) -> Unit>()

    @Volatile var now = Snapshot()
        private set

    fun publish(s: Snapshot) {
        now = s
        main.post { for (l in listeners.toList()) l(s) }
    }

    fun listen(l: (Snapshot) -> Unit) {
        listeners += l
        l(now)
    }

    fun unlisten(l: (Snapshot) -> Unit) {
        listeners -= l
    }
}
