package de.neon.service

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import androidx.core.content.ContextCompat

/**
 * Die Kachel in den Schnelleinstellungen.
 *
 * Der bequemste erlaubte Weg, Neon zu starten: Ein Tippen darauf gilt als Nutzerhandlung,
 * und nur eine solche darf unter Android 16 einen Mikrofondienst starten.
 */
class NeonTile : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        updateTile()
    }

    override fun onClick() {
        super.onClick()

        if (!hasMicrophonePermission()) {
            // Ohne Berechtigung führt der Start ins Leere — also erst die App öffnen.
            openApp()
            return
        }

        val active = qsTile?.state == Tile.STATE_ACTIVE
        if (active) NeonForegroundService.stop(this) else NeonForegroundService.start(this)
        qsTile?.state = if (active) Tile.STATE_INACTIVE else Tile.STATE_ACTIVE
        qsTile?.updateTile()
    }

    private fun updateTile() {
        val tile = qsTile ?: return
        tile.label = getString(R.string.neon_name)
        tile.state = if (hasMicrophonePermission()) Tile.STATE_INACTIVE else Tile.STATE_UNAVAILABLE
        tile.updateTile()
    }

    private fun hasMicrophonePermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    private fun openApp() {
        val launch = packageManager.getLaunchIntentForPackage(packageName)
            ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            ?: return
        startActivityAndCollapse(
            android.app.PendingIntent.getActivity(
                this,
                0,
                launch,
                android.app.PendingIntent.FLAG_IMMUTABLE,
            )
        )
    }
}
