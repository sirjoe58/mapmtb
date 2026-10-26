package it.sirjoe.mtbcomputer.service

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationManager
import android.app.PendingIntent
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import it.sirjoe.mtbcomputer.R
import it.sirjoe.mtbcomputer.data.Prefs
import it.sirjoe.mtbcomputer.data.RideRepository
import it.sirjoe.mtbcomputer.data.SosProtocol
import it.sirjoe.mtbcomputer.ui.SosActivity
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStream
import java.util.concurrent.LinkedBlockingQueue

/**
 * SOS sul telefono del manubrio: collegamento Bluetooth con il telefono in tasca
 * e conto alla rovescia dopo una caduta (o SOS manuale).
 * Il telefono sul manubrio non ha bisogno della SIM: gli SMS partono da quello in tasca.
 */
@SuppressLint("MissingPermission")
object SosBike {

    const val CHANNEL_SOS = "sos"
    private const val NOTIF_SOS = 77

    private val main = Handler(Looper.getMainLooper())
    private var appCtx: Context? = null

    @Volatile
    private var running = false
    private var thread: Thread? = null
    @Volatile
    private var socket: BluetoothSocket? = null
    private val outbox = LinkedBlockingQueue<String>()
    /** Messaggi importanti (ALERT/SOS/CANCEL) da riconsegnare se il collegamento cade. */
    private val pending = ArrayList<String>()

    private var countdownLeft = 0
    private var sosSentAt = 0L
    private var lastPosSent = 0L

    fun permissionsOk(ctx: Context): Boolean = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        ContextCompat.checkSelfPermission(ctx, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
    } else true

    fun configured(ctx: Context) = Prefs.sosRole(ctx) == Prefs.SOS_ROLE_BIKE && Prefs.sosPeer(ctx) != null

    private fun state() = RideRepository.sos.value
    private fun update(f: (it.sirjoe.mtbcomputer.data.SosLinkState) -> it.sirjoe.mtbcomputer.data.SosLinkState) {
        main.post { RideRepository.updateSos(f(state())) }
    }

    // ------------------------------------------------------------ collegamento

    /** Avvia il collegamento (all'inizio dell'uscita). */
    fun start(ctx: Context) {
        val app = ctx.applicationContext
        appCtx = app
        if (running || !configured(app) || !permissionsOk(app)) {
            if (configured(app) && !permissionsOk(app)) update { it.copy(enabled = true, message = "Permesso Bluetooth mancante") }
            return
        }
        running = true
        update { it.copy(enabled = true, peerName = Prefs.sosPeerName(app), message = "Collegamento al telefono in tasca…") }
        thread = Thread({ loop(app) }, "SirJoeSOS").apply {
            isDaemon = true
            start()
        }
    }

    fun stop() {
        running = false
        try {
            socket?.close()
        } catch (_: Exception) {
        }
        socket = null
        thread?.interrupt()
        thread = null
        if (state().countdown == null && !state().sosSent) {
            update { it.copy(enabled = false, connected = false, message = null) }
        }
    }

    private fun loop(ctx: Context) {
        val adapter = BleSensors.adapter(ctx)
        while (running) {
            val address = Prefs.sosPeer(ctx)
            if (adapter == null || !adapter.isEnabled || address == null) {
                update { it.copy(connected = false, message = "Bluetooth spento") }
                sleep(10_000); continue
            }
            try {
                val dev = adapter.getRemoteDevice(address)
                try {
                    adapter.cancelDiscovery()
                } catch (_: Exception) {
                }
                val s = dev.createRfcommSocketToServiceRecord(SosProtocol.SERVICE_UUID)
                socket = s
                s.connect()
                val out = s.outputStream
                write(out, SosProtocol.encode(SosProtocol.Msg(SosProtocol.Type.HELLO, text = Build.MODEL ?: "manubrio")))
                synchronized(pending) { for (m in pending) write(out, m) }
                update { it.copy(connected = true, lastOkAt = System.currentTimeMillis(), message = "Collegato al telefono in tasca") }
                val reader = Thread({ readLoop(s) }, "SirJoeSOSread").apply { isDaemon = true; start() }
                var lastPing = 0L
                while (running && s.isConnected && reader.isAlive) {
                    val m = outbox.poll(1, java.util.concurrent.TimeUnit.SECONDS)
                    if (m != null) write(out, m)
                    val now = System.currentTimeMillis()
                    if (now - lastPing > 20_000) {
                        write(out, SosProtocol.encode(SosProtocol.Msg(SosProtocol.Type.PING)))
                        lastPing = now
                    }
                }
            } catch (_: Exception) {
            }
            try {
                socket?.close()
            } catch (_: Exception) {
            }
            socket = null
            if (running) {
                update { it.copy(connected = false, message = "Telefono in tasca non raggiungibile, riprovo…") }
                sleep(12_000)
            }
        }
    }

    private fun sleep(ms: Long) {
        try {
            Thread.sleep(ms)
        } catch (_: InterruptedException) {
        }
    }

    private fun write(out: OutputStream, line: String) {
        out.write(line.toByteArray())
        out.flush()
    }

    private fun readLoop(s: BluetoothSocket) {
        try {
            val r = BufferedReader(InputStreamReader(s.inputStream))
            while (running) {
                val line = r.readLine() ?: break
                val m = SosProtocol.decode(line) ?: continue
                when (m.type) {
                    SosProtocol.Type.OK -> {
                        // consegnato: non serve più rimandarlo
                        if (m.text.isNotEmpty()) synchronized(pending) { pending.removeAll { it.startsWith(m.text + "|") || it.trim() == m.text } }
                        update { it.copy(connected = true, lastOkAt = System.currentTimeMillis()) }
                    }
                    SosProtocol.Type.CANCEL_POCKET -> main.post { appCtx?.let { cancel(it, fromPocket = true) } }
                    else -> {}
                }
            }
        } catch (_: Exception) {
        }
    }

    private fun send(m: SosProtocol.Msg, important: Boolean) {
        val line = SosProtocol.encode(m)
        if (important) synchronized(pending) {
            if (m.type == SosProtocol.Type.CANCEL) pending.clear()
            pending.add(line)
        }
        outbox.offer(line)
    }

    private fun locMsg(type: SosProtocol.Type, reason: String = ""): SosProtocol.Msg {
        val l = RideRepository.location.value
        return SosProtocol.Msg(type, l?.latitude, l?.longitude, l?.accuracy, l?.time ?: System.currentTimeMillis(), reason)
    }

    // ------------------------------------------------------------ conto alla rovescia

    /** Possibile caduta (o SOS manuale): allarme sul manubrio e avviso al telefono in tasca. */
    fun trigger(ctx: Context, reason: String, seconds: Int = Prefs.sosCountdown(ctx)) {
        val app = ctx.applicationContext
        appCtx = app
        val s = state()
        if (s.countdown != null || s.sosSent) return
        countdownLeft = seconds.coerceAtLeast(5)
        val l = RideRepository.location.value
        main.post {
            RideRepository.updateSos(
                state().copy(countdown = countdownLeft, alarm = true, reason = reason, lat = l?.latitude, lon = l?.longitude)
            )
        }
        if (running) send(locMsg(SosProtocol.Type.ALERT, reason), important = true)
        SosAlarm.start(app)
        showScreen(app, "Possibile caduta! Premi ANNULLA se stai bene")
        main.removeCallbacks(tick)
        main.postDelayed(tick, 1000)
    }

    private val tick = object : Runnable {
        override fun run() {
            val ctx = appCtx ?: return
            if (state().countdown == null) return
            countdownLeft--
            if (countdownLeft <= 0) {
                fire(ctx)
                return
            }
            RideRepository.updateSos(state().copy(countdown = countdownLeft))
            main.postDelayed(this, 1000)
        }
    }

    private fun fire(ctx: Context) {
        val reason = state().reason ?: "possibile caduta"
        sosSentAt = System.currentTimeMillis()
        lastPosSent = sosSentAt
        val delivered = running && state().connected
        RideRepository.updateSos(
            state().copy(
                countdown = null, sosSent = true, alarm = true,
                message = if (delivered) "Richiesta d'aiuto inviata al telefono in tasca"
                else "Telefono in tasca non collegato: riprovo a inviare l'SOS"
            )
        )
        if (running) send(locMsg(SosProtocol.Type.SOS, reason), important = true)
        // l'allarme continua: chi arriva sente dove sei
        main.postDelayed(posUpdates, 120_000)
    }

    /** Dopo l'SOS: posizione aggiornata ogni 2 minuti per 30 minuti. */
    private val posUpdates = object : Runnable {
        override fun run() {
            if (!state().sosSent) return
            if (System.currentTimeMillis() - sosSentAt > 30 * 60_000L) return
            if (running) send(locMsg(SosProtocol.Type.POS), important = false)
            main.postDelayed(this, 120_000)
        }
    }

    /** Annulla conto alla rovescia o SOS (falso allarme). */
    fun cancel(ctx: Context, fromPocket: Boolean = false) {
        main.removeCallbacks(tick)
        main.removeCallbacks(posUpdates)
        val wasActive = state().countdown != null || state().sosSent
        SosAlarm.stop(ctx)
        RideRepository.updateSos(
            state().copy(countdown = null, sosSent = false, alarm = false, reason = null,
                message = if (fromPocket) "Annullato dal telefono in tasca" else "Allarme annullato")
        )
        if (wasActive && !fromPocket && running) send(SosProtocol.Msg(SosProtocol.Type.CANCEL), important = true)
        (ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).cancel(NOTIF_SOS)
    }

    /** Apre la schermata SOS (anche sopra la schermata di blocco). */
    fun showScreen(ctx: Context, text: String) {
        val i = Intent(ctx, SosActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val pi = PendingIntent.getActivity(ctx, 5, i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val n = NotificationCompat.Builder(ctx, CHANNEL_SOS)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("SOS SirJoe MTB")
            .setContentText(text)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setFullScreenIntent(pi, true)
            .setContentIntent(pi)
            .setOngoing(true)
            .build()
        try {
            (ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).notify(NOTIF_SOS, n)
        } catch (_: Exception) {
        }
        try {
            ctx.startActivity(i)
        } catch (_: Exception) {
        }
    }

    fun clearNotification(ctx: Context) {
        (ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).cancel(NOTIF_SOS)
    }
}
