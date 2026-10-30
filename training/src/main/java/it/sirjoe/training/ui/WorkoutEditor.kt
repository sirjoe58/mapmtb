package it.sirjoe.training.ui

import android.text.InputType
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import androidx.appcompat.app.AlertDialog
import it.sirjoe.training.data.Coach
import it.sirjoe.training.data.Prefs
import it.sirjoe.training.data.Step
import it.sirjoe.training.data.Storage
import it.sirjoe.training.data.Workout
import it.sirjoe.training.data.Zones

/**
 * Modifica di un programma sul telefono: nome, descrizione e fasi (durata, zona, cadenza, consiglio).
 * Si salva tra "i miei programmi"; con «Sincronizza» nella pagina Uscite arriva anche al PC.
 */
class WorkoutEditor(private val act: MainActivity, original: Workout?, private val onSaved: (Workout) -> Unit) {

    private val ctx = act
    private val id = original?.id ?: ("m" + System.currentTimeMillis())
    private val steps = ArrayList(original?.steps ?: listOf(Step("Riscaldamento", 600, 1, 2, 80, 90)))
    private val name = EditText(ctx).apply {
        hint = "Nome del programma"
        setText(original?.name ?: "")
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
    }
    private val note = EditText(ctx).apply {
        hint = "Descrizione (facoltativa)"
        setText(original?.note ?: "")
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES or InputType.TYPE_TEXT_FLAG_MULTI_LINE
    }
    private val total = Ui.text(ctx, "", 14f, Ui.MUTED, true)
    private val stepsBox = Ui.vbox(ctx)
    private val maxHr = Prefs.effectiveMaxHr(ctx)

    fun show() {
        val col = Ui.vbox(ctx, 16)
        col.addView(name)
        col.addView(note)
        Ui.add(col, total, 6)
        Ui.add(col, stepsBox, 6)
        val r = Ui.hbox(ctx)
        Ui.add(r, Ui.button(ctx, "➕ Fase") {
            val last = steps.lastOrNull()
            steps += last?.copy(label = "Nuova fase", tip = "") ?: Step("Fase", 300, 2, 2)
            build()
            editStep(steps.size - 1)
        }, 0, weight = 1f)
        Ui.add(r, Ui.button(ctx, "🔁 Ripeti…") { repeatDialog() }, 6, weight = 1f)
        Ui.add(col, r, 10)
        build()
        AlertDialog.Builder(ctx)
            .setTitle("Programma")
            .setView(ScrollView(ctx).apply { addView(col) })
            .setPositiveButton("Salva") { _, _ -> save() }
            .setNegativeButton("Annulla", null)
            .show()
    }

    private fun save() {
        val w = Workout(id, name.text.toString().trim().ifEmpty { "Il mio programma" }, note.text.toString().trim(), steps.toList())
        Storage.saveMine(ctx, w)
        act.toast("Salvato tra i tuoi programmi. Per averlo anche sul PC: Uscite → Sincronizza")
        onSaved(w)
    }

    private fun build() {
        stepsBox.removeAllViews()
        total.text = "Durata ${Coach.clock(steps.sumOf { it.seconds })} · ${steps.size} fasi · tocca una fase per modificarla"
        steps.forEachIndexed { i, s ->
            val c = Ui.card(ctx, Ui.CARD2)
            c.addView(Ui.text(ctx, "${i + 1}. ${s.label}  ·  ${Coach.clock(s.seconds)}", 15f, Ui.TEXT, true))
            c.addView(Ui.text(ctx, s.target(maxHr) + if (s.tip.isNotBlank()) "\n" + s.tip else "", 13f, Ui.MUTED))
            val r = Ui.hbox(ctx)
            Ui.add(r, small("↑") { move(i, -1) }, 0, weight = 1f)
            Ui.add(r, small("↓") { move(i, 1) }, 4, weight = 1f)
            Ui.add(r, small("⧉") { steps.add(i + 1, s.copy()); build() }, 4, weight = 1f)
            Ui.add(r, small("✕", Ui.RED) {
                if (steps.size > 1) {
                    steps.removeAt(i); build()
                } else act.toast("Serve almeno una fase")
            }, 4, weight = 1f)
            Ui.add(c, r, 6)
            c.setOnClickListener { editStep(i) }
            Ui.add(stepsBox, c, 6)
        }
    }

    private fun small(t: String, color: Int = Ui.CARD, f: () -> Unit) = Ui.button(ctx, t, color, f).apply {
        minHeight = Ui.dp(ctx, 40)
        minimumHeight = Ui.dp(ctx, 40)
    }

    private fun move(i: Int, d: Int) {
        val j = i + d
        if (j < 0 || j >= steps.size) return
        val t = steps[i]; steps[i] = steps[j]; steps[j] = t
        build()
    }

    private fun repeatDialog() {
        val box = Ui.vbox(ctx, 16)
        val n = Ui.numberInput(ctx, minOf(2, steps.size), "quante fasi")
        val times = Ui.numberInput(ctx, 2, "quante volte in più")
        box.addView(Ui.text(ctx, "Ripeti le ultime … fasi", 13f, Ui.MUTED))
        box.addView(n)
        Ui.add(box, Ui.text(ctx, "… altre volte (es. 2 = in tutto 3 ripetute)", 13f, Ui.MUTED), 10)
        box.addView(times)
        AlertDialog.Builder(ctx)
            .setTitle("Ripetute")
            .setView(box)
            .setPositiveButton("Ripeti") { _, _ ->
                val k = n.text.toString().toIntOrNull() ?: 0
                val t = times.text.toString().toIntOrNull() ?: 0
                if (k !in 1..steps.size || t !in 1..20) {
                    act.toast("Valori non validi"); return@setPositiveButton
                }
                val block = steps.takeLast(k)
                repeat(t) { steps.addAll(block) }
                build()
            }
            .setNegativeButton("Annulla", null)
            .show()
    }

    private fun spinner(items: List<String>, sel: Int): Spinner = Spinner(ctx).apply {
        adapter = ArrayAdapter(ctx, android.R.layout.simple_spinner_dropdown_item, items)
        setSelection(sel.coerceIn(0, items.size - 1))
    }

    private fun editStep(i: Int) {
        val s = steps[i]
        val box = Ui.vbox(ctx, 16)
        fun label(t: String) = Ui.add(box, Ui.text(ctx, t, 13f, Ui.MUTED), 10)
        val lbl = EditText(ctx).apply { setText(s.label); inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES }
        val min = Ui.numberInput(ctx, s.seconds / 60, "minuti")
        val sec = Ui.numberInput(ctx, s.seconds % 60, "secondi")
        val zones = listOf("libero") + Zones.ALL.map { z -> "zona ${z.n} · ${z.name} (${Zones.bpmLo(z.n, maxHr)}–${Zones.bpmHi(z.n, maxHr)})" }
        val zLo = spinner(zones, s.zoneLo)
        val zHi = spinner(zones, s.zoneHi)
        val cLo = Ui.numberInput(ctx, s.cadLo, "da (0 = libera)")
        val cHi = Ui.numberInput(ctx, s.cadHi, "a")
        val tip = EditText(ctx).apply {
            setText(s.tip); hint = "es. rapporto agile, resta seduto"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        }
        label("Nome della fase"); box.addView(lbl)
        label("Durata")
        val dr = Ui.hbox(ctx)
        dr.addView(min, Ui.lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        dr.addView(sec, Ui.lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        box.addView(dr)
        label("Battito: dalla zona"); box.addView(zLo)
        label("alla zona"); box.addView(zHi)
        label("Cadenza (giri al minuto)")
        val cr = Ui.hbox(ctx)
        cr.addView(cLo, Ui.lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        cr.addView(cHi, Ui.lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        box.addView(cr, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        label("Consiglio letto dalla voce"); box.addView(tip)
        AlertDialog.Builder(ctx)
            .setTitle("Fase ${i + 1}")
            .setView(ScrollView(ctx).apply { addView(box) })
            .setPositiveButton("OK") { _, _ ->
                val seconds = ((min.text.toString().toIntOrNull() ?: 0) * 60 + (sec.text.toString().toIntOrNull() ?: 0).coerceIn(0, 59))
                    .coerceIn(10, 6 * 3600)
                var lo = zLo.selectedItemPosition
                var hi = zHi.selectedItemPosition
                if (lo == 0 || hi == 0) {
                    lo = maxOf(lo, hi); hi = lo
                }
                if (hi < lo) hi = lo
                var a = (cLo.text.toString().toIntOrNull() ?: 0).coerceIn(0, 200)
                var b = (cHi.text.toString().toIntOrNull() ?: 0).coerceIn(0, 200)
                if (a == 0) b = 0 else if (b < a) b = a
                if (b == 0) a = 0
                steps[i] = Step(lbl.text.toString().trim().ifEmpty { "Fase" }, seconds, lo, hi, a, b, tip.text.toString().trim())
                build()
            }
            .setNegativeButton("Annulla", null)
            .show()
    }
}
