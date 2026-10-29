package no.meltveit.cuecomm.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
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
import no.meltveit.cuecomm.core.AutomationTrigger
import no.meltveit.cuecomm.core.Palette
import no.meltveit.cuecomm.core.ScrollTarget
import no.meltveit.cuecomm.core.StoreState
import no.meltveit.cuecomm.core.TriggerKind
import java.util.UUID

private fun triggerSummary(t: AutomationTrigger) = when (t.kind) {
    TriggerKind.Keyword -> "Hears “${t.phrase}”"
    TriggerKind.Message -> "Any message arrives"
    TriggerKind.Osc -> "OSC ${t.oscAddress}"
    TriggerKind.Midi -> "MIDI (Mac only)"
}

@Composable
fun AutomationsScreen(s: StoreState, store: AppStore, onEdit: (UUID?) -> Unit, onBack: () -> Unit) {
    ScreenScaffold("Automations", onBack) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                "A trigger (a word heard, any message, an incoming OSC message) runs one or more actions. Quick cue words live under Cues. Setup files from CueComm for Mac work here too; MIDI actions are skipped on Android.",
                fontSize = 14.sp, color = T.dim,
            )
            ActionButton("New automation", { onEdit(null) }, icon = Icons.Filled.Add, modifier = Modifier.fillMaxWidth())
            for (a in s.automations) {
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(T.raised).clickable { onEdit(a.id) }.padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(a.name, fontSize = 17.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis, color = if (a.enabled) T.text else T.faint)
                        Text(triggerSummary(a.trigger) + "  →  " + a.actions.joinToString(", ") { it.kind.label }, fontSize = 13.sp, color = T.dim, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        if (a.isCue) Text("Cue word", fontSize = 12.sp, color = T.cue, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
            if (s.automations.isEmpty()) Text("No automations yet.", fontSize = 15.sp, color = T.faint)
            Spacer(Modifier.height(24.dp))
        }
    }
}

private val supportedTriggers = listOf(TriggerKind.Keyword, TriggerKind.Message, TriggerKind.Osc)

@Composable
fun AutomationEditorScreen(s: StoreState, store: AppStore, id: UUID?, onClose: () -> Unit) {
    val existing = s.automations.firstOrNull { it.id == id }
    var a by remember { mutableStateOf(existing ?: Automation(trigger = AutomationTrigger(kind = TriggerKind.Keyword), actions = listOf(AutomationAction(kind = ActionKind.FlashWindow)))) }

    fun setAction(i: Int, f: (AutomationAction) -> AutomationAction) { a = a.copy(actions = a.actions.mapIndexed { j, x -> if (j == i) f(x) else x }) }
    val channelOptions = listOf<Pair<UUID?, String>>(null to "Any channel") + s.channels.map { it.id to it.name }
    val currentChannelOptions = listOf<Pair<UUID?, String>>(null to "Current selection") + s.channels.map { it.id to it.name }

    ScreenScaffold(if (existing != null) "Edit automation" else "New automation", onClose) {
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Panel {
                SectionLabel("Automation")
                LabeledField("Name", a.name, { a = a.copy(name = it) })
                ToggleRow("Enabled", a.enabled, { a = a.copy(enabled = it) })
                SyncedField("Minimum seconds between runs", a.cooldown.toString(), { v -> v.toDoubleOrNull()?.let { a = a.copy(cooldown = it.coerceAtLeast(0.0)) } }, keyboardType = KeyboardType.Decimal)
            }

            Panel {
                SectionLabel("When")
                val kinds = (supportedTriggers + (if (a.trigger.kind == TriggerKind.Midi) listOf(TriggerKind.Midi) else emptyList()))
                Picker("Trigger", kinds.map { it to (it.label + if (it == TriggerKind.Midi) " (Mac only)" else "") }, a.trigger.kind, { a = a.copy(trigger = a.trigger.copy(kind = it)) })
                when (a.trigger.kind) {
                    TriggerKind.Keyword -> {
                        LabeledField("Words or phrases (comma separated)", a.trigger.phrase, { a = a.copy(trigger = a.trigger.copy(phrase = it)) })
                        ToggleRow("Whole words only", a.trigger.wholeWord, { a = a.copy(trigger = a.trigger.copy(wholeWord = it)) }, subtitle = "Off also matches inside longer words.")
                        Picker("Listens on", channelOptions, a.trigger.channelID, { a = a.copy(trigger = a.trigger.copy(channelID = it)) })
                    }
                    TriggerKind.Message -> Picker("Listens on", channelOptions, a.trigger.channelID, { a = a.copy(trigger = a.trigger.copy(channelID = it)) })
                    TriggerKind.Osc -> {
                        LabeledField("OSC address (* is a wildcard)", a.trigger.oscAddress, { a = a.copy(trigger = a.trigger.copy(oscAddress = it.trim())) }, placeholder = "/go/*", keyboardType = KeyboardType.Uri)
                        Text("Turn on “Listen for incoming OSC” in Settings, and send to this device's address on the OSC port.", fontSize = 13.sp, color = T.faint)
                    }
                    TriggerKind.Midi -> Text("MIDI triggers are not available on Android.", fontSize = 14.sp, color = T.faint)
                }
                if (s.inSession && a.trigger.kind != TriggerKind.Osc && a.trigger.kind != TriggerKind.Midi) {
                    ToggleRow("Also fire for other devices' speech", a.trigger.includeRemote, { a = a.copy(trigger = a.trigger.copy(includeRemote = it)) })
                }
            }

            Panel {
                SectionLabel("Then")
                a.actions.forEachIndexed { i, act ->
                    key(act.id) {
                        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(T.ground).padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            val label = { k: ActionKind -> k.label + if (k in macOnly) " (Mac only)" else "" }
                            Picker("Action", ActionKind.entries.map { it to label(it) }, act.kind, { v -> setAction(i) { it.copy(kind = v) } })
                            ActionFields(act, s, currentChannelOptions) { f -> setAction(i, f) }
                            SyncedField("Delay in seconds", act.delay.toString(), { v -> v.toDoubleOrNull()?.let { d -> setAction(i) { it.copy(delay = d.coerceAtLeast(0.0)) } } }, keyboardType = KeyboardType.Decimal)
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                                QuietButton("Remove action", { a = a.copy(actions = a.actions.filterIndexed { j, _ -> j != i }) }, tint = T.danger, icon = Icons.Filled.Delete, size = 13.sp)
                            }
                        }
                    }
                }
                QuietButton("Add action", { a = a.copy(actions = a.actions + AutomationAction(kind = ActionKind.SendOSC, oscHost = s.settings.oscDefaultHost, oscPort = s.settings.oscDefaultPort)) }, icon = Icons.Filled.Add)
            }
            Spacer(Modifier.height(8.dp))
        }
        Hairline()
        Row(Modifier.fillMaxWidth().padding(14.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            if (existing != null) QuietButton("Delete", { store.deleteAutomation(a.id); onClose() }, tint = T.danger)
            QuietButton("Test", { store.fire(a, ActionContext("test", "Test", a.trigger.phrase.substringBefore(',').trim()), force = true) }, icon = Icons.Filled.PlayArrow)
            Box(Modifier.weight(1f))
            QuietButton("Cancel", onClose)
            ActionButton("Save", { store.upsert(a.copy(name = a.name.ifBlank { "Automation" })); onClose() })
        }
    }
}

private val macOnly = setOf(ActionKind.SendMIDI, ActionKind.ShowWindow, ActionKind.HideWindow)

@Composable
private fun ActionFields(act: AutomationAction, s: StoreState, channelOptions: List<Pair<UUID?, String>>, change: ((AutomationAction) -> AutomationAction) -> Unit) {
    when (act.kind) {
        ActionKind.SendOSC -> {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SyncedField("Host", act.oscHost, { v -> change { it.copy(oscHost = v.trim()) } }, Modifier.weight(1f), keyboardType = KeyboardType.Uri)
                LabeledIntField("Port", act.oscPort, { v -> change { it.copy(oscPort = v.coerceIn(1, 65535)) } }, Modifier.width(104.dp))
            }
            SyncedField("OSC address", act.oscAddress, { v -> change { it.copy(oscAddress = v.trim()) } }, placeholder = "/cue", keyboardType = KeyboardType.Uri)
            SyncedField("Arguments (optional)", act.oscArgs, { v -> change { it.copy(oscArgs = v) } }, placeholder = "1  0.5  \"text\"  {text} {channel} {keyword}")
        }
        ActionKind.FlashWindow -> {
            Text("Flash colour", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = T.dim)
            SwatchRow(Palette.flash, act.flashColorHex, { v -> change { it.copy(flashColorHex = v) } }, size = 30.dp)
            ToggleRow("Flash every device in the session", act.flashEveryone, { v -> change { it.copy(flashEveryone = v) } })
        }
        ActionKind.SelectChannel, ActionKind.ClearTranscript, ActionKind.MarkRead ->
            Picker("Channel", channelOptions, act.targetChannelID, { v -> change { it.copy(targetChannelID = v) } })
        ActionKind.ScrollTranscript ->
            Picker("Scroll", ScrollTarget.entries.map { it to it.label }, act.scrollTarget, { v -> change { it.copy(scrollTarget = v) } })
        ActionKind.AdjustFontSize ->
            Picker("Change text size by", listOf(-4, -2, 2, 4).map { it to (if (it > 0) "+$it" else "$it") }, act.fontDelta, { v -> change { it.copy(fontDelta = v) } })
        ActionKind.SendMIDI, ActionKind.ShowWindow, ActionKind.HideWindow ->
            Text("This action only runs on CueComm for Mac. It is kept so the setup file stays complete.", fontSize = 13.sp, color = T.faint)
    }
}
