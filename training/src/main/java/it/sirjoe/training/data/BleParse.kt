package it.sirjoe.training.data

import java.util.UUID

/**
 * Lettura dei dati dei sensori Bluetooth standard (profili ufficiali Bluetooth SIG):
 * fascia cardio, sensore di cadenza/velocità, misuratore di potenza.
 * Nessuna dipendenza da Android (testabile).
 */
object BleParse {

    private fun uuid16(v: Int): UUID = UUID.fromString(String.format(java.util.Locale.US, "0000%04x-0000-1000-8000-00805f9b34fb", v))

    val SERVICE_HR: UUID = uuid16(0x180D)
    val CHAR_HR: UUID = uuid16(0x2A37)
    val SERVICE_CSC: UUID = uuid16(0x1816)
    val CHAR_CSC: UUID = uuid16(0x2A5B)
    val SERVICE_POWER: UUID = uuid16(0x1818)
    val CHAR_POWER: UUID = uuid16(0x2A63)
    val SERVICE_BATTERY: UUID = uuid16(0x180F)
    val CCCD: UUID = uuid16(0x2902)

    enum class Kind(val id: String, val label: String, val service: UUID, val characteristic: UUID) {
        HEART("hr", "Fascia cardio", SERVICE_HR, CHAR_HR),
        CADENCE("cad", "Sensore di cadenza", SERVICE_CSC, CHAR_CSC);

        companion object {
            fun of(id: String?) = values().firstOrNull { it.id == id }
            fun ofService(u: UUID) = values().firstOrNull { it.service == u }
        }
    }

    private fun u8(b: ByteArray, i: Int) = b[i].toInt() and 0xFF
    private fun u16(b: ByteArray, i: Int) = u8(b, i) or (u8(b, i + 1) shl 8)
    private fun s16(b: ByteArray, i: Int) = u16(b, i).toShort().toInt()

    /** Battiti al minuto dalla caratteristica Heart Rate Measurement (0x2A37). */
    fun heartRate(b: ByteArray): Int? {
        if (b.size < 2) return null
        val flags = u8(b, 0)
        val bpm = if (flags and 0x01 != 0) {
            if (b.size < 3) return null
            u16(b, 1)
        } else u8(b, 1)
        return bpm.takeIf { it in 25..250 }
    }

    /** Ultimi valori cumulativi della pedivella (per calcolare la cadenza tra due misure). */
    data class Crank(val revs: Int, val eventTime: Int)

    /** Dati pedivella dalla caratteristica CSC Measurement (0x2A5B), se presenti. */
    fun cscCrank(b: ByteArray): Crank? {
        if (b.isEmpty()) return null
        val flags = u8(b, 0)
        var i = 1
        if (flags and 0x01 != 0) i += 6 // giri ruota (uint32) + tempo (uint16)
        if (flags and 0x02 == 0 || b.size < i + 4) return null
        return Crank(u16(b, i), u16(b, i + 2))
    }

    /** Dati pedivella dalla caratteristica Cycling Power Measurement (0x2A63), se presenti. */
    fun powerCrank(b: ByteArray): Crank? {
        if (b.size < 4) return null
        val flags = u16(b, 0)
        var i = 4
        if (flags and 0x0001 != 0) i += 1 // pedal power balance
        if (flags and 0x0004 != 0) i += 2 // accumulated torque
        if (flags and 0x0010 != 0) i += 6 // wheel revolution data
        if (flags and 0x0020 == 0 || b.size < i + 4) return null
        return Crank(u16(b, i), u16(b, i + 2))
    }

    /** Potenza istantanea in watt (Cycling Power Measurement). */
    fun power(b: ByteArray): Int? {
        if (b.size < 4) return null
        return s16(b, 2).takeIf { it in 0..3000 }
    }

    /**
     * Cadenza (giri/minuto) tra due letture cumulative. I contatori ripartono da zero
     * dopo 65535; il tempo è in 1/1024 di secondo. null se non c'è un nuovo giro.
     */
    fun cadence(prev: Crank?, cur: Crank): Int? {
        if (prev == null) return null
        val dRev = (cur.revs - prev.revs) and 0xFFFF
        val dT = (cur.eventTime - prev.eventTime) and 0xFFFF
        if (dRev == 0 || dT == 0) return null
        val rpm = dRev * 60.0 * 1024.0 / dT
        return rpm.toInt().takeIf { it in 1..220 }
    }
}
