package it.sirjoe.mtbcomputer.ui

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.text.InputType
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.widget.SwitchCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import it.sirjoe.mtbcomputer.MainActivity
import it.sirjoe.mtbcomputer.data.BleParse
import it.sirjoe.mtbcomputer.data.FallDetector
import it.sirjoe.mtbcomputer.data.Prefs
import it.sirjoe.mtbcomputer.data.RideRepository
import it.sirjoe.mtbcomputer.service.BleSensors
import it.sirjoe.mtbcomputer.service.SosBike
import it.sirjoe.mtbcomputer.service.SosPocketService
import kotlinx.coroutines.launch

/**
 * Sezioni della pagina Info per le funzioni 2.0: SOS col telefono in tasca,
 * sensori Bluetooth, avvisi (tramonto, punti utili).
 */
@SuppressLint("MissingPermission", "SetTextI18n")
class InfoExtras(private val f: Fragment, private val box: LinearLayout) {

    private val ctx: Context = f.requireContext()
    private val dp = ctx.resources.displayMetrics.density
    private fun px(v: Int) = (v * dp).toInt()

    private lateinit var sosStatus: TextView
    private lateinit var sosRoleBtn: MaterialButton
    private lateinit var sosBikeBox: LinearLayout
    private lateinit var sosPocketBox: LinearLayout
    private lateinit var peerBtn: MaterialButton
    private lateinit var sensBtn: MaterialButton
    private lateinit var countBtn: MaterialButton
    private lateinit var nameBtn: MaterialButton
    private lateinit var contactsBtn: MaterialButton
    private lateinit var sensorsStatus: TextView
    private lateinit var maxHrBtn: MaterialButton

    private fun act() = f.activity as? MainActivity

    private fun header(text: String) = TextView(ctx).apply {
        this.text = text
        textSize = 18f
        setTypeface(typeface, Typeface.BOLD)
        setPadding(0, px(20), 0, 0)
    }

    private fun note(text: String = "") = TextView(ctx).apply {
        this.text = text
        textSize = 14f
        setPadding(0, px(6), 0, px(4))
    }

    private fun button(text: String, outlined: Boolean = false, onClick: () -> Unit) =
        (if (outlined) MaterialButton(ctx, null, com.google.android.material.R.attr.materialButtonOutlinedStyle) else MaterialButton(ctx)).apply {
            this.text = text
            isAllCaps = false
            setOnClickListener { onClick() }
        }

    private fun switch(text: String, value: Boolean, onChange: (Boolean) -> Unit) = SwitchCompat(ctx).apply {
        this.text = text
        isChecked = value
        minHeight = px(56)
        setOnCheckedChangeListener { _, v -> onChange(v) }
    }

    private fun LinearLayout.add(v: View) = addView(v, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))

    fun build() {
        buildSos()
        buildSensors()
        buildAlerts()
        refresh()
        f.viewLifecycleOwner.lifecycleScope.launch {
            f.viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { RideRepository.sos.collect { refreshSosStatus() } }
                launch { RideRepository.sensors.collect { refreshSensors() } }
            }
        }
    }

    // ------------------------------------------------------------ SOS

    private fun buildSos() {
        box.add(header("SOS: caduta e richiesta d'aiuto"))
        box.add(note(
            "Il telefono sul manubrio riconosce una probabile caduta (urto forte e poi fermo) e avvia un conto alla rovescia " +
                "con allarme. Se non lo annulli, avvisa via Bluetooth il telefono che hai in tasca, che manda un SMS " +
                "con la tua posizione ai contatti scelti e ti mostra i pulsanti per aprire 112 Where ARE U o chiamare il 112.\n" +
                "Installa SirJoe MTB su tutti e due i telefoni e accoppiali una volta nelle impostazioni Bluetooth."
        ))
        sosStatus = note()
        box.add(sosStatus)
        sosRoleBtn = button("", false) { chooseRole() }
        box.add(sosRoleBtn)

        sosBikeBox = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        peerBtn = button("", true) { choosePeer() }
        sensBtn = button("", true) { chooseSensitivity() }
        countBtn = button("", true) { chooseCountdown() }
        sosBikeBox.add(peerBtn)
        sosBikeBox.add(sensBtn)
        sosBikeBox.add(countBtn)
        sosBikeBox.add(button("Prova il collegamento", true) { testLink() })
        sosBikeBox.add(note("Durante l'uscita il collegamento è automatico. SOS manuale: sulla mappa, pulsante ⋯ → \"Invia SOS ora\"."))
        box.add(sosBikeBox)

        sosPocketBox = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        nameBtn = button("", true) { editName() }
        contactsBtn = button("", true) { editContacts() }
        sosPocketBox.add(nameBtn)
        sosPocketBox.add(contactsBtn)
        sosPocketBox.add(button("Invia un SMS di prova ai contatti", true) { testSms() })
        sosPocketBox.add(button("Riavvia la ricezione SOS", true) { startPocket() })
        sosPocketBox.add(note("Il telefono in tasca deve avere la SIM. Where ARE U non si può avviare da un'altra app: " +
            "in caso di SOS ti compare il pulsante per aprirla tu."))
        box.add(sosPocketBox)

        box.add(button("Impostazioni batteria di questa app", true) { openBatterySettings() })
        box.add(note("Huawei, Xiaomi e altri chiudono le app in background: in Impostazioni → Batteria → Avvio app " +
            "(Huawei) o Risparmio batteria (Xiaomi) imposta SirJoe MTB su \"gestione manuale\" / \"nessuna restrizione\" su tutti e due i telefoni."))
    }

    private fun roleLabel(r: String) = when (r) {
        Prefs.SOS_ROLE_BIKE -> "Questo telefono: SUL MANUBRIO"
        Prefs.SOS_ROLE_POCKET -> "Questo telefono: IN TASCA (manda gli SMS)"
        else -> "SOS spento (tocca per attivarlo)"
    }

    private fun chooseRole() {
        val roles = arrayOf(Prefs.SOS_ROLE_OFF, Prefs.SOS_ROLE_BIKE, Prefs.SOS_ROLE_POCKET)
        val labels = arrayOf("Spento", "Sul manubrio (riconosce le cadute)", "In tasca (riceve l'SOS e manda gli SMS)")
        MaterialAlertDialogBuilder(ctx)
            .setTitle("Ruolo di questo telefono")
            .setSingleChoiceItems(labels, roles.indexOf(Prefs.sosRole(ctx)).coerceAtLeast(0)) { d, w ->
                d.dismiss()
                setRole(roles[w])
            }
            .show()
    }

    private fun btPerms(): Array<String> = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
        arrayOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN) else emptyArray()

    private fun setRole(r: String) {
        val a = act() ?: return
        Prefs.setSosRole(ctx, r)
        when (r) {
            Prefs.SOS_ROLE_POCKET -> {
                val perms = btPerms() + Manifest.permission.SEND_SMS + Manifest.permission.ACCESS_FINE_LOCATION +
                    (if (Build.VERSION.SDK_INT >= 33) arrayOf(Manifest.permission.POST_NOTIFICATIONS) else emptyArray())
                a.requestPerms(perms) { ok ->
                    if (!ok) Toast.makeText(ctx, "Servono i permessi Bluetooth e SMS per ricevere l'SOS", Toast.LENGTH_LONG).show()
                    startPocket()
                    if (Prefs.sosContacts(ctx).isEmpty()) editContacts()
                    refresh()
                }
            }
            Prefs.SOS_ROLE_BIKE -> {
                SosPocketService.stop(ctx)
                a.requestPerms(btPerms()) {
                    if (Prefs.sosPeer(ctx) == null) choosePeer()
                    refresh()
                }
            }
            else -> {
                SosPocketService.stop(ctx)
                SosBike.stop()
            }
        }
        refresh()
    }

    private fun choosePeer() {
        val a = act() ?: return
        a.requestPerms(btPerms()) { ok ->
            if (!ok) {
                Toast.makeText(ctx, "Permesso Bluetooth negato", Toast.LENGTH_LONG).show(); return@requestPerms
            }
            val adapter = BleSensors.adapter(ctx)
            if (adapter == null || !adapter.isEnabled) {
                Toast.makeText(ctx, "Accendi il Bluetooth", Toast.LENGTH_LONG).show(); return@requestPerms
            }
            val devices = adapter.bondedDevices?.toList()?.sortedBy { it.name ?: "" } ?: emptyList()
            if (devices.isEmpty()) {
                MaterialAlertDialogBuilder(ctx)
                    .setTitle("Nessun telefono accoppiato")
                    .setMessage("Accoppia prima i due telefoni: Impostazioni → Bluetooth → cerca l'altro telefono → Accoppia (conferma il codice su tutti e due).")
                    .setPositiveButton("Apri Bluetooth") { _, _ -> openBt() }
                    .setNegativeButton("Chiudi", null)
                    .show()
                return@requestPerms
            }
            MaterialAlertDialogBuilder(ctx)
                .setTitle("Qual è il telefono in tasca?")
                .setItems(devices.map { (it.name ?: "Senza nome") + "\n" + it.address }.toTypedArray()) { _, w ->
                    val d = devices[w]
                    Prefs.setSosPeer(ctx, d.address, d.name ?: d.address)
                    refresh()
                    Toast.makeText(ctx, "Telefono in tasca: ${d.name}", Toast.LENGTH_SHORT).show()
                }
                .setNeutralButton("Accoppia un altro") { _, _ -> openBt() }
                .show()
        }
    }

    private fun openBt() {
        try {
            ctx.startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS))
        } catch (_: Exception) {
        }
    }

    private fun chooseSensitivity() {
        val values = FallDetector.Sensitivity.values()
        MaterialAlertDialogBuilder(ctx)
            .setTitle("Sensibilità caduta")
            .setSingleChoiceItems(values.map { it.label }.toTypedArray(), values.indexOf(Prefs.sosSensitivity(ctx))) { d, w ->
                Prefs.setSosSensitivity(ctx, values[w]); d.dismiss(); refresh()
            }
            .show()
    }

    private fun chooseCountdown() {
        val values = intArrayOf(30, 60, 90, 120)
        MaterialAlertDialogBuilder(ctx)
            .setTitle("Conto alla rovescia prima dell'SOS")
            .setSingleChoiceItems(values.map { "$it secondi" }.toTypedArray(), values.indexOf(Prefs.sosCountdown(ctx)).coerceAtLeast(1)) { d, w ->
                Prefs.setSosCountdown(ctx, values[w]); d.dismiss(); refresh()
            }
            .show()
    }

    private fun testLink() {
        if (!SosBike.configured(ctx)) {
            choosePeer(); return
        }
        SosBike.start(ctx)
        Toast.makeText(ctx, "Collegamento in corso: tieni aperto SirJoe MTB anche sul telefono in tasca", Toast.LENGTH_LONG).show()
        // se non c'è un'uscita in corso il collegamento si chiude dopo un minuto
        box.postDelayed({
            if (!RideRepository.stats.value.recording && RideRepository.sos.value.countdown == null && !RideRepository.sos.value.sosSent) SosBike.stop()
        }, 60_000)
    }

    private fun editName() {
        val input = EditText(ctx).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS
            setText(Prefs.sosName(ctx))
            hint = "Es. Sergio"
        }
        MaterialAlertDialogBuilder(ctx)
            .setTitle("Il tuo nome nel messaggio")
            .setView(padded(input))
            .setPositiveButton("Salva") { _, _ -> Prefs.setSosName(ctx, input.text.toString()); refresh() }
            .setNegativeButton("Annulla", null)
            .show()
    }

    private fun editContacts() {
        val input = EditText(ctx).apply {
            inputType = InputType.TYPE_CLASS_PHONE
            setText(Prefs.sosContacts(ctx).joinToString(", "))
            hint = "Es. 3331234567, 3479876543"
        }
        MaterialAlertDialogBuilder(ctx)
            .setTitle("Numeri a cui mandare l'SMS di aiuto")
            .setMessage("Uno o più numeri separati da virgola. Avvisa queste persone che potrebbero ricevere un SMS SOS.")
            .setView(padded(input))
            .setPositiveButton("Salva") { _, _ ->
                Prefs.setSosContacts(ctx, input.text.toString().split(',', ';').map { it.trim() }.filter { it.isNotEmpty() })
                refresh()
            }
            .setNegativeButton("Annulla", null)
            .show()
    }

    private fun padded(v: View) = LinearLayout(ctx).apply {
        setPadding(px(20), px(8), px(20), 0)
        add(v)
    }

    private fun testSms() {
        val a = act() ?: return
        if (Prefs.sosContacts(ctx).isEmpty()) {
            editContacts(); return
        }
        a.requestPerms(arrayOf(Manifest.permission.SEND_SMS)) { ok ->
            if (ok) SosPocketService.testSms(ctx) else Toast.makeText(ctx, "Permesso SMS negato", Toast.LENGTH_LONG).show()
        }
    }

    private fun startPocket() {
        if (!SosPocketService.btPermissionOk(ctx)) {
            act()?.requestPerms(btPerms()) { if (it) SosPocketService.start(ctx) }
            return
        }
        SosPocketService.start(ctx)
        Toast.makeText(ctx, "Ricezione SOS attiva (notifica fissa)", Toast.LENGTH_SHORT).show()
    }

    private fun openBatterySettings() {
        try {
            ctx.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + ctx.packageName)))
        } catch (_: Exception) {
        }
    }

    private fun refreshSosStatus() {
        val s = RideRepository.sos.value
        val role = Prefs.sosRole(ctx)
        sosStatus.text = when (role) {
            Prefs.SOS_ROLE_BIKE -> when {
                Prefs.sosPeer(ctx) == null -> "Scegli il telefono in tasca."
                s.connected -> "✔ Collegato a ${Prefs.sosPeerName(ctx)}"
                s.enabled -> "… ${s.message ?: "collegamento"}"
                else -> "Si collega a ${Prefs.sosPeerName(ctx)} quando avvii la registrazione."
            }
            Prefs.SOS_ROLE_POCKET -> {
                val c = Prefs.sosContacts(ctx).size
                (if (s.connected) "✔ Collegato al telefono sul manubrio" else (s.message ?: "In attesa del telefono sul manubrio")) +
                    "\nContatti SMS: " + (if (c == 0) "NESSUNO (aggiungili!)" else "$c") +
                    (if (!SosPocketService.canSendSms(ctx)) "\n⚠ Permesso SMS mancante" else "")
            }
            else -> ""
        }
        sosStatus.visibility = if (role == Prefs.SOS_ROLE_OFF) View.GONE else View.VISIBLE
    }

    // ------------------------------------------------------------ sensori

    private fun buildSensors() {
        box.add(header("Sensori Bluetooth"))
        box.add(note("Fascia cardio, sensore di cadenza e misuratore di potenza Bluetooth (non ANT+). Si collegano da soli " +
            "durante la registrazione; i valori compaiono nella pagina Dati e vengono salvati nella traccia (Strava li legge)."))
        sensorsStatus = note()
        box.add(sensorsStatus)
        box.add(button("Cerca sensori", false) { scanSensors() })
        maxHrBtn = button("", true) { editMaxHr() }
        box.add(maxHrBtn)
        box.add(button("Rimuovi i sensori", true) {
            for (k in BleParse.Kind.values()) Prefs.setSensor(ctx, k, null, null)
            refresh()
        })
    }

    private fun scanSensors() {
        val a = act() ?: return
        a.requestPerms(BleSensors.permissions()) { ok ->
            if (!ok) {
                Toast.makeText(ctx, "Permesso Bluetooth negato", Toast.LENGTH_LONG).show(); return@requestPerms
            }
            val found = ArrayList<Triple<String, String, BleParse.Kind>>()
            val labels = ArrayList<String>()
            val adapterList = android.widget.ArrayAdapter(ctx, android.R.layout.simple_list_item_1, labels)
            val dialog = MaterialAlertDialogBuilder(ctx)
                .setTitle("Ricerca sensori… (sveglia il sensore: indossa la fascia, gira la pedivella)")
                .setAdapter(adapterList) { _, w ->
                    val (name, addr, kind) = found[w]
                    Prefs.setSensor(ctx, kind, addr, name)
                    Toast.makeText(ctx, "${kind.label}: $name", Toast.LENGTH_SHORT).show()
                    refresh()
                }
                .setNegativeButton("Chiudi") { _, _ -> BleSensors.stopScan(ctx) }
                .show()
            val started = BleSensors.scan(ctx, 20, { name, addr, kind ->
                found.add(Triple(name, addr, kind))
                labels.add(kind.label + ": " + name)
                adapterList.notifyDataSetChanged()
            }) {
                if (found.isEmpty() && dialog.isShowing) dialog.setTitle("Nessun sensore trovato: è acceso e vicino?")
                else if (dialog.isShowing) dialog.setTitle("Tocca un sensore per usarlo")
            }
            if (!started) {
                dialog.dismiss()
                Toast.makeText(ctx, "Accendi il Bluetooth", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun editMaxHr() {
        val input = EditText(ctx).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            setText(Prefs.maxHr(ctx).toString())
        }
        MaterialAlertDialogBuilder(ctx)
            .setTitle("Frequenza cardiaca massima")
            .setMessage("Serve per le zone cardio (Z1-Z5). Se non la conosci, chiedi al medico sportivo; 220 meno l'età è solo una stima.")
            .setView(padded(input))
            .setPositiveButton("Salva") { _, _ ->
                input.text.toString().toIntOrNull()?.takeIf { it in 120..230 }?.let { Prefs.setMaxHr(ctx, it) }
                refresh()
            }
            .setNegativeButton("Annulla", null)
            .show()
    }

    private fun refreshSensors() {
        val v = RideRepository.sensors.value
        val lines = BleParse.Kind.values().map { k ->
            val name = Prefs.sensorName(ctx, k)
            if (name == null) "${k.label}: nessuno"
            else "${k.label}: $name" + (v.status[k.id]?.let { " ($it)" } ?: "")
        }
        sensorsStatus.text = lines.joinToString("\n")
    }

    // ------------------------------------------------------------ avvisi

    private fun buildAlerts() {
        box.add(header("Avvisi durante l'uscita"))
        box.add(switch("Avvisa se non rientri prima del buio (calcola tramonto e tempo che ti serve)", Prefs.sunsetAlert(ctx)) {
            Prefs.setSunsetAlert(ctx, it)
        })
        box.add(switch("Avvisi vocali dei punti utili (fontanelle, rifugi...) lungo il percorso", Prefs.poiVoice(ctx)) {
            Prefs.setPoiVoice(ctx, it)
        })
    }

    // ------------------------------------------------------------

    fun refresh() {
        val role = Prefs.sosRole(ctx)
        sosRoleBtn.text = roleLabel(role)
        sosBikeBox.visibility = if (role == Prefs.SOS_ROLE_BIKE) View.VISIBLE else View.GONE
        sosPocketBox.visibility = if (role == Prefs.SOS_ROLE_POCKET) View.VISIBLE else View.GONE
        peerBtn.text = "Telefono in tasca: " + (Prefs.sosPeerName(ctx) ?: "scegli…")
        sensBtn.text = "Sensibilità caduta: " + Prefs.sosSensitivity(ctx).label.substringBefore(" (")
        countBtn.text = "Conto alla rovescia: ${Prefs.sosCountdown(ctx)} secondi"
        nameBtn.text = "Nome nel messaggio: " + Prefs.sosName(ctx).ifBlank { "(non impostato)" }
        val c = Prefs.sosContacts(ctx)
        contactsBtn.text = "Contatti SMS: " + if (c.isEmpty()) "aggiungi…" else c.joinToString(", ")
        maxHrBtn.text = "Frequenza cardiaca massima: ${Prefs.maxHr(ctx)} bpm"
        refreshSosStatus()
        refreshSensors()
    }
}
