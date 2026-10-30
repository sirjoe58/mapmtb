package it.sirjoe.training.sensors

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import com.dsi.ant.plugins.antplus.pcc.AntPlusBikeCadencePcc
import com.dsi.ant.plugins.antplus.pcc.AntPlusHeartRatePcc
import com.dsi.ant.plugins.antplus.pcc.defines.DeviceState
import com.dsi.ant.plugins.antplus.pcc.defines.RequestAccessResult
import com.dsi.ant.plugins.antplus.pccbase.AntPluginPcc
import com.dsi.ant.plugins.antplus.pccbase.PccReleaseHandle
import it.sirjoe.training.data.Prefs

/**
 * Sensori ANT+ tramite i "Plugin ANT+" ufficiali di Garmin (app gratuita "ANT+ Plugins Service").
 * Funziona sui telefoni con chip ANT+ oppure con una chiavetta ANT+ USB (app "ANT USB Service").
 *
 * Valore salvato: "ant:NUMERO" (fascia o cadenza) oppure "ant:NUMERO:c" per un sensore
 * combinato velocità+cadenza (es. Garmin GSC 10).
 */
object AntSensors {

    private val main = Handler(Looper.getMainLooper())
    private val handles = HashMap<String, PccReleaseHandle<*>>()
    private val wanted = HashMap<String, String>()
    private var appCtx: Context? = null

    const val PLUGIN_PKG = "com.dsi.ant.plugins.antplus"
    const val RADIO_PKG = "com.dsi.ant.service.socket"
    const val USB_PKG = "com.dsi.ant.usbservice"

    fun installed(ctx: Context, pkg: String): Boolean = try {
        @Suppress("DEPRECATION")
        ctx.packageManager.getPackageInfo(pkg, 0)
        true
    } catch (e: Exception) {
        false
    }

    /** Apre la pagina del Play Store di un'app mancante. */
    fun openStore(ctx: Context, pkg: String) {
        val i = Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$pkg")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            ctx.startActivity(i)
        } catch (e: Exception) {
            ctx.startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=$pkg"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }

    fun resultText(r: RequestAccessResult): String = when (r) {
        RequestAccessResult.SUCCESS -> "collegato"
        RequestAccessResult.USER_CANCELLED -> "ricerca annullata"
        RequestAccessResult.CHANNEL_NOT_AVAILABLE -> "canale ANT+ occupato da un'altra app"
        RequestAccessResult.DEPENDENCY_NOT_INSTALLED ->
            "manca l'app \"" + (AntPluginPcc.getMissingDependencyName() ?: "ANT+ Plugins Service") + "\" (Play Store)"
        RequestAccessResult.DEVICE_ALREADY_IN_USE -> "sensore già usato da un'altra app"
        RequestAccessResult.SEARCH_TIMEOUT -> "sensore non trovato (è acceso? indossi la fascia?)"
        RequestAccessResult.ADAPTER_NOT_DETECTED -> "il telefono non ha l'ANT+ (serve una chiavetta ANT+ USB)"
        else -> "errore ANT+ ($r)"
    }

    // ------------------------------------------------------------ ricerca (finestra dei plugin ANT+)

    /**
     * Apre la finestra di ricerca dei plugin ANT+: l'utente sceglie il sensore dall'elenco.
     * [done] riceve il valore da salvare ("ant:…") e il nome, oppure null e il motivo.
     */
    fun search(activity: Activity, kind: String, done: (String?, String) -> Unit) {
        val ctx = activity.applicationContext
        if (kind == Prefs.HR) {
            var h: PccReleaseHandle<AntPlusHeartRatePcc>? = null
            h = AntPlusHeartRatePcc.requestAccess(activity, ctx,
                AntPluginPcc.IPluginAccessResultReceiver<AntPlusHeartRatePcc> { pcc, result, _ ->
                    main.post {
                        if (result == RequestAccessResult.SUCCESS && pcc != null) {
                            val v = "ant:" + pcc.antDeviceNumber
                            val name = pcc.deviceName ?: "Fascia ANT+ ${pcc.antDeviceNumber}"
                            h?.close()
                            done(v, name)
                        } else {
                            h?.close()
                            done(null, resultText(result))
                        }
                    }
                },
                AntPluginPcc.IDeviceStateChangeReceiver { })
        } else {
            var h: PccReleaseHandle<AntPlusBikeCadencePcc>? = null
            h = AntPlusBikeCadencePcc.requestAccess(activity, ctx,
                AntPluginPcc.IPluginAccessResultReceiver<AntPlusBikeCadencePcc> { pcc, result, _ ->
                    main.post {
                        if (result == RequestAccessResult.SUCCESS && pcc != null) {
                            val combined = try {
                                pcc.isSpeedAndCadenceCombinedSensor
                            } catch (e: Exception) {
                                false
                            }
                            val v = "ant:" + pcc.antDeviceNumber + if (combined) ":c" else ""
                            val name = pcc.deviceName ?: "Cadenza ANT+ ${pcc.antDeviceNumber}"
                            h?.close()
                            done(v, name)
                        } else {
                            h?.close()
                            done(null, resultText(result))
                        }
                    }
                },
                AntPluginPcc.IDeviceStateChangeReceiver { })
        }
    }

    // ------------------------------------------------------------ collegamento durante l'uscita

    fun connect(ctx: Context, kind: String, value: String) {
        appCtx = ctx.applicationContext
        wanted[kind] = value
        val parts = value.removePrefix("ant:").split(":")
        val number = parts.getOrNull(0)?.toIntOrNull()
        if (number == null) {
            Sensors.setStatus(kind, "ANT+: sensore non valido, cercalo di nuovo")
            return
        }
        if (!installed(ctx, PLUGIN_PKG)) {
            Sensors.setStatus(kind, "ANT+: installa \"ANT+ Plugins Service\" dal Play Store")
            return
        }
        handles.remove(kind)?.close()
        Sensors.setStatus(kind, "ANT+: ricerca del sensore…")
        val state = AntPluginPcc.IDeviceStateChangeReceiver { st ->
            main.post {
                when (st) {
                    DeviceState.TRACKING -> Sensors.setStatus(kind, "ANT+: dati in arrivo")
                    DeviceState.SEARCHING -> Sensors.setStatus(kind, "ANT+: segnale perso, ricerca…")
                    DeviceState.DEAD -> {
                        Sensors.setStatus(kind, "ANT+: collegamento perso, riprovo…")
                        retry(kind)
                    }
                    else -> {}
                }
            }
        }
        if (kind == Prefs.HR) {
            handles[kind] = AntPlusHeartRatePcc.requestAccess(ctx, number, 0,
                AntPluginPcc.IPluginAccessResultReceiver<AntPlusHeartRatePcc> { pcc, result, _ ->
                    if (result == RequestAccessResult.SUCCESS && pcc != null) {
                        main.post { Sensors.setStatus(kind, "ANT+: collegato") }
                        pcc.subscribeHeartRateDataEvent { _, _, computedHr, _, _, dataState ->
                            if (dataState == AntPlusHeartRatePcc.DataState.LIVE_DATA ||
                                dataState == AntPlusHeartRatePcc.DataState.INITIAL_VALUE
                            ) Sensors.pushHr(computedHr)
                        }
                    } else {
                        failed(kind, result)
                    }
                }, state)
        } else {
            val combined = parts.getOrNull(1) == "c"
            handles[kind] = AntPlusBikeCadencePcc.requestAccess(ctx, number, 0, combined,
                AntPluginPcc.IPluginAccessResultReceiver<AntPlusBikeCadencePcc> { pcc, result, _ ->
                    if (result == RequestAccessResult.SUCCESS && pcc != null) {
                        main.post { Sensors.setStatus(kind, "ANT+: collegato") }
                        pcc.subscribeCalculatedCadenceEvent { _, _, cadence ->
                            Sensors.pushCad(cadence.toInt())
                        }
                    } else {
                        failed(kind, result)
                    }
                }, state)
        }
    }

    private fun failed(kind: String, result: RequestAccessResult) {
        main.post {
            Sensors.setStatus(kind, "ANT+: " + resultText(result))
            // sensore spento o lontano: riprova; gli altri errori richiedono un intervento
            if (result == RequestAccessResult.SEARCH_TIMEOUT || result == RequestAccessResult.OTHER_FAILURE ||
                result == RequestAccessResult.CHANNEL_NOT_AVAILABLE
            ) retry(kind)
        }
    }

    private fun retry(kind: String) {
        main.postDelayed({
            val ctx = appCtx ?: return@postDelayed
            val v = wanted[kind] ?: return@postDelayed
            connect(ctx, kind, v)
        }, 10_000)
    }

    fun stopAll() {
        wanted.clear()
        main.removeCallbacksAndMessages(null)
        for (h in handles.values) {
            try {
                h.close()
            } catch (_: Exception) {
            }
        }
        handles.clear()
    }
}
