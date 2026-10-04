package com.emilioaugust.copypus.service

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import com.emilioaugust.copypus.R

class NotificationApp : Application() {

    override fun onCreate() {
        super.onCreate()

        ServiceLocator.init(this)
        createChannel()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.clipboard_monitoring),
                NotificationManager.IMPORTANCE_MIN
            ).apply {
                description = getString(
                    R.string.keeps_copypus_running_to_save_your_clipboard
                )
                setShowBadge(false)
                enableLights(false)
                enableVibration(false)
                setSound(null, null)
            }

            val manager = getSystemService(
                NotificationManager::class.java
            )
            manager.createNotificationChannel(channel)
        }
    }

    companion object {
        const val CHANNEL_ID = "copypus_clipboard_channel"
    }
}