package it.sirjoe.training.ui

import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import androidx.appcompat.app.AlertDialog
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
    private val planBox = Ui.vbox(ctx)
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

        col.addView(planBox)
        col.addView(Ui.title(ctx, "Programmi"))
        col.addView(Ui.text(ctx, "Tocca un programma per vederne le fasi. 💻 = dal PC · ✎ = tuo (creato o modificato qui) · 📋 = piano pronto.", 13f, Ui.MUTED))
        Ui.add(col, Ui.button(ctx, "➕  Nuovo programma") { WorkoutEditor(act, null) { w -> select(w.id) }.show() }, 8)
        Ui.add(col, listBox, 6)
        Ui.add(col, detailBox, 10)
        col.addView(Ui.title(ctx, "Le tue zone cardio"))
        col.addView(zonesBox)
    }

    override fun onShow() {
        buildPlans()
        buildList()
        buildDetail()
        buildZones(null)
    }

    private fun selected(): Workout? = Storage.workout(ctx, Prefs.str(ctx, "selected_workout"))

    private fun select(id: String) {
        Prefs.putStr(ctx, "selected_workout", id)
        buildList()
        buildDetail()
    }

    private fun badge(w: Workout): String = when {
        Storage.isMine(ctx, w.id) -> "✎ "
        w.fromPc -> "💻 "
        Storage.plans(ctx).any { p -> p.workouts.any { it.id == w.id } } -> "📋 "
        else -> ""
    }

    /** Piani pronti con il calendario delle settimane (si apre e si chiude toccandolo). */
    private fun buildPlans() {
        planBox.removeAllViews()
        for (p in Storage.plans(ctx)) {
            planBox.addView(Ui.title(ctx, p.name))
            val c = Ui.card(ctx)
            c.addView(Ui.text(ctx, p.note, 13f, Ui.MUTED))
            val cal = Ui.text(ctx, p.calendar.joinToString("\n"), 14f, Ui.TEXT).apply { visibility = View.GONE }
            val toggle = Ui.text(ctx, "▸ Mostra il calendario delle ${p.calendar.size} settimane", 14f, Ui.ORANGE, true)
            toggle.setPadding(0, Ui.dp(ctx, 8), 0, Ui.dp(ctx, 4))
            toggle.setOnClickListener {
                val open = cal.visibility != View.VISIBLE
                cal.visibility = if (open) View.VISIBLE else View.GONE
                toggle.text = (if (open) "▾ Nascondi" else "▸ Mostra") + " il calendario delle ${p.calendar.size} settimane"
            }
            c.addView(toggle)
            c.addView(cal)
            c.addView(Ui.text(ctx, "I programmi del piano sono qui sotto (📋). Scegline uno e premi Avvia.", 13f, Ui.MUTED))
            planBox.addView(c, Ui.lp())
        }
    }

    private fun buildList() {
        listBox.removeAllViews()
        val sel = Prefs.str(ctx, "selected_workout")
        for (w in Storage.allWorkouts(ctx)) {
            val c = Ui.card(ctx, if (w.id == sel) 0xFF3E2A12.toInt() else Ui.CARD)
            if (w.id == sel) c.background = Ui.rounded(0xFF3E2A12.toInt(), Ui.dp(ctx, 12).toFloat(), Ui.ORANGE, Ui.dp(ctx, 2))
            c.addView(Ui.text(ctx, badge(w) + w.name, 17f, Ui.TEXT, true))
            c.addView(Ui.text(ctx, "${w.totalSeconds / 60} minuti · ${w.steps.size} fasi" + if (w.note.isNotBlank()) "\n${w.note}" else "", 13f, Ui.MUTED))
            c.setOnClickListener { select(w.id) }
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
        val mine = Storage.isMine(ctx, w.id)
        val r = Ui.hbox(ctx)
        Ui.add(r, Ui.button(ctx, "✎  Modifica") {
            // un programma del PC o un piano pronto si modifica qui e diventa "tuo" (stesso nome e codice:
            // con Sincronizza aggiorna anche quello sul PC)
            WorkoutEditor(act, w) { saved -> select(saved.id) }.show()
        }, 0, weight = 1f)
        Ui.add(r, Ui.button(ctx, "⧉  Copia") {
            val copy = w.copy(id = "m" + System.currentTimeMillis(), name = w.name + " (copia)", fromPc = false)
            WorkoutEditor(act, copy) { saved -> select(saved.id) }.show()
        }, 6, weight = 1f)
        if (mine) Ui.add(r, Ui.button(ctx, "✕", Ui.RED) {
            AlertDialog.Builder(ctx)
                .setMessage("Togliere le tue modifiche a \"${w.name}\"?" +
                    if (w.id.startsWith("m")) " Il programma verrà eliminato dal telefono." else " Torna la versione originale.")
                .setPositiveButton("Sì") { _, _ ->
                    Storage.deleteMine(ctx, w.id)
                    buildList(); buildDetail()
                }
                .setNegativeButton("No", null)
                .show()
        }, 6, weight = 0.5f)
        Ui.add(detailBox, r, 8)
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
