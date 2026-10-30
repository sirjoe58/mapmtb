package it.sirjoe.training.data

import android.content.Context
import android.content.SharedPreferences

/** Impostazioni dell'app (restano anche aggiornando l'app). */
object Prefs {
    private fun p(ctx: Context): SharedPreferences = ctx.getSharedPreferences("training", Context.MODE_PRIVATE)

    fun str(ctx: Context, k: String): String? = p(ctx).getString(k, null)
    fun putStr(ctx: Context, k: String, v: String?) = p(ctx).edit().apply { if (v == null) remove(k) else putString(k, v) }.apply()
    fun int(ctx: Context, k: String, d: Int) = p(ctx).getInt(k, d)
    fun putInt(ctx: Context, k: String, v: Int) = p(ctx).edit().putInt(k, v).apply()
    fun bool(ctx: Context, k: String, d: Boolean) = p(ctx).getBoolean(k, d)
    fun putBool(ctx: Context, k: String, v: Boolean) = p(ctx).edit().putBoolean(k, v).apply()

    // ---- profilo
    /** FC max: 0 = non ancora impostata */
    fun maxHr(ctx: Context) = int(ctx, "max_hr", 0)
    fun setMaxHr(ctx: Context, v: Int) = putInt(ctx, "max_hr", v)
    fun age(ctx: Context) = int(ctx, "age", 0)
    fun setAge(ctx: Context, v: Int) = putInt(ctx, "age", v)
    /** FC max da usare: quella impostata o, se manca, una stima prudente */
    fun effectiveMaxHr(ctx: Context): Int {
        val m = maxHr(ctx)
        if (m in 100..230) return m
        val a = age(ctx)
        return if (a in 10..100) Zones.estimateMax(a) else 160
    }

    // ---- avvisi
    fun voice(ctx: Context) = bool(ctx, "cue_voice", true)
    fun beep(ctx: Context) = bool(ctx, "cue_beep", true)
    fun vibrate(ctx: Context) = bool(ctx, "cue_vibrate", true)
    fun flash(ctx: Context) = bool(ctx, "cue_flash", true)
    /** uscita libera: avvisa sopra questa zona (0 = mai) */
    fun freeMaxZone(ctx: Context) = int(ctx, "free_max_zone", 0)

    // ---- sensori: "ant:12345" (numero dispositivo ANT+) o "ble:AA:BB:…" (indirizzo Bluetooth)
    fun sensor(ctx: Context, kind: String): String? = str(ctx, "sensor_$kind")
    fun setSensor(ctx: Context, kind: String, v: String?) = putStr(ctx, "sensor_$kind", v)
    fun sensorName(ctx: Context, kind: String): String? = str(ctx, "sensor_name_$kind")
    fun setSensorName(ctx: Context, kind: String, v: String?) = putStr(ctx, "sensor_name_$kind", v)

    const val HR = "hr"
    const val CAD = "cad"
}
