package it.sirjoe.mtbcomputer

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import it.sirjoe.mtbcomputer.service.RecordingService
import org.osmdroid.config.Configuration
import org.osmdroid.mapsforge.MapsForgeTileSource
import java.io.File

class App : Application() {
    override fun onCreate() {
        super.onCreate()

        // osmdroid: identificazione corretta verso i server delle mappe (obbligatoria
        // per le policy d'uso dei tile) e cache in una cartella privata dell'app.
        val conf = Configuration.getInstance()
        conf.load(this, getSharedPreferences("osmdroid", MODE_PRIVATE))
        conf.userAgentValue = "SirJoeMTB/${BuildConfig.VERSION_NAME} (uso personale; ${BuildConfig.APPLICATION_ID})"
        val base = File(getExternalFilesDir(null) ?: filesDir, "osmdroid")
        conf.osmdroidBasePath = base
        conf.osmdroidTileCache = File(base, "tiles")
        // cache delle zone viste online: fino a circa 1 GB, utile anche senza segnale
        conf.tileFileSystemCacheMaxBytes = 1024L * 1024 * 1024
        conf.tileFileSystemCacheTrimBytes = 900L * 1024 * 1024

        // motore per le mappe vettoriali offline (OpenAndroMaps / Mapsforge)
        MapsForgeTileSource.createInstance(this)

        val channel = NotificationChannel(
            RecordingService.CHANNEL_ID,
            getString(R.string.channel_recording),
            NotificationManager.IMPORTANCE_LOW
        )
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }
}
