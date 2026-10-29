package no.meltveit.cuecomm.android

import android.Manifest
import android.app.Application
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.system.Os
import android.system.OsConstants
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import no.meltveit.cuecomm.R
import no.meltveit.cuecomm.core.Discovery
import no.meltveit.cuecomm.core.Platform
import no.meltveit.cuecomm.core.SpeechController
import no.meltveit.cuecomm.core.Translator
import java.net.Socket

/** The phone's side of the app: nearby discovery, speech, translation, haptics, notifications, socket tuning. */
class AndroidPlatform(private val app: Application) : Platform {
    override val discovery: Discovery = NsdDiscovery(app)
    override val speech: SpeechController = AndroidSpeech(app)
    override val translator: Translator = MlKitTranslator()

    private var cueId = 100

    override fun vibrate() {
        runCatching {
            val v = if (Build.VERSION.SDK_INT >= 31) (app.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
            else @Suppress("DEPRECATION") (app.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator)
            v.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 140, 90, 140, 90, 140), -1))
        }
    }

    override fun notifyCue(title: String, detail: String, colorHex: String) {
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(app, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        val open = PendingIntent.getActivity(
            app, 0, Intent(app, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_IMMUTABLE,
        )
        val n = NotificationCompat.Builder(app, CueCommApp.CHANNEL_CUE)
            .setSmallIcon(R.drawable.ic_stat_cue)
            .setContentTitle("CUE · ${title.uppercase()}")
            .setContentText(detail)
            .setColor(0xFF000000.toInt() or colorHex.toLong(16).toInt())
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setAutoCancel(true)
            .setTimeoutAfter(10_000)
            .setContentIntent(open)
            .build()
        runCatching { (app.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).notify(cueId++ % 20 + 100, n) }
    }

    /** Linux TCP keep-alive: a dead internet path is noticed within about a minute instead of two hours. */
    override fun tuneSocket(s: Socket) {
        runCatching {
            val pfd = ParcelFileDescriptor.fromSocket(s)
            try {
                val fd = pfd.fileDescriptor
                Os.setsockoptInt(fd, OsConstants.IPPROTO_TCP, 4, 20)      // TCP_KEEPIDLE
                Os.setsockoptInt(fd, OsConstants.IPPROTO_TCP, 5, 10)      // TCP_KEEPINTVL
                Os.setsockoptInt(fd, OsConstants.IPPROTO_TCP, 6, 3)       // TCP_KEEPCNT
                Os.setsockoptInt(fd, OsConstants.IPPROTO_TCP, 18, 60_000) // TCP_USER_TIMEOUT
            } finally {
                pfd.close()
            }
        }
    }
}
