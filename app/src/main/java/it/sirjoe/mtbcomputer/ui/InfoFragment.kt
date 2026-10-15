package it.sirjoe.mtbcomputer.ui

import android.os.Bundle
import android.text.InputType
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.widget.SwitchCompat
import androidx.fragment.app.Fragment
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import it.sirjoe.mtbcomputer.BuildConfig
import it.sirjoe.mtbcomputer.MainActivity
import it.sirjoe.mtbcomputer.R
import it.sirjoe.mtbcomputer.data.Prefs
import it.sirjoe.mtbcomputer.share.Strava

/** Pagina "Info": logo, impostazioni, collegamento Strava, avvertenze d'uso, licenze. */
class InfoFragment : Fragment(R.layout.fragment_info) {

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val ctx = requireContext()

        view.findViewById<TextView>(R.id.version).text =
            getString(R.string.app_name) + " " + BuildConfig.VERSION_NAME
        view.findViewById<TextView>(R.id.disclaimer).text = MainActivity.readDisclaimer(ctx)

        fun bind(id: Int, value: Boolean, save: (Boolean) -> Unit) {
            val sw = view.findViewById<SwitchCompat>(id)
            sw.isChecked = value
            sw.setOnCheckedChangeListener { _, checked -> save(checked) }
        }
        bind(R.id.swAutoPause, Prefs.autoPause(ctx)) { Prefs.setAutoPause(ctx, it) }
        bind(R.id.swHeading, Prefs.headingUp(ctx)) { Prefs.setHeadingUp(ctx, it) }
        bind(R.id.swSleep, Prefs.autoSleep(ctx)) { Prefs.setAutoSleep(ctx, it) }
        val delayBtn = view.findViewById<MaterialButton>(R.id.btnSleepDelay)
        val delays = intArrayOf(30, 60, 120, 300)
        val delayLabels = arrayOf("30 secondi", "1 minuto", "2 minuti", "5 minuti")
        fun showDelay() {
            val i = delays.indexOf(Prefs.sleepDelaySec(ctx)).coerceAtLeast(1)
            delayBtn.text = "Sosta dopo: " + delayLabels[i] + " da fermo"
        }
        showDelay()
        delayBtn.setOnClickListener {
            val sel = delays.indexOf(Prefs.sleepDelaySec(ctx)).coerceAtLeast(1)
            MaterialAlertDialogBuilder(ctx)
                .setTitle("Dopo quanto tempo da fermo")
                .setSingleChoiceItems(delayLabels, sel) { d, which ->
                    Prefs.setSleepDelaySec(ctx, delays[which])
                    showDelay()
                    d.dismiss()
                }
                .show()
        }
        bind(R.id.swScreen, Prefs.keepScreenOn(ctx)) { Prefs.setKeepScreenOn(ctx, it) }
        bind(R.id.swAutoHide, Prefs.autoHideBars(ctx)) { Prefs.setAutoHideBars(ctx, it) }

        // oscuramento dello schermo senza tocchi (si riaccende toccandolo)
        val dimBtn = view.findViewById<MaterialButton>(R.id.btnDim)
        val dims = intArrayOf(1, 2, 5, 10, 15, 30, 0)
        val dimLabels = arrayOf("1 minuto", "2 minuti", "5 minuti", "10 minuti", "15 minuti", "30 minuti", "Mai")
        fun showDim() {
            val i = dims.indexOf(Prefs.dimAfterMin(ctx)).let { if (it < 0) 2 else it }
            dimBtn.text = if (dims[i] == 0) "Oscura lo schermo: mai" else "Oscura lo schermo dopo ${dimLabels[i]} senza tocchi"
        }
        showDim()
        dimBtn.setOnClickListener {
            val sel = dims.indexOf(Prefs.dimAfterMin(ctx)).let { if (it < 0) 2 else it }
            MaterialAlertDialogBuilder(ctx)
                .setTitle("Oscura lo schermo dopo")
                .setSingleChoiceItems(dimLabels, sel) { d, which ->
                    Prefs.setDimAfterMin(ctx, dims[which])
                    showDim()
                    d.dismiss()
                }
                .show()
            Toast.makeText(ctx, "Durante l'uscita lo schermo si oscura ma resta attivo: toccalo per riaccenderlo", Toast.LENGTH_LONG).show()
        }

        // rotazione dello schermo
        val rotBtn = view.findViewById<MaterialButton>(R.id.btnRotation)
        val rots = arrayOf(Prefs.ROT_PORTRAIT, Prefs.ROT_LANDSCAPE, Prefs.ROT_AUTO)
        val rotLabels = arrayOf("Sempre verticale", "Sempre orizzontale", "Automatica (gira col telefono)")
        fun showRot() {
            rotBtn.text = "Schermo: " + rotLabels[rots.indexOf(Prefs.rotation(ctx)).coerceAtLeast(0)].lowercase()
        }
        showRot()
        rotBtn.setOnClickListener {
            MaterialAlertDialogBuilder(ctx)
                .setTitle("Orientamento dello schermo")
                .setSingleChoiceItems(rotLabels, rots.indexOf(Prefs.rotation(ctx)).coerceAtLeast(0)) { d, which ->
                    Prefs.setRotation(ctx, rots[which])
                    showRot()
                    d.dismiss()
                    (activity as? MainActivity)?.applyRotation()
                }
                .show()
        }
        bind(R.id.swVoice, Prefs.voice(ctx)) { Prefs.setVoice(ctx, it) }
        bind(R.id.swClimbs, Prefs.climbAlerts(ctx)) { Prefs.setClimbAlerts(ctx, it) }
        bind(R.id.swGhost, Prefs.ghost(ctx)) { Prefs.setGhost(ctx, it) }
        bind(R.id.swMtb, Prefs.mtbOverlay(ctx)) {
            Prefs.setMtbOverlay(ctx, it)
            (activity as? MainActivity)?.onBaseMapChanged()
        }

        view.findViewById<MaterialButton>(R.id.stravaConnect).setOnClickListener {
            when {
                Strava.isConnected(ctx) -> MaterialAlertDialogBuilder(ctx)
                    .setTitle("Scollegare Strava?")
                    .setPositiveButton("Scollega") { _, _ ->
                        Strava.disconnect(ctx)
                        refreshStrava()
                    }
                    .setNegativeButton(R.string.cancel, null)
                    .show()
                !Strava.hasApp(ctx) -> setupStrava(thenConnect = true)
                else -> connect()
            }
        }
        view.findViewById<MaterialButton>(R.id.stravaSetup).setOnClickListener { setupStrava(thenConnect = false) }
        refreshStrava()
    }

    fun refreshStrava() {
        val v = view ?: return
        val ctx = requireContext()
        val status = v.findViewById<TextView>(R.id.stravaStatus)
        val btn = v.findViewById<MaterialButton>(R.id.stravaConnect)
        when {
            Strava.isConnected(ctx) -> {
                status.text = "Collegato" + (Strava.athlete(ctx)?.let { " come $it" } ?: "") +
                    ". Dalla pagina Percorsi: traccia → Condividi → Carica su Strava."
                btn.text = "Scollega"
            }
            Strava.hasApp(ctx) -> {
                status.text = "Dati app inseriti. Tocca \"Collega Strava\" e autorizza il caricamento."
                btn.text = "Collega Strava"
            }
            else -> {
                status.text = "Per caricare le uscite su Strava serve una tua \"applicazione API\" Strava, " +
                    "gratuita e per uso personale. Tocca \"Collega Strava\" per le istruzioni."
                btn.text = "Collega Strava"
            }
        }
    }

    private fun connect() {
        try {
            Strava.startAuth(requireContext())
        } catch (e: Exception) {
            Toast.makeText(requireContext(), "Impossibile aprire Strava: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun setupStrava(thenConnect: Boolean) {
        val ctx = requireContext()
        val dp = resources.displayMetrics.density
        val box = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((24 * dp).toInt(), (8 * dp).toInt(), (24 * dp).toInt(), 0)
        }
        box.addView(TextView(ctx).apply {
            text = "1. Da computer o browser apri strava.com/settings/api e crea un'applicazione.\n" +
                "2. Nome: quello che vuoi (es. SirJoe MTB). Sito: anche https://github.com.\n" +
                "3. In \"Authorization Callback Domain\" scrivi: localhost\n" +
                "4. Copia qui sotto Client ID e Client Secret.\n\n" +
                "I dati restano solo su questo telefono. Non condividere il Client Secret."
            textSize = 14f
        })
        val id = EditText(ctx).apply {
            hint = "Client ID (numero)"
            inputType = InputType.TYPE_CLASS_NUMBER
            setText(Strava.clientId(ctx))
        }
        val secret = EditText(ctx).apply {
            hint = "Client Secret"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        box.addView(id)
        box.addView(secret)
        MaterialAlertDialogBuilder(ctx)
            .setTitle("Collegare Strava")
            .setView(box)
            .setPositiveButton(if (thenConnect) "Salva e collega" else "Salva") { _, _ ->
                val i = id.text.toString().trim()
                val s = secret.text.toString().trim()
                if (i.isEmpty() || s.isEmpty()) {
                    Toast.makeText(ctx, "Inserisci Client ID e Client Secret", Toast.LENGTH_LONG).show()
                    return@setPositiveButton
                }
                Strava.saveApp(ctx, i, s)
                refreshStrava()
                if (thenConnect) connect()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }
}
