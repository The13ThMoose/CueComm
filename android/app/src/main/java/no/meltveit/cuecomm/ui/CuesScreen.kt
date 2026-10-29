package no.meltveit.cuecomm.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import no.meltveit.cuecomm.core.ActionContext
import no.meltveit.cuecomm.core.ActionKind
import no.meltveit.cuecomm.core.AppStore
import no.meltveit.cuecomm.core.Automation
import no.meltveit.cuecomm.core.AutomationAction
import no.meltveit.cuecomm.core.Palette
import no.meltveit.cuecomm.core.StoreState
import no.meltveit.cuecomm.core.Text as TextUtil
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.UUID

private val clock: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault())

/** Cue words: say a word, send OSC, flash the screen. Each cue is a keyword automation under the hood. */
@Composable
fun CuesScreen(s: StoreState, store: AppStore, onListen: () -> Unit, onAdvanced: () -> Unit, onBack: () -> Unit) {
    var newWord by remember { mutableStateOf("") }
    var showTarget by remember { mutableStateOf(false) }
    val cues = s.automations.filter { it.isCue }
    fun add() { store.addCue(newWord); newWord = "" }

    ScreenScaffold("Cue Words", onBack) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text("Say the word: OSC goes out and this screen flashes.", fontSize = 15.sp, color = T.dim)

            if (s.isListening) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(Icons.Filled.CheckCircle, null, tint = T.live)
                    Text("Armed: listening as ${s.speakingChannel()?.name ?: "a channel"}", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = T.live)
                }
            } else {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Icon(Icons.Filled.Warning, null, tint = T.orange)
                    Text("Not listening: cues will not fire", Modifier.weight(1f), fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = T.orange)
                    ActionButton("Listen", onListen, icon = Icons.Filled.Mic, size = 14.sp)
                }
            }

            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                LabeledField("", newWord, { newWord = it }, Modifier.weight(1f), placeholder = "New cue word, e.g. GO", onDone = ::add)
                ActionButton("Add", ::add, fill = T.cue, enabled = newWord.isNotBlank())
            }

            Column {
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    QuietButton(
                        "Default OSC target: ${s.settings.oscDefaultHost}:${s.settings.oscDefaultPort}", { showTarget = !showTarget },
                        icon = if (showTarget) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore, size = 14.sp,
                    )
                }
                if (showTarget) {
                    Column(Modifier.padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            SyncedField("Host", s.settings.oscDefaultHost, { v -> store.updateSettings { it.copy(oscDefaultHost = v.trim()) } }, Modifier.weight(1f), keyboardType = KeyboardType.Uri)
                            LabeledIntField("Port", s.settings.oscDefaultPort, { v -> store.updateSettings { it.copy(oscDefaultPort = v.coerceIn(1, 65535)) } }, Modifier.width(110.dp))
                        }
                        Text("New cue words start with this OSC target. Each cue can override it. On this device, 127.0.0.1 means the phone itself: use your lighting desk's address.", fontSize = 13.sp, color = T.faint)
                    }
                }
            }

            Hairline()

            if (cues.isEmpty()) {
                Column(Modifier.fillMaxWidth().padding(top = 20.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Icon(Icons.Filled.Bolt, null, tint = T.cue, modifier = Modifier.size(32.dp))
                    Text("No cue words yet", fontSize = 19.sp, fontWeight = FontWeight.Bold)
                    Text("Add the words your crew says to fire a cue.", fontSize = 15.sp, color = T.dim)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        for (w in listOf("GO", "STANDBY", "CUE")) QuietButton(w, { store.addCue(w) }, tint = T.cue, size = 14.sp)
                    }
                }
            }
            for (a in cues) key(a.id) { CueCard(a, s, store) }

            QuietButton("Advanced automations…", onAdvanced, modifier = Modifier.fillMaxWidth())

            if (s.log.isNotEmpty()) {
                Hairline()
                SectionLabel("Recent activity")
                for (e in s.log.take(30)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(clock.format(e.date), fontSize = 13.sp, color = T.faint, fontWeight = FontWeight.Medium)
                        Text(e.text, fontSize = 13.sp, color = T.dim, modifier = Modifier.weight(1f))
                    }
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

/** One cue: a single scannable row, expanded on demand to edit the OSC target and flash colour. */
@Composable
private fun CueCard(a: Automation, s: StoreState, store: AppStore) {
    var expanded by remember { mutableStateOf(false) }
    val osc = a.actions.firstOrNull { it.kind == ActionKind.SendOSC }
    val flash = a.actions.firstOrNull { it.kind == ActionKind.FlashWindow }
    val connected = s.inSession || s.peers.isNotEmpty()

    fun update(f: (Automation) -> Automation) = store.upsert(f(a))
    fun mapAction(kind: ActionKind, f: (AutomationAction) -> AutomationAction) =
        update { it.copy(actions = it.actions.map { x -> if (x.kind == kind) f(x) else x }) }

    val summary = buildList {
        osc?.let { add("OSC ${it.oscAddress} → ${it.oscHost}:${it.oscPort}") }
        flash?.let { add(if (connected && it.flashEveryone) "Flash on all devices" else "Flash") }
    }.ifEmpty { listOf("Does nothing: turn on OSC or Flash") }.joinToString("  ·  ")

    Panel(borderColor = if (a.enabled) T.cue.copy(alpha = 0.55f) else T.hairline) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            QuietButton("", { expanded = !expanded }, icon = if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore, modifier = Modifier.width(48.dp))
            Column(Modifier.weight(1f)) {
                SyncedField("", a.trigger.phrase, { v -> update { it.copy(name = "Cue: $v", trigger = it.trigger.copy(phrase = v)) } }, fontSize = 22.sp)
            }
            QuietButton("", { store.fire(a, ActionContext("test", "Test", a.trigger.phrase), force = true) }, icon = Icons.Filled.PlayArrow, modifier = Modifier.width(48.dp))
            Switch(
                checked = a.enabled, onCheckedChange = { v -> update { it.copy(enabled = v) } },
                colors = SwitchDefaults.colors(checkedThumbColor = T.ink, checkedTrackColor = T.orange, uncheckedThumbColor = T.dim, uncheckedTrackColor = T.hairline, uncheckedBorderColor = T.faint),
            )
        }
        Text(summary, fontSize = 14.sp, color = T.dim)
        s.lastFired[a.id]?.let { Text("Fired ${clock.format(it)}", fontSize = 13.sp, fontWeight = FontWeight.Medium, color = T.cue) }

        if (expanded) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                QuietButton("Send OSC", {
                    if (osc == null) {
                        val action = AutomationAction(
                            kind = ActionKind.SendOSC, oscHost = s.settings.oscDefaultHost, oscPort = s.settings.oscDefaultPort,
                            oscAddress = "/cue/" + TextUtil.slug(a.trigger.phrase),
                        )
                        update { it.copy(actions = listOf(action) + it.actions) }
                    } else update { it.copy(actions = it.actions.filterNot { x -> x.kind == ActionKind.SendOSC }) }
                }, tint = T.orange, active = osc != null, icon = if (osc != null) Icons.Filled.CheckCircle else Icons.Filled.RadioButtonUnchecked, size = 14.sp)
                QuietButton("Flash", {
                    if (flash == null) update { it.copy(actions = it.actions + AutomationAction(kind = ActionKind.FlashWindow)) }
                    else update { it.copy(actions = it.actions.filterNot { x -> x.kind == ActionKind.FlashWindow }) }
                }, tint = T.cue, active = flash != null, icon = if (flash != null) Icons.Filled.CheckCircle else Icons.Filled.RadioButtonUnchecked, size = 14.sp)
            }

            if (osc != null) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SyncedField("Host", osc.oscHost, { v -> mapAction(ActionKind.SendOSC) { it.copy(oscHost = v.trim()) } }, Modifier.weight(1f), keyboardType = KeyboardType.Uri)
                    LabeledIntField("Port", osc.oscPort, { v -> mapAction(ActionKind.SendOSC) { it.copy(oscPort = v.coerceIn(1, 65535)) } }, Modifier.width(104.dp))
                }
                SyncedField("OSC address", osc.oscAddress, { v -> mapAction(ActionKind.SendOSC) { it.copy(oscAddress = v.trim()) } }, placeholder = "/cue/go", keyboardType = KeyboardType.Uri)
                SyncedField("Arguments (optional)", osc.oscArgs, { v -> mapAction(ActionKind.SendOSC) { it.copy(oscArgs = v) } }, placeholder = "1  0.5  \"text\"  {text}")
            }

            if (flash != null) {
                Text("Flash colour", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = T.dim)
                SwatchRow(Palette.flash, flash.flashColorHex, { v -> mapAction(ActionKind.FlashWindow) { it.copy(flashColorHex = v) } }, size = 30.dp)
                if (connected) {
                    ToggleRow(
                        "Flash every device in the session", flash.flashEveryone, { v -> mapAction(ActionKind.FlashWindow) { it.copy(flashEveryone = v) } },
                        subtitle = "The device that hears the cue word tells the others to flash too. OSC still goes out from this device only.",
                    )
                }
            }

            Picker<UUID?>(
                "Listens on", listOf<Pair<UUID?, String>>(null to "Any channel") + s.channels.map { it.id to it.name },
                a.trigger.channelID, { v -> update { it.copy(trigger = it.trigger.copy(channelID = v)) } },
            )
            if (s.inSession) {
                ToggleRow(
                    "Also fire for other devices' speech", a.trigger.includeRemote, { v -> update { it.copy(trigger = it.trigger.copy(includeRemote = v)) } },
                    subtitle = "Off: only words heard on this device send OSC, so a lighting desk gets one GO, not one per device.",
                )
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                QuietButton("Delete cue", { store.deleteAutomation(a.id) }, tint = T.danger, icon = Icons.Filled.Delete, size = 14.sp)
            }
        }
    }
}
