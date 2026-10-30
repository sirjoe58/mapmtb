package it.sirjoe.training.service

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import it.sirjoe.training.R
import it.sirjoe.training.data.Coach
import it.sirjoe.training.data.Prefs
import it.sirjoe.training.data.Sample
import it.sirjoe.training.data.Session
import it.sirjoe.training.data.Storage
import it.sirjoe.training.data.Zones
import it.sirjoe.training.sensors.Sensors
import it.sirjoe.training.ui.MainActivity

/**
 * Servizio in primo piano: registra l'uscita anche a schermo spento, legge i sensori,
 * segue il programma e dà gli avvisi.
 */
class RideService : Service() {

    companion object {
        const val CHANNEL_ID = "allenamento"
        private const val NOTIF_ID = 7
        private const val ACTION_START = "start"
        private const val ACTION_PAUSE = "pause"
        private const val ACTION_RESUME = "resume"
        private const val ACTION_SKIP = "skip"
        private const val ACTION_STOP = "stop"
        private const val ACTION_DISCARD = "discard"
        private const val EXTRA_WORKOUT = "workout"

        /** Nome dell'ultima uscita salvata (per il messaggio nella pagina). */
        @Volatile var lastSaved: String? = null

        private fun send(ctx: Context, action: String, workoutId: String? = null) {
            val i = Intent(ctx, RideService::class.java).setAction(action)
            if (workoutId != null) i.putExtra(EXTRA_WORKOUT, workoutId)
            if (action == ACTION_START) ContextCompat.startForegroundService(ctx, i) else ctx.startService(i)
        }

        fun start(ctx: Context, workoutId: String?) = send(ctx, ACTION_START, workoutId)
        fun pause(ctx: Context) = send(ctx, ACTION_PAUSE)
        fun resume(ctx: Context) = send(ctx, ACTION_RESUME)
        fun skip(ctx: Context) = send(ctx, ACTION_SKIP)
        fun stop(ctx: Context) = send(ctx, ACTION_STOP)
        fun discard(ctx: Context) = send(ctx, ACTION_DISCARD)
    }

    private val main = Handler(Looper.getMainLooper())
    private var coach: Coach? = null
    private var alerts: Alerts? = null
    private var wake: PowerManager.WakeLock? = null
    private var lm: LocationManager? = null

    private var running = false
    private var paused = false
    private var seconds = 0
    private var distance = 0.0
    private var maxHr = 160
    private val samples = ArrayList<Sample>()
    private val zoneSeconds = IntArray(5)

    private var lastFix: Location? = null
    private var lastFixAt = 0L
    private var lastCounted: Location? = null
    private var lastCue: Coach.Cue? = null
    private var lastCueAt = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> if (!running) begin(intent.getStringExtra(EXTRA_WORKOUT))
            ACTION_PAUSE -> if (running && !paused) {
                paused = true
                alerts?.say("Pausa.")
                publish()
            }
            ACTION_RESUME -> if (running && paused) {
                paused = false
                lastCounted = null
                alerts?.say("Si riparte.")
                publish()
            }
            ACTION_SKIP -> coach?.skip()?.let { cue(it) }
            ACTION_STOP -> finish(save = true)
            ACTION_DISCARD -> finish(save = false)
            else -> if (!running) stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun begin(workoutId: String?) {
        startInForeground()
        running = true
        paused = false
        seconds = 0
        distance = 0.0
        samples.clear()
        zoneSeconds.fill(0)
        lastSaved = null
        maxHr = Prefs.effectiveMaxHr(this)
        val w = Storage.workout(this, workoutId)
        coach = Coach(w, maxHr, Prefs.freeMaxZone(this))
        alerts = Alerts(this)

        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wake = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "SirJoeTraining:uscita").apply {
            setReferenceCounted(false)
            acquire(8 * 60 * 60 * 1000L)
        }
        Sensors.start(this)
        startGps()
        // la sintesi vocale si prepara in un attimo: il primo avviso dopo 2 secondi
        main.postDelayed({
            val c = coach ?: return@postDelayed
            val first = c.startCue()
            if (first != null) cue(first) else alerts?.say("Uscita libera avviata.")
        }, 2_000)
        main.postDelayed(ticker, 1_000)
        publish()
    }

    private val ticker = object : Runnable {
        override fun run() {
            if (!running) return
            tick()
            main.postDelayed(this, 1_000)
        }
    }

    private fun tick() {
        val hr = Sensors.currentHr()
        val cad = Sensors.currentCad()
        val now = System.currentTimeMillis()
        val fix = lastFix?.takeIf { now - lastFixAt < 5_000 }
        if (!paused) {
            seconds++
            if (hr != null) zoneSeconds[Zones.zoneOf(hr, maxHr) - 1]++
            samples += Sample(
                t = now,
                lat = fix?.latitude, lon = fix?.longitude,
                ele = fix?.takeIf { it.hasAltitude() }?.altitude,
                hr = hr, cad = cad,
                kmh = fix?.takeIf { it.hasSpeed() }?.speed?.times(3.6f)
            )
        }
        coach?.tick(hr, cad, paused)?.forEach { cue(it) }
        publish()
        if (seconds % 5 == 0) updateNotification()
    }

    private fun cue(c: Coach.Cue) {
        lastCue = c
        lastCueAt = System.currentTimeMillis()
        alerts?.play(c)
        publish()
    }

    private fun publish() {
        val c = coach
        val now = System.currentTimeMillis()
        val fix = lastFix?.takeIf { now - lastFixAt < 5_000 }
        RideState.publish(
            Snapshot(
                running = running,
                paused = paused,
                seconds = seconds,
                distanceM = distance,
                kmh = fix?.takeIf { it.hasSpeed() }?.speed?.times(3.6f),
                avgKmh = if (seconds > 0) distance / seconds * 3.6 else 0.0,
                hr = Sensors.currentHr(),
                cad = Sensors.currentCad(),
                maxHr = maxHr,
                gpsOk = fix != null,
                workout = c?.workout,
                stepIndex = c?.stepIndex ?: 0,
                step = c?.step,
                nextStep = c?.nextStep,
                stepRemaining = c?.stepRemaining ?: 0,
                totalRemaining = c?.totalRemaining ?: 0,
                finished = c?.finished ?: true,
                hrStatus = c?.hrStatus ?: Coach.Status.NONE,
                cadStatus = c?.cadStatus ?: Coach.Status.NONE,
                lastCue = lastCue,
                lastCueAt = lastCueAt,
                zoneSeconds = zoneSeconds.copyOf()
            )
        )
    }

    private fun finish(save: Boolean) {
        if (!running) {
            stopSelf(); return
        }
        running = false
        main.removeCallbacksAndMessages(null)
        stopGps()
        Sensors.stop()
        if (save && samples.size >= 30) {
            val w = coach?.workout
            val name = Session.defaultName(samples.first().t, w?.name)
            val s = Session(name, w?.name, maxHr, ArrayList(samples), distance, seconds)
            try {
                Storage.saveSession(this, s)
                lastSaved = name
                alerts?.say("Uscita salvata.")
            } catch (e: Exception) {
                lastSaved = "ERRORE: " + e.message
            }
        }
        // lascia finire la frase prima di spegnere la voce
        val a = alerts
        alerts = null
        main.postDelayed({ a?.release() }, 3_000)
        coach = null
        lastCue = null
        wake?.let { if (it.isHeld) it.release() }
        wake = null
        RideState.publish(Snapshot(maxHr = maxHr))
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        if (running) finish(save = true)
        super.onDestroy()
    }

    // ------------------------------------------------------------ GPS

    private val gpsListener = object : LocationListener {
        override fun onLocationChanged(l: Location) {
            onFix(l)
        }

        // necessari su Android 10 e precedenti (nelle versioni vecchie non hanno un'implementazione)
        @Deprecated("Android 10 e precedenti")
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {
        }

        override fun onProviderEnabled(provider: String) {}
        override fun onProviderDisabled(provider: String) {}
    }

    @SuppressLint("MissingPermission")
    private fun startGps() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) return
        val m = getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return
        lm = m
        try {
            m.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1_000L, 0f, gpsListener, Looper.getMainLooper())
        } catch (_: Exception) {
        }
    }

    private fun stopGps() {
        try {
            lm?.removeUpdates(gpsListener)
        } catch (_: Exception) {
        }
        lm = null
        lastFix = null
        lastCounted = null
    }

    private fun onFix(l: Location) {
        lastFix = l
        lastFixAt = System.currentTimeMillis()
        if (!running || paused) return
        if (l.hasAccuracy() && l.accuracy > 25f) return
        val prev = lastCounted
        if (prev == null) {
            lastCounted = l
            return
        }
        val d = prev.distanceTo(l)
        val moving = (l.hasSpeed() && l.speed > 0.8f) || d > 6f
        // scarta salti impossibili (oltre 30 m/s)
        val dt = ((l.time - prev.time) / 1000.0).coerceAtLeast(1.0)
        if (moving && d / dt < 30) {
            distance += d
            lastCounted = l
        } else if (!moving) {
            lastCounted = l
        }
    }

    // ------------------------------------------------------------ notifica

    private fun notification(text: String): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(open)
            .build()
    }

    private fun statusText(): String {
        val hr = Sensors.currentHr()
        val parts = ArrayList<String>()
        parts += Coach.clock(seconds)
        if (hr != null) parts += "$hr bpm · zona ${Zones.zoneOf(hr, maxHr)}"
        coach?.step?.let { parts += it.label + " " + Coach.clock(coach?.stepRemaining ?: 0) }
        return parts.joinToString("  ·  ")
    }

    private fun updateNotification() {
        try {
            androidx.core.app.NotificationManagerCompat.from(this).notify(NOTIF_ID, notification(statusText()))
        } catch (_: SecurityException) {
        }
    }

    private fun startInForeground() {
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        ) ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION else 0
        ServiceCompat.startForeground(this, NOTIF_ID, notification("Allenamento in corso"), type)
    }
}
