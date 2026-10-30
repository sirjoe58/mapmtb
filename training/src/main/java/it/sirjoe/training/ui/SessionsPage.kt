package it.sirjoe.training.ui

import android.content.Intent
import android.text.InputType
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import androidx.appcompat.app.AlertDialog
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import it.sirjoe.training.data.Coach
import it.sirjoe.training.data.Prefs
import it.sirjoe.training.data.Storage
import it.sirjoe.training.data.Zones
import it.sirjoe.training.share.LinkClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Pagina "Uscite": collegamento col PC (QR di SirJoe Link), scarico dei programmi,
 * invio delle uscite e archivio delle uscite registrate.
 */
class SessionsPage(act: MainActivity) : Page(act) {

    private val ctx = act
    private val col = Ui.vbox(ctx, 12)
    override val view: View = ScrollView(ctx).apply { addView(col) }

    private val pcState = Ui.text(ctx, "", 15f, Ui.TEXT, true)
    private val pcLog = Ui.text(ctx, "", 13f, Ui.MUTED)
    private val listBox = Ui.vbox(ctx)
    private var busy = false

    init {
        col.addView(Ui.title(ctx, "Collegamento con il PC"))
        val card = Ui.card(ctx)
        card.addView(pcState)
        card.addView(Ui.text(ctx, "Sul PC apri SirJoe MTB Planner → 📲 SirJoe Link e inquadra il QR. Telefono e PC devono essere sulla stessa rete Wi-Fi.", 13f, Ui.MUTED))
        val r1 = Ui.hbox(ctx)
        Ui.add(r1, Ui.button(ctx, "📷  Inquadra QR") { scan() }, 0, weight = 1f)
        Ui.add(r1, Ui.button(ctx, "🔢  PIN") { pinDialog() }, 8, weight = 1f)
        Ui.add(card, r1, 10)
        Ui.add(card, Ui.button(ctx, "⬇  Scarica programmi e FC max dal PC", Ui.BLUE) { download() }, 8)
        Ui.add(card, Ui.button(ctx, "⬆  Invia al PC le uscite nuove", Ui.GREEN) { sendAll() }, 8)
        Ui.add(card, pcLog, 8)
        col.addView(card, Ui.lp())

        col.addView(Ui.title(ctx, "Uscite registrate"))
        col.addView(listBox)
    }

    override fun onShow() {
        val pc = LinkClient.last(ctx)
        pcState.text = if (pc == null) "Nessun PC collegato" else "PC: ${pc.name} (${pc.host})"
        buildList()
    }

    private fun log(s: String) {
        pcLog.text = s
    }

    // ------------------------------------------------------------ collegamento

    private fun scan() {
        act.scanQr { text ->
            val pc = LinkClient.parse(text)
            if (pc == null) {
                if (text != null) act.toast("Non è il QR di SirJoe Link")
                return@scanQr
            }
            connect(pc)
        }
    }

    private fun pinDialog() {
        val box = Ui.vbox(ctx, 16)
        val addr = EditText(ctx).apply {
            hint = "Indirizzo (es. 192.168.1.10:47891)"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            LinkClient.last(ctx)?.let { setText("${it.host}:${it.port}") }
        }
        val pin = EditText(ctx).apply {
            hint = "PIN di 6 cifre"
            inputType = InputType.TYPE_CLASS_NUMBER
        }
        box.addView(addr)
        box.addView(pin)
        AlertDialog.Builder(ctx)
            .setTitle("Collega con PIN")
            .setMessage("Scrivi indirizzo e PIN che vedi nella finestra SirJoe Link sul PC.")
            .setView(box)
            .setPositiveButton("Collega") { _, _ ->
                act.lifecycleScope.launch {
                    try {
                        val pc = LinkClient.pair(addr.text.toString(), pin.text.toString())
                        connect(pc)
                    } catch (e: Exception) {
                        log("✖ " + (e.message ?: "errore"))
                    }
                }
            }
            .setNegativeButton("Annulla", null)
            .show()
    }

    private fun connect(pc: LinkClient.Pc) {
        log("Collegamento a ${pc.name}…")
        act.lifecycleScope.launch {
            try {
                val name = LinkClient.hello(pc)
                LinkClient.saveLast(ctx, pc.copy(name = name))
                pcState.text = "PC: $name (${pc.host})"
                log("✔ Collegato a $name")
                download()
            } catch (e: LinkClient.LinkError) {
                log("✖ " + e.message + if (e.unreachable) "\nControlla che telefono e PC siano sullo stesso Wi-Fi e che la finestra SirJoe Link sia aperta (sul PC: «Sblocca firewall»)." else "")
            } catch (e: Exception) {
                log("✖ " + (e.message ?: "errore"))
            }
        }
    }

    private fun download() {
        val pc = LinkClient.last(ctx) ?: return act.toast("Prima collega il PC (QR o PIN)")
        if (busy) return
        busy = true
        log("Scarico i programmi…")
        act.lifecycleScope.launch {
            try {
                val r = LinkClient.training(pc)
                withContext(Dispatchers.IO) { Storage.savePcWorkouts(ctx, r.workouts) }
                val extra = ArrayList<String>()
                if (r.maxHr in 100..230) {
                    Prefs.setMaxHr(ctx, r.maxHr); extra += "FC max ${r.maxHr}"
                }
                if (r.age in 10..100) Prefs.setAge(ctx, r.age)
                log("✔ ${r.workouts.size} programmi ricevuti" + if (extra.isNotEmpty()) " · " + extra.joinToString() else "")
                act.toast("Programmi aggiornati: li trovi nella pagina Allenamento")
            } catch (e: Exception) {
                log("✖ " + (e.message ?: "errore"))
            } finally {
                busy = false
            }
        }
    }

    private fun sendAll() {
        val pending = Storage.list(ctx).filter { !it.sentToPc }
        if (pending.isEmpty()) return act.toast("Nessuna uscita nuova da inviare")
        send(pending.map { it.file })
    }

    private fun send(files: List<File>) {
        val pc = LinkClient.last(ctx) ?: return act.toast("Prima collega il PC (QR o PIN)")
        if (busy) return
        busy = true
        act.lifecycleScope.launch {
            var ok = 0
            try {
                for (f in files) {
                    log("Invio ${ok + 1} di ${files.size}…")
                    val (json, session) = withContext(Dispatchers.IO) { f.readText() to Storage.loadSession(f) }
                    LinkClient.uploadSession(pc, json)
                    // la traccia con battito e cadenza va anche nelle Uscite del Planner (Sala analisi)
                    if (session.hasGps) LinkClient.uploadGpx(pc, session.name, withContext(Dispatchers.IO) { session.toGpx() })
                    withContext(Dispatchers.IO) { Storage.markSent(f) }
                    ok++
                }
                log("✔ $ok uscite inviate al PC")
            } catch (e: Exception) {
                log("✖ Inviate $ok di ${files.size}: " + (e.message ?: "errore"))
            } finally {
                busy = false
                buildList()
            }
        }
    }

    // ------------------------------------------------------------ elenco

    private fun buildList() {
        listBox.removeAllViews()
        val entries = Storage.list(ctx)
        if (entries.isEmpty()) {
            listBox.addView(Ui.text(ctx, "Nessuna uscita registrata. Parti dalla pagina In bici.", 14f, Ui.MUTED))
            return
        }
        val df = SimpleDateFormat("EEE d MMM yyyy, HH:mm", Locale.ITALY)
        for (e in entries) {
            val s = e.summary
            val c = Ui.card(ctx)
            c.addView(Ui.text(ctx, e.name, 16f, Ui.TEXT, true))
            c.addView(Ui.text(ctx, df.format(Date(s.start)) + if (e.sentToPc) "  ·  ✔ sul PC" else "  ·  da inviare", 13f, Ui.MUTED))
            val parts = arrayListOf(Coach.clock(s.seconds), String.format(Locale.ITALY, "%.2f km", s.distanceM / 1000))
            s.avgHr?.let { parts += "media $it bpm" }
            s.maxHrSeen?.let { parts += "max $it" }
            s.avgCad?.let { parts += "cadenza $it" }
            Ui.add(c, Ui.text(ctx, parts.joinToString("  ·  "), 15f, Ui.TEXT), 4)
            if (s.zoneSeconds.sum() > 0) {
                Ui.add(c, zoneBar(s.zoneSeconds), 8)
                Ui.add(c, Ui.text(ctx, (1..5).filter { s.zoneSeconds[it - 1] > 0 }
                    .joinToString("  ") { "Z$it ${s.zonePct(it)}%" }, 12f, Ui.MUTED), 2)
            }
            val r = Ui.hbox(ctx)
            Ui.add(r, Ui.button(ctx, "⬆ PC") { send(listOf(e.file)) }, 0, weight = 1f)
            Ui.add(r, Ui.button(ctx, "↗ GPX") { share(e.file) }, 6, weight = 1f)
            Ui.add(r, Ui.button(ctx, "✎") { rename(e.file, e.name) }, 6, weight = 0.6f)
            Ui.add(r, Ui.button(ctx, "✕", Ui.RED) { delete(e.file) }, 6, weight = 0.6f)
            Ui.add(c, r, 10)
            Ui.add(listBox, c, 8)
        }
    }

    private fun zoneBar(z: IntArray): View {
        val bar = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        for (i in 0 until 5) {
            if (z[i] == 0) continue
            bar.addView(View(ctx).apply { setBackgroundColor(Zones.zone(i + 1).color) },
                LinearLayout.LayoutParams(0, Ui.dp(ctx, 14), z[i].toFloat()))
        }
        bar.layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(ctx, 14))
        return bar
    }

    private fun share(f: File) {
        val s = Storage.loadSession(f)
        if (!s.hasGps) return act.toast("Uscita senza GPS: niente traccia da condividere")
        val dir = File(ctx.cacheDir, "condividi").apply { mkdirs() }
        val safe = s.name.replace(Regex("[\\\\/:*?\"<>|]"), "_")
        val out = File(dir, "$safe.gpx")
        out.writeText(s.toGpx())
        val uri = FileProvider.getUriForFile(ctx, ctx.packageName + ".fileprovider", out)
        val i = Intent(Intent.ACTION_SEND)
            .setType("application/gpx+xml")
            .putExtra(Intent.EXTRA_STREAM, uri)
            .putExtra(Intent.EXTRA_SUBJECT, s.name)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        act.startActivity(Intent.createChooser(i, "Condividi la traccia (Strava, email…)"))
    }

    private fun rename(f: File, old: String) {
        val et = EditText(ctx).apply { setText(old) }
        AlertDialog.Builder(ctx)
            .setTitle("Nome dell'uscita")
            .setView(et)
            .setPositiveButton("Salva") { _, _ ->
                val n = et.text.toString().trim()
                if (n.isNotEmpty()) Storage.rename(f, n)
                buildList()
            }
            .setNegativeButton("Annulla", null)
            .show()
    }

    private fun delete(f: File) {
        AlertDialog.Builder(ctx)
            .setMessage("Eliminare questa uscita dal telefono?")
            .setPositiveButton("Elimina") { _, _ ->
                f.delete()
                buildList()
            }
            .setNegativeButton("Annulla", null)
            .show()
    }
}
