package it.sirjoe.mtbcomputer.service

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import androidx.core.content.ContextCompat
import it.sirjoe.mtbcomputer.data.BleParse
import it.sirjoe.mtbcomputer.data.Prefs
import it.sirjoe.mtbcomputer.data.RideRepository

/**
 * Sensori Bluetooth Low Energy standard: fascia cardio, cadenza (o cadenza/velocità), potenza.
 * Si collegano durante l'uscita e si ricollegano da soli se il segnale si perde.
 */
@SuppressLint("MissingPermission")
object BleSensors {

    private val main = Handler(Looper.getMainLooper())
    private val gatts = HashMap<BleParse.Kind, BluetoothGatt>()
    private var appCtx: Context? = null
    private var running = false

    private var hr: Int? = null
    private var hrAt = 0L
    private var cad: Int? = null
    private var cadAt = 0L
    private var power: Int? = null
    private var powerAt = 0L
    private val lastCrank = HashMap<BleParse.Kind, BleParse.Crank>()
    private val status = HashMap<String, String>()

    /** Permessi Bluetooth necessari su questa versione di Android. */
    fun permissions(): Array<String> = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
    } else {
        arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
    }

    fun hasPermissions(ctx: Context) = permissions().all {
        ContextCompat.checkSelfPermission(ctx, it) == PackageManager.PERMISSION_GRANTED
    }

    fun adapter(ctx: Context): BluetoothAdapter? =
        (ctx.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter

    fun anyConfigured(ctx: Context) = BleParse.Kind.values().any { Prefs.sensorAddress(ctx, it) != null }

    /** Valori recenti (non più vecchi di 5 secondi). */
    fun currentHr(): Int? = hr.takeIf { System.currentTimeMillis() - hrAt < 5_000 }
    fun currentCadence(): Int? = cad.takeIf { System.currentTimeMillis() - cadAt < 5_000 }
    fun currentPower(): Int? = power.takeIf { System.currentTimeMillis() - powerAt < 5_000 }

    private fun publish() {
        RideRepository.updateSensors(
            it.sirjoe.mtbcomputer.data.SensorValues(currentHr(), currentCadence(), currentPower(), HashMap(status))
        )
    }

    private fun setStatus(kind: BleParse.Kind, s: String) {
        status[kind.id] = s
        main.post { publish() }
    }

    // ------------------------------------------------------------ collegamento

    fun start(ctx: Context) {
        val app = ctx.applicationContext
        appCtx = app
        if (running) return
        if (!hasPermissions(app) || adapter(app)?.isEnabled != true) {
            for (k in BleParse.Kind.values()) if (Prefs.sensorAddress(app, k) != null) setStatus(k, "Bluetooth spento o senza permesso")
            return
        }
        running = true
        for (k in BleParse.Kind.values()) connect(app, k)
        main.post(ticker)
    }

    fun stop() {
        running = false
        main.removeCallbacks(ticker)
        for (g in gatts.values) {
            try {
                g.disconnect(); g.close()
            } catch (_: Exception) {
            }
        }
        gatts.clear()
        lastCrank.clear()
        status.clear()
        hr = null; cad = null; power = null
        publish()
    }

    /** Aggiorna la pagina dati ogni secondo (i valori vecchi spariscono). */
    private val ticker = object : Runnable {
        override fun run() {
            if (!running) return
            publish()
            main.postDelayed(this, 1000)
        }
    }

    private fun connect(ctx: Context, kind: BleParse.Kind) {
        val address = Prefs.sensorAddress(ctx, kind) ?: return
        val dev: BluetoothDevice = try {
            adapter(ctx)?.getRemoteDevice(address) ?: return
        } catch (_: Exception) {
            setStatus(kind, "indirizzo non valido"); return
        }
        setStatus(kind, "collegamento…")
        val g = dev.connectGatt(ctx, false, callback(kind), BluetoothDevice.TRANSPORT_LE)
        if (g != null) gatts[kind] = g
    }

    private fun retry(kind: BleParse.Kind) {
        val ctx = appCtx ?: return
        main.postDelayed({
            if (running) {
                gatts.remove(kind)?.let {
                    try {
                        it.close()
                    } catch (_: Exception) {
                    }
                }
                connect(ctx, kind)
            }
        }, 8_000)
    }

    private fun callback(kind: BleParse.Kind) = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, st: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                setStatus(kind, "collegato")
                gatt.discoverServices()
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                setStatus(kind, "non raggiungibile, riprovo…")
                retry(kind)
            }
        }

        @Suppress("DEPRECATION")
        override fun onServicesDiscovered(gatt: BluetoothGatt, st: Int) {
            val ch = gatt.getService(kind.service)?.getCharacteristic(kind.characteristic)
            if (ch == null) {
                setStatus(kind, "il sensore non offre questo dato")
                return
            }
            gatt.setCharacteristicNotification(ch, true)
            val d = ch.getDescriptor(BleParse.CCCD) ?: return
            if (Build.VERSION.SDK_INT >= 33) {
                gatt.writeDescriptor(d, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
            } else {
                d.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                gatt.writeDescriptor(d)
            }
        }

        @Deprecated("Android 12 e precedenti")
        @Suppress("DEPRECATION")
        override fun onCharacteristicChanged(gatt: BluetoothGatt, ch: BluetoothGattCharacteristic) {
            ch.value?.let { onData(kind, it) }
        }

        override fun onCharacteristicChanged(gatt: BluetoothGatt, ch: BluetoothGattCharacteristic, value: ByteArray) {
            onData(kind, value)
        }
    }

    private fun onData(kind: BleParse.Kind, b: ByteArray) {
        val now = System.currentTimeMillis()
        when (kind) {
            BleParse.Kind.HEART -> BleParse.heartRate(b)?.let { hr = it; hrAt = now }
            BleParse.Kind.CADENCE -> BleParse.cscCrank(b)?.let { c ->
                BleParse.cadence(lastCrank[kind], c)?.let { cad = it; cadAt = now }
                lastCrank[kind] = c
            }
            BleParse.Kind.POWER -> {
                BleParse.power(b)?.let { power = it; powerAt = now }
                // cadenza dal misuratore di potenza se non c'è un sensore di cadenza
                BleParse.powerCrank(b)?.let { c ->
                    if (cadAt < now - 3_000) BleParse.cadence(lastCrank[kind], c)?.let { cad = it; cadAt = now }
                    lastCrank[kind] = c
                }
            }
        }
    }

    // ------------------------------------------------------------ ricerca

    private var scanCb: ScanCallback? = null

    /** Cerca i sensori per [seconds] secondi; [onFound] riceve nome, indirizzo e tipo. */
    fun scan(ctx: Context, seconds: Int, onFound: (String, String, BleParse.Kind) -> Unit, onEnd: () -> Unit): Boolean {
        val scanner = adapter(ctx)?.takeIf { it.isEnabled }?.bluetoothLeScanner ?: return false
        stopScan(ctx)
        val filters = BleParse.Kind.values().map { ScanFilter.Builder().setServiceUuid(ParcelUuid(it.service)).build() }
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        val seen = HashSet<String>()
        val cb = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val uuids = result.scanRecord?.serviceUuids?.map { it.uuid } ?: emptyList()
                val kind = uuids.firstNotNullOfOrNull { BleParse.Kind.ofService(it) } ?: return
                val addr = result.device.address
                if (!seen.add(addr + kind.id)) return
                val name = result.scanRecord?.deviceName ?: result.device.name ?: "Sensore $addr"
                main.post { onFound(name, addr, kind) }
            }
        }
        scanCb = cb
        scanner.startScan(filters, settings, cb)
        main.postDelayed({
            stopScan(ctx)
            onEnd()
        }, seconds * 1000L)
        return true
    }

    fun stopScan(ctx: Context) {
        val cb = scanCb ?: return
        scanCb = null
        try {
            adapter(ctx)?.bluetoothLeScanner?.stopScan(cb)
        } catch (_: Exception) {
        }
    }
}
