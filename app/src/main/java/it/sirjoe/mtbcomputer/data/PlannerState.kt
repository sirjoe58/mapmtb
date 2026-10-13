package it.sirjoe.mtbcomputer.data

import it.sirjoe.mtbcomputer.util.Geo
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Percorso in costruzione nella pagina "Crea".
 * Ogni tratto collega un punto di passaggio al successivo.
 * Resta in memoria anche cambiando pagina.
 */
object PlannerState {
    private val waypoints = ArrayList<TrackPoint>()
    private val segments = ArrayList<List<TrackPoint>>()
    var name: String? = null

    private val _version = MutableStateFlow(0)
    val version: StateFlow<Int> = _version.asStateFlow()

    private fun changed() {
        _version.value = _version.value + 1
    }

    @Synchronized
    fun waypoints(): List<TrackPoint> = ArrayList(waypoints)

    @Synchronized
    fun lastWaypoint(): TrackPoint? = waypoints.lastOrNull()

    @Synchronized
    fun firstWaypoint(): TrackPoint? = waypoints.firstOrNull()

    @Synchronized
    fun addFirst(p: TrackPoint) {
        waypoints.clear()
        segments.clear()
        waypoints.add(p)
        changed()
    }

    @Synchronized
    fun addSegment(to: TrackPoint, segment: List<TrackPoint>) {
        waypoints.add(to)
        segments.add(segment)
        changed()
    }

    @Synchronized
    fun undo() {
        if (waypoints.isEmpty()) return
        waypoints.removeAt(waypoints.lastIndex)
        if (segments.isNotEmpty()) segments.removeAt(segments.lastIndex)
        changed()
    }

    @Synchronized
    fun clear() {
        waypoints.clear()
        segments.clear()
        name = null
        changed()
    }

    @Synchronized
    fun reverse() {
        if (waypoints.size < 2) return
        waypoints.reverse()
        val rev = segments.map { it.reversed() }.reversed()
        segments.clear()
        segments.addAll(rev)
        changed()
    }

    /** Carica una traccia esistente per modificarla o allungarla. */
    @Synchronized
    fun loadFrom(track: Track) {
        waypoints.clear()
        segments.clear()
        if (track.points.isEmpty()) return
        val pts = track.points.map { it.copy(time = null) }
        waypoints.add(pts.first())
        if (pts.size > 1) {
            waypoints.add(pts.last())
            segments.add(pts)
        }
        name = track.name
        changed()
    }

    /** Tutti i punti del percorso, tratti uniti senza doppioni. */
    @Synchronized
    fun allPoints(): List<TrackPoint> {
        if (segments.isEmpty()) return ArrayList(waypoints)
        val out = ArrayList<TrackPoint>()
        for (s in segments) {
            if (out.isNotEmpty() && s.isNotEmpty() &&
                Geo.distance(out.last(), s.first()) < 1.0
            ) {
                out.addAll(s.drop(1))
            } else {
                out.addAll(s)
            }
        }
        return out
    }

    fun toTrack(name: String) = Track(name, allPoints())
}

/** Distanza e dislivello di una lista di punti. */
data class RouteSummary(val distanceM: Double, val ascentM: Double?, val descentM: Double?) {
    companion object {
        fun of(points: List<TrackPoint>): RouteSummary {
            val dist = Geo.cumulative(points).lastOrNull() ?: 0.0
            val ele = points.mapNotNull { it.ele }
            if (ele.size < 2) return RouteSummary(dist, null, null)
            var up = 0.0
            var down = 0.0
            var ref = ele.first()
            for (e in ele) {
                if (e - ref >= 3.0) {
                    up += e - ref; ref = e
                } else if (ref - e >= 3.0) {
                    down += ref - e; ref = e
                }
            }
            return RouteSummary(dist, up, down)
        }
    }
}
