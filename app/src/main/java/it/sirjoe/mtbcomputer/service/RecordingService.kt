package it.sirjoe.mtbcomputer.service

import android.annotation.SuppressLint
import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import it.sirjoe.mtbcomputer.MainActivity
import it.sirjoe.mtbcomputer.R
import it.sirjoe.mtbcomputer.data.Climb
import it.sirjoe.mtbcomputer.data.CoachState
import it.sirjoe.mtbcomputer.data.FallDetector
import it.sirjoe.mtbcomputer.data.Ghost
import it.sirjoe.mtbcomputer.data.Pois
import it.sirjoe.mtbcomputer.data.Reports
import it.sirjoe.mtbcomputer.data.SunCalc
import it.sirjoe.mtbcomputer.data.Prefs
import it.sirjoe.mtbcomputer.data.RouteAnalysis
import it.sirjoe.mtbcomputer.data.RouteProgress
import it.sirjoe.mtbcomputer.data.Track
import it.sirjoe.mtbcomputer.data.RideRepository
import it.sirjoe.mtbcomputer.data.RideStats
import it.sirjoe.mtbcomputer.data.TrackPoint
import it.sirjoe.mtbcomputer.data.TrackStorage
import it.sirjoe.mtbcomputer.util.Format
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Servizio in primo piano che registra l'uscita: continua a funzionare
 * anche con lo schermo spento, mostrando una notifica fissa.
 */
class RecordingService : Service() {

    companion object {
        const val ACTION_START = "it.sirjoe.mtbcomputer.START"
        const val ACTION_PAUSE = "it.sirjoe.mtbcomputer.PAUSE"
        const val ACTION_RESUME = "it.sirjoe.mtbcomputer.RESUME"
        const val ACTION_STOP = "it.sirjoe.mtbcomputer.STOP"
        const val CHANNEL_ID = "recording"
        private const val NOTIF_ID = 42

        /** Velocita' sotto cui scatta la pausa automatica (circa 3 km/h). */
        private const val AUTOPAUSE_MS = 0.8
        /** Velocità che fa uscire dalla sosta (circa 7 km/h). */
        private const val WAKE_SPEED_MS = 2.0
        /** Spostamento dal punto di sosta che fa uscire dalla sosta. */
        private const val WAKE_DISTANCE_M = 30f

        fun send(ctx: Context, action: String) {
            val i = Intent(ctx, RecordingService::class.java).setAction(action)
            if (action == ACTION_START) ContextCompat.startForegroundService(ctx, i) else ctx.startService(i)
        }
    }

    private lateinit var fused: FusedLocationProviderClient
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var ticker: Job? = null

    private var lastLoc: Location? = null
    private var altRef: Double? = null
    private var slopeMarkAlt: Double? = null
    private var slopeMarkDist = 0.0
    private var pausedAccumMs = 0L
    private var pauseStarted = 0L
    private var nextKm = 1

    // sosta automatica
    private var stillSince: Long? = null
    private var sleepAnchor: Location? = null
    private var wakeHits = 0

    // fantasma e avvisi salite
    private var coachRoute: Track? = null
    private var coachGhostMode = false
    private var progress: RouteProgress? = null
    private var climbs: List<Climb> = emptyList()
    private var climbsRev: List<Climb> = emptyList()
    private var routeTotal = 0.0
    private val announced = HashSet<Int>()
    private var ghost: Ghost? = null
    private var nextGhostKm = 1
    private var lastReverse = false
    private var coachJob: Job? = null

    private var tts: TextToSpeech? = null
    private var ttsReady = false

    // caduta e SOS
    private var fall: FallDetector? = null
    private var sensorManager: SensorManager? = null
    private val accelListener = object : SensorEventListener {
        override fun onSensorChanged(e: SensorEvent) {
            val v = e.values
            val mag = sqrt((v[0] * v[0] + v[1] * v[1] + v[2] * v[2]).toDouble())
            if (fall?.onAccel(mag, System.currentTimeMillis()) == true) onFall()
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
    }

    // tramonto e punti utili
    private var ticks = 0
    private var sunsetText: String? = null
    private var lastSunsetWarn = 0L
    private var sunsetHourWarned = false
    private var sunsetDoneWarned = false
    private var routeRemaining: Double? = null
    private var poiText: String? = null
    private val poiAnnounced = HashSet<String>()
    private var lastClimbText: String? = null
    private var lastGhost: Triple<Double?, Double?, Int?> = Triple(null, null, null)

    private val callback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            for (l in result.locations) onLocation(l)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        fused = LocationServices.getFusedLocationProviderClient(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> start()
            ACTION_PAUSE -> pause()
            ACTION_RESUME -> resume()
            ACTION_STOP -> stopRide()
        }
        return START_NOT_STICKY
    }

    private fun start() {
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
        } else {
            0
        }
        ServiceCompat.startForeground(this, NOTIF_ID, buildNotification(getString(R.string.notif_recording)), type)
        if (RideRepository.stats.value.recording) return

        RideRepository.clearPoints()
        lastLoc = null
        stillSince = null
        sleepAnchor = null
        altRef = null
        slopeMarkAlt = null
        slopeMarkDist = 0.0
        pausedAccumMs = 0L
        nextKm = 1
        RideRepository.updateStats(RideStats(recording = true, startTime = System.currentTimeMillis()))

        requestUpdates()
        coachJob = scope.launch {
            RideRepository.route.collect { setupCoach(it) }
        }
        ticker = scope.launch {
            while (isActive) {
                delay(1000)
                tick()
            }
        }

        if (Prefs.voice(this)) {
            tts = TextToSpeech(this) { status ->
                ttsReady = status == TextToSpeech.SUCCESS
                if (ttsReady) tts?.setLanguage(Locale.ITALIAN)
            }
        }
        speak("Registrazione avviata")

        // sensori Bluetooth (cardio, cadenza, potenza)
        if (BleSensors.anyConfigured(this)) BleSensors.start(this)
        // SOS: collegamento al telefono in tasca e riconoscimento cadute
        if (SosBike.configured(this)) {
            SosBike.start(this)
            fall = FallDetector(Prefs.sosSensitivity(this).g)
            sensorManager = getSystemService(SensorManager::class.java)
            sensorManager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)?.let {
                sensorManager?.registerListener(accelListener, it, SensorManager.SENSOR_DELAY_GAME)
            }
        }
        sunsetHourWarned = false
        sunsetDoneWarned = false
        lastSunsetWarn = 0L
        poiAnnounced.clear()
    }

    private fun onFall() {
        speak("Possibile caduta. Se stai bene premi annulla")
        SosBike.trigger(this, "possibile caduta")
    }

    @SuppressLint("MissingPermission")
    private fun requestUpdates(intervalMs: Long = 1000L) {
        fused.removeLocationUpdates(callback)
        val req = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, intervalMs)
            .setMinUpdateIntervalMillis(intervalMs)
            .build()
        try {
            fused.requestLocationUpdates(req, callback, Looper.getMainLooper())
        } catch (e: SecurityException) {
            Toast.makeText(this, R.string.need_location, Toast.LENGTH_LONG).show()
            stopRide()
        }
    }

    private fun tick() {
        val s = RideRepository.stats.value
        if (!s.recording) return
        val now = System.currentTimeMillis()
        val pausedNow = if (s.paused) now - pauseStarted else 0L
        RideRepository.updateStats(s.copy(elapsedMs = now - s.startTime - pausedAccumMs - pausedNow))
        if (fall?.onTick(now) == true) onFall()
        if (ticks++ % 30 == 0) checkSunset()
        // fermo da abbastanza tempo (anche se il GPS non manda più posizioni): sosta
        val since = stillSince
        if (!s.paused && !s.sleeping && Prefs.autoSleep(this) && since != null &&
            now - since >= Prefs.sleepDelaySec(this) * 1000L
        ) {
            enterSleep()
        }
    }

    // ------------------------------------------------------------ sosta

    private fun enterSleep() {
        val s = RideRepository.stats.value
        if (!s.recording || s.sleeping) return
        sleepAnchor = lastLoc ?: RideRepository.location.value
        wakeHits = 0
        RideRepository.updateStats(s.copy(sleeping = true, currentSpeedMs = 0.0))
        requestUpdates(3000L) // GPS più lento: risparmio batteria
        updateNotification(getString(R.string.notif_sleeping))
        speak("Sosta")
    }

    private fun wake(loc: Location) {
        val s = RideRepository.stats.value
        if (!s.sleeping) return
        stillSince = null
        // il tratto percorso dalla sosta a qui viene conteggiato
        lastLoc = sleepAnchor
        sleepAnchor = null
        RideRepository.updateStats(s.copy(sleeping = false))
        requestUpdates(1000L)
        updateNotification(getString(R.string.notif_recording))
        wakeScreen()
        speak("Si riparte")
        onLocation(loc)
    }

    /** Riaccende lo schermo per qualche secondo: l'app compare sopra la schermata di blocco. */
    @Suppress("DEPRECATION")
    private fun wakeScreen() {
        try {
            val pm = getSystemService(PowerManager::class.java) ?: return
            val wl = pm.newWakeLock(
                PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP or
                    PowerManager.ON_AFTER_RELEASE,
                "SirJoeMTB:ripresa"
            )
            wl.acquire(5_000L)
        } catch (_: Exception) {
        }
    }

    private fun onLocation(loc: Location) {
        RideRepository.updateLocation(loc)
        val s = RideRepository.stats.value
        if (!s.recording || s.paused) return

        if (s.sleeping) {
            // in sosta: si riparte con due posizioni di fila in movimento
            val anchor = sleepAnchor
            val good = !loc.hasAccuracy() || loc.accuracy <= 25f
            val fast = loc.hasSpeed() && loc.speed >= WAKE_SPEED_MS
            val far = anchor != null && good && anchor.distanceTo(loc) > WAKE_DISTANCE_M
            wakeHits = if (fast || far) wakeHits + 1 else 0
            if (wakeHits >= 2) wake(loc)
            return
        }
        if (loc.hasAccuracy() && loc.accuracy > 35f) return

        val prev = lastLoc
        val speed: Double = when {
            loc.hasSpeed() -> loc.speed.toDouble()
            prev != null -> {
                val dt = (loc.time - prev.time) / 1000.0
                if (dt > 0) prev.distanceTo(loc) / dt else 0.0
            }
            else -> 0.0
        }
        val alt: Double? = if (loc.hasAltitude()) loc.altitude else null

        var dist = s.distanceM
        var moving = s.movingMs
        var asc = s.ascentM
        var desc = s.descentM
        var slope = s.slopePct

        val isMoving = !Prefs.autoPause(this) || speed >= AUTOPAUSE_MS
        if (prev != null && isMoving) {
            val d = prev.distanceTo(loc).toDouble()
            val dt = loc.time - prev.time
            // scarta i "salti" anomali del GPS
            if (d < 200.0) {
                dist += d
                if (dt in 1..10_000) moving += dt
            }
        }

        if (alt != null) {
            val ref = altRef
            if (ref == null) {
                altRef = alt
            } else if (alt - ref >= 3.0) {
                asc += alt - ref
                altRef = alt
            } else if (ref - alt >= 3.0) {
                desc += ref - alt
                altRef = alt
            }
            val markAlt = slopeMarkAlt
            if (markAlt == null) {
                slopeMarkAlt = alt
                slopeMarkDist = dist
            } else if (dist - slopeMarkDist >= 30.0) {
                slope = (alt - markAlt) / (dist - slopeMarkDist) * 100.0
                slopeMarkAlt = alt
                slopeMarkDist = dist
            }
        }

        if (fall?.onSpeed(speed, System.currentTimeMillis()) == true) onFall()
        if (isMoving) RideRepository.addPoint(
            TrackPoint(
                loc.latitude, loc.longitude, alt, loc.time,
                BleSensors.currentHr(), BleSensors.currentCadence(), BleSensors.currentPower()
            )
        )
        lastLoc = loc
        stillSince = if (speed < AUTOPAUSE_MS) (stillSince ?: System.currentTimeMillis()) else null

        val maxSpeed = if (speed < 30.0) max(s.maxSpeedMs, speed) else s.maxSpeedMs
        RideRepository.updateStats(
            s.copy(
                distanceM = dist,
                movingMs = moving,
                currentSpeedMs = speed,
                maxSpeedMs = maxSpeed,
                altitudeM = alt ?: s.altitudeM,
                ascentM = asc,
                descentM = desc,
                slopePct = slope
            )
        )

        updateCoach(loc, RideRepository.stats.value)

        if (dist / 1000.0 >= nextKm) {
            speak("Chilometro $nextKm")
            nextKm++
        }
    }

    // ------------------------------------------------- fantasma e salite

    private fun setupCoach(route: Track?) {
        coachRoute = route
        coachGhostMode = RideRepository.ghostMode
        announced.clear()
        nextGhostKm = 1
        poiAnnounced.clear()
        poiText = null
        routeRemaining = null
        if (route == null || route.points.size < 2) {
            progress = null; climbs = emptyList(); climbsRev = emptyList(); ghost = null
            RideRepository.setPois(emptyList())
            publishCoach()
            return
        }
        // punti utili cercati in precedenza per questo percorso
        if (RideRepository.pois.value.isEmpty()) RideRepository.setPois(Pois.load(this, route.points))
        progress = RouteProgress(route.points)
        routeTotal = progress!!.total
        climbs = if (Prefs.climbAlerts(this)) RouteAnalysis.climbs(route.points) else emptyList()
        // salite per chi percorre il giro al contrario
        climbsRev = if (Prefs.climbAlerts(this)) RouteAnalysis.climbs(route.points.asReversed()) else emptyList()
        ghost = if (coachGhostMode && Prefs.ghost(this) && Ghost.canUse(route)) Ghost(route) else null
        lastClimbText = null
        lastGhost = Triple(null, null, null)
        publishCoach()
    }

    private fun updateCoach(loc: Location, s: RideStats) {
        val route = RideRepository.route.value
        if (route !== coachRoute || RideRepository.ghostMode != coachGhostMode) setupCoach(route)
        val prog = progress ?: return
        val moving = loc.hasBearing() && loc.hasSpeed() && loc.speed > 1.5f
        val fwd = prog.update(loc.latitude, loc.longitude, if (moving) loc.bearing else null)
        val onRoute = prog.offDistance < 80
        // al contrario: distanze misurate dalla fine del percorso e salite del verso opposto
        val rev = prog.reverse
        val done = if (rev) routeTotal - fwd else fwd
        routeRemaining = if (onRoute) routeTotal - done else null
        updatePoi(fwd, rev, onRoute)
        val climbList = if (rev) climbsRev else climbs
        if (rev != lastReverse) {
            announced.clear(); lastReverse = rev
        }

        // salite: avviso prima e indicazione durante
        var climbText: String? = null
        if (onRoute) {
            for ((i, c) in climbList.withIndex()) {
                val toStart = c.start - done
                if (toStart in 0.0..250.0) {
                    if (announced.add(i)) {
                        val m = (Math.round(toStart / 50.0) * 50).toInt().coerceAtLeast(50)
                        speak("Tra $m metri salita al ${pct(c.avg)} per cento per ${Format.meters(c.length)} metri")
                    }
                    climbText = "⛰ Tra ${toStart.toInt()} m salita ${pct(c.avg)}% · ${Format.meters(c.length)} m · ↗ ${c.gain.toInt()} m"
                    break
                }
                if (done >= c.start && done <= c.end) {
                    announced.add(i)
                    climbText = "⛰ Salita ${pct(c.avg)}% · mancano ${Format.meters(c.end - done)} m"
                    break
                }
            }
        }

        // fantasma: la stessa uscita fatta in passato, partita insieme a te
        var gLat: Double? = null
        var gLon: Double? = null
        var diff: Int? = null
        val g = ghost
        if (g != null && !rev) {
            val p = g.positionAt(s.elapsedMs)
            gLat = p.lat; gLon = p.lon
            if (onRoute) {
                diff = ((g.timeAtDistance(done) - s.elapsedMs) / 1000).toInt()
                if (done / 1000.0 >= nextGhostKm) {
                    val d = diff
                    speak(
                        when {
                            d > 3 -> "Sei avanti di ${secondsText(d)} sul fantasma"
                            d < -3 -> "Sei indietro di ${secondsText(-d)} sul fantasma"
                            else -> "Sei alla pari con il fantasma"
                        }
                    )
                    nextGhostKm = (done / 1000.0).toInt() + 1
                }
            }
        }
        lastClimbText = climbText
        lastGhost = Triple(gLat, gLon, diff)
        publishCoach()
    }

    /** Banner sulla mappa: fantasma, salite, prossimo punto utile, tramonto. */
    private fun publishCoach() {
        val extra = listOfNotNull(poiText, sunsetText)
        val (gLat, gLon, diff) = lastGhost
        val climbText = lastClimbText
        RideRepository.updateCoach(
            if (gLat == null && diff == null && climbText == null && extra.isEmpty()) null
            else CoachState(gLat, gLon, diff, climbText, extra)
        )
    }

    // ------------------------------------------------- punti utili

    private fun updatePoi(fwd: Double, rev: Boolean, onRoute: Boolean) {
        val list = RideRepository.pois.value
        if (list.isEmpty() || !onRoute) {
            poiText = null; return
        }
        val next = Pois.nextAhead(list, fwd, routeTotal, rev)
        if (next == null) {
            poiText = null; return
        }
        val (p, ahead) = next
        poiText = p.type.emoji + " " + p.name + " tra " + Format.distanceKm(ahead) + " km"
        val key = p.lat.toString() + p.lon
        if (Prefs.poiVoice(this) && ahead <= 300 && poiAnnounced.add(key)) {
            val m = (Math.round(ahead / 50.0) * 50).toInt()
            speak(if (m < 50) "${p.type.spoken} qui vicino" else "${p.type.spoken} tra $m metri")
        }
    }

    // ------------------------------------------------- tramonto

    private fun hm(ms: Long): String = SimpleDateFormat("HH:mm", Locale.ITALY).format(Date(ms))
    private fun spokenTime(ms: Long): String {
        val c = java.util.Calendar.getInstance().apply { timeInMillis = ms }
        val h = c.get(java.util.Calendar.HOUR_OF_DAY)
        val m = c.get(java.util.Calendar.MINUTE)
        return if (m == 0) "$h" else "$h e $m"
    }
    private fun minutes(ms: Long) = (ms / 60_000).toInt()

    /** Avvisa se non si fa in tempo a rientrare prima del buio. */
    private fun checkSunset() {
        if (!Prefs.sunsetAlert(this)) {
            if (sunsetText != null) { sunsetText = null; publishCoach() }
            return
        }
        val l = lastLoc ?: RideRepository.location.value ?: return
        val now = System.currentTimeMillis()
        val sunset = SunCalc.sunset(now, l.latitude, l.longitude) ?: return
        val s = RideRepository.stats.value
        // distanza da fare: al traguardo del percorso, altrimenti la strada fatta per tornare alla partenza
        val toGo = routeRemaining ?: s.distanceM
        val speed = if (s.movingMs > 10 * 60_000L && s.avgSpeedMs > 1.5) s.avgSpeedMs else 3.3
        val needMs = (toGo / speed * 1000).toLong()
        val left = sunset - now
        val target = if (routeRemaining != null) "arrivare" else "tornare alla partenza"
        val old = sunsetText
        sunsetText = when {
            left < 0 -> "🌙 Sole tramontato alle ${hm(sunset)}: accendi le luci"
            left < 90 * 60_000L -> "🌅 Tramonto ${hm(sunset)} · per $target ~${minutes(needMs)} min"
            else -> null
        }
        if (left < 0) {
            if (!sunsetDoneWarned && left > -60 * 60_000L) {
                sunsetDoneWarned = true
                speak("Il sole è tramontato. Accendi le luci e fai attenzione")
            }
        } else if (needMs + 15 * 60_000L > left && now - lastSunsetWarn > 15 * 60_000L) {
            lastSunsetWarn = now
            speak(
                "Attenzione, il sole tramonta alle ${spokenTime(sunset)}, tra ${minutes(left)} minuti. " +
                    "Per $target ti servono circa ${minutes(needMs)} minuti"
            )
        } else if (!sunsetHourWarned && left in 50 * 60_000L..60 * 60_000L) {
            sunsetHourWarned = true
            speak("Tra un'ora tramonta il sole, alle ${spokenTime(sunset)}")
        }
        if (old != sunsetText) publishCoach()
    }

    private fun pct(f: Double) = Math.round(f * 100).toInt()

    private fun secondsText(sec: Int): String =
        if (sec < 90) "$sec secondi" else "${sec / 60} minuti e ${sec % 60} secondi"

    private fun pause() {
        val s = RideRepository.stats.value
        if (!s.recording || s.paused) return
        pauseStarted = System.currentTimeMillis()
        stillSince = null
        if (s.sleeping) requestUpdates(1000L)
        RideRepository.updateStats(s.copy(paused = true, sleeping = false, currentSpeedMs = 0.0))
        updateNotification(getString(R.string.notif_paused))
        speak("Pausa")
    }

    private fun resume() {
        val s = RideRepository.stats.value
        if (!s.recording || !s.paused) return
        pausedAccumMs += System.currentTimeMillis() - pauseStarted
        lastLoc = null
        RideRepository.updateStats(s.copy(paused = false))
        updateNotification(getString(R.string.notif_recording))
        speak("Ripresa")
    }

    private fun stopRide() {
        fused.removeLocationUpdates(callback)
        ticker?.cancel()
        coachJob?.cancel()
        progress = null; ghost = null; climbs = emptyList(); coachRoute = null
        RideRepository.updateCoach(null)
        sunsetText = null; poiText = null
        sensorManager?.unregisterListener(accelListener)
        sensorManager = null
        fall = null
        BleSensors.stop()
        SosBike.stop()
        val s = RideRepository.stats.value
        val points = RideRepository.snapshotPoints()
        if (s.recording && points.size >= 2) {
            // segnalazioni fatte durante l'uscita: salvate come segnaposto nella traccia
            val reports = Reports.between(this, s.startTime, System.currentTimeMillis())
            val f = TrackStorage.saveRecorded(this, points, Reports.asPlacemarks(reports))
            Toast.makeText(
                this,
                getString(R.string.ride_saved, f.nameWithoutExtension, Format.distanceKm(s.distanceM)),
                Toast.LENGTH_LONG
            ).show()
        }
        RideRepository.updateStats(RideStats())
        RideRepository.clearPoints()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun speak(text: String) {
        if (ttsReady) tts?.speak(text, TextToSpeech.QUEUE_ADD, null, text)
    }

    private fun buildNotification(text: String): Notification {
        val pi = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(pi)
            .build()
    }

    private fun updateNotification(text: String) {
        val nm = getSystemService(android.app.NotificationManager::class.java)
        nm.notify(NOTIF_ID, buildNotification(text))
    }

    override fun onDestroy() {
        fused.removeLocationUpdates(callback)
        sensorManager?.unregisterListener(accelListener)
        scope.cancel()
        tts?.shutdown()
        tts = null
        super.onDestroy()
    }
}
