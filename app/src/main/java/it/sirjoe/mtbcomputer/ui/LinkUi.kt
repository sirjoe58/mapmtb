package it.sirjoe.mtbcomputer.ui

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
        "Sul PC apri SirJoe MTB Planner e premi \"📲 SirJoe Link\": compare un QR code.\n\n" +
            "Telefono e PC devono essere sulla stessa rete Wi-Fi. La prima volta Windows può chiedere " +
            "di consentire l'accesso alla rete: scegli \"Reti private\"."

    fun menu(act: MainActivity) {
        val last = LinkClient.last(act)
        val items = mutableListOf("Inquadra il QR del PC")
        if (last != null) items.add("Ricollega a ${last.name}")
        MaterialAlertDialogBuilder(act)
            .setTitle("📲 SirJoe Link")
            .setMessage(HELP)
            .setPositiveButton("Inquadra QR") { _, _ -> act.scanLinkQr() }
            .apply { if (last != null) setNeutralButton("Ricollega a ${last.name}") { _, _ -> connect(act, last) } }
            .setNegativeButton(R.string.cancel, null)
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
                MaterialAlertDialogBuilder(act)
                    .setTitle("PC non raggiungibile")
                    .setMessage(
                        "Controlla che:\n• telefono e PC siano sulla stessa rete Wi-Fi;\n" +
                            "• sul PC sia aperta la finestra \"SirJoe Link\";\n" +
                            "• Windows abbia consentito l'accesso (Reti private).\n\nDettaglio: ${e.message}"
                    )
                    .setPositiveButton("Inquadra di nuovo") { _, _ -> act.scanLinkQr() }
                    .setNegativeButton("Chiudi", null)
                    .show()
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
