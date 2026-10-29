package no.meltveit.cuecomm.android

import android.Manifest
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import no.meltveit.cuecomm.R
import no.meltveit.cuecomm.core.StoreState

/** Foreground service: keeps a session connected and cue words armed with the screen off. */
class SessionService : Service() {
    private var job: Job? = null
    private var cpu: PowerManager.WakeLock? = null
    private var wifi: WifiManager.WifiLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val app = application as CueCommApp
        val state = app.store.state.value
        goForeground(notification(state), listening = state.isListening)
        acquireLocks()
        if (job == null) {
            job = app.appScope.launch {
                app.store.state.map { notificationKey(it) }.distinctUntilChanged().collect {
                    (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).notify(ID, notification(app.store.state.value))
                }
            }
        }
        return START_NOT_STICKY
    }

    private fun notificationKey(s: StoreState) = listOf(s.isListening, s.settings.sessionName, s.peers.size, s.speakingChannel()?.name)

    private fun goForeground(n: Notification, listening: Boolean) {
        val micOk = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        runCatching {
            when {
                Build.VERSION.SDK_INT >= 34 -> {
                    val type = ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE or
                        (if (listening && micOk) ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE else 0)
                    startForeground(ID, n, type)
                }
                Build.VERSION.SDK_INT >= 29 -> startForeground(ID, n, if (listening && micOk) ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE else 0)
                else -> startForeground(ID, n)
            }
        }.onFailure { runCatching { startForeground(ID, n) } }
    }

    private fun acquireLocks() {
        if (cpu == null) {
            cpu = (getSystemService(Context.POWER_SERVICE) as PowerManager).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "cuecomm:session").apply {
                setReferenceCounted(false); acquire()
            }
        }
        if (wifi == null) {
            val mode = if (Build.VERSION.SDK_INT >= 29) WifiManager.WIFI_MODE_FULL_LOW_LATENCY else @Suppress("DEPRECATION") WifiManager.WIFI_MODE_FULL_HIGH_PERF
            wifi = (applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager).createWifiLock(mode, "cuecomm:session").apply {
                setReferenceCounted(false); acquire()
            }
        }
    }

    private fun notification(s: StoreState): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_IMMUTABLE,
        )
        val title = if (s.inSession) "Session: ${s.settings.sessionName}" else "CueComm"
        val body = buildString {
            if (s.isListening) append("Listening as ${s.speakingChannel()?.name ?: "a channel"}") else append("Not listening")
            if (s.inSession) append(" · ${if (s.peers.isEmpty()) "waiting for others" else "${s.peers.size + 1} devices online"}")
        }
        return NotificationCompat.Builder(this, CueCommApp.CHANNEL_LIVE)
            .setSmallIcon(R.drawable.ic_stat_cue)
            .setContentTitle(title)
            .setContentText(body)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(open)
            .build()
    }

    override fun onDestroy() {
        job?.cancel(); job = null
        runCatching { cpu?.takeIf { it.isHeld }?.release() }; cpu = null
        runCatching { wifi?.takeIf { it.isHeld }?.release() }; wifi = null
        super.onDestroy()
    }

    private companion object { const val ID = 1 }
}
