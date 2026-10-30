package it.sirjoe.training.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Programmi scaricati dal PC e uscite registrate, nella memoria privata dell'app. */
object Storage {

    // ------------------------------------------------------------ programmi

    private fun workoutsFile(ctx: Context) = File(ctx.filesDir, "programmi.json")

    /** Programmi ricevuti dal PC (sostituiscono i precedenti a ogni scaricamento). */
    fun pcWorkouts(ctx: Context): List<Workout> = try {
        WorkoutJson.listFrom(JSONArray(workoutsFile(ctx).readText()), fromPc = true)
    } catch (e: Exception) {
        emptyList()
    }

    fun savePcWorkouts(ctx: Context, list: List<Workout>) {
        workoutsFile(ctx).writeText(WorkoutJson.listTo(list.map { it.copy(fromPc = true) }).toString())
    }

    /** Tutti i programmi: prima quelli del PC, poi quelli già pronti. */
    fun allWorkouts(ctx: Context): List<Workout> = pcWorkouts(ctx) + BuiltinWorkouts.ALL

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
