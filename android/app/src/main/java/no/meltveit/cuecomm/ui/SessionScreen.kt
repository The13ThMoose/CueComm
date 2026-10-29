package no.meltveit.cuecomm.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import no.meltveit.cuecomm.core.AddressJoin
import no.meltveit.cuecomm.core.AppStore
import no.meltveit.cuecomm.core.LocalAddress
import no.meltveit.cuecomm.core.ManualLinkState
import no.meltveit.cuecomm.core.StoreState

/** Host a named session, or join one: found on this network, or by typing the address of a device in it. */
@Composable
fun SessionScreen(s: StoreState, store: AppStore, onBack: () -> Unit) {
    // Browsing for sessions only runs while this screen is open (and always while in a session).
    DisposableEffect(Unit) {
        store.setSessionScreenOpen(true)
        onDispose { store.setSessionScreenOpen(false) }
    }
    ScreenScaffold("Session", onBack) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(22.dp)) {
            if (s.inSession) {
                CurrentSession(s, store)
                Hairline()
                JoinByAddress(s, store)
                Hairline()
                ShareAddress(s, store)
            } else {
                HostSession(store)
                JoinSession(s, store)
                Hairline()
                JoinByAddress(s, store)
            }
            Hairline()
            ThisDevice(s, store)
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun CurrentSession(s: StoreState, store: AppStore) {
    val linking = s.settings.manualPeers.any { (s.manualStatus[it] ?: ManualLinkState.Connecting) !is ManualLinkState.Failed }
    val tint = if (s.peers.isEmpty()) T.orange else T.live
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(s.settings.sessionName, fontSize = 34.sp, fontWeight = FontWeight.ExtraBold)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(Modifier.size(12.dp).clip(CircleShape).background(tint))
                Text(
                    if (s.peers.isEmpty()) (if (linking) "Connecting…" else "Waiting for other devices to join…") else "Live on the network",
                    fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = tint,
                )
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SectionLabel("In this session")
            MemberRow(s.settings.displayName + " (this device)")
            s.peers.forEach { MemberRow(it) }
        }
        Text("Every channel is shared with the session unless you mark it private in its menu. Late joiners get the recent transcript.", fontSize = 14.sp, color = T.dim)
        QuietButton("Leave session", { store.leaveSession() }, tint = T.danger, icon = Icons.AutoMirrored.Filled.Logout)
    }
}

@Composable
private fun MemberRow(name: String) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(T.raised).padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(Icons.Filled.PhoneAndroid, null, tint = T.dim)
        Text(name, Modifier.weight(1f), fontSize = 16.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Box(Modifier.size(10.dp).clip(CircleShape).background(T.live))
    }
}

@Composable
private fun HostSession(store: AppStore) {
    var name by remember { mutableStateOf("") }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        SectionLabel("Host a session")
        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            LabeledField("Session name", name, { name = it }, Modifier.weight(1f), placeholder = "e.g. Sunday Service", onDone = { store.startSession(name) })
            ActionButton("Start", { store.startSession(name) }, enabled = name.isNotBlank())
        }
    }
}

@Composable
private fun JoinSession(s: StoreState, store: AppStore) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        SectionLabel("Join a session on this network")
        if (s.sessions.isEmpty()) {
            Row(Modifier.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                CircularProgressIndicator(Modifier.size(18.dp), color = T.dim, strokeWidth = 2.dp)
                Text("Looking for sessions…", fontSize = 15.sp, color = T.dim)
            }
        }
        for (x in s.sessions) {
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(T.raised).padding(12.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Box(Modifier.size(11.dp).clip(CircleShape).background(T.live))
                Column(Modifier.weight(1f)) {
                    Text(x.name, fontSize = 18.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(x.members.joinToString(", "), fontSize = 13.sp, color = T.dim, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                ActionButton("Join", { store.joinSession(x.name) }, fill = T.live, size = 15.sp)
            }
        }
    }
}

/** Type the address of any device in the session and join its session straight away: no session name needed. */
@Composable
private fun JoinByAddress(s: StoreState, store: AppStore) {
    var address by remember { mutableStateOf("") }
    val joining = s.addressJoin is AddressJoin.Joining
    LaunchedEffect(s.addressJoin) { if (s.addressJoin is AddressJoin.Idle) address = "" }
    fun go() {
        val t = address.trim()
        if (t.isNotEmpty() && !joining) store.joinByAddress(t)
    }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SectionLabel(if (s.inSession) "Add a device by address" else "Join by IP address")
        Text(
            if (s.inSession) "A device on another network or subnet, in this session. Type its address to connect to it."
            else "For a device on another network or subnet. Type the address of any device that is hosting a session and you join that session straight away.",
            fontSize = 14.sp, color = T.dim,
        )
        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            LabeledField("Address", address, { address = it }, Modifier.weight(1f), placeholder = "203.0.113.5 or 203.0.113.5:47800", keyboardType = KeyboardType.Uri, onDone = ::go, mono = true, fontSize = 16.sp)
            ActionButton(if (s.inSession) "Connect" else "Join", ::go, fill = T.live, enabled = address.isNotBlank() && !joining)
        }
        when (val j = s.addressJoin) {
            AddressJoin.Idle -> {}
            is AddressJoin.Joining -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CircularProgressIndicator(Modifier.size(16.dp), color = T.dim, strokeWidth = 2.dp)
                Text("Joining ${j.address}…", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = T.dim)
            }
            is AddressJoin.Failed -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Filled.Warning, null, tint = T.danger, modifier = Modifier.size(18.dp))
                Text(j.why, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = T.danger)
            }
        }
        if (s.inSession) s.settings.manualPeers.forEach { ManualPeerRow(s, store, it) }
    }
}

@Composable
private fun ManualPeerRow(s: StoreState, store: AppStore, key: String) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(T.raised).padding(start = 12.dp, top = 8.dp, bottom = 8.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(key, fontSize = 17.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
            when (val st = s.manualStatus[key] ?: ManualLinkState.Connecting) {
                ManualLinkState.Connecting -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    CircularProgressIndicator(Modifier.size(14.dp), color = T.dim, strokeWidth = 2.dp)
                    Text("Connecting…", fontSize = 14.sp, color = T.dim)
                }
                is ManualLinkState.Connected -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Icon(Icons.Filled.CheckCircle, null, tint = T.live, modifier = Modifier.size(16.dp))
                    Text("Connected to ${st.name}", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = T.live)
                }
                is ManualLinkState.Failed -> Text("${st.why} Retrying…", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = T.danger)
            }
        }
        IconButton({ store.removeManualPeer(key) }) { Icon(Icons.Filled.Close, "Remove $key") }
    }
}

/** What to tell a device on another network so it can join this session. */
@Composable
private fun ShareAddress(s: StoreState, store: AppStore) {
    val clipboard = LocalClipboardManager.current
    var addresses by remember { mutableStateOf(LocalAddress.ipv4()) }
    var copied by remember { mutableStateOf<String?>(null) }
    var portText by remember { mutableStateOf(s.settings.listenPort.toString()) }
    var problem by remember { mutableStateOf<String?>(null) }
    val port = s.activePort ?: s.settings.listenPort
    LaunchedEffect(Unit) { while (true) { addresses = LocalAddress.ipv4(); delay(5000) } }

    fun apply() {
        val p = portText.trim().toIntOrNull()
        if (p == null || p !in 1024..65535) { problem = "Use a port between 1024 and 65535."; portText = s.settings.listenPort.toString(); return }
        problem = null
        store.setListenPort(p)
    }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        SectionLabel("Others can join this session at")
        if (addresses.isEmpty()) Text("No network address found. Connect to a network first.", fontSize = 14.sp, color = T.faint)
        for (a in addresses) {
            val full = "${a.ip}:$port"
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(T.raised).padding(start = 12.dp, top = 6.dp, bottom = 6.dp, end = 8.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Column(Modifier.weight(1f)) {
                    Text(full, fontSize = 17.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                    Text(a.kind, fontSize = 13.sp, color = T.dim)
                }
                QuietButton(if (copied == full) "Copied" else "Copy", { clipboard.setText(AnnotatedString(full)); copied = full },
                    icon = if (copied == full) Icons.Filled.Check else Icons.Filled.ContentCopy, size = 13.sp)
            }
        }
        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            LabeledField("Listening port", portText, { portText = it.filter { c -> c.isDigit() }.take(5) }, Modifier.width(150.dp), keyboardType = KeyboardType.Number, onDone = ::apply, mono = true)
            if (portText != s.settings.listenPort.toString()) QuietButton("Apply", ::apply, size = 13.sp)
        }
        val active = s.activePort
        if (active != null && active != s.settings.listenPort) {
            Text("Port ${s.settings.listenPort} is busy, so this device is listening on $active. Pick a free port.", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = T.orange)
        }
        problem?.let { Text(it, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = T.danger) }
        Text(
            "They type this under Join by IP address. Over the internet, forward TCP port $port on this network's router, or put both devices on a VPN such as Tailscale and use its address. Connections are not encrypted, so use a VPN on networks you do not trust. Anyone who can reach the port can see the session name.",
            fontSize = 13.sp, color = T.faint,
        )
    }
}

@Composable
private fun ThisDevice(s: StoreState, store: AppStore) {
    var name by remember { mutableStateOf(s.settings.displayName) }
    // Peers learn the new name from a fresh hello, so wait for a pause in typing.
    LaunchedEffect(name) {
        delay(700)
        if (name.isNotBlank() && name != s.settings.displayName) store.updateSettings { it.copy(displayName = name.trim()) }
    }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        SectionLabel("This device")
        LabeledField("Name others see", name, { name = it })
        ToggleRow(
            "Open sharing (no session)", s.settings.networkEnabled, { v -> store.updateSettings { it.copy(networkEnabled = v) } },
            subtitle = "Share only the channels you flag with every device that has open sharing on.", enabled = !s.inSession,
        )
    }
}
