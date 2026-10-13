package it.sirjoe.mtbcomputer.data

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Archivio delle tracce sul telefono (cartella privata dell'app, formato GPX). */
object TrackStorage {

    fun dir(ctx: Context): File = File(ctx.filesDir, "tracks").apply { mkdirs() }

    fun list(ctx: Context): List<File> =
        dir(ctx).listFiles { f -> f.extension.equals("gpx", ignoreCase = true) }
            ?.sortedByDescending { it.lastModified() }
            ?: emptyList()

    fun saveRecorded(ctx: Context, points: List<TrackPoint>): File {
        val name = "Uscita " + SimpleDateFormat("yyyy-MM-dd HH-mm", Locale.ITALY).format(Date())
        return save(ctx, Track(name, points))
    }

    fun save(ctx: Context, track: Track): File {
        val safe = track.name.replace(Regex("[\\\\/:*?\"<>|]"), "_").trim().take(60).ifBlank { "Traccia" }
        var f = File(dir(ctx), "$safe.gpx")
        var i = 2
        while (f.exists()) {
            f = File(dir(ctx), "$safe ($i).gpx")
            i++
        }
        f.writeBytes(TrackIO.write(track, TrackFormat.GPX))
        return f
    }

    fun rename(file: File, newName: String): File {
        val safe = newName.replace(Regex("[\\\\/:*?\"<>|]"), "_").trim().take(60).ifBlank { return file }
        val dest = File(file.parentFile, "$safe.gpx")
        if (dest.exists()) return file
        return if (file.renameTo(dest)) dest else file
    }

    fun load(file: File): Track {
        val t = TrackIO.parse(file.readBytes(), file.name)
        return t.copy(name = file.nameWithoutExtension)
    }
}
