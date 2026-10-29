package no.meltveit.cuecomm.ui

import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import no.meltveit.cuecomm.core.AppSettings
import no.meltveit.cuecomm.core.AppStore
import no.meltveit.cuecomm.core.Languages
import no.meltveit.cuecomm.core.StoreState
import kotlin.math.roundToInt

@Composable
fun SettingsScreen(s: StoreState, store: AppStore, onOpenAppSettings: () -> Unit, onBack: () -> Unit) {
    val ctx = LocalContext.current
    var pendingImport by remember { mutableStateOf<String?>(null) }
    var importMessage by remember { mutableStateOf<String?>(null) }
    val set = s.settings

    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) runCatching { ctx.contentResolver.openOutputStream(uri)?.use { it.write(store.exportConfiguration().toByteArray()) } }
            .onFailure { store.addLog("Export failed: ${it.message}") }
    }
    val import = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) pendingImport = runCatching { ctx.contentResolver.openInputStream(uri)?.use { String(it.readBytes()) } }.getOrNull()
            ?: run { importMessage = "Could not read that file."; null }
    }

    fun update(f: (AppSettings) -> AppSettings) = store.updateSettings(f)

    ScreenScaffold("Settings", onBack) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Panel {
                SectionLabel("Transcript")
                Text("Text size: ${set.fontSize.roundToInt()} sp", fontSize = 15.sp, color = T.dim)
                Slider(set.fontSize.toFloat(), { v -> update { it.copy(fontSize = v.roundToInt().toDouble()) } }, valueRange = 12f..48f, colors = sliderColors())
                ToggleRow("Show timestamps", set.showTimestamps, { v -> update { it.copy(showTimestamps = v) } })
                ToggleRow("Filter offensive language", set.filterOffensive, { v -> update { it.copy(filterOffensive = v) } })
                Text("History per channel: ${set.historyLimit}", fontSize = 15.sp, color = T.dim)
                Slider(set.historyLimit.toFloat(), { v -> update { it.copy(historyLimit = (v / 100).roundToInt() * 100) } }, valueRange = 100f..5000f, colors = sliderColors())
            }

            Panel {
                SectionLabel("Listening")
                Text("Pause that ends a message: ${"%.1f".format(set.silenceCommit)} s", fontSize = 15.sp, color = T.dim)
                Slider(set.silenceCommit.toFloat(), { v -> update { it.copy(silenceCommit = (v * 10).roundToInt() / 10.0) } }, valueRange = 0.5f..3f, colors = sliderColors())
                ToggleRow(
                    "Fire keyword automations before the sentence ends", set.triggerOnPartials, { v -> update { it.copy(triggerOnPartials = v) } },
                    subtitle = "Faster cues, but a word that is later corrected by the recogniser may still trigger.",
                )
                Text(
                    "Android's speech recogniser listens through one microphone at a time, so each device speaks into one channel: the one you are viewing when you press Listen, or the one you chose with “Speak on this channel”. A USB or Bluetooth microphone that Android routes as the input works as usual.",
                    fontSize = 13.sp, color = T.faint,
                )
            }

            Panel {
                SectionLabel("Translation")
                Picker("Translate into", Languages.translation.map { it.code to it.name }, set.preferredLanguage, { v -> update { it.copy(preferredLanguage = v) } })
                Text("Turn translation on per channel in the channel editor. The language pack (about 30 MB) downloads the first time it is needed.", fontSize = 13.sp, color = T.faint)
            }

            Panel {
                SectionLabel("OSC")
                ToggleRow("Listen for incoming OSC (UDP)", set.oscListenEnabled, { v -> update { it.copy(oscListenEnabled = v) } })
                LabeledIntField("Listen port", set.oscListenPort, { v -> update { it.copy(oscListenPort = v.coerceIn(1024, 65535)) } }, Modifier.width(150.dp))
                SyncedField("Default OSC host for new cue words", set.oscDefaultHost, { v -> update { it.copy(oscDefaultHost = v.trim()) } }, keyboardType = KeyboardType.Uri)
                LabeledIntField("Default OSC port", set.oscDefaultPort, { v -> update { it.copy(oscDefaultPort = v.coerceIn(1, 65535)) } }, Modifier.width(150.dp))
            }

            Panel {
                SectionLabel("This device")
                ToggleRow("Flash this screen when another device fires a cue", set.flashOnRemoteCues, { v -> update { it.copy(flashOnRemoteCues = v) } })
                ToggleRow("Vibrate on cues", set.vibrateOnCue, { v -> update { it.copy(vibrateOnCue = v) } })
                ToggleRow("Keep the screen on during a session or while listening", set.keepScreenOn, { v -> update { it.copy(keepScreenOn = v) } })
            }

            Panel {
                SectionLabel("Share your setup")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    QuietButton("Export setup…", { export.launch("CueComm Setup.cuecomm.json") })
                    QuietButton("Import setup…", { import.launch(arrayOf("application/json", "text/plain", "application/octet-stream", "*/*")) })
                }
                Text("A setup file holds your channels, groups and automations. It is the same format CueComm for Mac uses, so crews can reuse proven configurations on either.", fontSize = 13.sp, color = T.faint)
                importMessage?.let { Text(it, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = T.dim) }
            }

            Panel {
                SectionLabel("Permissions")
                Text("Microphone: ${if (s.micGranted) "Allowed" else "Not allowed"}", fontSize = 15.sp, color = T.dim)
                QuietButton("Open Android settings", onOpenAppSettings)
            }
            Spacer(Modifier.height(24.dp))
        }
    }

    pendingImport?.let { text ->
        AlertDialog(
            onDismissRequest = { pendingImport = null },
            containerColor = T.raised,
            title = { Text("Import setup") },
            text = { Text("Merge adds the file's channels and automations to yours. Replace discards your current channels, groups and automations first.") },
            confirmButton = {
                Row {
                    TextButton({ importMessage = store.importConfiguration(text, replace = false) ?: "Setup merged."; pendingImport = null }) { Text("Merge") }
                    TextButton({ importMessage = store.importConfiguration(text, replace = true) ?: "Setup replaced."; pendingImport = null }) { Text("Replace") }
                }
            },
            dismissButton = { TextButton({ pendingImport = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun sliderColors() = SliderDefaults.colors(thumbColor = T.orange, activeTrackColor = T.orange, inactiveTrackColor = T.hairline)
