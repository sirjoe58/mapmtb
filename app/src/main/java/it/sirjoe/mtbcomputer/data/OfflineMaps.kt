package it.sirjoe.mtbcomputer.data

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import java.io.File
import java.io.InputStream
import java.util.Locale
import java.util.zip.ZipInputStream

/**
 * Mappe offline:
 * - vettoriali Mapsforge (.map), es. OpenAndroMaps, anche dentro uno ZIP;
 * - stile di disegno (tema) Mapsforge, es. "Elevate" di OpenAndroMaps (ZIP con .xml);
 * - mappe a immagini (MBTiles, .sqlite, .gemf, ZIP di riquadri).
 */
object OfflineMaps {

    fun rasterDir(ctx: Context) = File(ctx.filesDir, "maps").apply { mkdirs() }
    fun vectorDir(ctx: Context) = File(ctx.filesDir, "maps-vector").apply { mkdirs() }
    private fun themeDir(ctx: Context) = File(ctx.filesDir, "theme")

    fun vectorMaps(ctx: Context): List<File> =
        vectorDir(ctx).listFiles { f -> f.extension.equals("map", true) }?.sortedBy { it.name } ?: emptyList()

    /** File .xml dello stile importato (preferisce "Elevate.xml"). */
    fun themeFile(ctx: Context): File? {
        val dir = themeDir(ctx)
        if (!dir.exists()) return null
        val xmls = dir.walkTopDown().filter { it.isFile && it.extension.equals("xml", true) }.toList()
        return xmls.firstOrNull { it.name.equals("Elevate.xml", true) }
            ?: xmls.firstOrNull { f -> f.bufferedReader().use { it.readText().take(2000) }.contains("<rendertheme") }
    }

    fun deleteVector(ctx: Context) {
        vectorDir(ctx).deleteRecursively()
    }

    fun deleteTheme(ctx: Context) {
        themeDir(ctx).deleteRecursively()
    }

    fun deleteRaster(ctx: Context) {
        rasterDir(ctx).deleteRecursively()
        Prefs.setOfflineMapPath(ctx, null)
    }

    fun describe(ctx: Context): String {
        val sb = StringBuilder()
        val v = vectorMaps(ctx)
        if (v.isEmpty()) sb.append("Mappe OpenAndroMaps: nessuna\n")
        else v.forEach { sb.append("Mappa: ${it.name} (${it.length() / 1_048_576} MB)\n") }
        sb.append("Stile: ").append(themeFile(ctx)?.nameWithoutExtension ?: "standard").append('\n')
        val r = Prefs.offlineMapPath(ctx)?.let { File(it) }?.takeIf { it.exists() }
        sb.append("Mappa a immagini: ").append(r?.name ?: "nessuna")
        return sb.toString()
    }

    // ------------------------------------------------------------- import

    private fun nameAndSize(ctx: Context, uri: Uri): Pair<String, Long> {
        var name = uri.lastPathSegment?.substringAfterLast('/') ?: "file"
        var size = -1L
        try {
            ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)
                ?.use { c ->
                    if (c.moveToFirst()) {
                        c.getString(0)?.let { name = it }
                        if (!c.isNull(1)) size = c.getLong(1)
                    }
                }
        } catch (_: Exception) {
        }
        return name to size
    }

    private fun open(ctx: Context, uri: Uri): InputStream =
        ctx.contentResolver.openInputStream(uri) ?: throw IllegalArgumentException("Impossibile leggere il file")

    private fun checkSpace(ctx: Context, size: Long) {
        if (size > 0 && ctx.filesDir.usableSpace < size + 200L * 1024 * 1024) {
            throw IllegalStateException("Spazio insufficiente sul telefono (servono circa ${size / 1_048_576 + 200} MB)")
        }
    }

    /** Copia sicura: impedisce che un nome nello ZIP scriva fuori dalla cartella. */
    private fun safeTarget(dir: File, entryName: String): File? {
        val f = File(dir, entryName)
        return if (f.canonicalPath.startsWith(dir.canonicalPath + File.separator)) f else null
    }

    private enum class ZipKind { VECTOR, THEME, RASTER }

    private fun inspectZip(ctx: Context, uri: Uri): ZipKind {
        var hasXml = false
        ZipInputStream(open(ctx, uri)).use { z ->
            var e = z.nextEntry
            while (e != null) {
                val n = e.name.lowercase(Locale.ROOT)
                if (n.endsWith(".map")) return ZipKind.VECTOR
                if (n.endsWith(".xml")) hasXml = true
                e = z.nextEntry
            }
        }
        return if (hasXml) ZipKind.THEME else ZipKind.RASTER
    }

    /**
     * Importa un file scelto dall'utente. Lavoro lungo: chiamare fuori dal thread principale.
     * Restituisce il messaggio da mostrare.
     */
    fun import(ctx: Context, uri: Uri): String {
        val (name, size) = nameAndSize(ctx, uri)
        checkSpace(ctx, size)
        val ext = name.substringAfterLast('.', "").lowercase(Locale.ROOT)
        return when (ext) {
            "map" -> {
                val out = File(vectorDir(ctx), name)
                open(ctx, uri).use { input -> out.outputStream().use { input.copyTo(it, 1 shl 16) } }
                Prefs.setBaseMap(ctx, Prefs.BASE_VECTOR)
                "Mappa offline attiva: ${out.nameWithoutExtension}"
            }
            "xml" -> {
                deleteTheme(ctx)
                val dir = themeDir(ctx).apply { mkdirs() }
                open(ctx, uri).use { input -> File(dir, name).outputStream().use { input.copyTo(it) } }
                "Stile importato: ${name.substringBeforeLast('.')}"
            }
            "zip" -> when (inspectZip(ctx, uri)) {
                ZipKind.VECTOR -> {
                    val dir = vectorDir(ctx)
                    var count = 0
                    ZipInputStream(open(ctx, uri)).use { z ->
                        var e = z.nextEntry
                        while (e != null) {
                            if (!e.isDirectory && e.name.lowercase(Locale.ROOT).endsWith(".map")) {
                                val target = safeTarget(dir, e.name.substringAfterLast('/'))
                                if (target != null) {
                                    target.outputStream().use { z.copyTo(it, 1 shl 16) }
                                    count++
                                }
                            }
                            e = z.nextEntry
                        }
                    }
                    Prefs.setBaseMap(ctx, Prefs.BASE_VECTOR)
                    "Mappa offline attiva ($count file)"
                }
                ZipKind.THEME -> {
                    deleteTheme(ctx)
                    val dir = themeDir(ctx).apply { mkdirs() }
                    ZipInputStream(open(ctx, uri)).use { z ->
                        var e = z.nextEntry
                        while (e != null) {
                            val target = safeTarget(dir, e.name)
                            if (target != null) {
                                if (e.isDirectory) target.mkdirs() else {
                                    target.parentFile?.mkdirs()
                                    target.outputStream().use { z.copyTo(it) }
                                }
                            }
                            e = z.nextEntry
                        }
                    }
                    val t = themeFile(ctx) ?: run {
                        deleteTheme(ctx)
                        throw IllegalArgumentException("Nello ZIP non c'è uno stile Mapsforge valido")
                    }
                    "Stile importato: ${t.nameWithoutExtension}"
                }
                ZipKind.RASTER -> importRaster(ctx, uri, name)
            }
            "mbtiles", "sqlite", "gemf" -> importRaster(ctx, uri, name)
            else -> throw IllegalArgumentException(
                "Formato non supportato. Usa una mappa OpenAndroMaps (.map o .zip), uno stile (.zip) o un file MBTiles"
            )
        }
    }

    private fun importRaster(ctx: Context, uri: Uri, name: String): String {
        val dir = rasterDir(ctx)
        dir.listFiles()?.forEach { it.delete() }
        val out = File(dir, name)
        open(ctx, uri).use { input -> out.outputStream().use { input.copyTo(it, 1 shl 16) } }
        Prefs.setOfflineMapPath(ctx, out.absolutePath)
        Prefs.setBaseMap(ctx, Prefs.BASE_OFFLINE)
        return "Mappa offline attiva: ${out.name}"
    }
}
