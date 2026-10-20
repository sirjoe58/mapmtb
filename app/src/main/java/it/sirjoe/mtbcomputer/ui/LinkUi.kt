package it.sirjoe.mtbcomputer.ui

import android.content.Intent
import android.net.Uri
import android.text.InputFilter
import android.text.InputType
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import it.sirjoe.mtbcomputer.MainActivity
import it.sirjoe.mtbcomputer.R
import it.sirjoe.mtbcomputer.data.TrackStorage
import it.sirjoe.mtbcomputer.share.LinkClient
import it.sirjoe.mtbcomputer.share.ShareHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Finestre di SirJoe Link sul telefono. */
object LinkUi {

    private const val HELP =
        "Sul PC apri SirJoe MTB Planner e premi \"📲 SirJoe Link\": compare un QR code, e sotto l'indirizzo e il PIN " +
            "per collegarti anche senza QR.\n\n" +
            "Telefono e PC devono essere sulla stessa rete Wi-Fi. La prima volta Windows può chiedere " +
            "di consentire l'accesso alla rete: scegli \"Reti private\"."

    fun menu(act: MainActivity) {
        val last = LinkClient.last(act)
        val items = mutableListOf("📷 Inquadra il QR del PC", "⌨ Inserisci codice (indirizzo e PIN)")
        if (last != null) items.add("🔁 Ricollega a ${last.name}")
        items.add("❓ Come funziona")
        MaterialAlertDialogBuilder(act)
            .setTitle("📲 SirJoe Link")
            .setItems(items.toTypedArray()) { _, which ->
                when {
                    which == 0 -> act.scanLinkQr()
                    which == 1 -> manual(act)
                    last != null && which == 2 -> connect(act, last)
                    else -> MaterialAlertDialogBuilder(act).setTitle("SirJoe Link").setMessage(HELP)
                        .setPositiveButton("OK", null).show()
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    /** Collegamento senza QR: indirizzo e PIN scritti sotto il QR nella finestra del PC. */
    fun manual(act: MainActivity) {
        val dp = act.resources.displayMetrics.density
        val box = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((20 * dp).toInt(), (8 * dp).toInt(), (20 * dp).toInt(), 0)
        }
        val addr = EditText(act).apply {
            hint = "Indirizzo, es. 192.168.1.10:47891"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            setSingleLine()
            LinkClient.last(act)?.let { setText("${it.host}:${it.port}") }
        }
        val pin = EditText(act).apply {
            hint = "PIN di 6 cifre"
            inputType = InputType.TYPE_CLASS_NUMBER
            filters = arrayOf(InputFilter.LengthFilter(6))
        }
        box.addView(addr)
        box.addView(pin)
        MaterialAlertDialogBuilder(act)
            .setTitle("Codice del PC")
            .setMessage("Li trovi nella finestra SirJoe Link del Planner, sotto il QR (\"Senza QR\").")
            .setView(box)
            .setPositiveButton("Collega") { _, _ ->
                act.lifecycleScope.launch {
                    try {
                        val pc = LinkClient.pair(addr.text.toString(), pin.text.toString())
                        connect(act, pc)
                    } catch (e: Exception) {
                        failure(act, e, addr.text.toString().trim())
                    }
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun failure(act: MainActivity, e: Exception, address: String) {
        val unreachable = e !is LinkClient.LinkError || e.unreachable
        val ping = "http://" + address.removePrefix("http://").trimEnd('/') + "/ping"
        val msg = if (unreachable) {
            "Il telefono non riesce a raggiungere il PC ($address).\n\n" +
                "1. Telefono e PC sulla stessa rete Wi-Fi (non la rete \"ospiti\", non i dati mobili).\n" +
                "2. Sul PC la finestra SirJoe Link deve restare aperta.\n" +
                "3. Prova ad aprire nel browser del telefono:\n$ping\n" +
                "Se non compare \"SirJoe Link OK\", sul computer premi \"🛡 Sblocca nel firewall\" nella finestra SirJoe Link (Windows o Mac) e conferma (sul Mac serve la password del Mac).\n\n" +
                "Dettaglio: ${e.message}"
        } else {
            "Il PC ha rifiutato il collegamento: ${e.message}\n\nChiudi e riapri SirJoe Link sul PC e inquadra il nuovo QR (o scrivi il nuovo PIN)."
        }
        MaterialAlertDialogBuilder(act)
            .setTitle(if (unreachable) "PC non raggiungibile" else "Codice non accettato")
            .setMessage(msg)
            .setPositiveButton("Riprova") { _, _ -> menu(act) }
            .apply {
                if (unreachable) setNeutralButton("Apri nel browser") { _, _ ->
                    try {
                        act.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(ping)))
                    } catch (_: Exception) {
                    }
                }
            }
            .setNegativeButton("Chiudi", null)
            .show()
    }

    private fun toast(act: MainActivity, msg: String) = Toast.makeText(act, msg, Toast.LENGTH_LONG).show()

    fun connect(act: MainActivity, pc: LinkClient.Pc) {
        toast(act, "Collegamento a ${pc.name}…")
        act.lifecycleScope.launch {
            try {
                val name = LinkClient.hello(pc)
                LinkClient.saveLast(act, pc)
                val offers = LinkClient.offers(pc)
                MaterialAlertDialogBuilder(act)
                    .setTitle("Collegato a $name")
                    .setItems(
                        arrayOf(
                            "⬇ Ricevi percorsi dal PC (${offers.size})",
                            "⬆ Invia le mie uscite al PC (Sala analisi)"
                        )
                    ) { _, which -> if (which == 0) receive(act, pc, offers) else send(act, pc) }
                    .setNegativeButton("Chiudi", null)
                    .show()
            } catch (e: Exception) {
                failure(act, e, "${pc.host}:${pc.port}")
            }
        }
    }

    private fun receive(act: MainActivity, pc: LinkClient.Pc, offers: List<LinkClient.Offer>) {
        if (offers.isEmpty()) {
            toast(act, "Il PC non offre percorsi: creane uno o salvalo in archivio nel Planner")
            return
        }
        val checked = BooleanArray(offers.size) { it == 0 }
        MaterialAlertDialogBuilder(act)
            .setTitle("Percorsi dal PC")
            .setMultiChoiceItems(offers.map { "${it.name}  (${it.km} km)" }.toTypedArray(), checked) { _, i, v -> checked[i] = v }
            .setPositiveButton("Ricevi") { _, _ ->
                act.lifecycleScope.launch {
                    var tracks = 0
                    var places = 0
                    for ((i, o) in offers.withIndex()) {
                        if (!checked[i]) continue
                        try {
                            val bytes = LinkClient.download(pc, o.id)
                            val got = withContext(Dispatchers.IO) { ShareHelper.importBytes(act.applicationContext, bytes, o.name + ".gpx") }
                            tracks += got.size
                            places += ShareHelper.lastImportedWaypoints
                        } catch (e: Exception) {
                            toast(act, "${o.name}: ${e.message}")
                        }
                    }
                    toast(act, "Ricevuti $tracks percorsi" + if (places > 0) " e $places segnaposto" else "")
                    act.onTracksChanged()
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun send(act: MainActivity, pc: LinkClient.Pc) {
        val files = TrackStorage.list(act)
        if (files.isEmpty()) {
            toast(act, "Nessuna traccia da inviare")
            return
        }
        val checked = BooleanArray(files.size) { it == 0 }
        MaterialAlertDialogBuilder(act)
            .setTitle("Uscite da inviare al PC")
            .setMultiChoiceItems(files.map { it.nameWithoutExtension }.toTypedArray(), checked) { _, i, v -> checked[i] = v }
            .setPositiveButton("Invia") { _, _ ->
                act.lifecycleScope.launch {
                    var ok = 0
                    for ((i, f) in files.withIndex()) {
                        if (!checked[i]) continue
                        try {
                            val bytes = withContext(Dispatchers.IO) { f.readBytes() }
                            LinkClient.upload(pc, f.nameWithoutExtension, bytes)
                            ok++
                        } catch (e: Exception) {
                            toast(act, "${f.nameWithoutExtension}: ${e.message}")
                        }
                    }
                    if (ok > 0) toast(act, "Inviate $ok uscite: sul PC le trovi nella scheda Uscite")
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }
}
