package it.sirjoe.mtbcomputer.data

import android.content.Context
import android.content.SharedPreferences

/** Impostazioni dell'app salvate sul telefono. */
object Prefs {
    private const val NAME = "sirjoe_mtb"

    const val BASE_TOPO = "topo"
    const val BASE_OSM = "osm"
    const val BASE_OFFLINE = "offline"

    private fun p(ctx: Context): SharedPreferences =
        ctx.applicationContext.getSharedPreferences(NAME, Context.MODE_PRIVATE)

    fun disclaimerAccepted(ctx: Context) = p(ctx).getBoolean("disclaimer_v1", false)
    fun setDisclaimerAccepted(ctx: Context) = p(ctx).edit().putBoolean("disclaimer_v1", true).apply()

    fun autoPause(ctx: Context) = p(ctx).getBoolean("auto_pause", true)
    fun setAutoPause(ctx: Context, v: Boolean) = p(ctx).edit().putBoolean("auto_pause", v).apply()

    fun headingUp(ctx: Context) = p(ctx).getBoolean("heading_up", true)
    fun setHeadingUp(ctx: Context, v: Boolean) = p(ctx).edit().putBoolean("heading_up", v).apply()

    fun keepScreenOn(ctx: Context) = p(ctx).getBoolean("keep_screen_on", true)
    fun setKeepScreenOn(ctx: Context, v: Boolean) = p(ctx).edit().putBoolean("keep_screen_on", v).apply()

    fun mtbOverlay(ctx: Context) = p(ctx).getBoolean("mtb_overlay", true)
    fun setMtbOverlay(ctx: Context, v: Boolean) = p(ctx).edit().putBoolean("mtb_overlay", v).apply()

    fun voice(ctx: Context) = p(ctx).getBoolean("voice", true)
    fun setVoice(ctx: Context, v: Boolean) = p(ctx).edit().putBoolean("voice", v).apply()

    fun baseMap(ctx: Context): String = p(ctx).getString("base_map", BASE_TOPO) ?: BASE_TOPO
    fun setBaseMap(ctx: Context, v: String) = p(ctx).edit().putString("base_map", v).apply()

    fun offlineMapPath(ctx: Context): String? = p(ctx).getString("offline_map", null)
    fun setOfflineMapPath(ctx: Context, v: String?) = p(ctx).edit().putString("offline_map", v).apply()
}
