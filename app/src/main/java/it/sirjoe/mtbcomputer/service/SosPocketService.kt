package it.sirjoe.mtbcomputer.service

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.location.Location
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.telephony.SmsManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import it.sirjoe.mtbcomputer.MainActivity
import it.sirjoe.mtbcomputer.R
import it.sirjoe.mtbcomputer.data.Prefs
import it.sirjoe.mtbcomputer.data.RideRepository
import it.sirjoe.mtbcomputer.data.SosProtocol
import java.io.BufferedReader
import java.io.InputStreamReader
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Telefono in tasca: aspetta il collegamento Bluetooth del telefono sul manubrio.
 * Alla richiesta d'aiuto suona, manda gli SMS ai contatti con la posizione e
 * mostra i pulsanti per aprire Where ARE U o chiamare il 112.
 */
@SuppressLint("MissingPermission")
class SosPocketService : Service() {

    companion object {
        const val ACTION_START = "it.sirjoe.mtbcomputer.SOS_POCKET_START"
        const val ACTION_STOP = "it.sirjoe.mtbcomputer.SOS_POCKET_STOP"
        const val ACTION_CANCEL = "it.sirjoe.mtbcomputer.SOS_POCKET_CANCEL"
        const val ACTION_TEST_SMS = "it.sirjoe.mtbcomputer.SOS_POCKET_TEST"
        private const val NOTIF_ID = 78
        /** Pacchetto dell'app ufficiale 112 Where ARE U (AREU). */
        const val WHERE_ARE_U = "it.Beta80Group.whereareu"

        /** Permesso Bluetooth (Android 12+) necessario per il servizio in primo piano. */
        fun btPermissionOk(ctx: Context) = Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED

        fun start(ctx: Context) {
            if (!btPermissionOk(ctx)) return
            ContextCompat.startForegroundService(ctx, Intent(ctx, SosPocketService::class.java).setAction(ACTION_START))
        }

        fun stop(ctx: Context) {
            ctx.startService(Intent(ctx, SosPocketService::class.java).setAction(ACTION_STOP))
        }

        fun cancelFromPocket(ctx: Context) {
            ctx.startService(Intent(ctx, SosPocketService::class.java).setAction(ACTION_CANCEL))
        }

        fun testSms(ctx: Context) {
            ctx.startService(Intent(ctx, SosPocketService::class.java).setAction(ACTION_TEST_SMS))
        }

        fun canSendSms(ctx: Context) =
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.SEND_SMS) == PackageManager.PERMISSION_GRANTED

        /** Invia un SMS (diviso in più parti se lungo). Restituisce il numero di destinatari raggiunti. */
        fun sendSms(ctx: Context, numbers: List<String>, text: String): Int {
            if (!canSendSms(ctx)) return 0
            val sms: SmsManager = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                ctx.getSystemService(SmsManager::class.java)
            } else {
                @Suppress("DEPRECATION")
                SmsManager.getDefault()
            }
            var ok = 0
            for (n in numbers) {
                try {
                    val parts = sms.divideMessage(text)
                    if (parts.size > 1) sms.sendMultipartTextMessage(n, null, parts, null, null)
                    else sms.sendTextMessage(n, null, text, null, null)
                    ok++
                } catch (_: Exception) {
                }
            }
            return ok
        }
    }

    private val main = Handler(Looper.getMainLooper())
    @Volatile
    private var running = false
    private var server: BluetoothServerSocket? = null
    @Volatile
    private var client: BluetoothSocket? = null
    private var acceptThread: Thread? = null

    private var peer: String? = null
    private var alertAt = 0L
    private var sosAt = 0L
    private var lastSmsUpdate = 0L
    private var lastLat: Double? = null
    private var lastLon: Double? = null
    private var lastAcc: Float? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                shutdown()
                return START_NOT_STICKY
            }
            ACTION_CANCEL -> {
                cancel(fromPocket = true)
                return START_STICKY
            }
            ACTION_TEST_SMS -> {
                goForeground("SOS attivo")
                val n = sendSms(this, Prefs.sosContacts(this),
                    "Prova SirJoe MTB: questo è un messaggio di prova del sistema SOS di ${Prefs.sosName(this).ifBlank { "un ciclista" }}. Nessuna emergenza.")
                toast(if (n > 0) "SMS di prova inviato a $n contatti" else "SMS non inviato: controlla contatti e permesso SMS")
                if (!running) startServer()
                return START_STICKY
            }
        }
        goForeground("In attesa del telefono sul manubrio")
        if (!running) startServer()
        return START_STICKY
    }

    private fun goForeground(text: String) {
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE else 0
        try {
            ServiceCompat.startForeground(this, NOTIF_ID, notification(text), type)
        } catch (_: Exception) {
            // Android 14: serve il permesso Bluetooth concesso prima di avviare
            stopSelf()
        }
    }

    private fun notification(text: String): Notification {
        val pi = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, RecordingService.CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("SirJoe SOS: telefono in tasca")
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(pi)
            .build()
    }

    private fun setText(text: String) {
        try {
            getSystemService(android.app.NotificationManager::class.java).notify(NOTIF_ID, notification(text))
        } catch (_: Exception) {
        }
        main.post { RideRepository.updateSos(RideRepository.sos.value.copy(enabled = true, message = text)) }
    }

    private fun toast(t: String) = main.post { android.widget.Toast.makeText(this, t, android.widget.Toast.LENGTH_LONG).show() }

    // ------------------------------------------------------------ Bluetooth

    private fun startServer() {
        val adapter = BleSensors.adapter(this)
        if (adapter == null || !adapter.isEnabled) {
            setText("Bluetooth spento: accendilo per ricevere l'SOS")
            main.postDelayed({ if (!running) startServer() }, 15_000)
            return
        }
        running = true
        acceptThread = Thread({
            while (running) {
                try {
                    val ss = adapter.listenUsingRfcommWithServiceRecord(SosProtocol.SERVICE_NAME, SosProtocol.SERVICE_UUID)
                    server = ss
                    val s = ss.accept()
                    try {
                        ss.close()
                    } catch (_: Exception) {
                    }
                    handle(s)
                } catch (_: Exception) {
                    if (running) try {
                        Thread.sleep(3_000)
                    } catch (_: InterruptedException) {
                    }
                }
            }
        }, "SirJoeSOSserver").apply { isDaemon = true; start() }
        main.post(watchdog)
    }

    private fun handle(s: BluetoothSocket) {
        client = s
        val out = s.outputStream
        fun reply(t: SosProtocol.Type) {
            try {
                out.write(SosProtocol.encode(SosProtocol.Msg(SosProtocol.Type.OK, text = t.name)).toByteArray())
                out.flush()
            } catch (_: Exception) {
            }
        }
        try {
            val r = BufferedReader(InputStreamReader(s.inputStream))
            while (running) {
                val line = r.readLine() ?: break
                val m = SosProtocol.decode(line) ?: continue
                reply(m.type)
                main.post { onMessage(m) }
            }
        } catch (_: Exception) {
        }
        try {
            s.close()
        } catch (_: Exception) {
        }
        client = null
        main.post {
            RideRepository.updateSos(RideRepository.sos.value.copy(connected = false))
            setText(if (alertAt > 0 && sosAt == 0L) "Collegamento perso durante un allarme!" else "In attesa del telefono sul manubrio")
        }
    }

    private fun sendToBike(m: SosProtocol.Msg) {
        val s = client ?: return
        Thread {
            try {
                s.outputStream.write(SosProtocol.encode(m).toByteArray())
                s.outputStream.flush()
            } catch (_: Exception) {
            }
        }.start()
    }

    // ------------------------------------------------------------ messaggi

    private fun remember(m: SosProtocol.Msg) {
        if (m.lat != null && m.lon != null) {
            lastLat = m.lat; lastLon = m.lon; lastAcc = m.accuracy
        }
    }

    private fun onMessage(m: SosProtocol.Msg) {
        val st = RideRepository.sos.value
        when (m.type) {
            SosProtocol.Type.HELLO -> {
                peer = m.text
                RideRepository.updateSos(st.copy(enabled = true, connected = true, peerName = m.text, lastOkAt = System.currentTimeMillis()))
                setText("Collegato al telefono sul manubrio (${m.text})")
            }
            SosProtocol.Type.PING -> RideRepository.updateSos(st.copy(connected = true, lastOkAt = System.currentTimeMillis()))
            SosProtocol.Type.ALERT -> {
                remember(m)
                if (alertAt == 0L) alertAt = System.currentTimeMillis()
                RideRepository.updateSos(st.copy(alarm = true, countdown = Prefs.sosCountdown(this), reason = "possibile caduta", lat = lastLat, lon = lastLon))
                SosAlarm.start(this)
                SosBike.showScreen(this, "Possibile caduta sul manubrio: se stai bene premi ANNULLA")
                setText("ALLARME: possibile caduta")
            }
            SosProtocol.Type.SOS -> {
                remember(m)
                if (sosAt == 0L) sendSos(if (m.text.isBlank()) "possibile caduta" else m.text)
            }
            SosProtocol.Type.POS -> {
                remember(m)
                val la = lastLat
                val lo = lastLon
                if (sosAt > 0 && la != null && lo != null && System.currentTimeMillis() - lastSmsUpdate > 5 * 60_000L) {
                    lastSmsUpdate = System.currentTimeMillis()
                    sendSms(this, Prefs.sosContacts(this), SosProtocol.smsUpdate(la, lo, lastAcc, now()))
                }
            }
            SosProtocol.Type.CANCEL -> cancel(fromPocket = false)
            else -> {}
        }
    }

    private fun now() = SimpleDateFormat("HH:mm", Locale.ITALY).format(Date())

    /** Manda gli SMS di richiesta d'aiuto. Se manca la posizione del manubrio usa quella di questo telefono. */
    private fun sendSos(reason: String) {
        sosAt = System.currentTimeMillis()
        lastSmsUpdate = sosAt
        RideRepository.updateSos(RideRepository.sos.value.copy(countdown = null, sosSent = true, alarm = true, reason = reason, lat = lastLat, lon = lastLon))
        SosAlarm.start(this)
        SosBike.showScreen(this, "SOS inviato: apri Where ARE U o chiama il 112")
        val send = { lat: Double?, lon: Double?, acc: Float? ->
            val text = SosProtocol.smsText(Prefs.sosName(this), reason, lat, lon, acc, now())
            val n = sendSms(this, Prefs.sosContacts(this), text)
            setText(if (n > 0) "SOS: SMS inviato a $n contatti" else "SOS: SMS NON inviati (contatti o permesso mancanti)")
            RideRepository.updateSos(RideRepository.sos.value.copy(message = if (n > 0) "SMS di aiuto inviato a $n contatti" else "SMS non inviati: chiama tu il 112"))
        }
        if (lastLat != null) {
            send(lastLat, lastLon, lastAcc)
        } else {
            ownLocation { l -> send(l?.latitude, l?.longitude, l?.accuracy) }
        }
    }

    private fun ownLocation(done: (Location?) -> Unit) {
        try {
            LocationServices.getFusedLocationProviderClient(this)
                .getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, null)
                .addOnSuccessListener { done(it) }
                .addOnFailureListener { done(null) }
        } catch (_: Exception) {
            done(null)
        }
    }

    private fun cancel(fromPocket: Boolean) {
        val hadSos = sosAt > 0
        SosAlarm.stop(this)
        SosBike.clearNotification(this)
        if (hadSos) sendSms(this, Prefs.sosContacts(this), SosProtocol.smsCancel(Prefs.sosName(this)))
        if (fromPocket) sendToBike(SosProtocol.Msg(SosProtocol.Type.CANCEL_POCKET))
        alertAt = 0L
        sosAt = 0L
        RideRepository.updateSos(RideRepository.sos.value.copy(countdown = null, sosSent = false, alarm = false, reason = null,
            message = if (hadSos) "Annullato: inviato SMS di falso allarme" else "Allarme annullato"))
        setText(if (client != null) "Collegato al telefono sul manubrio" else "In attesa del telefono sul manubrio")
    }

    /**
     * Sicurezza in più: se dopo un allarme il manubrio non conferma né annulla
     * (telefono rotto o collegamento perso), l'SOS parte comunque da qui.
     */
    private val watchdog = object : Runnable {
        override fun run() {
            if (!running) return
            val a = alertAt
            if (a > 0 && sosAt == 0L && System.currentTimeMillis() - a > (Prefs.sosCountdown(this@SosPocketService) + 45) * 1000L) {
                sendSos("possibile caduta, il telefono sul manubrio non risponde")
            }
            val st = RideRepository.sos.value
            if (st.countdown != null && st.countdown > 0 && a > 0) {
                RideRepository.updateSos(st.copy(countdown = st.countdown - 1))
            }
            main.postDelayed(this, 1000)
        }
    }

    private fun shutdown() {
        running = false
        main.removeCallbacks(watchdog)
        try {
            server?.close()
        } catch (_: Exception) {
        }
        try {
            client?.close()
        } catch (_: Exception) {
        }
        SosAlarm.stop(this)
        RideRepository.updateSos(RideRepository.sos.value.copy(enabled = false, connected = false, message = null))
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        running = false
        try {
            server?.close()
        } catch (_: Exception) {
        }
        super.onDestroy()
    }
}
