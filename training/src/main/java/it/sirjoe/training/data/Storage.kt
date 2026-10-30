package it.sirjoe.training.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Programmi scaricati dal PC e uscite registrate, nella memoria privata dell'app. */
object Storage {

    // ------------------------------------------------------------ programmi

    private fun workoutsFile(ctx: Context) = File(ctx.filesDir, "programmi.json")
    private fun mineFile(ctx: Context) = File(ctx.filesDir, "miei-programmi.json")

    /** Programmi ricevuti dal PC (sostituiscono i precedenti a ogni scaricamento). */
    fun pcWorkouts(ctx: Context): List<Workout> = try {
        WorkoutJson.listFrom(JSONArray(workoutsFile(ctx).readText()), fromPc = true)
    } catch (e: Exception) {
        emptyList()
    }

    fun savePcWorkouts(ctx: Context, list: List<Workout>) {
        workoutsFile(ctx).writeText(WorkoutJson.listTo(list.map { it.copy(fromPc = true) }).toString())
    }

    /** Programmi creati o modificati sul telefono. */
    fun mine(ctx: Context): List<Workout> = try {
        WorkoutJson.listFrom(JSONArray(mineFile(ctx).readText()), fromPc = false)
    } catch (e: Exception) {
        emptyList()
    }

    fun isMine(ctx: Context, id: String) = mine(ctx).any { it.id == id }

    fun saveMine(ctx: Context, w: Workout) {
        val list = mine(ctx).toMutableList()
        val i = list.indexOfFirst { it.id == w.id }
        val clean = w.copy(fromPc = false)
        if (i >= 0) list[i] = clean else list.add(0, clean)
        mineFile(ctx).writeText(WorkoutJson.listTo(list).toString())
    }

    fun deleteMine(ctx: Context, id: String) {
        mineFile(ctx).writeText(WorkoutJson.listTo(mine(ctx).filter { it.id != id }).toString())
    }

    /** Piani pronti (stesso file del Planner per PC: tools/piani/genera.py). */
    data class Plan(val id: String, val name: String, val note: String, val calendar: List<String>, val workouts: List<Workout>)

    private var plansCache: List<Plan>? = null

    fun plans(ctx: Context): List<Plan> {
        plansCache?.let { return it }
        val list = try {
            val arr = JSONArray(ctx.assets.open("piani.json").bufferedReader().readText())
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                val cal = o.optJSONArray("calendar")
                Plan(
                    o.optString("id"), o.optString("name"), o.optString("note"),
                    (0 until (cal?.length() ?: 0)).map { cal!!.optString(it) },
                    WorkoutJson.listFrom(o.optJSONArray("workouts"), fromPc = false)
                )
            }
        } catch (e: Exception) {
            emptyList()
        }
        plansCache = list
        return list
    }

    /**
     * Tutti i programmi, senza doppioni (stesso id): prima i miei, poi quelli del PC,
     * poi i piani pronti e gli esempi già inclusi nell'app.
     */
    fun allWorkouts(ctx: Context): List<Workout> {
        val out = LinkedHashMap<String, Workout>()
        for (w in mine(ctx) + pcWorkouts(ctx) + plans(ctx).flatMap { it.workouts } + BuiltinWorkouts.ALL) {
            if (!out.containsKey(w.id)) out[w.id] = w
        }
        return out.values.toList()
    }

    fun workout(ctx: Context, id: String?): Workout? = if (id == null) null else allWorkouts(ctx).firstOrNull { it.id == id }

    // ------------------------------------------------------------ uscite

    data class Entry(val file: File, val name: String, val summary: Summary, val sentToPc: Boolean, val workout: String?)

    fun sessionsDir(ctx: Context) = File(ctx.filesDir, "uscite").apply { mkdirs() }

    fun saveSession(ctx: Context, s: Session): File {
        val start = s.samples.firstOrNull()?.t ?: System.currentTimeMillis()
        val f = File(sessionsDir(ctx), "uscita-$start.json")
        f.writeText(s.toJson().toString())
        return f
    }

    fun loadSession(f: File): Session = Session.fromJson(JSONObject(f.readText()))

    fun list(ctx: Context): List<Entry> {
        val files = sessionsDir(ctx).listFiles { f -> f.name.endsWith(".json") }?.sortedByDescending { it.name } ?: return emptyList()
        return files.mapNotNull { f ->
            try {
                val o = JSONObject(f.readText())
                val s = Session.fromJson(o)
                Entry(f, s.name, s.summary(), o.optBoolean("sentToPc", false), s.workoutName)
            } catch (e: Exception) {
                null
            }
        }
    }

    fun markSent(f: File) {
        try {
            val o = JSONObject(f.readText()).put("sentToPc", true)
            f.writeText(o.toString())
        } catch (_: Exception) {
        }
    }

    fun rename(f: File, name: String) {
        val o = JSONObject(f.readText()).put("name", name)
        f.writeText(o.toString())
    }
}
