package no.meltveit.cuecomm.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import no.meltveit.cuecomm.core.AppStore
import no.meltveit.cuecomm.core.Channel
import no.meltveit.cuecomm.core.ChannelGroup
import no.meltveit.cuecomm.core.Languages
import no.meltveit.cuecomm.core.Palette
import no.meltveit.cuecomm.core.Selection
import no.meltveit.cuecomm.core.StoreState
import no.meltveit.cuecomm.core.Text as TextUtil
import java.util.UUID

@Composable
fun ChannelEditorScreen(s: StoreState, store: AppStore, draft: Channel, isNew: Boolean, onClose: () -> Unit) {
    var ch by remember { mutableStateOf(draft) }
    var keywords by remember { mutableStateOf(draft.keywords.joinToString(", ")) }
    var newGroup by remember { mutableStateOf("") }

    fun save() {
        val saved = ch.copy(name = ch.name.ifBlank { "Channel" }, keywords = TextUtil.split(keywords))
        store.upsert(saved)
        store.broadcastChannels()
        if (isNew) store.select(Selection.Chan(saved.id))
        onClose()
    }

    ScreenScaffold(if (isNew) "New channel" else "Edit channel", onClose) {
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                HueBlock(hue(ch.colorHex), ch.initials, active = true, size = 56.dp)
                Text(ch.name.ifBlank { "Channel" }, fontSize = 22.sp, fontWeight = FontWeight.Bold, color = hue(ch.colorHex))
            }

            Panel {
                SectionLabel("Name and colour")
                LabeledField("Channel name", ch.name, { ch = ch.copy(name = it) })
                SwatchRow(Palette.hexes, ch.colorHex, { ch = ch.copy(colorHex = it) })
                val groups = s.groups.filter { !it.isRemote || ch.isRemote }
                Picker<UUID?>("Group", listOf<Pair<UUID?, String>>(null to "No group") + groups.map { it.id to it.name }, ch.groupID, { ch = ch.copy(groupID = it) })
                Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    LabeledField("", newGroup, { newGroup = it }, Modifier.weight(1f), placeholder = "New group")
                    QuietButton("Add", {
                        val g = ChannelGroup(name = newGroup.trim())
                        store.upsert(g); ch = ch.copy(groupID = g.id); newGroup = ""
                    }, enabled = newGroup.isNotBlank())
                }
                ToggleRow(if (ch.pinned) "Pinned to the top of the list" else "Pin to the top of the list", ch.pinned, { ch = ch.copy(pinned = it) })
            }

            if (!ch.isRemote) {
                Panel {
                    SectionLabel("Language")
                    val speech = (Languages.speech.map { it.code to it.name } + (ch.localeID to ch.localeID)).distinctBy { it.first }
                    Picker("Spoken language", speech, ch.localeID, { ch = ch.copy(localeID = it) })
                    ToggleRow("Translate to ${Languages.translation.firstOrNull { it.code == s.settings.preferredLanguage }?.name ?: s.settings.preferredLanguage}", ch.translate, { ch = ch.copy(translate = it) })
                    ToggleRow("Use offline recognition when available", ch.onDeviceOnly, { ch = ch.copy(onDeviceOnly = it) },
                        subtitle = "Faster and private. Needs the language downloaded in Android's speech settings.")
                    ToggleRow("Can be spoken into", ch.enabled, { ch = ch.copy(enabled = it) }, subtitle = "Off mutes this channel.")
                }
            } else {
                Panel {
                    SectionLabel("Network channel")
                    Text("Shared by ${ch.ownerName ?: "another device"}. Messages you type here go back to everyone sharing it.", fontSize = 15.sp, color = T.dim)
                    ToggleRow("Translate incoming messages", ch.translate, { ch = ch.copy(translate = it) })
                }
            }

            Panel {
                SectionLabel("Keywords")
                LabeledField("Words to flag on this channel (comma separated)", keywords, { keywords = it }, singleLine = false)
                Text(
                    "Matches are marked yellow, and the channel badge turns yellow with a flag if one arrives while you are looking elsewhere. To send OSC when a word is said, add it under Cues.",
                    fontSize = 13.sp, color = T.faint,
                )
            }

            if (!ch.isRemote) {
                Panel {
                    SectionLabel("Network")
                    if (s.inSession) {
                        ToggleRow("Keep this channel private to this device", ch.isPrivate, { ch = ch.copy(isPrivate = it) })
                        Text("Otherwise it is shared with session “${s.settings.sessionName}”.", fontSize = 13.sp, color = T.faint)
                    } else {
                        ToggleRow("Share this channel on the local network", ch.shared, { ch = ch.copy(shared = it) })
                        if (!s.settings.networkEnabled && ch.shared) {
                            Text("Open sharing is off. Turn it on, or start a session, from the Session screen.", fontSize = 13.sp, color = T.cue)
                        }
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
        }
        Hairline()
        Row(Modifier.fillMaxWidth().padding(14.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            if (!isNew) QuietButton(if (ch.isRemote) "Remove" else "Delete", { store.delete(ch.id); onClose() }, tint = T.danger)
            Box(Modifier.weight(1f))
            QuietButton("Cancel", onClose)
            ActionButton(if (isNew) "Add channel" else "Save", ::save)
        }
    }
}

@Composable
fun GroupEditorScreen(s: StoreState, store: AppStore, id: UUID?, onClose: () -> Unit) {
    val existing = s.group(id)
    var group by remember { mutableStateOf(existing ?: ChannelGroup(name = "New Group")) }
    ScreenScaffold(if (existing != null) "Edit group" else "New group", onClose) {
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            LabeledField("Group name", group.name, { group = group.copy(name = it) })
            if (!s.inSession) {
                ToggleRow("Share every channel in this group on the network", group.shared, { group = group.copy(shared = it) }, enabled = !group.isRemote)
            }
        }
        Hairline()
        Row(Modifier.fillMaxWidth().padding(14.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            if (existing != null && !group.isRemote) QuietButton("Delete group", { store.deleteGroup(group.id); onClose() }, tint = T.danger)
            Box(Modifier.weight(1f))
            QuietButton("Cancel", onClose)
            ActionButton("Save", { store.upsert(group.copy(name = group.name.ifBlank { "Group" })); store.broadcastChannels(); onClose() })
        }
    }
}
