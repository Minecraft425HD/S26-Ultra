package de.neon.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat

/**
 * Bietet nach einem Neustart an, Neon wieder zu aktivieren.
 *
 * Android 16 erlaubt es ausdrücklich nicht, aus `BOOT_COMPLETED` heraus einen
 * Mikrofondienst zu starten — und das ist auch richtig so: Ein Assistent, der sich nach
 * jedem Neustart unbemerkt selbst ans Mikrofon setzt, wäre genau das, wovor diese Regel
 * schützen soll.
 *
 * Der einzige zuverlässige Weg ist deshalb eine Benachrichtigung, die der Nutzer antippt.
 * Das Antippen zählt als Nutzerhandlung und erlaubt den Start.
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return

        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.neon_boot_channel_name),
                NotificationManager.IMPORTANCE_DEFAULT,
            )
        )

        val launch = context.packageManager.getLaunchIntentForPackage(context.packageName)
            ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            ?: return

        val pending = PendingIntent.getActivity(
            context,
            0,
            launch,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        manager.notify(
            NOTIFICATION_ID,
            NotificationCompat.Builder(context, CHANNEL_ID)
                .setContentTitle(context.getString(R.string.neon_boot_title))
                .setContentText(context.getString(R.string.neon_boot_text))
                .setSmallIcon(android.R.drawable.ic_btn_speak_now)
                .setContentIntent(pending)
                .setAutoCancel(true)
                .build(),
        )
    }

    private companion object {
        const val CHANNEL_ID = "neon_boot"
        const val NOTIFICATION_ID = 2
    }
}
