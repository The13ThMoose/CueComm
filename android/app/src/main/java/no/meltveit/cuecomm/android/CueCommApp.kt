package no.meltveit.cuecomm.android

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import no.meltveit.cuecomm.core.AppStore
import no.meltveit.cuecomm.ui.Nav
import java.io.File

class CueCommApp : Application() {
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    lateinit var store: AppStore
        private set
    val nav = Nav()

    override fun onCreate() {
        super.onCreate()
        createChannels()
        val device = runCatching { Settings.Global.getString(contentResolver, "device_name") }.getOrNull()?.takeIf { it.isNotBlank() } ?: Build.MODEL
        store = AppStore(File(filesDir, "cuecomm"), device, appScope, Dispatchers.Main.immediate, AndroidPlatform(this))

        // Keep the process alive and the CPU awake while a session is open or cue words are armed.
        appScope.launch {
            store.state.map { Triple(it.isListening, it.inSession || it.settings.networkEnabled, it.micGranted) }.distinctUntilChanged().collect { (listening, networked) ->
                if (listening || networked) {
                    runCatching { ContextCompat.startForegroundService(this@CueCommApp, Intent(this@CueCommApp, SessionService::class.java)) }
                } else {
                    stopService(Intent(this@CueCommApp, SessionService::class.java))
                }
            }
        }
    }

    private fun createChannels() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(NotificationChannel(CHANNEL_LIVE, "Session and listening", NotificationManager.IMPORTANCE_LOW).apply {
            description = "Shown while CueComm is connected to a session or listening for cue words."
        })
        nm.createNotificationChannel(NotificationChannel(CHANNEL_CUE, "Cues", NotificationManager.IMPORTANCE_HIGH).apply {
            description = "A cue fired while CueComm was not on screen."
            enableVibration(true)
        })
    }

    companion object {
        const val CHANNEL_LIVE = "live"
        const val CHANNEL_CUE = "cue"
    }
}
