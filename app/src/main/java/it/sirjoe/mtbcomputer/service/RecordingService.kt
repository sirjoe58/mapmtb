package it.sirjoe.mtbcomputer.service

import android.annotation.SuppressLint
import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.location.Location
import android.os.Build
import android.os.IBinder
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
import it.sirjoe.mtbcomputer.data.Prefs
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
import java.util.Locale
import kotlin.math.max

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

    private var tts: TextToSpeech? = null
    private var ttsReady = false

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
        altRef = null
        slopeMarkAlt = null
        slopeMarkDist = 0.0
        pausedAccumMs = 0L
        nextKm = 1
        RideRepository.updateStats(RideStats(recording = true, startTime = System.currentTimeMillis()))

        requestUpdates()
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
    }

    @SuppressLint("MissingPermission")
    private fun requestUpdates() {
        val req = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 1000L)
            .setMinUpdateIntervalMillis(1000L)
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
    }

    private fun onLocation(loc: Location) {
        RideRepository.updateLocation(loc)
        val s = RideRepository.stats.value
        if (!s.recording || s.paused) return
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

        if (isMoving) RideRepository.addPoint(TrackPoint(loc.latitude, loc.longitude, alt, loc.time))
        lastLoc = loc

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

        if (dist / 1000.0 >= nextKm) {
            speak("Chilometro $nextKm")
            nextKm++
        }
    }

    private fun pause() {
        val s = RideRepository.stats.value
        if (!s.recording || s.paused) return
        pauseStarted = System.currentTimeMillis()
        RideRepository.updateStats(s.copy(paused = true, currentSpeedMs = 0.0))
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
        val s = RideRepository.stats.value
        val points = RideRepository.snapshotPoints()
        if (s.recording && points.size >= 2) {
            val f = TrackStorage.saveRecorded(this, points)
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
        scope.cancel()
        tts?.shutdown()
        tts = null
        super.onDestroy()
    }
}
