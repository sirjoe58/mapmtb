package it.sirjoe.training.data

import org.json.JSONArray
import org.json.JSONObject

/**
 * Programma di allenamento: una sequenza di fasi a tempo, ognuna con zona cardio
 * e cadenza da tenere. Arriva dal Planner per PC (SirJoe Link) o è uno di quelli già pronti.
 *
 * Formato JSON (uguale sul PC):
 * { "id":"…", "name":"…", "note":"…",
 *   "steps":[ { "label":"Riscaldamento", "sec":600, "zLo":1, "zHi":2, "cLo":80, "cHi":90, "tip":"…" } ] }
 * zLo/zHi = 0 → battito libero; cLo/cHi = 0 → cadenza libera.
 */
data class Step(
    val label: String,
    val seconds: Int,
    val zoneLo: Int = 0,
    val zoneHi: Int = 0,
    val cadLo: Int = 0,
    val cadHi: Int = 0,
    val tip: String = ""
) {
    val hasHr get() = zoneLo in 1..5 && zoneHi in zoneLo..5
    val hasCad get() = cadLo > 0 && cadHi >= cadLo

    /** "zona 2 (99–116 battiti) · cadenza 80–90" */
    fun target(maxHr: Int): String {
        val parts = ArrayList<String>()
        if (hasHr) parts += Zones.describe(zoneLo, zoneHi, maxHr)
        if (hasCad) parts += "cadenza $cadLo–$cadHi"
        return if (parts.isEmpty()) "ritmo libero" else parts.joinToString(" · ")
    }
}

data class Workout(
    val id: String,
    val name: String,
    val note: String,
    val steps: List<Step>,
    val fromPc: Boolean = false
) {
    val totalSeconds get() = steps.sumOf { it.seconds }
}

object WorkoutJson {

    fun stepFrom(o: JSONObject) = Step(
        label = o.optString("label", "Fase").ifBlank { "Fase" },
        seconds = o.optInt("sec", 60).coerceIn(10, 6 * 3600),
        zoneLo = o.optInt("zLo", 0).coerceIn(0, 5),
        zoneHi = o.optInt("zHi", o.optInt("zLo", 0)).coerceIn(0, 5),
        cadLo = o.optInt("cLo", 0).coerceIn(0, 200),
        cadHi = o.optInt("cHi", o.optInt("cLo", 0)).coerceIn(0, 200),
        tip = o.optString("tip", "")
    )

    fun from(o: JSONObject, fromPc: Boolean = o.optBoolean("fromPc", false)): Workout? {
        val arr = o.optJSONArray("steps") ?: return null
        val steps = (0 until arr.length()).mapNotNull { arr.optJSONObject(it)?.let(::stepFrom) }
        if (steps.isEmpty()) return null
        return Workout(
            id = o.optString("id", o.optString("name", "programma")),
            name = o.optString("name", "Programma").ifBlank { "Programma" },
            note = o.optString("note", ""),
            steps = steps,
            fromPc = fromPc
        )
    }

    fun to(w: Workout): JSONObject = JSONObject()
        .put("id", w.id)
        .put("name", w.name)
        .put("note", w.note)
        .put("fromPc", w.fromPc)
        .put("steps", JSONArray().apply {
            for (s in w.steps) put(
                JSONObject()
                    .put("label", s.label).put("sec", s.seconds)
                    .put("zLo", s.zoneLo).put("zHi", s.zoneHi)
                    .put("cLo", s.cadLo).put("cHi", s.cadHi)
                    .put("tip", s.tip)
            )
        })

    fun listFrom(arr: JSONArray?, fromPc: Boolean): List<Workout> {
        if (arr == null) return emptyList()
        return (0 until arr.length()).mapNotNull { i -> arr.optJSONObject(i)?.let { from(it, fromPc) } }
    }

    fun listTo(list: List<Workout>): JSONArray = JSONArray().apply { list.forEach { put(to(it)) } }
}

/** Programmi già pronti, utilizzabili anche senza PC. */
object BuiltinWorkouts {
    private fun min(m: Int) = m * 60

    val ALL: List<Workout> = listOf(
        Workout(
            "base-fondo-lento", "Fondo lento 45'",
            "Uscita tranquilla per costruire resistenza: quasi tutta in zona 2.",
            listOf(
                Step("Riscaldamento", min(10), 1, 2, 80, 95, "Rapporto agile, gambe sciolte."),
                Step("Fondo lento", min(30), 2, 2, 80, 95, "Ritmo da chiacchierata, anche in salita."),
                Step("Defaticamento", min(5), 1, 1, 75, 95, "Rallenta e lascia scendere il battito.")
            )
        ),
        Workout(
            "base-agilita", "Agilità 40'",
            "Allena a pedalare rotondo con cadenza alta e battito basso.",
            listOf(
                Step("Riscaldamento", min(10), 1, 2, 80, 90),
                Step("Agile", min(3), 2, 2, 95, 105, "Rapporto leggero, pedala veloce senza saltare sulla sella."),
                Step("Normale", min(2), 1, 2, 80, 90),
                Step("Agile", min(3), 2, 2, 95, 105),
                Step("Normale", min(2), 1, 2, 80, 90),
                Step("Agile", min(3), 2, 2, 95, 105),
                Step("Normale", min(2), 1, 2, 80, 90),
                Step("Fondo", min(10), 2, 2, 80, 95),
                Step("Defaticamento", min(5), 1, 1, 75, 95)
            )
        ),
        Workout(
            "base-salite", "Salite in zona 3 (50')",
            "Tre tratti sostenuti in zona 3 con recupero: ideale su salite regolari.",
            listOf(
                Step("Riscaldamento", min(12), 1, 2, 80, 95),
                Step("Salita sostenuta", min(6), 3, 3, 70, 85, "Ritmo costante, seduto, rapporto che non ti fa piantare."),
                Step("Recupero", min(4), 1, 2, 80, 95, "Scendi di rapporto e respira."),
                Step("Salita sostenuta", min(6), 3, 3, 70, 85),
                Step("Recupero", min(4), 1, 2, 80, 95),
                Step("Salita sostenuta", min(6), 3, 3, 70, 85),
                Step("Defaticamento", min(12), 1, 2, 75, 95)
            )
        ),
        Workout(
            "base-recupero", "Recupero attivo 30'",
            "Dopo un'uscita impegnativa: tutto in zona 1, gambe che girano.",
            listOf(
                Step("Recupero attivo", min(30), 1, 1, 80, 95, "Nessuno sforzo: se il battito sale, rallenta.")
            )
        )
    )
}
