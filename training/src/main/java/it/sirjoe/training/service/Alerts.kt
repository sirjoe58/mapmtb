package it.sirjoe.training.service

import android.content.Context
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.speech.tts.TextToSpeech
import it.sirjoe.training.data.Coach
import it.sirjoe.training.data.Prefs
import java.util.Locale

/**
 * Avvisi durante l'allenamento: voce in italiano, bip diversi per "spingi" e "rallenta",
 * vibrazione (utile col telefono in tasca). Il colore sullo schermo lo gestisce la pagina In bici.
 */
class Alerts(private val ctx: Context) {

    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var tone: ToneGenerator? = null

    init {
        if (Prefs.voice(ctx)) {
            tts = TextToSpeech(ctx.applicationContext) { status ->
                ttsReady = status == TextToSpeech.SUCCESS
                if (ttsReady) {
                    tts?.setLanguage(Locale.ITALIAN)
                    tts?.setSpeechRate(1.05f)
                }
            }
        }
        try {
            tone = ToneGenerator(AudioManager.STREAM_NOTIFICATION, 90)
        } catch (_: Exception) {
        }
    }

    fun play(cue: Coach.Cue) {
        if (Prefs.beep(ctx)) beep(cue.kind)
        if (Prefs.vibrate(ctx)) vibrate(cue.kind)
        if (Prefs.voice(ctx)) say(cue.text)
    }

    fun say(text: String) {
        if (ttsReady) tts?.speak(text, TextToSpeech.QUEUE_ADD, null, text.hashCode().toString())
    }

    private fun beep(k: Coach.Kind) {
        val t = tone ?: return
        try {
            when (k) {
                Coach.Kind.UP -> t.startTone(ToneGenerator.TONE_PROP_ACK, 400)        // due note che salgono
                Coach.Kind.DOWN -> t.startTone(ToneGenerator.TONE_PROP_NACK, 400)     // due note che scendono
                Coach.Kind.WARN -> t.startTone(ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD, 700)
                Coach.Kind.STEP, Coach.Kind.DONE -> t.startTone(ToneGenerator.TONE_PROP_BEEP2, 400)
                Coach.Kind.INFO -> t.startTone(ToneGenerator.TONE_PROP_BEEP, 150)
            }
        } catch (_: Exception) {
        }
    }

    private fun vibrate(k: Coach.Kind) {
        val pattern = when (k) {
            Coach.Kind.UP -> longArrayOf(0, 120, 120, 120)                 // due brevi: spingi
            Coach.Kind.DOWN -> longArrayOf(0, 450)                         // una lunga: rallenta
            Coach.Kind.WARN -> longArrayOf(0, 300, 150, 300, 150, 300)     // tre: attenzione
            Coach.Kind.STEP, Coach.Kind.DONE -> longArrayOf(0, 200, 100, 200)
            Coach.Kind.INFO -> return
        }
        @Suppress("DEPRECATION")
        val v = ctx.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            v.vibrate(VibrationEffect.createWaveform(pattern, -1))
        } else {
            @Suppress("DEPRECATION")
            v.vibrate(pattern, -1)
        }
    }

    fun release() {
        try {
            tts?.stop(); tts?.shutdown()
        } catch (_: Exception) {
        }
        tone?.release()
        tts = null; tone = null
    }
}
