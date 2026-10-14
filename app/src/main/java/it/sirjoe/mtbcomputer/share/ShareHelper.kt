package it.sirjoe.mtbcomputer.share

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.provider.OpenableColumns
import androidx.core.content.FileProvider
import it.sirjoe.mtbcomputer.data.FitCodec
import it.sirjoe.mtbcomputer.data.Placemarks
import it.sirjoe.mtbcomputer.data.RouteSummary
import it.sirjoe.mtbcomputer.data.Track
import it.sirjoe.mtbcomputer.data.TrackFormat
import it.sirjoe.mtbcomputer.data.TrackIO
import it.sirjoe.mtbcomputer.data.TrackStorage
import it.sirjoe.mtbcomputer.util.Format
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Condivisione con le altre app: file traccia, immagine per i social, archivio completo. */
object ShareHelper {

    private fun exportDir(ctx: Context) = File(ctx.cacheDir, "export").apply {
        mkdirs()
        listFiles()?.forEach { it.delete() }
    }

    private fun safe(name: String) = name.replace(Regex("[\\\\/:*?\"<>|]"), "_").trim().ifBlank { "traccia" }

    private fun uriFor(ctx: Context, f: File): Uri =
        FileProvider.getUriForFile(ctx, ctx.packageName + ".fileprovider", f)

    private fun launch(ctx: Context, send: Intent, title: String) {
        send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        ctx.startActivity(Intent.createChooser(send, title))
    }

    fun caption(track: Track): String {
        val s = RouteSummary.of(track.points)
        val sb = StringBuilder("${track.name}: ${Format.distanceKm(s.distanceM)} km")
        s.ascentM?.let { sb.append(", +${Format.meters(it)} m") }
        sb.append(" in MTB #mtb #mountainbike #SirJoeMTB")
        return sb.toString()
    }

    /** File della traccia (GPX, FIT, KML...) verso Komoot, Garmin, Wikiloc, WhatsApp, email, Drive... */
    fun shareFile(ctx: Context, track: Track, format: TrackFormat) {
        val f = File(exportDir(ctx), safe(track.name) + "." + format.ext)
        f.writeBytes(TrackIO.write(track, format))
        val send = Intent(Intent.ACTION_SEND).apply {
            type = if (format == TrackFormat.FIT) "application/octet-stream" else format.mime
            putExtra(Intent.EXTRA_STREAM, uriFor(ctx, f))
            putExtra(Intent.EXTRA_SUBJECT, track.name)
            putExtra(Intent.EXTRA_TEXT, caption(track))
        }
        launch(ctx, send, "Invia ${format.ext.uppercase()} a…")
    }

    /** Immagine con percorso e numeri per Instagram, Facebook, WhatsApp, Telegram, X... */
    fun shareImage(ctx: Context, track: Track) {
        val bmp = ShareImage.render(ctx, track)
        val f = File(exportDir(ctx), safe(track.name) + ".png")
        f.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bmp.recycle()
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "image/png"
            putExtra(Intent.EXTRA_STREAM, uriFor(ctx, f))
            putExtra(Intent.EXTRA_TEXT, caption(track))
        }
        launch(ctx, send, "Condividi sui social")
    }

    /** Segnaposto in un file GPX, verso altre app o persone. */
    fun shareWaypoints(ctx: Context, list: List<it.sirjoe.mtbcomputer.data.Placemark>) {
        val f = File(exportDir(ctx), "Segnaposto SirJoe MTB.gpx")
        f.writeBytes(TrackIO.writeWaypointsGpx(list))
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "application/gpx+xml"
            putExtra(Intent.EXTRA_STREAM, uriFor(ctx, f))
            putExtra(Intent.EXTRA_SUBJECT, "Segnaposto SirJoe MTB")
        }
        launch(ctx, send, "Invia segnaposto")
    }

    /** Tutte le tracce in un unico archivio ZIP (GPX), per backup o per inviarle in blocco. */
    fun shareAll(ctx: Context): Boolean {
        val files = TrackStorage.list(ctx)
        if (files.isEmpty()) return false
        val zip = File(exportDir(ctx), "SirJoeMTB-tracce.zip")
        ZipOutputStream(zip.outputStream()).use { out ->
            for (f in files) {
                out.putNextEntry(ZipEntry(f.name))
                f.inputStream().use { it.copyTo(out) }
                out.closeEntry()
            }
        }
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "application/zip"
            putExtra(Intent.EXTRA_STREAM, uriFor(ctx, zip))
            putExtra(Intent.EXTRA_SUBJECT, "Tracce SirJoe MTB")
        }
        launch(ctx, send, "Invia tutte le tracce")
        return true
    }

    fun displayName(ctx: Context, uri: Uri): String {
        try {
            ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { c -> if (c.moveToFirst()) c.getString(0)?.let { return it } }
        } catch (_: Exception) {
        }
        return uri.lastPathSegment?.substringAfterLast('/') ?: "traccia"
    }

    /** Importa una o più tracce da un file (anche ZIP) e le salva nell'archivio. */
    fun importUri(ctx: Context, uri: Uri): List<Track> {
        lastImportedWaypoints = 0
        val name = displayName(ctx, uri)
        val bytes = ctx.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            ?: throw IllegalArgumentException("Impossibile leggere il file")
        if (bytes.size > 50 * 1024 * 1024) throw IllegalArgumentException("File troppo grande (max 50 MB)")

        val out = ArrayList<Track>()
        val isZip = bytes.size > 4 && bytes[0] == 'P'.code.toByte() && bytes[1] == 'K'.code.toByte()
        if (isZip && !name.lowercase().endsWith(".kmz")) {
            java.util.zip.ZipInputStream(bytes.inputStream()).use { z ->
                var e = z.nextEntry
                while (e != null) {
                    val n = e.name.substringAfterLast('/')
                    val ext = n.substringAfterLast('.', "").lowercase()
                    if (!e.isDirectory && ext in listOf("gpx", "fit", "kml", "kmz", "tcx", "geojson", "json", "csv")) {
                        try {
                            out.add(TrackIO.parse(z.readBytes(), n))
                        } catch (_: Exception) {
                        }
                    }
                    e = z.nextEntry
                }
            }
            if (out.isEmpty()) throw IllegalArgumentException("Nessuna traccia valida nello ZIP")
        } else {
            // segnaposto (<wpt>) di un GPX: vanno nell'elenco segnaposto della pagina Crea
            val wpts = if (name.lowercase().endsWith(".gpx") || !FitCodec.isFit(bytes)) TrackIO.parseWaypoints(bytes) else emptyList()
            if (wpts.isNotEmpty()) Placemarks.addAll(ctx, wpts)
            try {
                out.add(TrackIO.parse(bytes, name))
            } catch (e: Exception) {
                if (wpts.isEmpty()) throw e
                lastImportedWaypoints = wpts.size
                return out
            }
            lastImportedWaypoints = wpts.size
        }
        for (t in out) TrackStorage.save(ctx, t)
        return out
    }

    /** Numero di segnaposto trovati nell'ultimo file importato. */
    @Volatile
    var lastImportedWaypoints = 0
}
