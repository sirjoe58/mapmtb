package it.sirjoe.training.ui

import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import it.sirjoe.training.data.Coach
import it.sirjoe.training.data.Prefs
import it.sirjoe.training.data.Storage
import it.sirjoe.training.data.Workout
import it.sirjoe.training.data.Zones
import it.sirjoe.training.service.RideService
import it.sirjoe.training.service.Snapshot

/**
 * Pagina "Allenamento": cosa fare adesso (fase, zona, battiti, cadenza e come comportarsi),
 * scelta del programma con tutte le fasi, tabella delle zone cardio personali.
 */
class PlanPage(act: MainActivity) : Page(act) {

    private val ctx = act
    private val col = Ui.vbox(ctx, 12)
    override val view: View = ScrollView(ctx).apply { addView(col) }

    // "adesso"
    private val nowCard = Ui.card(ctx, Ui.CARD2)
    private val nowTitle = Ui.text(ctx, "", 13f, Ui.MUTED, true)
    private val nowLabel = Ui.text(ctx, "", 24f, Ui.TEXT, true)
    private val nowTime = Ui.big(ctx, 30f).apply { gravity = Gravity.START }
    private val nowHr = Ui.text(ctx, "", 18f, Ui.TEXT, true)
    private val nowCad = Ui.text(ctx, "", 18f, Ui.TEXT, true)
    private val nowState = Ui.text(ctx, "", 18f, Color.WHITE, true)
    private val nowHow = Ui.text(ctx, "", 15f, Ui.TEXT)
    private val nowNext = Ui.text(ctx, "", 14f, Ui.MUTED)

    private val listBox = Ui.vbox(ctx)
    private val detailBox = Ui.vbox(ctx)
    private val zonesBox = Ui.vbox(ctx)
    private var shownStep = -2

    init {
        nowCard.addView(nowTitle)
        nowCard.addView(nowLabel)
        nowCard.addView(nowTime)
        Ui.add(nowCard, nowState, 6)
        Ui.add(nowCard, nowHr, 6)
        Ui.add(nowCard, nowCad, 2)
        Ui.add(nowCard, nowHow, 8)
        Ui.add(nowCard, nowNext, 8)
        Ui.add(nowCard, Ui.button(ctx, "⏭  Passa alla fase successiva") { RideService.skip(ctx) }, 10)
        col.addView(nowCard, Ui.lp())

        col.addView(Ui.title(ctx, "Programmi"))
        col.addView(Ui.text(ctx, "Tocca un programma per vederne le fasi. 💻 = arrivato dal PC (pagina Uscite → Scarica programmi).", 13f, Ui.MUTED))
        Ui.add(col, listBox, 6)
        Ui.add(col, detailBox, 10)
        col.addView(Ui.title(ctx, "Le tue zone cardio"))
        col.addView(zonesBox)
    }

    override fun onShow() {
        buildList()
        buildDetail()
        buildZones(null)
    }

    private fun selected(): Workout? = Storage.workout(ctx, Prefs.str(ctx, "selected_workout"))

    private fun buildList() {
        listBox.removeAllViews()
        val sel = Prefs.str(ctx, "selected_workout")
        for (w in Storage.allWorkouts(ctx)) {
            val c = Ui.card(ctx, if (w.id == sel) 0xFF3E2A12.toInt() else Ui.CARD)
            if (w.id == sel) c.background = Ui.rounded(0xFF3E2A12.toInt(), Ui.dp(ctx, 12).toFloat(), Ui.ORANGE, Ui.dp(ctx, 2))
            c.addView(Ui.text(ctx, (if (w.fromPc) "💻 " else "") + w.name, 17f, Ui.TEXT, true))
            c.addView(Ui.text(ctx, "${w.totalSeconds / 60} minuti · ${w.steps.size} fasi" + if (w.note.isNotBlank()) "\n${w.note}" else "", 13f, Ui.MUTED))
            c.setOnClickListener {
                Prefs.putStr(ctx, "selected_workout", w.id)
                buildList()
                buildDetail()
            }
            Ui.add(listBox, c, 6)
        }
    }

    private fun buildDetail() {
        detailBox.removeAllViews()
        val w = selected() ?: return
        val maxHr = Prefs.effectiveMaxHr(ctx)
        detailBox.addView(Ui.title(ctx, "Fasi di \"${w.name}\""))
        var t = 0
        w.steps.forEachIndexed { i, s ->
            val row = Ui.hbox(ctx)
            val bar = View(ctx).apply {
                background = Ui.rounded(if (s.hasHr) Zones.zone(s.zoneHi).color else Ui.MUTED, Ui.dp(ctx, 3).toFloat())
            }
            row.addView(bar, LinearLayout.LayoutParams(Ui.dp(ctx, 6), ViewGroup.LayoutParams.MATCH_PARENT))
            val txt = Ui.vbox(ctx).apply { setPadding(Ui.dp(ctx, 10), Ui.dp(ctx, 6), 0, Ui.dp(ctx, 6)) }
            txt.addView(Ui.text(ctx, "${i + 1}. ${s.label}  ·  ${Coach.clock(s.seconds)}", 16f, Ui.TEXT, true))
            txt.addView(Ui.text(ctx, "da ${Coach.clock(t)} · " + s.target(maxHr) + if (s.tip.isNotBlank()) "\n" + s.tip else "", 13f, Ui.MUTED))
            row.addView(txt, Ui.lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            Ui.add(detailBox, row, 4)
            t += s.seconds
        }
        Ui.add(detailBox, Ui.button(ctx, "▶  Avvia \"${w.name}\"", Ui.GREEN) { act.startRide(w.id) }, 12)
    }

    private fun buildZones(current: Int?) {
        zonesBox.removeAllViews()
        val maxHr = Prefs.effectiveMaxHr(ctx)
        zonesBox.addView(
            Ui.text(
                ctx, "Calcolate su FC max $maxHr" +
                    if (Prefs.maxHr(ctx) == 0) " (stima: inserisci la tua in Impostazioni)." else ".", 13f, Ui.MUTED
            )
        )
        for (z in Zones.ALL) {
            val c = Ui.card(ctx, z.color)
            val fg = Ui.onColor(z.color)
            if (current == z.n) c.background = Ui.rounded(z.color, Ui.dp(ctx, 12).toFloat(), Color.WHITE, Ui.dp(ctx, 4))
            c.addView(Ui.text(ctx, (if (current == z.n) "▶ SEI QUI  ·  " else "") + "Zona ${z.n} · ${z.name}", 17f, fg, true))
            c.addView(Ui.text(ctx, "${Zones.bpmLo(z.n, maxHr)}–${Zones.bpmHi(z.n, maxHr)} battiti (${z.loPct}-${z.hiPct}%) · ${z.feel}", 14f, fg))
            c.addView(Ui.text(ctx, z.advice, 13f, fg))
            Ui.add(zonesBox, c, 6)
        }
    }

    override fun onSnapshot(s: Snapshot) {
        val st = s.step
        if (!s.running) {
            nowCard.visibility = View.GONE
            return
        }
        nowCard.visibility = View.VISIBLE
        val maxHr = s.maxHr
        val w = s.workout
        if (w == null || st == null) {
            nowTitle.text = if (w == null) "USCITA LIBERA" else "PROGRAMMA COMPLETATO"
            nowLabel.text = if (w == null) "Nessun programma" else "Ben fatto! ✓"
            nowTime.text = Coach.clock(s.seconds)
            nowHr.text = s.hr?.let { "Battito $it · zona ${Zones.zoneOf(it, maxHr)}" } ?: "Battito: --"
            nowCad.text = s.cad?.let { "Cadenza $it" } ?: ""
            nowState.text = ""
            nowHow.text = s.hr?.let { Zones.zone(Zones.zoneOf(it, maxHr)).advice } ?: ""
            nowNext.text = if (w != null) "Pedala tranquillo in zona 1 per qualche minuto, poi premi Fine nella pagina In bici." else ""
        } else {
            nowTitle.text = "ADESSO · FASE ${s.stepIndex + 1} DI ${w.steps.size} · ${w.name}"
            nowLabel.text = st.label
            nowTime.text = Coach.clock(s.stepRemaining) + "  rimasti"
            nowHr.text = if (st.hasHr) {
                "❤ Tieni ${Zones.describe(st.zoneLo, st.zoneHi, maxHr)}" + (s.hr?.let { "\n    ora: $it (zona ${Zones.zoneOf(it, maxHr)})" } ?: "")
            } else "❤ Battito libero"
            nowCad.text = if (st.hasCad) "⟳ Cadenza ${st.cadLo}–${st.cadHi}" + (s.cad?.let { "  ·  ora: $it" } ?: "") else "⟳ Cadenza libera"
            val (stateText, stateColor) = when {
                s.hr != null && s.hr >= Zones.dangerBpm(maxHr) -> "⚠ Battito troppo alto: rallenta subito" to Ui.RED
                s.hrStatus == Coach.Status.HIGH -> "▼ Rallenta: sei sopra la zona" to Ui.ORANGE
                s.hrStatus == Coach.Status.LOW -> "▲ Spingi un po' di più: sei sotto la zona" to Ui.BLUE
                s.cadStatus == Coach.Status.LOW -> "▲ Pedala più agile (rapporto più leggero)" to Ui.BLUE
                s.cadStatus == Coach.Status.HIGH -> "▼ Cadenza alta: rapporto più duro" to Ui.ORANGE
                s.hrStatus == Coach.Status.OK -> "✓ Sei nella zona giusta: continua così" to Ui.GREEN
                else -> "" to Ui.CARD2
            }
            nowState.text = stateText
            nowState.background = if (stateText.isEmpty()) null else Ui.rounded(stateColor, Ui.dp(ctx, 8).toFloat())
            nowState.setPadding(Ui.dp(ctx, 10), Ui.dp(ctx, 6), Ui.dp(ctx, 10), Ui.dp(ctx, 6))
            val zoneTip = if (st.hasHr) Zones.zone(st.zoneHi).advice else ""
            nowHow.text = listOf(st.tip, zoneTip).filter { it.isNotBlank() }.joinToString("\n")
            nowNext.text = s.nextStep?.let { "Poi: ${it.label}, ${Coach.clock(it.seconds)} · ${it.target(maxHr)}" } ?: "È l'ultima fase."
        }
        // evidenzia la zona attuale (ricostruita solo quando cambia)
        val z = s.hr?.let { Zones.zoneOf(it, maxHr) } ?: -1
        if (z != shownStep) {
            shownStep = z
            buildZones(if (z > 0) z else null)
        }
    }
}
