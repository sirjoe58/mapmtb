package it.sirjoe.training.ui

import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import it.sirjoe.training.data.Coach
import it.sirjoe.training.data.Prefs
import it.sirjoe.training.data.Zones
import it.sirjoe.training.sensors.Sensors
import it.sirjoe.training.service.RideService
import it.sirjoe.training.service.Snapshot
import java.util.Locale

/**
 * Pagina "In bici": battito grande colorato con la zona, cadenza, velocità, tempo e distanza,
 * fase del programma e ultimo consiglio. Leggibile al sole sul manubrio.
 */
class RidePage(act: MainActivity) : Page(act) {

    private val ctx = act
    private val root = Ui.vbox(ctx, 10)
    override val view: View = root

    // fase del programma
    private val stepBox = Ui.card(ctx, Ui.CARD)
    private val stepTitle = Ui.text(ctx, "", 12f, Ui.MUTED, true).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END }
    private val stepLabel = Ui.text(ctx, "", 17f, Ui.TEXT, true).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END }
    private val stepTime = Ui.big(ctx, 26f).apply { gravity = Gravity.END }
    private val stepTarget = Ui.text(ctx, "", 13f, Ui.TEXT).apply { maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END }

    // battito
    private val hrBox = Ui.vbox(ctx, 8)
    private val hrLabel = Ui.text(ctx, "BATTITO", 12f, Color.WHITE, true).apply { gravity = Gravity.CENTER; maxLines = 1 }
    private val hrValue = Ui.big(ctx, 110f)
    private val hrZone = Ui.text(ctx, "", 16f, Color.WHITE, true).apply { gravity = Gravity.CENTER; maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END }
    private val hrAdvice = Ui.text(ctx, "", 20f, Color.WHITE, true).apply { gravity = Gravity.CENTER; maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END }

    // altri dati
    private val cadValue = Ui.big(ctx, 80f)
    private val cadNote = Ui.text(ctx, "", 13f, Ui.MUTED).apply { gravity = Gravity.CENTER }
    private val cadBox = tile("CADENZA", cadValue, cadNote)
    private val speedValue = Ui.big(ctx, 80f)
    private val speedNote = Ui.text(ctx, "km/h", 13f, Ui.MUTED).apply { gravity = Gravity.CENTER }
    private val timeValue = Ui.big(ctx, 22f)
    private val timeNote = Ui.text(ctx, "", 13f, Ui.MUTED).apply { gravity = Gravity.CENTER }
    private val distValue = Ui.big(ctx, 22f)
    private val distNote = Ui.text(ctx, "", 13f, Ui.MUTED).apply { gravity = Gravity.CENTER }

    // consiglio e pulsanti
    private val cueText = Ui.text(ctx, "", 15f, Color.WHITE, true).apply {
        gravity = Gravity.CENTER
        maxLines = 2
        ellipsize = android.text.TextUtils.TruncateAt.END
        setPadding(Ui.dp(ctx, 10), Ui.dp(ctx, 8), Ui.dp(ctx, 10), Ui.dp(ctx, 8))
    }
    private val sensorsText = Ui.text(ctx, "", 12f, Ui.MUTED).apply { gravity = Gravity.CENTER }
    private val btnRow = Ui.hbox(ctx)
    private val btnStart: Button = Ui.button(ctx, "▶  Avvia", Ui.GREEN) { menuShownAt = System.currentTimeMillis(); act.startRide() }
    private val btnPause: Button = Ui.button(ctx, "⏸  Pausa", Ui.CARD2) {
        menuShownAt = System.currentTimeMillis()
        if (lastSnap.paused) RideService.resume(ctx) else RideService.pause(ctx)
    }
    private val btnSkip: Button = Ui.button(ctx, "⏭  Fase", Ui.CARD2) { menuShownAt = System.currentTimeMillis(); RideService.skip(ctx) }
    private val btnStop: Button = Ui.button(ctx, "■  Fine", Ui.RED) { menuShownAt = System.currentTimeMillis(); act.confirmStop() }

    // durante l'uscita pulsanti e menu si nascondono; un tocco sullo schermo li fa ricomparire per 8 secondi
    private val menuHint = Ui.text(ctx, "▲  tocca lo schermo per menu e pulsanti", 12f, Ui.MUTED).apply {
        gravity = Gravity.CENTER
        setPadding(0, Ui.dp(ctx, 6), 0, Ui.dp(ctx, 2))
    }
    private var menuShownAt = 0L
    private val menuShowMs = 8_000L

    private fun menuOpen() = !lastSnap.running || System.currentTimeMillis() - menuShownAt < menuShowMs

    private fun applyMenu() {
        val open = menuOpen()
        btnRow.visibility = if (open) View.VISIBLE else View.GONE
        menuHint.visibility = if (open) View.GONE else View.VISIBLE
        act.setMenuVisible(open)
    }

    private var lastSnap = Snapshot()
    private var flashedAt = 0L

    init {
        // fase
        val stepTop = Ui.hbox(ctx)
        val stepLeft = Ui.vbox(ctx)
        stepLeft.addView(stepTitle)
        stepLeft.addView(stepLabel)
        stepTop.addView(stepLeft, Ui.lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        stepTop.addView(stepTime)
        stepBox.addView(stepTop)
        stepBox.addView(stepTarget)
        root.addView(stepBox, Ui.lp())

        // battito: il riquadro più grande
        hrBox.gravity = Gravity.CENTER
        
        hrBox.addView(hrLabel, Ui.lp())
        Ui.autoFit(hrValue, 84f)
        hrBox.addView(hrValue, Ui.lp(h = 0, weight = 1f))
        hrBox.addView(hrZone, Ui.lp())
        hrBox.addView(hrAdvice, Ui.lp())
        root.addView(hrBox, Ui.lp(h = 0, weight = 1f).apply { topMargin = Ui.dp(ctx, 8) })

        val row1 = Ui.hbox(ctx)
        row1.addView(cadBox, Ui.lp(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
        row1.addView(tile("VELOCITÀ", speedValue, speedNote), Ui.lp(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f).apply { marginStart = Ui.dp(ctx, 8) })
        root.addView(row1, Ui.lp(h = 0, weight = 0.9f).apply { topMargin = Ui.dp(ctx, 8) })

        val row2 = Ui.hbox(ctx)
        row2.addView(strip("TEMPO", timeValue, timeNote), Ui.lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        row2.addView(strip("KM", distValue, distNote), Ui.lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = Ui.dp(ctx, 8) })
        root.addView(row2, Ui.lp().apply { topMargin = Ui.dp(ctx, 8) })

        root.addView(cueText, Ui.lp().apply { topMargin = Ui.dp(ctx, 8) })
        root.addView(sensorsText, Ui.lp().apply { topMargin = Ui.dp(ctx, 4) })

        for (b in listOf(btnStart, btnPause, btnSkip, btnStop)) {
            btnRow.addView(b, Ui.lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = Ui.dp(ctx, 4); marginEnd = Ui.dp(ctx, 4) })
        }
        root.addView(btnRow, Ui.lp().apply { topMargin = Ui.dp(ctx, 8) })
        root.addView(menuHint, Ui.lp())
        // tocco ovunque sulla pagina: mostra o nasconde menu e pulsanti
        root.setOnClickListener {
            if (!lastSnap.running) return@setOnClickListener
            menuShownAt = if (menuOpen()) 0L else System.currentTimeMillis()
            applyMenu()
        }
    }

    /** Riquadro basso su una riga: "TEMPO 12:05" (tempo e distanza, meno importanti). */
    private fun strip(label: String, value: TextView, note: TextView): LinearLayout {
        val t = Ui.hbox(ctx)
        t.background = Ui.rounded(Ui.CARD, Ui.dp(ctx, 10).toFloat())
        t.gravity = Gravity.CENTER
        t.setPadding(Ui.dp(ctx, 8), Ui.dp(ctx, 4), Ui.dp(ctx, 8), Ui.dp(ctx, 4))
        t.addView(Ui.text(ctx, label, 11f, Ui.MUTED, true).apply { maxLines = 1 })
        t.addView(value, Ui.lp(ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = Ui.dp(ctx, 8) })
        note.maxLines = 1
        note.textSize = 11f
        note.ellipsize = android.text.TextUtils.TruncateAt.END
        t.addView(note, Ui.lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = Ui.dp(ctx, 6) })
        return t
    }

    @Suppress("DEPRECATION")
    private fun tile(label: String, value: TextView, note: TextView): LinearLayout {
        val t = Ui.card(ctx)
        t.gravity = Gravity.CENTER
        val pad = Ui.dp(ctx, 6)
        t.setPadding(pad, pad, pad, pad)
        t.addView(Ui.text(ctx, label, 12f, Ui.MUTED, true).apply { gravity = Gravity.CENTER; maxLines = 1 }, Ui.lp())
        // il numero prende tutta l'altezza rimasta e si rimpicciolisce se non ci sta
        Ui.autoFit(value, value.textSize / ctx.resources.displayMetrics.scaledDensity)
        t.addView(value, Ui.lp(h = 0, weight = 1f))
        note.maxLines = 1
        note.ellipsize = android.text.TextUtils.TruncateAt.END
        t.addView(note, Ui.lp())
        return t
    }

    override fun onSnapshot(s: Snapshot) {
        lastSnap = s
        val maxHr = if (s.running) s.maxHr else Prefs.effectiveMaxHr(ctx)
        // valori dei sensori anche prima di partire (per controllare che arrivino)
        val hr = if (s.running) s.hr else Sensors.currentHr()
        val cad = if (s.running) s.cad else Sensors.currentCad()

        // ---- fase del programma
        val st = s.step
        if (s.running && s.workout != null) {
            stepBox.visibility = View.VISIBLE
            if (st != null) {
                stepTitle.text = "FASE ${s.stepIndex + 1}/${s.workout.steps.size}  ·  ${s.workout.name}"
                stepLabel.text = st.label
                stepTime.text = Coach.clock(s.stepRemaining)
                stepTarget.text = "Obiettivo: " + st.target(maxHr) +
                    (s.nextStep?.let { "\nPoi: ${it.label} (${it.seconds / 60} min)" } ?: "\nUltima fase")
            } else {
                stepTitle.text = s.workout.name
                stepLabel.text = "Programma completato ✓"
                stepTime.text = ""
                stepTarget.text = "Puoi continuare a pedalare o premere Fine per salvare."
            }
        } else if (s.running) {
            stepBox.visibility = View.VISIBLE
            stepTitle.text = "USCITA LIBERA"
            stepLabel.text = "Nessun programma"
            stepTime.text = ""
            stepTarget.text = "Le zone ti dicono quanto stai spingendo."
        } else {
            stepBox.visibility = View.VISIBLE
            stepTitle.text = "PRONTO"
            stepLabel.text = "Premi Avvia per partire"
            stepTime.text = ""
            stepTarget.text = "FC max usata: $maxHr" + if (Prefs.maxHr(ctx) == 0) " (stima: impostala in Impostazioni)" else ""
        }

        // ---- battito e zona
        if (hr != null) {
            val z = Zones.zoneOf(hr, maxHr)
            val zone = Zones.zone(z)
            hrBox.background = Ui.rounded(zone.color, Ui.dp(ctx, 14).toFloat())
            val fg = Ui.onColor(zone.color)
            hrValue.setTextColor(fg); hrZone.setTextColor(fg); hrAdvice.setTextColor(fg); hrLabel.setTextColor(fg)
            hrValue.text = hr.toString()
            hrZone.text = "ZONA $z · ${zone.name} · ${Zones.pct(hr, maxHr)}%"
            hrAdvice.text = when {
                hr >= Zones.dangerBpm(maxHr) -> "⚠ RALLENTA SUBITO"
                s.running && s.step != null -> when (s.hrStatus) {
                    Coach.Status.HIGH -> "▼ RALLENTA"
                    Coach.Status.LOW -> "▲ SPINGI DI PIÙ"
                    Coach.Status.OK -> "✓ ZONA GIUSTA"
                    Coach.Status.NONE -> zone.feel
                }
                else -> zone.feel
            }
        } else {
            hrBox.background = Ui.rounded(Ui.CARD, Ui.dp(ctx, 14).toFloat())
            hrValue.setTextColor(Ui.MUTED); hrZone.setTextColor(Ui.MUTED); hrAdvice.setTextColor(Ui.MUTED); hrLabel.setTextColor(Ui.MUTED)
            hrValue.text = "--"
            hrZone.text = if (Sensors.configured(ctx, Prefs.HR)) "in attesa della fascia…" else "fascia non impostata"
            hrAdvice.text = ""
        }

        // ---- cadenza
        cadValue.text = cad?.toString() ?: "--"
        val stp = s.step
        cadNote.text = when {
            cad == null -> if (Sensors.configured(ctx, Prefs.CAD)) "in attesa…" else "sensore non impostato"
            s.running && stp != null && stp.hasCad -> when (s.cadStatus) {
                Coach.Status.LOW -> "▲ più agile (${stp.cadLo}–${stp.cadHi})"
                Coach.Status.HIGH -> "▼ più duro (${stp.cadLo}–${stp.cadHi})"
                else -> "obiettivo ${stp.cadLo}–${stp.cadHi}"
            }
            else -> "giri/min"
        }
        cadBox.background = Ui.rounded(
            when (s.cadStatus) {
                Coach.Status.LOW, Coach.Status.HIGH -> 0xFF8D3B00.toInt()
                else -> Ui.CARD
            }, Ui.dp(ctx, 12).toFloat()
        )

        // ---- velocità, tempo, distanza
        speedValue.text = s.kmh?.let { String.format(Locale.ITALY, "%.1f", it) } ?: "--"
        speedNote.text = if (s.running && !s.gpsOk) "km/h · GPS in attesa" else "km/h"
        timeValue.text = Coach.clock(s.seconds)
        timeNote.text = if (s.paused) "IN PAUSA" else ""
        distValue.text = String.format(Locale.ITALY, "%.2f", s.distanceM / 1000)
        distNote.text = if (s.running && s.seconds > 60) String.format(Locale.ITALY, "media %.1f km/h", s.avgKmh) else ""

        // ---- ultimo consiglio (visibile 15 secondi) e lampeggio colorato
        val cue = s.lastCue
        val age = System.currentTimeMillis() - s.lastCueAt
        if (s.running && cue != null && age < 15_000) {
            cueText.text = cue.text
            cueText.background = Ui.rounded(cueColor(cue.kind), Ui.dp(ctx, 10).toFloat())
            cueText.visibility = View.VISIBLE
            if (s.lastCueAt != flashedAt && Prefs.flash(ctx) && cue.kind != Coach.Kind.INFO) {
                flashedAt = s.lastCueAt
                flash(cueColor(cue.kind))
            }
        } else {
            cueText.visibility = View.GONE
        }

        val hs = Sensors.status[Prefs.HR]
        val cs = Sensors.status[Prefs.CAD]
        // stato dei sensori solo se manca un dato (così non ruba spazio ai numeri)
        sensorsText.text = if (s.running && (hr == null || cad == null)) "Fascia: ${hs ?: "—"}  ·  Cadenza: ${cs ?: "—"}" else ""
        sensorsText.visibility = if (sensorsText.text.isEmpty()) View.GONE else View.VISIBLE

        // ---- pulsanti
        btnStart.visibility = if (s.running) View.GONE else View.VISIBLE
        btnPause.visibility = if (s.running) View.VISIBLE else View.GONE
        btnPause.text = if (s.paused) "▶  Riprendi" else "⏸  Pausa"
        btnSkip.visibility = if (s.running && s.workout != null && !s.finished) View.VISIBLE else View.GONE
        btnStop.visibility = if (s.running) View.VISIBLE else View.GONE
        // un tocco sui pulsanti tiene aperto il menu
        applyMenu()
    }

    private fun cueColor(k: Coach.Kind) = when (k) {
        Coach.Kind.UP -> Ui.BLUE
        Coach.Kind.DOWN -> Ui.ORANGE
        Coach.Kind.WARN -> Ui.RED
        Coach.Kind.STEP, Coach.Kind.DONE -> 0xFF6A1B9A.toInt()
        Coach.Kind.INFO -> Ui.GREEN
    }

    /** Lo sfondo lampeggia tre volte del colore dell'avviso. */
    private fun flash(color: Int) {
        for (i in 0 until 6) {
            root.postDelayed({ root.setBackgroundColor(if (i % 2 == 0) color else Ui.BG) }, i * 250L)
        }
        root.postDelayed({ root.setBackgroundColor(Ui.BG) }, 1_600)
    }

    private var poll: Runnable? = null

    override fun onShow() {
        // collega già i sensori, così prima di partire vedi se battito e cadenza arrivano
        if (!lastSnap.running && (Sensors.configured(ctx, Prefs.HR) || Sensors.configured(ctx, Prefs.CAD))) {
            act.withPermissions(act.allPerms()) { Sensors.start(ctx) }
        }
        // prima di partire aggiorna i valori dei sensori ogni secondo (se collegati)
        val r = object : Runnable {
            override fun run() {
                if (!lastSnap.running) onSnapshot(lastSnap)
                root.postDelayed(this, 1_000)
            }
        }
        poll?.let { root.removeCallbacks(it) }
        poll = r
        root.post(r)
    }
}
