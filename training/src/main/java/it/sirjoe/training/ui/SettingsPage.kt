package it.sirjoe.training.ui

import android.view.View
import android.widget.CompoundButton
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.SwitchCompat
import it.sirjoe.training.BuildConfig
import it.sirjoe.training.data.Prefs
import it.sirjoe.training.data.Zones
import it.sirjoe.training.sensors.AntSensors
import it.sirjoe.training.sensors.BleSensors
import it.sirjoe.training.sensors.Sensors
import it.sirjoe.training.service.RideState

/** Pagina "Impostazioni": profilo (FC max), sensori ANT+/Bluetooth, avvisi, informazioni. */
class SettingsPage(act: MainActivity) : Page(act) {

    private val ctx = act
    private val col = Ui.vbox(ctx, 12)
    override val view: View = ScrollView(ctx).apply { addView(col) }

    private val profileText = Ui.text(ctx, "", 15f)
    private val hrText = Ui.text(ctx, "", 15f)
    private val cadText = Ui.text(ctx, "", 15f)
    private val antText = Ui.text(ctx, "", 13f, Ui.MUTED)

    init {
        // ---- profilo
        col.addView(Ui.title(ctx, "Profilo"))
        val p = Ui.card(ctx)
        p.addView(profileText)
        Ui.add(p, Ui.button(ctx, "Imposta età e FC max") { profileDialog() }, 10)
        p.addView(Ui.text(ctx, "\nLa FC max migliore è quella misurata (test da sforzo, visita medico-sportiva o il valore più alto visto col Garmin). " +
            "Se non la conosci l'app la stima dall'età, ma la stima può sbagliare anche di 10-15 battiti.", 13f, Ui.MUTED))
        col.addView(p, Ui.lp())

        // ---- sensori
        col.addView(Ui.title(ctx, "Sensori"))
        col.addView(sensorCard("❤ Fascia cardio", Prefs.HR, hrText), Ui.lp())
        Ui.add(col, sensorCard("⟳ Sensore di cadenza", Prefs.CAD, cadText), 8)
        val ant = Ui.card(ctx)
        ant.addView(Ui.text(ctx, "ANT+ sul telefono", 16f, Ui.TEXT, true))
        ant.addView(antText)
        Ui.add(ant, Ui.button(ctx, "Installa «ANT+ Plugins Service»") { AntSensors.openStore(ctx, AntSensors.PLUGIN_PKG) }, 8)
        Ui.add(ant, Ui.button(ctx, "Installa «ANT Radio Service»") { AntSensors.openStore(ctx, AntSensors.RADIO_PKG) }, 6)
        Ui.add(ant, Ui.button(ctx, "Chiavetta USB: «ANT USB Service»") { AntSensors.openStore(ctx, AntSensors.USB_PKG) }, 6)
        Ui.add(col, ant, 8)

        // ---- avvisi
        col.addView(Ui.title(ctx, "Avvisi durante l'allenamento"))
        val a = Ui.card(ctx)
        a.addView(switch("Voce in italiano", Prefs.voice(ctx)) { Prefs.putBool(ctx, "cue_voice", it) })
        a.addView(switch("Bip (due note che salgono = spingi, che scendono = rallenta)", Prefs.beep(ctx)) { Prefs.putBool(ctx, "cue_beep", it) })
        a.addView(switch("Vibrazione (2 brevi = spingi, 1 lunga = rallenta)", Prefs.vibrate(ctx)) { Prefs.putBool(ctx, "cue_vibrate", it) })
        a.addView(switch("Schermo che lampeggia del colore dell'avviso", Prefs.flash(ctx)) { Prefs.putBool(ctx, "cue_flash", it) })
        val freeBtn = Ui.button(ctx, freeLabel()) {}
        freeBtn.setOnClickListener {
            val opts = arrayOf("Mai", "Sopra la zona 2", "Sopra la zona 3", "Sopra la zona 4")
            val vals = intArrayOf(0, 2, 3, 4)
            AlertDialog.Builder(ctx)
                .setTitle("Uscita libera: avvisami quando…")
                .setSingleChoiceItems(opts, vals.indexOf(Prefs.freeMaxZone(ctx)).coerceAtLeast(0)) { d, w ->
                    Prefs.putInt(ctx, "free_max_zone", vals[w])
                    freeBtn.text = freeLabel()
                    d.dismiss()
                }
                .show()
        }
        Ui.add(a, freeBtn, 8)
        a.addView(Ui.text(ctx, "Sopra il 95% della FC max l'app avvisa sempre di rallentare.", 13f, Ui.MUTED))
        col.addView(a, Ui.lp())

        // ---- info
        col.addView(Ui.title(ctx, "Informazioni"))
        val i = Ui.card(ctx)
        i.addView(Ui.text(ctx, "SirJoe Training ${BuildConfig.VERSION_NAME}  ·  BY SIRJOE", 15f, Ui.TEXT, true))
        i.addView(Ui.text(ctx, "Uso personale. Non è un dispositivo medico.\nANT+ Android SDK © Garmin Canada Inc. (ANT+ Shared Source License).", 13f, Ui.MUTED))
        Ui.add(i, Ui.button(ctx, "Avvertenze d'uso") { act.showDisclaimer() }, 8)
        col.addView(i, Ui.lp())
    }

    private fun profileDialog() {
        val box = Ui.vbox(ctx, 16)
        val age = Ui.numberInput(ctx, Prefs.age(ctx), "Età (anni)")
        val max = Ui.numberInput(ctx, Prefs.maxHr(ctx), "FC max (vuoto = stima dall'età)")
        val hint = Ui.text(ctx, "", 13f, Ui.MUTED)
        fun refreshHint() {
            val a = age.text.toString().toIntOrNull()
            hint.text = if (a != null && a in 10..100) "Stima dall'età: ${Zones.estimateMax(a)} battiti" else ""
        }
        age.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {}
            override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) = refreshHint()
        })
        refreshHint()
        box.addView(Ui.text(ctx, "Età", 13f, Ui.MUTED))
        box.addView(age)
        Ui.add(box, Ui.text(ctx, "Frequenza cardiaca massima", 13f, Ui.MUTED), 10)
        box.addView(max)
        box.addView(hint)
        val dlg = AlertDialog.Builder(ctx)
            .setTitle("Profilo")
            .setView(box)
            .setPositiveButton("Salva") { _, _ ->
                val a = age.text.toString().toIntOrNull() ?: 0
                val m = max.text.toString().toIntOrNull() ?: 0
                Prefs.setAge(ctx, if (a in 10..100) a else 0)
                Prefs.setMaxHr(ctx, if (m in 100..230) m else 0)
                if (m != 0 && m !in 100..230) act.toast("FC max non valida (100-230): uso la stima")
                onShow()
            }
            .setNegativeButton("Annulla", null)
            .create()
        dlg.show()
    }

    private fun freeLabel(): String {
        val z = Prefs.freeMaxZone(ctx)
        return "Uscita libera: " + if (z == 0) "nessun limite di zona" else "avvisa sopra la zona $z"
    }

    private fun switch(label: String, on: Boolean, set: (Boolean) -> Unit): View = SwitchCompat(ctx).apply {
        text = label
        textSize = 15f
        setTextColor(Ui.TEXT)
        isChecked = on
        setPadding(0, Ui.dp(ctx, 8), 0, Ui.dp(ctx, 8))
        setOnCheckedChangeListener { _: CompoundButton, v: Boolean -> set(v) }
    }

    private fun sensorCard(title: String, kind: String, state: TextView): View {
        val c = Ui.card(ctx)
        c.addView(Ui.text(ctx, title, 17f, Ui.TEXT, true))
        c.addView(state)
        val r = Ui.hbox(ctx)
        Ui.add(r, Ui.button(ctx, "Cerca ANT+", Ui.BLUE) { searchAnt(kind) }, 0, weight = 1f)
        Ui.add(r, Ui.button(ctx, "Cerca Bluetooth", Ui.BLUE) { searchBle(kind) }, 6, weight = 1f)
        Ui.add(c, r, 8)
        Ui.add(c, Ui.button(ctx, "Rimuovi") {
            Prefs.setSensor(ctx, kind, null)
            Prefs.setSensorName(ctx, kind, null)
            restartSensors()
            onShow()
        }, 6)
        return c
    }

    override fun onShow() {
        val maxHr = Prefs.effectiveMaxHr(ctx)
        val age = Prefs.age(ctx)
        profileText.text = (if (age > 0) "Età: $age anni\n" else "Età: non impostata\n") +
            "FC max: $maxHr" + (if (Prefs.maxHr(ctx) == 0) " (stima)" else " (impostata)") +
            "\nZona 2 (fondo lento): ${Zones.bpmLo(2, maxHr)}–${Zones.bpmHi(2, maxHr)} battiti"
        hrText.text = sensorText(Prefs.HR)
        cadText.text = sensorText(Prefs.CAD)
        val plugin = AntSensors.installed(ctx, AntSensors.PLUGIN_PKG)
        val radio = AntSensors.installed(ctx, AntSensors.RADIO_PKG)
        antText.text = "Per la fascia Garmin ANT+ servono due app gratuite di Garmin dal Play Store.\n" +
            "ANT+ Plugins Service: " + (if (plugin) "✔ installata" else "✖ manca") + "\n" +
            "ANT Radio Service: " + (if (radio) "✔ installata" else "✖ manca") + "\n" +
            "Se il telefono non ha il chip ANT+ funziona con una chiavetta ANT+ USB e un adattatore OTG (più l'app ANT USB Service)."
    }

    private fun sensorText(kind: String): String {
        val v = Prefs.sensor(ctx, kind) ?: return "Non impostato"
        val type = when {
            Sensors.isAnt(v) -> "ANT+"
            Sensors.isBle(v) -> "Bluetooth"
            else -> "?"
        }
        val name = Prefs.sensorName(ctx, kind) ?: v
        val st = Sensors.status[kind]?.let { "\nStato: $it" } ?: ""
        return "$name · $type$st"
    }

    private fun save(kind: String, value: String, name: String) {
        Prefs.setSensor(ctx, kind, value)
        Prefs.setSensorName(ctx, kind, name)
        act.toast("Salvato: $name")
        restartSensors()
        onShow()
    }

    private fun restartSensors() {
        if (Sensors.started) Sensors.restart(ctx)
        else if (!RideState.now.running) Sensors.start(ctx)
    }

    private fun searchAnt(kind: String) {
        if (!AntSensors.installed(ctx, AntSensors.PLUGIN_PKG)) {
            AlertDialog.Builder(ctx)
                .setTitle("Manca un'app di Garmin")
                .setMessage("Per i sensori ANT+ installa dal Play Store «ANT+ Plugins Service» (gratuita, di Garmin). Poi torna qui e premi di nuovo Cerca ANT+.")
                .setPositiveButton("Apri Play Store") { _, _ -> AntSensors.openStore(ctx, AntSensors.PLUGIN_PKG) }
                .setNegativeButton("Annulla", null)
                .show()
            return
        }
        // libera il canale ANT+ (se i sensori sono già collegati) prima di cercare
        Sensors.stop()
        act.toast(if (kind == Prefs.HR) "Indossa la fascia (inumidisci gli elettrodi)" else "Gira la pedivella per svegliare il sensore")
        AntSensors.search(act, kind) { value, msg ->
            if (value != null) save(kind, value, msg)
            else {
                act.toast("ANT+: $msg")
                onShow()
            }
        }
    }

    private fun searchBle(kind: String) {
        act.withPermissions(BleSensors.permissions().toList()) {
            if (!BleSensors.hasPermissions(ctx)) return@withPermissions act.toast("Serve il permesso Bluetooth")
            val names = ArrayList<String>()
            val addrs = ArrayList<String>()
            val adapter = android.widget.ArrayAdapter(ctx, android.R.layout.simple_list_item_1, names)
            val dlg = AlertDialog.Builder(ctx)
                .setTitle(if (kind == Prefs.HR) "Ricerca fascia Bluetooth…" else "Ricerca cadenza Bluetooth…")
                .setAdapter(adapter) { _, w ->
                    BleSensors.stopScan(ctx)
                    save(kind, "ble:" + addrs[w], names[w])
                }
                .setNegativeButton("Annulla") { _, _ -> BleSensors.stopScan(ctx) }
                .create()
            val ok = BleSensors.scan(ctx, kind, 20, { name, addr ->
                names += name; addrs += addr
                adapter.notifyDataSetChanged()
            }, {
                if (dlg.isShowing && names.isEmpty()) {
                    dlg.setTitle("Nessun sensore trovato")
                }
            })
            if (!ok) {
                act.toast("Accendi il Bluetooth")
                return@withPermissions
            }
            act.toast(if (kind == Prefs.HR) "Indossa la fascia" else "Gira la pedivella per svegliare il sensore")
            dlg.show()
        }
    }
}
