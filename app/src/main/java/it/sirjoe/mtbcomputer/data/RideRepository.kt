package it.sirjoe.mtbcomputer.data

import android.location.Location
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Un punto di una traccia. */
data class TrackPoint(
    val lat: Double,
    val lon: Double,
    val ele: Double? = null,
    val time: Long? = null
)

/** Una traccia o un percorso, con nome. */
data class Track(val name: String, val points: List<TrackPoint>)

/** Statistiche dell'uscita in corso. */
data class RideStats(
    val recording: Boolean = false,
    val paused: Boolean = false,
    /** Sosta automatica: fermo da un po', schermo libero di spegnersi, riparte da solo. */
    val sleeping: Boolean = false,
    val startTime: Long = 0L,
    val elapsedMs: Long = 0L,
    val movingMs: Long = 0L,
    val distanceM: Double = 0.0,
    val maxSpeedMs: Double = 0.0,
    val currentSpeedMs: Double = 0.0,
    val altitudeM: Double? = null,
    val ascentM: Double = 0.0,
    val descentM: Double = 0.0,
    val slopePct: Double? = null
) {
    val avgSpeedMs: Double
        get() = if (movingMs > 0) distanceM / (movingMs / 1000.0) else 0.0
}

/**
 * Stato condiviso dell'app: posizione attuale, uscita in registrazione,
 * percorso caricato da seguire. Letto da tutte le pagine.
 */
object RideRepository {
    private val _stats = MutableStateFlow(RideStats())
    val stats: StateFlow<RideStats> = _stats.asStateFlow()

    private val _location = MutableStateFlow<Location?>(null)
    val location: StateFlow<Location?> = _location.asStateFlow()

    private val _route = MutableStateFlow<Track?>(null)
    val route: StateFlow<Track?> = _route.asStateFlow()

    private val points = ArrayList<TrackPoint>()
    private val _pointCount = MutableStateFlow(0)
    val pointCount: StateFlow<Int> = _pointCount.asStateFlow()

    fun updateStats(s: RideStats) {
        _stats.value = s
    }

    /** Ultima volta in movimento (oltre 7 km/h): serve a capire se si sta navigando. */
    @Volatile
    var lastMovingAt = 0L
        private set

    fun updateLocation(l: Location) {
        if (l.hasSpeed() && l.speed > 2f) lastMovingAt = System.currentTimeMillis()
        _location.value = l
    }

    /** Uscita in corso: registrazione, oppure percorso da seguire mentre ci si muove (ultimi 2 minuti). */
    fun rideActive(): Boolean = _stats.value.recording ||
        (_route.value != null && System.currentTimeMillis() - lastMovingAt < 120_000L)

    /** true se il percorso attivo va sfidato come "fantasma". */
    @Volatile
    var ghostMode = false
        private set

    private val _coach = MutableStateFlow<CoachState?>(null)
    val coach: StateFlow<CoachState?> = _coach.asStateFlow()

    fun setRoute(t: Track?, ghost: Boolean = false) {
        ghostMode = ghost && t != null
        _route.value = t
    }

    fun updateCoach(c: CoachState?) {
        _coach.value = c
    }

    @Synchronized
    fun addPoint(p: TrackPoint) {
        points.add(p)
        _pointCount.value = points.size
    }

    @Synchronized
    fun snapshotPoints(): List<TrackPoint> = ArrayList(points)

    @Synchronized
    fun clearPoints() {
        points.clear()
        _pointCount.value = 0
    }
}
