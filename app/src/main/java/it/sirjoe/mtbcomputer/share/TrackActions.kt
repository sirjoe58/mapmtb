package it.sirjoe.mtbcomputer.share

import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import it.sirjoe.mtbcomputer.MainActivity
import it.sirjoe.mtbcomputer.R
import it.sirjoe.mtbcomputer.data.Track
import it.sirjoe.mtbcomputer.data.TrackFormat
import it.sirjoe.mtbcomputer.data.TrackIO
import kotlinx.coroutines.launch

/** Menu "Condividi" comune alle pagine Percorsi e Crea. */
object TrackActions {

    fun shareMenu(act: MainActivity, track: Track) {
        if (track.points.size < 2) {
            Toast.makeText(act, "Percorso troppo corto", Toast.LENGTH_SHORT).show()
            return
        }
        val items = arrayOf(
            "Carica su Strava",
            "Immagine per i social (Instagram, Facebook, WhatsApp, Telegram, X…)",
            "File per app e piattaforme (Komoot, Garmin, Wahoo, Wikiloc, email…)",
            "Salva su file nel telefono"
        )
        MaterialAlertDialogBuilder(act)
            .setTitle("Condividi \"${track.name}\"")
            .setItems(items) { _, which ->
                when (which) {
                    0 -> strava(act, track)
                    1 -> ShareHelper.shareImage(act, track)
                    2 -> chooseFormat(act, "Formato del file") { ShareHelper.shareFile(act, track, it) }
                    3 -> chooseFormat(act, "Salva come") { f ->
                        act.saveToFile(track.name + "." + f.ext, TrackIO.write(track, f))
                    }
                }
            }
            .show()
    }

    fun chooseFormat(act: MainActivity, title: String, onChosen: (TrackFormat) -> Unit) {
        val formats = TrackFormat.values()
        MaterialAlertDialogBuilder(act)
            .setTitle(title)
            .setItems(formats.map { it.label }.toTypedArray()) { _, which -> onChosen(formats[which]) }
            .show()
    }

    private fun strava(act: MainActivity, track: Track) {
        if (!Strava.canUpload(track)) {
            MaterialAlertDialogBuilder(act)
                .setTitle("Strava")
                .setMessage(
                    "Strava accetta come attività solo le uscite registrate (con gli orari).\n\n" +
                        "Per un percorso creato a mano usa \"File per app e piattaforme\" in formato GPX " +
                        "e importalo dal sito o dall'app della piattaforma."
                )
                .setPositiveButton("OK", null)
                .show()
            return
        }
        if (!Strava.isConnected(act)) {
            MaterialAlertDialogBuilder(act)
                .setTitle("Strava non collegato")
                .setMessage("Collega il tuo account Strava dalla pagina Info, poi riprova.")
                .setPositiveButton("Vai a Info") { _, _ -> act.selectTab(R.id.nav_info) }
                .setNegativeButton(R.string.cancel, null)
                .show()
            return
        }
        MaterialAlertDialogBuilder(act)
            .setTitle("Caricare su Strava?")
            .setMessage(
                "\"${track.name}\" verrà pubblicata sul tuo profilo Strava con la visibilità " +
                    "impostata nel tuo account.\n\nAttenzione: la traccia mostra partenza e arrivo. " +
                    "Se partono da casa tua, usa le \"zone di privacy\" di Strava."
            )
            .setPositiveButton("Carica") { _, _ ->
                Toast.makeText(act, "Caricamento su Strava…", Toast.LENGTH_SHORT).show()
                act.lifecycleScope.launch {
                    val msg = try {
                        Strava.upload(act, track, "Registrata con SirJoe MTB")
                    } catch (e: Exception) {
                        "Errore Strava: ${e.message}"
                    }
                    Toast.makeText(act, msg, Toast.LENGTH_LONG).show()
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }
}
