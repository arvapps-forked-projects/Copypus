package com.emilioaugust.copypus.data.tiles

import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import androidx.annotation.RequiresApi
import com.emilioaugust.copypus.R
import com.emilioaugust.copypus.SaveClipboardActivity
import com.emilioaugust.copypus.data.datastore.SettingsDataStore
import com.emilioaugust.copypus.utils.LocaleHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.jvm.java

class SaveCopiedTileService : TileService() {

    private val serviceScope =
        CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val settingsDataStore by lazy {
        SettingsDataStore(applicationContext)
    }

    override fun onDestroy() {
        serviceScope.cancel()
        super.onDestroy()
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    override fun onStartListening() {
        super.onStartListening()

        serviceScope.launch {
            val language = settingsDataStore.language.first()
            val context =
                LocaleHelper.setLocale(
                    applicationContext,
                    language.code
                )

            withContext(Dispatchers.Main) {
                qsTile?.apply {
                    label = context.getString(
                        R.string.save_clipboard_label
                    )

                    subtitle = context.getString(
                        R.string.save_clipboard_subtitle
                    )

                    state = Tile.STATE_ACTIVE
                    updateTile()
                }
            }
        }
    }

    @RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    override fun onClick() {
        super.onClick()

        val intent = Intent(this, SaveClipboardActivity::class.java).apply {
            addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_CLEAR_TASK or
                        Intent.FLAG_ACTIVITY_NO_ANIMATION or
                        Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS
            )
        }

        val pendingIntent = PendingIntent.getActivity(
            this,
            1001,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        startActivityAndCollapse(pendingIntent)
    }
}