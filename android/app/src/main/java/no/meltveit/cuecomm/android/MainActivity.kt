package no.meltveit.cuecomm.android

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import android.graphics.Color
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import no.meltveit.cuecomm.ui.AppRoot
import no.meltveit.cuecomm.ui.CueCommTheme
import no.meltveit.cuecomm.ui.MainActions

class MainActivity : ComponentActivity() {
    private val app get() = application as CueCommApp
    private var afterMic: (() -> Unit)? = null

    private val micPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        app.store.setMicGranted(granted)
        val next = afterMic
        afterMic = null
        if (granted) next?.invoke()
    }
    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )
        super.onCreate(savedInstanceState)
        app.store.setMicGranted(hasMic())

        val actions = MainActions(
            onListen = {
                if (app.store.listening || hasMic()) {
                    app.store.setMicGranted(true)
                    app.store.toggleListening()
                } else {
                    afterMic = { app.store.toggleListening() }
                    micPermission.launch(Manifest.permission.RECORD_AUDIO)
                }
            },
            onShare = { text, csv ->
                val send = Intent(Intent.ACTION_SEND).apply {
                    type = if (csv) "text/csv" else "text/plain"
                    putExtra(Intent.EXTRA_TEXT, text)
                    putExtra(Intent.EXTRA_SUBJECT, "CueComm transcript")
                }
                startActivity(Intent.createChooser(send, "Share transcript"))
            },
            onOpenAppSettings = {
                startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null)))
            },
        )
        setContent { CueCommTheme { AppRoot(app, actions) } }

        // Cue notifications while the app is in the background need this on Android 13+.
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    private fun hasMic() = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    override fun onStart() {
        super.onStart()
        app.store.setMicGranted(hasMic())
        app.store.setAppActive(true)
    }

    override fun onStop() {
        app.store.setAppActive(false)
        app.store.save()
        super.onStop()
    }
}
