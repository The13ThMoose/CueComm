package no.meltveit.cuecomm.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.Text
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import no.meltveit.cuecomm.R
import no.meltveit.cuecomm.core.AppStore
import no.meltveit.cuecomm.core.Channel
import no.meltveit.cuecomm.core.ChannelGroup
import no.meltveit.cuecomm.core.CueEvent
import no.meltveit.cuecomm.core.Message
import no.meltveit.cuecomm.core.Offensive
import no.meltveit.cuecomm.core.Selection
import no.meltveit.cuecomm.core.StoreState
import no.meltveit.cuecomm.core.Text as TextUtil
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.UUID

class MainActions(
    val onListen: () -> Unit,
    val onShare: (text: String, csv: Boolean) -> Unit,
    val onOpenAppSettings: () -> Unit,
)

@Composable
fun MainScreen(s: StoreState, store: AppStore, nav: Nav, actions: MainActions) {
    BoxWithConstraints(Modifier.fillMaxSize().background(T.ground)) {
        if (maxWidth >= 720.dp) {
            Row(Modifier.fillMaxSize()) {
                Sidebar(s, store, nav, Modifier.width(300.dp), onPicked = {})
                Box(Modifier.width(1.dp).fillMaxHeight().background(T.hairline))
                Detail(s, store, nav, actions, compact = false, onMenu = {}, Modifier.weight(1f))
            }
        } else {
            val drawer = rememberDrawerState(DrawerValue.Closed)
            val scope = rememberCoroutineScope()
            ModalNavigationDrawer(
                drawerState = drawer,
                drawerContent = {
                    ModalDrawerSheet(drawerContainerColor = T.panel, modifier = Modifier.width(318.dp)) {
                        Sidebar(s, store, nav, Modifier.fillMaxSize(), onPicked = { scope.launch { drawer.close() } })
                    }
                },
            ) {
                Detail(s, store, nav, actions, compact = true, onMenu = { scope.launch { drawer.open() } }, Modifier.fillMaxSize())
            }
        }
    }
}

// MARK: - Sidebar

@Composable
private fun Sidebar(s: StoreState, store: AppStore, nav: Nav, modifier: Modifier, onPicked: () -> Unit) {
    fun ordered(list: List<Channel>) = list.sortedWith(compareBy({ if (it.pinned) 0 else 1 }, { it.name.lowercase() }))
    Column(modifier.background(T.panel).statusBarsPadding()) {
        Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("CUECOMM", fontSize = 15.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = 3.sp)
            SessionPill(s) { onPicked(); nav.push(Route.Session) }
        }

        androidx.compose.foundation.lazy.LazyColumn(Modifier.weight(1f).padding(horizontal = 10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            item { AllChannelsRow(s) { store.select(Selection.All); onPicked() } }

            val pinned = s.channels.filter { it.pinned }
            if (pinned.isNotEmpty()) {
                item { SectionLabel("Pinned", Modifier.padding(start = 10.dp, top = 14.dp), 14.sp) }
                items(ordered(pinned), key = { "c" + it.id }) { ChannelRow(s, store, nav, it, onPicked) }
            }
            for (g in s.groups) {
                val all = s.channels.filter { it.groupID == g.id }
                val members = ordered(all.filter { !it.pinned })
                if (members.isNotEmpty() || all.isEmpty()) {
                    item(key = "g" + g.id) { GroupHeader(s, store, nav, g, onPicked) }
                    items(members, key = { "c" + it.id }) { ChannelRow(s, store, nav, it, onPicked) }
                }
            }
            val loose = ordered(s.channels.filter { it.groupID == null && !it.pinned })
            if (loose.isNotEmpty()) {
                item { SectionLabel("Channels", Modifier.padding(start = 10.dp, top = 14.dp), 14.sp) }
                items(loose, key = { "c" + it.id }) { ChannelRow(s, store, nav, it, onPicked) }
            }
        }

        Column(Modifier.fillMaxWidth().background(T.panel).navigationBarsPadding().padding(14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                QuietButton("Channel", { onPicked(); nav.push(Route.ChannelEditor(store.newChannelDraft(), true)) }, icon = Icons.Filled.Add, size = 14.sp)
                QuietButton("Group", { onPicked(); nav.push(Route.GroupEditor(null)) }, icon = Icons.Filled.CreateNewFolder, size = 14.sp)
            }
            Image(
                painterResource(R.drawable.meltveit_logo), "Meltveit Lydproduksjon",
                Modifier.width(140.dp).align(Alignment.CenterHorizontally), contentScale = ContentScale.FillWidth,
            )
        }
    }
}

@Composable
private fun SessionPill(s: StoreState, onClick: () -> Unit) {
    val tint = when {
        !s.inSession -> T.faint
        s.peers.isEmpty() -> T.orange
        else -> T.live
    }
    val title = if (s.inSession) s.settings.sessionName else "No session"
    val subtitle = when {
        !s.inSession -> if (s.settings.networkEnabled) "Open sharing on" else "Host or join"
        s.peers.isEmpty() -> "Waiting for others…"
        else -> "${s.peers.size + 1} devices online"
    }
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(T.raised)
            .border(1.5.dp, if (s.inSession) tint.copy(alpha = 0.7f) else T.hairline, RoundedCornerShape(12.dp))
            .clickable(onClick = onClick).padding(12.dp).semantics { contentDescription = "Session: $title, $subtitle" },
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(Icons.Filled.Public, null, tint = tint, modifier = Modifier.size(22.dp))
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 16.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(subtitle, fontSize = 13.sp, color = if (tint == T.faint) T.dim else tint)
        }
        Icon(Icons.Filled.ChevronRight, null, tint = T.faint, modifier = Modifier.size(20.dp))
    }
}

@Composable
private fun RowBg(selected: Boolean, tint: Color) =
    Modifier.clip(RoundedCornerShape(12.dp)).background(if (selected) tint.copy(alpha = 0.16f) else Color.Transparent)
        .border(1.5.dp, if (selected) tint else Color.Transparent, RoundedCornerShape(12.dp))

@Composable
private fun AllChannelsRow(s: StoreState, onClick: () -> Unit) {
    val unread = s.channels.sumOf { s.unread[it.id] ?: 0 }
    val missed = s.channels.any { it.id in s.missed }
    Row(
        RowBg(s.selection == Selection.All, T.text).clickable(onClick = onClick).padding(8.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        StripBlock(s.channels.map { hue(it.colorHex) })
        Column(Modifier.weight(1f)) {
            Text("All Channels", fontSize = 17.sp, fontWeight = FontWeight.Bold)
            Text("${s.channels.size} channels", fontSize = 13.sp, color = T.dim)
        }
        if (unread > 0) UnreadBadge(unread, missed)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun GroupHeader(s: StoreState, store: AppStore, nav: Nav, g: ChannelGroup, onPicked: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    Box {
        Row(
            Modifier.fillMaxWidth().padding(top = 14.dp).clip(RoundedCornerShape(8.dp))
                .combinedClickable(onClick = { store.select(Selection.Group(g.id)); onPicked() }, onLongClick = { menu = true })
                .padding(start = 10.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(g.name, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = if (s.selection == Selection.Group(g.id)) T.text else T.dim)
            if (g.isRemote) Icon(Icons.Filled.Public, "Shared", tint = T.faint, modifier = Modifier.size(14.dp))
        }
        DropdownMenu(menu, { menu = false }, containerColor = T.raised) {
            MenuItem("Show group transcript") { menu = false; store.select(Selection.Group(g.id)); onPicked() }
            MenuItem("Mark group as read") { menu = false; store.markRead(Selection.Group(g.id)) }
            MenuItem("Clear group transcript") { menu = false; store.clear(Selection.Group(g.id)) }
            if (!g.isRemote) MenuItem("Edit group…") { menu = false; onPicked(); nav.push(Route.GroupEditor(g.id)) }
            if (!g.isRemote) MenuItem("Delete group", T.danger) { menu = false; store.deleteGroup(g.id) }
        }
    }
}

@Composable
private fun MenuItem(text: String, color: Color = T.text, onClick: () -> Unit) {
    DropdownMenuItem(text = { Text(text, fontSize = 16.sp, color = color) }, onClick = onClick)
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ChannelRow(s: StoreState, store: AppStore, nav: Nav, ch: Channel, onPicked: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    val unread = s.unread[ch.id] ?: 0
    val level = s.levels[ch.id] ?: 0f
    val speakingHere = s.isListening && !ch.isRemote && s.speakingChannel()?.id == ch.id
    val speaking = (speakingHere && s.partials[ch.id] != null) || (speakingHere && level > 0.55f)
    val color = hue(ch.colorHex)
    val subtitle = when {
        ch.isRemote -> "From ${ch.ownerName ?: "another device"}"
        !ch.enabled -> "Muted"
        speakingHere -> s.status[ch.id] ?: "Listening…"
        else -> "Idle"
    }
    Box {
        Row(
            RowBg(s.selection == Selection.Chan(ch.id), color).combinedClickable(
                onClick = { store.select(Selection.Chan(ch.id)); onPicked() }, onLongClick = { menu = true },
            ).padding(8.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            HueBlock(color, ch.initials, speaking)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    Text(ch.name, fontSize = 17.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                    if (ch.pinned) Icon(Icons.Filled.PushPin, "Pinned", tint = T.orange, modifier = Modifier.size(14.dp))
                    if (s.isShared(ch)) Icon(Icons.Filled.Public, "Shared", tint = T.faint, modifier = Modifier.size(14.dp))
                    if (speakingHere) Icon(Icons.Filled.Mic, "Speaking channel", tint = T.live, modifier = Modifier.size(15.dp))
                }
                if (speakingHere) SegmentMeter(level, color) else Text(subtitle, fontSize = 13.sp, color = T.dim, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (unread > 0) UnreadBadge(unread, ch.id in s.missed)
        }
        DropdownMenu(menu, { menu = false }, containerColor = T.raised) {
            MenuItem("Edit channel…") { menu = false; onPicked(); nav.push(Route.ChannelEditor(ch, false)) }
            MenuItem(if (ch.pinned) "Unpin" else "Pin to top") { menu = false; store.upsert(ch.copy(pinned = !ch.pinned)) }
            if (!ch.isRemote) {
                MenuItem("Speak on this channel") { menu = false; store.setSpeaking(ch.id) }
                MenuItem(if (ch.enabled) "Mute" else "Unmute") { menu = false; store.upsert(ch.copy(enabled = !ch.enabled)) }
                if (s.inSession) MenuItem(if (ch.isPrivate) "Share with session" else "Keep private to this device") {
                    menu = false; store.upsert(ch.copy(isPrivate = !ch.isPrivate)); store.broadcastChannels()
                } else MenuItem(if (ch.shared) "Stop sharing on network" else "Share on network") {
                    menu = false; store.upsert(ch.copy(shared = !ch.shared)); store.broadcastChannels()
                }
            }
            MenuItem("Mark as read") { menu = false; store.markRead(Selection.Chan(ch.id)) }
            MenuItem("Clear transcript") { menu = false; store.clear(Selection.Chan(ch.id)) }
            MenuItem(if (ch.isRemote) "Remove network channel" else "Delete channel", T.danger) { menu = false; store.delete(ch.id) }
        }
    }
}

// MARK: - Detail

@Composable
private fun Detail(s: StoreState, store: AppStore, nav: Nav, actions: MainActions, compact: Boolean, onMenu: () -> Unit, modifier: Modifier) {
    Column(modifier.background(T.ground).statusBarsPadding().navigationBarsPadding().imePadding()) {
        HeaderBar(s, store, nav, actions, compact, onMenu)
        if (s.isListening && !s.micGranted) PermissionBanner("Microphone access is off for CueComm.", actions.onOpenAppSettings)
        AnimatedVisibility(s.lastCue != null, enter = fadeIn() + expandVertically(), exit = fadeOut() + shrinkVertically()) {
            s.lastCue?.let { Box(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 10.dp)) { CueBanner(it) } }
        }
        Box(Modifier.weight(1f)) { TranscriptView(s, store) }
        Hairline()
        Composer(s, store)
    }
}

@Composable
private fun HeaderBar(s: StoreState, store: AppStore, nav: Nav, actions: MainActions, compact: Boolean, onMenu: () -> Unit) {
    val tint = (s.selection as? Selection.Chan)?.let { s.channel(it.id) }?.let { hue(it.colorHex) } ?: T.text
    val speakingName = s.speakingChannel()?.name
    val subtitle = when (val sel = s.selection) {
        is Selection.Chan -> {
            val c = s.channel(sel.id)
            when {
                c == null -> ""
                c.isRemote -> "Shared by ${c.ownerName ?: "another device"}"
                else -> s.status[sel.id]?.takeIf { s.isListening } ?: if (c.enabled) "Ready to listen" else "Muted"
            }
        }
        else -> if (s.isListening) "Listening as ${speakingName ?: "—"}" else "${s.channelIDs().size} channels"
    }
    var more by remember { mutableStateOf(false) }

    @Composable fun listenButton(mod: Modifier) = ActionButton(
        text = if (s.isListening) "LIVE · Stop" else "Listen", onClick = actions.onListen, modifier = mod,
        fill = if (s.isListening) T.live else T.orange, icon = if (s.isListening) Icons.Filled.Stop else Icons.Filled.Mic, size = 17.sp,
    )

    @Composable fun overflow() {
        Box {
            IconTile(Icons.Filled.MoreVert, "More", { more = true })
            DropdownMenu(more, { more = false }, containerColor = T.raised) {
                MenuItem("Jump to current") { more = false; store.requestScroll(false) }
                MenuItem("Mark as read") { more = false; store.markRead(s.selection) }
                MenuItem("Clear transcript") { more = false; store.clear(s.selection) }
                MenuItem(if (s.settings.filterOffensive) "Show offensive language" else "Filter offensive language") {
                    more = false; store.updateSettings { it.copy(filterOffensive = !it.filterOffensive) }
                }
                MenuItem("Larger text") { more = false; store.updateSettings { it.copy(fontSize = (it.fontSize + 2).coerceAtMost(48.0)) } }
                MenuItem("Smaller text") { more = false; store.updateSettings { it.copy(fontSize = (it.fontSize - 2).coerceAtLeast(14.0)) } }
                MenuItem("Share as text…") { more = false; actions.onShare(store.transcriptText(false), false) }
                MenuItem("Share as CSV…") { more = false; actions.onShare(store.transcriptText(true), true) }
                MenuItem("Advanced automations…") { more = false; nav.push(Route.Automations) }
                MenuItem("Settings…") { more = false; nav.push(Route.Settings) }
            }
        }
    }

    if (compact) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                IconTile(Icons.Filled.Menu, "Channels", onMenu)
                Column(Modifier.weight(1f)) {
                    Text(s.title(), fontSize = 24.sp, fontWeight = FontWeight.Bold, color = tint, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(subtitle, fontSize = 14.sp, color = T.dim, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                overflow()
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                listenButton(Modifier.weight(1f))
                QuietButton("Cues", { nav.push(Route.Cues) }, tint = T.cue, icon = Icons.Filled.Bolt, modifier = Modifier.height(48.dp))
            }
        }
    } else {
        Row(Modifier.padding(horizontal = 24.dp, vertical = 16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(Modifier.weight(1f)) {
                Text(s.title(), fontSize = 30.sp, fontWeight = FontWeight.Bold, color = tint, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(subtitle, fontSize = 15.sp, color = T.dim, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            listenButton(Modifier)
            QuietButton("Cues", { nav.push(Route.Cues) }, tint = T.cue, icon = Icons.Filled.Bolt)
            overflow()
        }
    }
}

@Composable
private fun PermissionBanner(text: String, onFix: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().background(T.cue).padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(Icons.Filled.Warning, null, tint = T.ink)
        Text(text, Modifier.weight(1f), fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = T.ink)
        ActionButton("Settings", onFix, fill = T.ink, ink = T.cue, size = 14.sp)
    }
}

@Composable
fun CueBanner(cue: CueEvent) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(hue(cue.colorHex)).padding(horizontal = 16.dp, vertical = 12.dp)
            .semantics(mergeDescendants = true) {},
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(Icons.Filled.Bolt, null, tint = T.ink, modifier = Modifier.size(26.dp))
        Column(Modifier.weight(1f)) {
            Text("CUE · ${cue.title.uppercase()}", fontSize = 20.sp, fontWeight = FontWeight.ExtraBold, color = T.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (cue.detail.isNotEmpty()) Text(cue.detail, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = T.ink, maxLines = 2)
        }
    }
}

// MARK: - Transcript

@Composable
private fun TranscriptView(s: StoreState, store: AppStore) {
    val msgs = remember(s.messages, s.selection, s.channels) { s.visibleMessages() }
    val ids = s.channelIDs()
    val partials = ids.mapNotNull { id -> s.partials[id]?.let { text -> s.channel(id)?.let { it to text } } }
    val listState = rememberLazyListState()
    var stick by remember { mutableStateOf(true) }
    val total = msgs.size + partials.size

    // "Stuck to the bottom" follows the reader: it changes when they stop scrolling, not when new lines arrive.
    // Only a scroll the reader actually made and finished counts: the first emission arrives before the list is laid
    // out (when it cannot scroll yet) and must not be read as "scrolled away from the end".
    LaunchedEffect(listState) {
        var was = false
        snapshotFlow { listState.isScrollInProgress }.collect { scrolling ->
            if (was && !scrolling) { stick = !listState.canScrollForward; store.setAtBottom(stick) }
            was = scrolling
        }
    }
    val viewportHeight = listState.layoutInfo.viewportSize.height          // changes when the keyboard opens
    LaunchedEffect(total, partials.joinToString { it.second }, s.scrollRequest?.token, viewportHeight) {
        val req = s.scrollRequest
        if (req != null && req.top) { stick = false; store.setAtBottom(false); listState.scrollToItem(0) }
        else if (stick || req != null) {
            stick = true; store.setAtBottom(true)
            if (total > 0) listState.scrollToItem(total - 1)
        }
    }

    Box(Modifier.fillMaxSize()) {
        LazyColumn(
            state = listState, modifier = Modifier.fillMaxSize(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            if (total == 0) item { EmptyState(s) }
            items(msgs, key = { it.id }) { m -> MessageRow(m, s) }
            items(partials, key = { "p" + it.first.id }) { (ch, text) -> PartialRow(ch, text, s) }
        }
        if (!stick) {
            ActionButton(
                "Jump to current", { stick = true; store.setAtBottom(true); store.requestScroll(false) },
                Modifier.align(Alignment.BottomEnd).padding(16.dp), icon = Icons.Filled.ArrowDownward, size = 15.sp,
            )
        }
    }
}

@Composable
private fun EmptyState(s: StoreState) {
    Column(Modifier.fillMaxWidth().padding(top = 70.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp)) {
        StripBlock(s.channels.map { hue(it.colorHex) }, size = 72.dp)
        Text("No transcript yet", fontSize = 24.sp, fontWeight = FontWeight.Bold)
        Text(
            "Press Listen to turn what you say into words, or type a message below. Join a session to share channels with other devices.",
            fontSize = 16.sp, color = T.dim, textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
    }
}

private val clock: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault())

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun MessageRow(m: Message, s: StoreState) {
    val ch = s.channel(m.channelID)
    val color = ch?.let { hue(it.colorHex) } ?: T.faint
    val terms = ch?.let { s.highlightTerms(it) } ?: emptyList()
    val size = s.settings.fontSize.toFloat()
    val raw = if (s.settings.filterOffensive) Offensive.mask(m.text) else m.text
    val text = remember(raw, terms) {
        buildAnnotatedString {
            append(raw)
            for (r in TextUtil.ranges(raw, terms)) addStyle(SpanStyle(background = T.cue, color = T.ink), r.first, r.last + 1)
        }
    }
    Row(Modifier.fillMaxWidth().semantics(mergeDescendants = true) {}, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        if (s.settings.showTimestamps) {
            Text(
                clock.format(m.date), fontSize = 13.sp, fontWeight = FontWeight.Bold, color = color, maxLines = 1,
                modifier = Modifier.padding(top = 8.dp).clip(RoundedCornerShape(8.dp)).background(color.copy(alpha = 0.18f)).padding(horizontal = 6.dp, vertical = 5.dp),
            )
        }
        Column(
            Modifier.weight(1f).clip(RoundedCornerShape(14.dp)).background(if (m.keywordHit) T.cue.copy(alpha = 0.11f) else Color.Transparent)
                .border(1.5.dp, if (m.keywordHit) T.cue.copy(alpha = 0.8f) else Color.Transparent, RoundedCornerShape(14.dp))
                .padding(horizontal = 10.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            // Wraps on narrow screens: a long device name must never squeeze the labels beside it.
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                HueChip(ch?.name ?: "Channel", color)
                if (m.typed) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        Icon(Icons.Filled.Keyboard, null, tint = T.dim, modifier = Modifier.size(15.dp))
                        Text(m.speaker, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = T.dim, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 180.dp))
                    }
                }
                if (m.keywordHit) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        Icon(Icons.Filled.Flag, null, tint = T.cue, modifier = Modifier.size(14.dp))
                        Text("KEYWORD", fontSize = 12.sp, fontWeight = FontWeight.ExtraBold, color = T.cue, maxLines = 1, softWrap = false)
                    }
                }
            }
            SelectionContainer {
                Text(text, fontSize = size.sp, fontWeight = FontWeight.Medium, lineHeight = (size * 1.28f).sp)
            }
            m.translation?.let { t ->
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Icon(Icons.Filled.Language, null, tint = T.dim, modifier = Modifier.size((size * 0.8f).dp))
                    Text(if (s.settings.filterOffensive) Offensive.mask(t) else t, fontSize = (size * 0.82f).sp, color = T.dim)
                }
            }
        }
    }
}

@Composable
private fun PartialRow(ch: Channel, text: String, s: StoreState) {
    val color = hue(ch.colorHex)
    Row(Modifier.fillMaxWidth().semantics { contentDescription = "${ch.name}, speaking: $text" }, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        if (s.settings.showTimestamps) {
            Box(
                Modifier.padding(top = 8.dp).clip(RoundedCornerShape(8.dp)).background(color).padding(horizontal = 14.dp, vertical = 5.dp),
                contentAlignment = Alignment.Center,
            ) { Icon(Icons.Filled.GraphicEq, null, tint = T.ink, modifier = Modifier.size(16.dp)) }
        }
        Column(Modifier.weight(1f).padding(horizontal = 10.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            HueChip(ch.name, color)
            Text(if (s.settings.filterOffensive) Offensive.mask(text) else text, fontSize = s.settings.fontSize.sp, fontWeight = FontWeight.Medium, color = T.dim)
        }
    }
}

// MARK: - Composer

@Composable
private fun Composer(s: StoreState, store: AppStore) {
    var text by remember { mutableStateOf("") }
    val ids = s.channelIDs()
    val target: UUID? = (s.selection as? Selection.Chan)?.id ?: s.composeTarget?.takeIf { it in ids } ?: ids.firstOrNull()
    val name = target?.let { s.channel(it)?.name } ?: "a channel"
    fun send() {
        val t = target ?: return
        if (text.isBlank()) return
        store.send(text, t)
        text = ""
    }
    Row(
        Modifier.fillMaxWidth().background(T.panel).padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (s.selection !is Selection.Chan && ids.size > 1) {
            Picker("", ids.mapNotNull { id -> s.channel(id)?.let { id to it.name } }, target ?: ids.first(), { store.setComposeTarget(it) }, Modifier.width(120.dp))
        }
        LabeledField("", text, { text = it }, Modifier.weight(1f), placeholder = "Reply in $name…", onDone = ::send)
        ActionButton("", ::send, enabled = text.isNotBlank() && target != null, icon = Icons.AutoMirrored.Filled.Send)
    }
}
