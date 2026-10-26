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
    const val BASE_SAT_MAPTILER = "sat_maptiler"
    const val BASE_SAT_ESRI = "sat_esri"

    fun isSatellite(base: String) = base == BASE_SAT_MAPTILER || base == BASE_SAT_ESRI

    private fun p(ctx: Context): SharedPreferences =
        ctx.applicationContext.getSharedPreferences(NAME, Context.MODE_PRIVATE)

    fun disclaimerAccepted(ctx: Context) = p(ctx).getBoolean("disclaimer_v2", false)
    fun setDisclaimerAccepted(ctx: Context) = p(ctx).edit().putBoolean("disclaimer_v2", true).apply()

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

    // Sosta automatica ("sleep") da fermo, con ripresa automatica
    fun autoSleep(ctx: Context) = p(ctx).getBoolean("auto_sleep", true)
    fun setAutoSleep(ctx: Context, v: Boolean) = p(ctx).edit().putBoolean("auto_sleep", v).apply()
    fun sleepDelaySec(ctx: Context) = p(ctx).getInt("sleep_delay", 60)
    fun setSleepDelaySec(ctx: Context, v: Int) = p(ctx).edit().putInt("sleep_delay", v).apply()

    /** Barre (pulsanti laterali e pagine in basso) nascoste 30 s dopo l'avvio di uscita o navigazione. */
    fun autoHideBars(ctx: Context) = p(ctx).getBoolean("auto_hide_bars", true)
    fun setAutoHideBars(ctx: Context, v: Boolean) = p(ctx).edit().putBoolean("auto_hide_bars", v).apply()

    /** Minuti senza tocchi dopo cui lo schermo si oscura durante l'uscita (0 = mai). */
    fun dimAfterMin(ctx: Context) = p(ctx).getInt("dim_after_min", 5)
    fun setDimAfterMin(ctx: Context, v: Int) = p(ctx).edit().putInt("dim_after_min", v).apply()

    const val ROT_PORTRAIT = "portrait"
    const val ROT_LANDSCAPE = "landscape"
    const val ROT_AUTO = "auto"
    /** Rotazione dello schermo: verticale bloccata se non si sceglie altro. */
    fun rotation(ctx: Context): String = p(ctx).getString("rotation", ROT_PORTRAIT) ?: ROT_PORTRAIT
    fun setRotation(ctx: Context, v: String) = p(ctx).edit().putString("rotation", v).apply()

    fun keepScreenOn(ctx: Context) = p(ctx).getBoolean("keep_screen_on", true)
    fun setKeepScreenOn(ctx: Context, v: Boolean) = p(ctx).edit().putBoolean("keep_screen_on", v).apply()

    fun mtbOverlay(ctx: Context) = p(ctx).getBoolean("mtb_overlay", true)
    fun setMtbOverlay(ctx: Context, v: Boolean) = p(ctx).edit().putBoolean("mtb_overlay", v).apply()

    /** Sfida al fantasma (uscita precedente sullo stesso percorso). */
    fun ghost(ctx: Context) = p(ctx).getBoolean("ghost", true)
    fun setGhost(ctx: Context, v: Boolean) = p(ctx).edit().putBoolean("ghost", v).apply()

    /** Avvisi vocali e a schermo prima delle salite del percorso. */
    fun climbAlerts(ctx: Context) = p(ctx).getBoolean("climb_alerts", true)
    fun setClimbAlerts(ctx: Context, v: Boolean) = p(ctx).edit().putBoolean("climb_alerts", v).apply()

    /** Mostra sulla mappa tutte le uscite registrate. */
    fun showRides(ctx: Context) = p(ctx).getBoolean("show_rides", false)
    fun setShowRides(ctx: Context, v: Boolean) = p(ctx).edit().putBoolean("show_rides", v).apply()

    fun voice(ctx: Context) = p(ctx).getBoolean("voice", true)
    fun setVoice(ctx: Context, v: Boolean) = p(ctx).edit().putBoolean("voice", v).apply()

    fun baseMap(ctx: Context): String = p(ctx).getString("base_map", BASE_TOPO) ?: BASE_TOPO
    fun setBaseMap(ctx: Context, v: String) = p(ctx).edit().putString("base_map", v).apply()

    /** Chiave personale MapTiler (piano gratuito) per la mappa satellitare. */
    fun maptilerKey(ctx: Context): String? = p(ctx).getString("maptiler_key", null)?.takeIf { it.isNotBlank() }
    fun setMaptilerKey(ctx: Context, v: String?) = p(ctx).edit().putString("maptiler_key", v?.trim()).apply()

    fun offlineMapPath(ctx: Context): String? = p(ctx).getString("offline_map", null)
    fun setOfflineMapPath(ctx: Context, v: String?) = p(ctx).edit().putString("offline_map", v).apply()

    // ------------------------------------------------------------ SOS col telefono in tasca

    const val SOS_ROLE_OFF = "off"
    /** Telefono sul manubrio: riconosce le cadute e manda l'SOS al telefono in tasca. */
    const val SOS_ROLE_BIKE = "bike"
    /** Telefono in tasca: riceve l'SOS via Bluetooth e manda gli SMS. */
    const val SOS_ROLE_POCKET = "pocket"

    fun sosRole(ctx: Context): String = p(ctx).getString("sos_role", SOS_ROLE_OFF) ?: SOS_ROLE_OFF
    fun setSosRole(ctx: Context, v: String) = p(ctx).edit().putString("sos_role", v).apply()

    /** Indirizzo Bluetooth del telefono in tasca (scelto tra quelli accoppiati). */
    fun sosPeer(ctx: Context): String? = p(ctx).getString("sos_peer", null)
    fun sosPeerName(ctx: Context): String? = p(ctx).getString("sos_peer_name", null)
    fun setSosPeer(ctx: Context, address: String?, name: String?) =
        p(ctx).edit().putString("sos_peer", address).putString("sos_peer_name", name).apply()

    fun sosSensitivity(ctx: Context): FallDetector.Sensitivity = FallDetector.Sensitivity.of(p(ctx).getString("sos_sens", null))
    fun setSosSensitivity(ctx: Context, v: FallDetector.Sensitivity) = p(ctx).edit().putString("sos_sens", v.id).apply()

    /** Secondi del conto alla rovescia prima di mandare l'SOS. */
    fun sosCountdown(ctx: Context) = p(ctx).getInt("sos_countdown", 60)
    fun setSosCountdown(ctx: Context, v: Int) = p(ctx).edit().putInt("sos_countdown", v).apply()

    /** Numeri a cui il telefono in tasca manda l'SMS (separati da virgola). */
    fun sosContacts(ctx: Context): List<String> =
        (p(ctx).getString("sos_contacts", "") ?: "").split(',', ';', '\n').map { it.trim() }.filter { it.length >= 3 }
    fun setSosContacts(ctx: Context, v: List<String>) = p(ctx).edit().putString("sos_contacts", v.joinToString(",")).apply()

    /** Nome del ciclista nel messaggio SMS. */
    fun sosName(ctx: Context): String = p(ctx).getString("sos_name", "") ?: ""
    fun setSosName(ctx: Context, v: String) = p(ctx).edit().putString("sos_name", v.trim()).apply()

    // ------------------------------------------------------------ sensori Bluetooth

    fun sensorAddress(ctx: Context, kind: BleParse.Kind): String? = p(ctx).getString("ble_" + kind.id, null)
    fun sensorName(ctx: Context, kind: BleParse.Kind): String? = p(ctx).getString("ble_name_" + kind.id, null)
    fun setSensor(ctx: Context, kind: BleParse.Kind, address: String?, name: String?) =
        p(ctx).edit().putString("ble_" + kind.id, address).putString("ble_name_" + kind.id, name).apply()

    /** Frequenza cardiaca massima per le zone. */
    fun maxHr(ctx: Context) = p(ctx).getInt("max_hr", 180)
    fun setMaxHr(ctx: Context, v: Int) = p(ctx).edit().putInt("max_hr", v).apply()

    // ------------------------------------------------------------ avvisi

    /** Avviso "rientra prima del buio". */
    fun sunsetAlert(ctx: Context) = p(ctx).getBoolean("sunset_alert", true)
    fun setSunsetAlert(ctx: Context, v: Boolean) = p(ctx).edit().putBoolean("sunset_alert", v).apply()

    /** Avvisi vocali dei punti utili lungo il percorso (fontanelle, rifugi...). */
    fun poiVoice(ctx: Context) = p(ctx).getBoolean("poi_voice", true)
    fun setPoiVoice(ctx: Context, v: Boolean) = p(ctx).edit().putBoolean("poi_voice", v).apply()

    // Pagina "Crea"
    fun plannerRouting(ctx: Context) = p(ctx).getBoolean("planner_routing", true)
    fun setPlannerRouting(ctx: Context, v: Boolean) = p(ctx).edit().putBoolean("planner_routing", v).apply()

    fun plannerProfile(ctx: Context): BRouterClient.Profile {
        val id = p(ctx).getString("planner_profile", null)
        return BRouterClient.Profile.values().firstOrNull { it.id == id } ?: BRouterClient.Profile.OFFROAD
    }
    fun setPlannerProfile(ctx: Context, v: BRouterClient.Profile) =
        p(ctx).edit().putString("planner_profile", v.id).apply()

    // Strava (dati dell'applicazione personale dell'utente e token di accesso)
    fun str(ctx: Context, key: String): String? = p(ctx).getString(key, null)
    fun putStr(ctx: Context, key: String, v: String?) = p(ctx).edit().putString(key, v).apply()
    fun long(ctx: Context, key: String): Long = p(ctx).getLong(key, 0L)
    fun putLong(ctx: Context, key: String, v: Long) = p(ctx).edit().putLong(key, v).apply()
}
