package it.sirjoe.training

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import it.sirjoe.training.service.RideService

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        // i canali di notifica esistono da Android 8
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(RideService.CHANNEL_ID, "Allenamento in corso", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }
}
