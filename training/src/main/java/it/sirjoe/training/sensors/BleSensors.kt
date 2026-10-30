package it.sirjoe.training.sensors

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
import it.sirjoe.training.data.BleParse
import it.sirjoe.training.data.Prefs

/**
 * Sensori Bluetooth Low Energy standard: fascia cardio (Heart Rate) e cadenza (Cycling Speed
 * and Cadence, es. Van Rysel montato sulla pedivella). Si ricollegano da soli se il segnale si perde.
 */
@SuppressLint("MissingPermission")
object BleSensors {

    private val main = Handler(Looper.getMainLooper())
    private val gatts = HashMap<String, BluetoothGatt>()
    private val lastCrank = HashMap<String, BleParse.Crank>()
    private val wanted = HashMap<String, String>()
    private var appCtx: Context? = null

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

    private fun kindOf(k: String) = if (k == Prefs.HR) BleParse.Kind.HEART else BleParse.Kind.CADENCE

    // ------------------------------------------------------------ collegamento

    fun connect(ctx: Context, kind: String, address: String) {
        appCtx = ctx.applicationContext
        wanted[kind] = address
        if (!hasPermissions(ctx)) {
            Sensors.setStatus(kind, "Bluetooth: manca il permesso")
            return
        }
        if (adapter(ctx)?.isEnabled != true) {
            Sensors.setStatus(kind, "Bluetooth spento")
            retry(kind)
            return
        }
        val dev: BluetoothDevice = try {
            adapter(ctx)?.getRemoteDevice(address) ?: return
        } catch (e: Exception) {
            Sensors.setStatus(kind, "Bluetooth: indirizzo non valido")
            return
        }
        Sensors.setStatus(kind, "Bluetooth: collegamento…")
        val g = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            dev.connectGatt(ctx, false, callback(kind), BluetoothDevice.TRANSPORT_LE)
        } else {
            dev.connectGatt(ctx, false, callback(kind))
        }
        if (g != null) gatts[kind] = g
    }

    fun stopAll() {
        wanted.clear()
        main.removeCallbacksAndMessages(null)
        for (g in gatts.values) {
            try {
                g.disconnect(); g.close()
            } catch (_: Exception) {
            }
        }
        gatts.clear()
        lastCrank.clear()
    }

    private fun retry(kind: String) {
        main.postDelayed({
            val ctx = appCtx ?: return@postDelayed
            val addr = wanted[kind] ?: return@postDelayed
            gatts.remove(kind)?.let {
                try {
                    it.close()
                } catch (_: Exception) {
                }
            }
            connect(ctx, kind, addr)
        }, 8_000)
    }

    private fun callback(kind: String) = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, st: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                Sensors.setStatus(kind, "Bluetooth: collegato")
                gatt.discoverServices()
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                if (wanted.containsKey(kind)) {
                    Sensors.setStatus(kind, "Bluetooth: non raggiungibile, riprovo…")
                    retry(kind)
                }
            }
        }

        @Suppress("DEPRECATION")
        override fun onServicesDiscovered(gatt: BluetoothGatt, st: Int) {
            val k = kindOf(kind)
            val ch = gatt.getService(k.service)?.getCharacteristic(k.characteristic)
            if (ch == null) {
                Sensors.setStatus(kind, "Bluetooth: il sensore non manda questo dato")
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
            Sensors.setStatus(kind, "Bluetooth: dati in arrivo")
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

    private fun onData(kind: String, b: ByteArray) {
        if (kind == Prefs.HR) {
            BleParse.heartRate(b)?.let { Sensors.pushHr(it) }
        } else {
            BleParse.cscCrank(b)?.let { c ->
                BleParse.cadence(lastCrank[kind], c)?.let { Sensors.pushCad(it) }
                lastCrank[kind] = c
            }
        }
    }

    // ------------------------------------------------------------ ricerca

    private var scanCb: ScanCallback? = null

    /** Cerca sensori del tipo [kind] per [seconds] secondi. */
    fun scan(ctx: Context, kind: String, seconds: Int, onFound: (String, String) -> Unit, onEnd: () -> Unit): Boolean {
        val scanner = adapter(ctx)?.takeIf { it.isEnabled }?.bluetoothLeScanner ?: return false
        stopScan(ctx)
        val k = kindOf(kind)
        val filters = listOf(ScanFilter.Builder().setServiceUuid(ParcelUuid(k.service)).build())
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        val seen = HashSet<String>()
        val cb = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val addr = result.device.address
                if (!seen.add(addr)) return
                val name = result.scanRecord?.deviceName ?: result.device.name ?: "Sensore $addr"
                main.post { onFound(name, addr) }
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
