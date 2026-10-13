package it.sirjoe.mtbcomputer.data

import android.content.Context
import android.content.SharedPreferences

/** Impostazioni dell'app salvate sul telefono. */
object Prefs {
    private const val NAME = "sirjoe_mtb"

    const val BASE_TOPO = "topo"
    const val BASE_OSM = "osm"
    const val BASE_OFFLINE = "offline"
    const val BASE_VECTOR = "vector"

    private fun p(ctx: Context): SharedPreferences =
        ctx.applicationContext.getSharedPreferences(NAME, Context.MODE_PRIVATE)

    fun disclaimerAccepted(ctx: Context) = p(ctx).getBoolean("disclaimer_v1", false)
    fun setDisclaimerAccepted(ctx: Context) = p(ctx).edit().putBoolean("disclaimer_v1", true).apply()

    fun autoPause(ctx: Context) = p(ctx).getBoolean("auto_pause", true)
    fun setAutoPause(ctx: Context, v: Boolean) = p(ctx).edit().putBoolean("auto_pause", v).apply()

    // Orientamento della mappa: direzione di marcia, Nord in alto, oppure libero (ruoti tu)
    const val ORIENT_HEADING = "heading"
    const val ORIENT_NORTH = "north"
    const val ORIENT_FREE = "free"

    fun orientation(ctx: Context): String =
        p(ctx).getString("orientation", null)
            ?: if (p(ctx).getBoolean("heading_up", true)) ORIENT_HEADING else ORIENT_NORTH
    fun setOrientation(ctx: Context, v: String) = p(ctx).edit().putString("orientation", v).apply()

    fun headingUp(ctx: Context) = orientation(ctx) == ORIENT_HEADING
    fun setHeadingUp(ctx: Context, v: Boolean) = setOrientation(ctx, if (v) ORIENT_HEADING else ORIENT_NORTH)

    /** Dimensione di testi e simboli delle mappe offline (1.0 = normale). */
    fun vectorScale(ctx: Context): Float = p(ctx).getFloat("vector_scale", 1.0f)
    fun setVectorScale(ctx: Context, v: Float) = p(ctx).edit().putFloat("vector_scale", v).apply()

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

    // Pagina "Crea"
    fun plannerRouting(ctx: Context) = p(ctx).getBoolean("planner_routing", true)
    fun setPlannerRouting(ctx: Context, v: Boolean) = p(ctx).edit().putBoolean("planner_routing", v).apply()

    fun plannerProfile(ctx: Context): BRouterClient.Profile {
        val id = p(ctx).getString("planner_profile", null)
        return BRouterClient.Profile.values().firstOrNull { it.id == id } ?: BRouterClient.Profile.TREKKING
    }
    fun setPlannerProfile(ctx: Context, v: BRouterClient.Profile) =
        p(ctx).edit().putString("planner_profile", v.id).apply()

    // Strava (dati dell'applicazione personale dell'utente e token di accesso)
    fun str(ctx: Context, key: String): String? = p(ctx).getString(key, null)
    fun putStr(ctx: Context, key: String, v: String?) = p(ctx).edit().putString(key, v).apply()
    fun long(ctx: Context, key: String): Long = p(ctx).getLong(key, 0L)
    fun putLong(ctx: Context, key: String, v: Long) = p(ctx).edit().putLong(key, v).apply()
}
