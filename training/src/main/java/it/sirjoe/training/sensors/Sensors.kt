package it.sirjoe.training.sensors

import android.content.Context
import it.sirjoe.training.data.Prefs
import java.util.concurrent.ConcurrentHashMap

/**
 * Punto unico per battito e cadenza, qualunque sia il collegamento:
 * ANT+ (fascia Garmin, chip del telefono o chiavetta USB) oppure Bluetooth.
 */
object Sensors {

    @Volatile private var hr: Int? = null
    @Volatile private var hrAt = 0L
    @Volatile private var cad: Int? = null
    @Volatile private var cadAt = 0L

    /** Stato leggibile di ogni sensore: "hr" → "ANT+ collegato", "cad" → "Bluetooth: ricerca…" */
    val status = ConcurrentHashMap<String, String>()

    fun pushHr(v: Int) {
        if (v in 25..250) {
            hr = v; hrAt = System.currentTimeMillis()
        }
    }

    fun pushCad(v: Int) {
        if (v in 0..220) {
            cad = v; cadAt = System.currentTimeMillis()
        }
    }

    /** Valori recenti (non più vecchi di 5 secondi), altrimenti null. */
    fun currentHr(): Int? = hr.takeIf { System.currentTimeMillis() - hrAt < 5_000 }

    /** La cadenza ANT+/Bluetooth smette di arrivare quando non pedali: dopo 4 s vale 0. */
    fun currentCad(): Int? {
        val age = System.currentTimeMillis() - cadAt
        return when {
            cad == null -> null
            age < 4_000 -> cad
            age < 60_000 -> 0
            else -> null
        }
    }

    fun setStatus(kind: String, s: String) {
        status[kind] = s
    }

    fun isAnt(v: String?) = v?.startsWith("ant:") == true
    fun isBle(v: String?) = v?.startsWith("ble:") == true

    fun configured(ctx: Context, kind: String) = Prefs.sensor(ctx, kind) != null

    @Volatile var started = false
        private set

    /** Collega i sensori impostati (non fa nulla se sono già collegati). */
    fun start(ctx: Context) {
        if (started) return
        started = true
        val app = ctx.applicationContext
        for (k in listOf(Prefs.HR, Prefs.CAD)) {
            val v = Prefs.sensor(app, k)
            when {
                v == null -> status[k] = "non impostato"
                isAnt(v) -> AntSensors.connect(app, k, v)
                isBle(v) -> BleSensors.connect(app, k, v.removePrefix("ble:"))
            }
        }
    }

    /** Ricollega da capo (dopo aver cambiato sensore nelle impostazioni). */
    fun restart(ctx: Context) {
        stop()
        start(ctx)
    }

    fun stop() {
        started = false
        AntSensors.stopAll()
        BleSensors.stopAll()
        hr = null; cad = null
        status.clear()
    }
}
