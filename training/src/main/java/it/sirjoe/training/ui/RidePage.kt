package it.sirjoe.training.ui

import android.graphics.Color
import android.text.TextUtils
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
import kotlin.math.roundToInt

/**
 * Pagina "In bici". Battito, cadenza e velocità occupano quasi tutto lo schermo e i loro numeri
 * si adattano al riquadro (mai tagliati). Sotto, in piccolo: tempo, km e calorie.
 * Durante l'uscita menu, pulsanti e scritte secondarie si nascondono: un tocco li mostra per 8 secondi.
 */
class RidePage(act: MainActivity) : Page(act) {

    private val ctx = act
    private val root = Ui.vbox(ctx, 8)
    override val view: View = root

    // ---- orologio, sempre visibile sopra al battito (su fondo scuro, non cambia col colore della zona)
    private val clock = Ui.big(ctx, 34f)
    private val clockFmt = java.text.SimpleDateFormat("HH:mm", Locale.ITALY)

    // ---- striscia in alto: fase del programma oppure ultimo avviso (una o due righe)
    private val topLine = Ui.text(ctx, "", 15f, Ui.TEXT, true).apply {
        maxLines = 2
        ellipsize = TextUtils.TruncateAt.END
        gravity = Gravity.CENTER_VERTICAL
        setPadding(Ui.dp(ctx, 10), Ui.dp(ctx, 6), Ui.dp(ctx, 10), Ui.dp(ctx, 6))
    }

    // ---- battito
    private val hrBox = Ui.vbox(ctx)
    private val hrLabel = small("BATTITO", Color.WHITE)
    private val hrValue = Ui.big(ctx, 200f)
    private val hrZone = Ui.text(ctx, "", 17f, Color.WHITE, true).apply { oneLine(this) }

    // ---- cadenza e velocità
    private val cadValue = Ui.big(ctx, 160f)
    private val cadNote = small("giri/min", Ui.MUTED)
    private val cadBox = tile("CADENZA", cadValue, cadNote)
    private val speedValue = Ui.big(ctx, 160f)
    private val speedNote = small("km/h", Ui.MUTED)
    private val speedBox = tile("VELOCITÀ", speedValue, speedNote)

    // ---- tempo, km, calorie
    private val timeValue = Ui.big(ctx, 60f)
    private val distValue = Ui.big(ctx, 60f)
    private val kcalValue = Ui.big(ctx, 60f)
    private val timeLabel = small("TEMPO", Ui.MUTED)

    // ---- stato sensori, pulsanti, suggerimento
    private val sensorsText = small("", Ui.MUTED)
    private val btnRow = Ui.hbox(ctx)
    private val btnStart: Button = Ui.button(ctx, "▶  Avvia", Ui.GREEN) { keepOpen(); act.startRide() }
    private val btnPause: Button = Ui.button(ctx, "⏸  Pausa", Ui.CARD2) {
        keepOpen()
        if (lastSnap.paused) RideService.resume(ctx) else RideService.pause(ctx)
    }
    private val btnSkip: Button = Ui.button(ctx, "⏭  Fase", Ui.CARD2) { keepOpen(); RideService.skip(ctx) }
    private val btnStop: Button = Ui.button(ctx, "■  Fine", Ui.RED) { keepOpen(); act.confirmStop() }
    private val btnExit: Button = Ui.button(ctx, "✕  Esci", Ui.CARD2) { keepOpen(); act.confirmExit() }
    private val menuHint = small("▲  tocca lo schermo per menu e pulsanti", Ui.MUTED).apply {
        setPadding(0, Ui.dp(ctx, 4), 0, 0)
    }

    private var lastSnap = Snapshot()
    private var flashedAt = 0L
    private var menuShownAt = 0L
    private val menuShowMs = 8_000L

    init {
        root.addView(clock, Ui.lp())
        root.addView(topLine, Ui.lp().apply { topMargin = Ui.dp(ctx, 4) })

        hrBox.gravity = Gravity.CENTER
        hrBox.setPadding(Ui.dp(ctx, 6), Ui.dp(ctx, 4), Ui.dp(ctx, 6), Ui.dp(ctx, 6))
        hrBox.addView(hrLabel, Ui.lp())
        Ui.autoFit(hrValue, 200f)
        hrBox.addView(hrValue, Ui.lp(h = 0, weight = 1f))
        hrBox.addView(hrZone, Ui.lp())
        root.addView(hrBox, Ui.lp(h = 0, weight = 1.15f).apply { topMargin = Ui.dp(ctx, 6) })

        val row1 = Ui.hbox(ctx)
        row1.addView(cadBox, Ui.lp(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
        row1.addView(speedBox, Ui.lp(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f).apply { marginStart = Ui.dp(ctx, 6) })
        root.addView(row1, Ui.lp(h = 0, weight = 1f).apply { topMargin = Ui.dp(ctx, 6) })

        val row2 = Ui.hbox(ctx)
        row2.addView(tile(null, timeValue, timeLabel, 60f), Ui.lp(0, ViewGroup.LayoutParams.MATCH_PARENT, 1.15f))
        row2.addView(tile(null, distValue, small("KM", Ui.MUTED), 60f), Ui.lp(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f).apply { marginStart = Ui.dp(ctx, 6) })
        row2.addView(tile(null, kcalValue, small("KCAL", Ui.MUTED), 60f), Ui.lp(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f).apply { marginStart = Ui.dp(ctx, 6) })
        root.addView(row2, Ui.lp(h = 0, weight = 0.38f).apply { topMargin = Ui.dp(ctx, 6) })

        root.addView(sensorsText, Ui.lp().apply { topMargin = Ui.dp(ctx, 4) })
        for (b in listOf(btnStart, btnPause, btnSkip, btnStop, btnExit)) {
            // fino a 4 pulsanti in una riga: testo su una riga sola anche su schermi stretti
            b.textSize = 14f
            b.maxLines = 1
            b.setPadding(Ui.dp(ctx, 4), 0, Ui.dp(ctx, 4), 0)
            btnRow.addView(b, Ui.lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = Ui.dp(ctx, 3); marginEnd = Ui.dp(ctx, 3) })
        }
        root.addView(btnRow, Ui.lp().apply { topMargin = Ui.dp(ctx, 6) })
        root.addView(menuHint, Ui.lp())

        // un tocco ovunque: mostra o nasconde menu e pulsanti
        root.setOnClickListener {
            if (!lastSnap.running) return@setOnClickListener
            menuShownAt = if (menuOpen()) 0L else System.currentTimeMillis()
            onSnapshot(lastSnap)
        }
    }

    private fun oneLine(t: TextView) {
        t.maxLines = 1
        t.ellipsize = TextUtils.TruncateAt.END
        t.gravity = Gravity.CENTER
    }

    private fun small(s: String, color: Int) = Ui.text(ctx, s, 12f, color, true).apply { oneLine(this) }

    /** Riquadro: etichetta (facoltativa), numero che riempie lo spazio e nota sotto. */
    private fun tile(label: String?, value: TextView, note: TextView, max: Float = 160f): LinearLayout {
        val t = Ui.card(ctx)
        t.gravity = Gravity.CENTER
        val pad = Ui.dp(ctx, 4)
        t.setPadding(pad, pad, pad, pad)
        if (label != null) t.addView(small(label, Ui.MUTED), Ui.lp())
        Ui.autoFit(value, max)
        t.addView(value, Ui.lp(h = 0, weight = 1f))
        t.addView(note, Ui.lp())
        return t
    }

    private fun keepOpen() {
        menuShownAt = System.currentTimeMillis()
    }

    private fun menuOpen() = !lastSnap.running || System.currentTimeMillis() - menuShownAt < menuShowMs

    override fun onSnapshot(s: Snapshot) {
        lastSnap = s
        val open = menuOpen()
        clock.text = clockFmt.format(java.util.Date())
        val maxHr = if (s.running) s.maxHr else Prefs.effectiveMaxHr(ctx)
        // valori dei sensori anche prima di partire (per controllare che arrivino)
        val hr = if (s.running) s.hr else Sensors.currentHr()
        val cad = if (s.running) s.cad else Sensors.currentCad()

        // ---- striscia in alto: avviso recente (15 s), poi la fase; in uscita libera niente
        val cue = s.lastCue
        val cueAge = System.currentTimeMillis() - s.lastCueAt
        val st = s.step
        when {
            s.running && cue != null && cueAge < 15_000 -> {
                topLine.text = cue.text
                topLine.background = Ui.rounded(cueColor(cue.kind), Ui.dp(ctx, 10).toFloat())
                topLine.setTextColor(Color.WHITE)
                topLine.visibility = View.VISIBLE
                if (s.lastCueAt != flashedAt && Prefs.flash(ctx) && cue.kind != Coach.Kind.INFO) {
                    flashedAt = s.lastCueAt
                    flash(cueColor(cue.kind))
                }
            }
            s.running && s.workout != null && st != null -> {
                topLine.text = "${st.label} · ${Coach.clock(s.stepRemaining)} · ${st.target(maxHr)}"
                topLine.background = Ui.rounded(Ui.CARD, Ui.dp(ctx, 10).toFloat())
                topLine.setTextColor(Ui.TEXT)
                topLine.visibility = View.VISIBLE
            }
            s.running && s.workout != null -> {
                topLine.text = "Programma completato ✓ · premi Fine per salvare"
                topLine.background = Ui.rounded(Ui.CARD, Ui.dp(ctx, 10).toFloat())
                topLine.setTextColor(Ui.TEXT)
                topLine.visibility = View.VISIBLE
            }
            s.running -> topLine.visibility = View.GONE
            else -> {
                topLine.text = "Pronto · FC max $maxHr" + if (Prefs.maxHr(ctx) == 0) " (stima)" else ""
                topLine.background = Ui.rounded(Ui.CARD, Ui.dp(ctx, 10).toFloat())
                topLine.setTextColor(Ui.MUTED)
                topLine.visibility = View.VISIBLE
            }
        }

        // ---- battito e zona
        if (hr != null) {
            val z = Zones.zoneOf(hr, maxHr)
            val zone = Zones.zone(z)
            hrBox.background = Ui.rounded(zone.color, Ui.dp(ctx, 14).toFloat())
            val fg = Ui.onColor(zone.color)
            hrValue.setTextColor(fg); hrZone.setTextColor(fg); hrLabel.setTextColor(fg)
            hrValue.text = hr.toString()
            val advice = when {
                hr >= Zones.dangerBpm(maxHr) -> "⚠ RALLENTA SUBITO"
                s.running && st != null && s.hrStatus == Coach.Status.HIGH -> "▼ RALLENTA"
                s.running && st != null && s.hrStatus == Coach.Status.LOW -> "▲ SPINGI"
                s.running && st != null && s.hrStatus == Coach.Status.OK -> "✓ GIUSTO"
                else -> zone.name.uppercase()
            }
            hrZone.text = "ZONA $z · $advice"
        } else {
            hrBox.background = Ui.rounded(Ui.CARD, Ui.dp(ctx, 14).toFloat())
            hrValue.setTextColor(Ui.MUTED); hrZone.setTextColor(Ui.MUTED); hrLabel.setTextColor(Ui.MUTED)
            hrValue.text = "--"
            hrZone.text = if (Sensors.configured(ctx, Prefs.HR)) "in attesa della fascia…" else "fascia non impostata"
        }

        // ---- cadenza
        cadValue.text = cad?.toString() ?: "--"
        cadNote.text = when {
            cad == null -> if (Sensors.configured(ctx, Prefs.CAD)) "in attesa…" else "non impostato"
            s.running && st != null && st.hasCad -> when (s.cadStatus) {
                Coach.Status.LOW -> "▲ più agile ${st.cadLo}–${st.cadHi}"
                Coach.Status.HIGH -> "▼ più duro ${st.cadLo}–${st.cadHi}"
                else -> "obiettivo ${st.cadLo}–${st.cadHi}"
            }
            else -> "giri/min"
        }
        cadBox.background = Ui.rounded(
            if (s.cadStatus == Coach.Status.LOW || s.cadStatus == Coach.Status.HIGH) 0xFF8D3B00.toInt() else Ui.CARD,
            Ui.dp(ctx, 12).toFloat()
        )

        // ---- velocità, tempo, km, calorie
        speedValue.text = s.kmh?.let { String.format(Locale.ITALY, "%.1f", it) } ?: "--"
        speedNote.text = if (s.running && !s.gpsOk) "km/h · GPS in attesa" else "km/h"
        timeValue.text = Coach.clock(s.seconds)
        timeLabel.text = if (s.paused) "IN PAUSA" else "TEMPO"
        distValue.text = String.format(Locale.ITALY, "%.2f", s.distanceM / 1000)
        kcalValue.text = s.kcal.roundToInt().toString()

        // ---- stato dei sensori: solo se manca un dato e il menu è aperto
        val hs = Sensors.status[Prefs.HR]
        val cs = Sensors.status[Prefs.CAD]
        val missing = s.running && (hr == null || cad == null)
        sensorsText.text = if (missing) "Fascia: ${hs ?: "—"} · Cadenza: ${cs ?: "—"}" else ""
        sensorsText.visibility = if (missing && open) View.VISIBLE else View.GONE

        // ---- pulsanti e menu: sempre visibili da fermi, a comparsa durante l'uscita
        btnStart.visibility = if (s.running) View.GONE else View.VISIBLE
        btnPause.visibility = if (s.running) View.VISIBLE else View.GONE
        btnPause.text = if (s.paused) "▶  Riprendi" else "⏸  Pausa"
        btnSkip.visibility = if (s.running && s.workout != null && !s.finished) View.VISIBLE else View.GONE
        btnStop.visibility = if (s.running) View.VISIBLE else View.GONE
        btnRow.visibility = if (open) View.VISIBLE else View.GONE
        menuHint.visibility = if (open) View.GONE else View.VISIBLE
        act.setMenuVisible(open)
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
        // aggiorna ogni secondo anche senza nuovi dati (sensori prima di partire, chiusura del menu)
        val r = object : Runnable {
            override fun run() {
                onSnapshot(lastSnap)
                root.postDelayed(this, 1_000)
            }
        }
        poll?.let { root.removeCallbacks(it) }
        poll = r
        root.post(r)
    }
}
