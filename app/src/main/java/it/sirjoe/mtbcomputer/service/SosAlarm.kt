package it.sirjoe.mtbcomputer.service

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator

/** Suono d'allarme forte (volume sveglia al massimo) e vibrazione continua, finché non si ferma. */
object SosAlarm {
    private var player: MediaPlayer? = null
    private var savedVolume: Int? = null

    @Synchronized
    fun start(ctx: Context) {
        if (player != null) return
        val app = ctx.applicationContext
        try {
            val am = app.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            savedVolume = am.getStreamVolume(AudioManager.STREAM_ALARM)
            am.setStreamVolume(AudioManager.STREAM_ALARM, am.getStreamMaxVolume(AudioManager.STREAM_ALARM), 0)
        } catch (_: Exception) {
        }
        try {
            val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
                ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
            player = MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
                setDataSource(app, uri)
                isLooping = true
                prepare()
                start()
            }
        } catch (_: Exception) {
            player = null
        }
        vibrate(app)
    }

    private fun vibrate(ctx: Context) {
        val v = ctx.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator ?: return
        val pattern = longArrayOf(0, 800, 400, 800, 400)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            v.vibrate(VibrationEffect.createWaveform(pattern, 0))
        } else {
            @Suppress("DEPRECATION")
            v.vibrate(pattern, 0)
        }
    }

    /** Breve vibrazione e bip (es. ogni secondo del conto alla rovescia sul manubrio). */
    fun beep(ctx: Context) {
        try {
            val tg = android.media.ToneGenerator(AudioManager.STREAM_ALARM, 100)
            tg.startTone(android.media.ToneGenerator.TONE_PROP_BEEP, 200)
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({ tg.release() }, 400)
        } catch (_: Exception) {
        }
    }

    @Synchronized
    fun stop(ctx: Context) {
        val app = ctx.applicationContext
        try {
            player?.stop()
            player?.release()
        } catch (_: Exception) {
        }
        player = null
        (app.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator)?.cancel()
        savedVolume?.let {
            try {
                (app.getSystemService(Context.AUDIO_SERVICE) as AudioManager).setStreamVolume(AudioManager.STREAM_ALARM, it, 0)
            } catch (_: Exception) {
            }
        }
        savedVolume = null
    }

    val ringing: Boolean get() = player != null
}
