package it.sirjoe.mtbcomputer.data

import java.util.Locale
import java.util.UUID

/**
 * Messaggi tra il telefono sul manubrio e quello in tasca (Bluetooth, una riga di testo per messaggio).
 *
 *   HELLO|nome          collegamento aperto
 *   PING                ogni 20 s, per sapere che il collegamento è vivo
 *   ALERT|lat|lon|acc|t possibile caduta: conto alla rovescia avviato sul manubrio
 *   SOS|lat|lon|acc|t|motivo   richiesta d'aiuto confermata (conto alla rovescia finito o SOS manuale)
 *   POS|lat|lon|acc|t   posizione aggiornata dopo l'SOS
 *   CANCEL              falso allarme, annullato sul manubrio
 *   OK|tipo             conferma ricezione del messaggio di quel tipo (dal telefono in tasca)
 *   CANCEL_POCKET       annullato dal telefono in tasca
 *
 * Nessuna dipendenza da Android (testabile).
 */
object SosProtocol {

    /** Identificativo del servizio Bluetooth SirJoe SOS (fisso, uguale su tutti e due i telefoni). */
    val SERVICE_UUID: UUID = UUID.fromString("6d8b3c1e-5a2f-4f0e-9b6a-51a7e0c05a11")
    const val SERVICE_NAME = "SirJoe MTB SOS"

    enum class Type { HELLO, PING, ALERT, SOS, POS, CANCEL, OK, CANCEL_POCKET }

    data class Msg(
        val type: Type,
        val lat: Double? = null,
        val lon: Double? = null,
        val accuracy: Float? = null,
        val time: Long? = null,
        val text: String = ""
    )

    private fun clean(s: String) = s.replace('|', '/').replace('\n', ' ').take(80)

    fun encode(m: Msg): String {
        val sb = StringBuilder(m.type.name)
        when (m.type) {
            Type.HELLO, Type.OK -> sb.append('|').append(clean(m.text))
            Type.ALERT, Type.SOS, Type.POS -> {
                sb.append('|').append(m.lat?.let { String.format(Locale.US, "%.6f", it) } ?: "")
                sb.append('|').append(m.lon?.let { String.format(Locale.US, "%.6f", it) } ?: "")
                sb.append('|').append(m.accuracy?.let { String.format(Locale.US, "%.0f", it) } ?: "")
                sb.append('|').append(m.time ?: "")
                if (m.type == Type.SOS) sb.append('|').append(clean(m.text))
            }
            else -> {}
        }
        return sb.append('\n').toString()
    }

    fun decode(line: String): Msg? {
        val p = line.trim().split('|')
        val type = try {
            Type.valueOf(p[0])
        } catch (_: Exception) {
            return null
        }
        return when (type) {
            Type.HELLO, Type.OK -> Msg(type, text = p.getOrElse(1) { "" })
            Type.ALERT, Type.SOS, Type.POS -> Msg(
                type,
                lat = p.getOrNull(1)?.toDoubleOrNull(),
                lon = p.getOrNull(2)?.toDoubleOrNull(),
                accuracy = p.getOrNull(3)?.toFloatOrNull(),
                time = p.getOrNull(4)?.toLongOrNull(),
                text = p.getOrElse(5) { "" }
            )
            else -> Msg(type)
        }
    }

    /** Link alla posizione apribile da qualsiasi telefono. */
    fun mapLink(lat: Double, lon: Double) = String.format(Locale.US, "https://maps.google.com/?q=%.6f,%.6f", lat, lon)

    /** Testo dell'SMS di richiesta d'aiuto. */
    fun smsText(name: String, reason: String, lat: Double?, lon: Double?, accuracy: Float?, timeText: String): String {
        val who = name.ifBlank { "Il ciclista" }
        val pos = if (lat != null && lon != null) {
            "Posizione: " + mapLink(lat, lon) + (accuracy?.let { " (precisione ${it.toInt()} m)" } ?: "")
        } else "Posizione GPS non disponibile."
        return "SOS SirJoe MTB: $who ha bisogno di aiuto ($reason) alle $timeText. $pos " +
            "Se non risponde al telefono, chiama il 112 e indica questa posizione."
    }

    fun smsUpdate(lat: Double, lon: Double, accuracy: Float?, timeText: String) =
        "SirJoe MTB, posizione aggiornata alle $timeText: " + mapLink(lat, lon) +
            (accuracy?.let { " (precisione ${it.toInt()} m)" } ?: "")

    fun smsCancel(name: String) = "SirJoe MTB: falso allarme, ${name.ifBlank { "il ciclista" }} sta bene. Nessun aiuto necessario."
}
