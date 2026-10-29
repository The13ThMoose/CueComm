package no.meltveit.cuecomm.core

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import java.io.File
import java.net.Socket
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.UUID

// MARK: - Platform hooks

/** One local speech-recognition stream. Android implements it with SpeechRecognizer. */
interface SpeechController {
    var onPartial: (UUID, String, UUID) -> Unit            // channel, text so far, utterance
    var onCommit: (UUID, String, UUID) -> Unit             // channel, finished text, utterance
    var onStatus: (UUID, String) -> Unit
    var onLevel: (UUID, Float) -> Unit
    fun start(channel: Channel, silenceSeconds: Double)
    fun stop()
}

fun interface Translator {
    fun translate(text: String, source: String, target: String, done: (String?) -> Unit)
}

/** Everything the store needs from the device. All optional so the store runs in plain JVM tests. */
interface Platform {
    val discovery: Discovery? get() = null
    val speech: SpeechController? get() = null
    val translator: Translator? get() = null
    fun vibrate() {}
    /** A cue arrived while the app is not on screen. */
    fun notifyCue(title: String, detail: String, colorHex: String) {}
    fun tuneSocket(s: Socket) {}
}

// MARK: - State

sealed class Selection {
    data object All : Selection()
    data class Group(val id: UUID) : Selection()
    data class Chan(val id: UUID) : Selection()
}

data class CueEvent(val id: UUID = UUID.randomUUID(), val title: String, val detail: String, val colorHex: String)
data class FlashEvent(val token: Int = 0, val colorHex: String = "FFE14D")
data class LogEntry(val id: UUID = UUID.randomUUID(), val date: Instant = Instant.now(), val text: String)
data class ScrollRequest(val token: Int, val top: Boolean)

data class StoreState(
    val channels: List<Channel> = emptyList(),
    val groups: List<ChannelGroup> = emptyList(),
    val automations: List<Automation> = emptyList(),
    val settings: AppSettings = AppSettings(),
    val messages: Map<UUID, List<Message>> = emptyMap(),
    val unread: Map<UUID, Int> = emptyMap(),
    val missed: Set<UUID> = emptySet(),
    val selection: Selection = Selection.All,
    val isListening: Boolean = false,
    val status: Map<UUID, String> = emptyMap(),
    val levels: Map<UUID, Float> = emptyMap(),
    val partials: Map<UUID, String> = emptyMap(),
    val peers: List<String> = emptyList(),
    val sessions: List<DiscoveredSession> = emptyList(),
    val manualStatus: Map<String, ManualLinkState> = emptyMap(),
    val activePort: Int? = null,
    val addressJoin: AddressJoin = AddressJoin.Idle,
    val lastCue: CueEvent? = null,
    val flash: FlashEvent = FlashEvent(),
    val log: List<LogEntry> = emptyList(),
    val lastFired: Map<UUID, Instant> = emptyMap(),
    val micGranted: Boolean = true,
    val appActive: Boolean = true,
    val atBottom: Boolean = true,
    val scrollRequest: ScrollRequest? = null,
    val sessionScreenOpen: Boolean = false,
    val composeTarget: UUID? = null,
) {
    val inSession: Boolean get() = settings.sessionName.isNotEmpty()
    fun channel(id: UUID?): Channel? = id?.let { i -> channels.firstOrNull { it.id == i } }
    fun group(id: UUID?): ChannelGroup? = id?.let { i -> groups.firstOrNull { it.id == i } }

    fun channelIDs(sel: Selection = selection): List<UUID> = when (sel) {
        Selection.All -> channels.map { it.id }
        is Selection.Group -> channels.filter { it.groupID == sel.id }.map { it.id }
        is Selection.Chan -> listOf(sel.id)
    }

    fun title(sel: Selection = selection): String = when (sel) {
        Selection.All -> "All Channels"
        is Selection.Group -> group(sel.id)?.name ?: "Group"
        is Selection.Chan -> channel(sel.id)?.name ?: "Channel"
    }

    fun visibleMessages(sel: Selection = selection): List<Message> {
        val ids = channelIDs(sel)
        if (ids.size == 1) return messages[ids[0]] ?: emptyList()
        return ids.flatMap { messages[it] ?: emptyList() }.sortedBy { it.date }.takeLast(1500)
    }

    /** Sessions share everything unless marked private; open sharing shares only what is flagged. */
    fun isShared(ch: Channel): Boolean {
        if (ch.isRemote) return true
        if (inSession) return !ch.isPrivate
        return ch.shared || (group(ch.groupID)?.shared ?: false)
    }

    fun highlightTerms(ch: Channel): List<String> {
        val terms = ch.keywords.toMutableList()
        for (a in automations) {
            if (a.enabled && a.trigger.kind == TriggerKind.Keyword && (a.trigger.channelID == null || a.trigger.channelID == ch.id)) {
                terms += Text.split(a.trigger.phrase)
            }
        }
        return terms.filter { it.isNotEmpty() }
    }

    /** The channel this device speaks into: the saved choice if still usable, else the first local one. */
    fun speakingChannel(): Channel? {
        val usable = channels.filter { !it.isRemote && it.enabled }
        return usable.firstOrNull { it.id == settings.speakingChannelID } ?: usable.firstOrNull()
    }
}

// Top level, not class members: the store's init block calls load(), and members declared below it would still be null.
private val prettyJson = Json(CueJson) { prettyPrint = true }
private val historyJson = Json(CueJson) { prettyPrint = false }
private val historySerializer = MapSerializer(String.serializer(), ListSerializer(Message.serializer()))

class ActionContext(val text: String, val channel: String, val keyword: String, val args: List<String> = emptyList(), var automationName: String = "")

// MARK: - Store

/**
 * All app state and logic. It runs on [main]; every public function must be called from there. The UI
 * observes [state]; the network, speech and translation hooks call back onto [main].
 */
class AppStore(
    private val dir: File,
    deviceName: String,
    private val scope: CoroutineScope,
    private val main: CoroutineDispatcher,
    private val platform: Platform = object : Platform {},
) {
    private val _state = MutableStateFlow(StoreState(settings = AppSettings(displayName = deviceName)))
    val state: StateFlow<StoreState> get() = _state

    val net = NetworkSync(scope, main)
    private val osc = OscService(scope, main)
    var instanceID: String = UUID.randomUUID().toString().uppercase()
        private set
    private var ignoredRemote = mutableSetOf<UUID>()
    private var firedInUtterance = mutableMapOf<UUID, MutableSet<UUID>>()
    private var lastFlash = 0L
    private var scrollToken = 0
    private var saveJob: Job? = null
    private var probeJob: Job? = null
    private var speechKey: List<Any?>? = null
    private val snapshot get() = _state.value

    private inline fun mutate(persist: Boolean = true, f: (StoreState) -> StoreState) {
        _state.value = f(_state.value)
        if (persist) saveSoon()
    }

    init {
        load()
        if (_state.value.channels.isEmpty()) seedDefaults()
        wire()
        applySettings(snapshot.settings)
    }

    private fun wire() {
        net.discovery = platform.discovery
        net.socketTuner = { platform.tuneSocket(it) }
        net.helloChannels = { sharedChannelMetas() }
        net.helloHistory = { recentSharedMessages() }
        net.onSessions = { s -> if (snapshot.sessions != s) mutate(false) { it.copy(sessions = s) } }
        net.onFrame = { f, name -> handleNet(f, name) }
        net.onPeersChanged = { p -> if (snapshot.peers != p) mutate(false) { it.copy(peers = p) } }
        net.onManualStatus = { m -> if (snapshot.manualStatus != m) mutate(false) { it.copy(manualStatus = m) } }
        net.onListenPort = { p -> if (snapshot.activePort != p) mutate(false) { it.copy(activePort = p) } }
        net.onError = { addLog(it) }

        osc.onMessage = { m, from -> handleOSC(m, from) }
        osc.onError = { addLog(it) }

        platform.speech?.let { sp ->
            sp.onPartial = { cid, text, utt -> pipePartial(cid, text, utt) }
            sp.onCommit = { cid, text, utt -> pipeCommit(cid, text, utt) }
            sp.onStatus = { cid, s -> if (snapshot.status[cid] != s) mutate(false) { it.copy(status = it.status + (cid to s)) } }
            sp.onLevel = { cid, v -> mutate(false) { it.copy(levels = it.levels + (cid to v)) } }
        }
    }

    private fun seedDefaults() {
        val audio = ChannelGroup(name = "Audio")
        val prod = ChannelGroup(name = "Production")
        val channels = listOf(
            Channel(name = "Monitors", colorHex = Palette.hexes[0], groupID = audio.id, pinned = true, keywords = listOf("monitor", "wedge")),
            Channel(name = "FOH", colorHex = Palette.hexes[1], groupID = audio.id, keywords = listOf("more", "less")),
            Channel(name = "Show Caller", colorHex = Palette.hexes[2], groupID = prod.id, pinned = true, keywords = listOf("standby")),
        )
        mutate { it.copy(groups = listOf(audio, prod), channels = channels, automations = emptyList()) }
        addCue("go")
        addCue("standby")
    }

    // MARK: Settings & network

    /** Replaces the settings in one step and applies what they imply (network, OSC, speech). */
    fun updateSettings(f: (AppSettings) -> AppSettings) {
        val old = snapshot.settings
        val new = f(old)
        if (new == old) return
        mutate { it.copy(settings = new) }
        applySettings(new)
    }

    private fun applySettings(s: AppSettings) {
        if (s.oscListenEnabled) osc.listen(s.oscListenPort) else osc.stopListening()
        refreshNetwork(s)
        syncSpeech()
    }

    val inSession get() = snapshot.inSession

    /** Full networking while in a session or open sharing; browse-only while the Session screen is open. */
    fun refreshNetwork(s: AppSettings = snapshot.settings) {
        net.manualPeers = s.manualPeers
        when {
            s.sessionName.isNotEmpty() || s.networkEnabled ->
                net.start(instanceID, s.displayName, s.sessionName, advertise = true, port = s.listenPort)
            snapshot.sessionScreenOpen -> net.start(instanceID, s.displayName, "", advertise = false)
            net.isRunning -> net.stop()
        }
    }

    fun setSessionScreenOpen(open: Boolean) {
        if (snapshot.sessionScreenOpen == open) return
        mutate(false) { it.copy(sessionScreenOpen = open) }
        refreshNetwork()
    }

    fun startSession(name: String) {
        val n = name.trim()
        if (n.isEmpty()) return
        dropRemote()
        updateSettings { it.copy(sessionName = n) }
        addLog("Session “$n” started")
    }

    fun joinSession(name: String) = startSession(name)

    /**
     * Joins the session of the device at this address, whatever its name: asks it which session it is in, then
     * switches to that session and connects at once. The address is kept so a dropped link is redialled.
     */
    fun joinByAddress(text: String) {
        val a = ManualAddress.parse(text)
        val key = ManualAddress.normalised(text)
        if (a == null || key == null) {
            mutate(false) { it.copy(addressJoin = AddressJoin.Failed("Type an IP address or host name, like 203.0.113.5 or 203.0.113.5:47800.")) }
            return
        }
        probeJob?.cancel()
        mutate(false) { it.copy(addressJoin = AddressJoin.Joining(key)) }
        val ownName = snapshot.settings.displayName
        probeJob = scope.launch(main) {
            val result = NetworkSync.probe(a.host, a.port, instanceID, ownName, tuner = { platform.tuneSocket(it) })
            finishJoin(key, result)
        }
    }

    private fun finishJoin(key: String, result: Result<Answer>) {
        val answer = result.getOrElse { e ->
            mutate(false) { it.copy(addressJoin = AddressJoin.Failed(e.message ?: "Could not connect.")) }
            return
        }
        fun fail(why: String) = mutate(false) { it.copy(addressJoin = AddressJoin.Failed(why)) }
        val s = snapshot.settings
        if (answer.peerID == instanceID) return fail("That address is this device.")
        if (answer.session.isEmpty()) return fail("${answer.name} is not hosting a session yet. Start one there, then join it from here.")
        if (snapshot.inSession && s.sessionName != answer.session) {
            return fail("${answer.name} is in “${answer.session}”. Leave “${s.sessionName}” first to join it.")
        }
        if (!snapshot.inSession) dropRemote()
        updateSettings { cur ->                                // one change, so networking restarts once
            cur.copy(sessionName = answer.session, manualPeers = if (key in cur.manualPeers) cur.manualPeers else cur.manualPeers + key)
        }
        mutate(false) { it.copy(addressJoin = AddressJoin.Idle) }
        addLog("Joined “${answer.session}” at ${answer.name} ($key)")
    }

    fun clearAddressJoin() = mutate(false) { it.copy(addressJoin = AddressJoin.Idle) }

    fun removeManualPeer(key: String) {
        updateSettings { it.copy(manualPeers = it.manualPeers - key) }
        addLog("Stopped connecting to $key")
    }

    fun setListenPort(port: Int) = updateSettings { it.copy(listenPort = port.coerceIn(1024, 65535)) }

    fun leaveSession() {
        addLog("Left session “${snapshot.settings.sessionName}”")
        probeJob?.cancel()
        updateSettings { it.copy(sessionName = "", manualPeers = emptyList()) }    // addresses belong to the session just left
        mutate(false) { it.copy(addressJoin = AddressJoin.Idle) }
        dropRemote()
    }

    /** Forget channels, groups and transcripts that belong to other devices. */
    private fun dropRemote() {
        val ids = snapshot.channels.filter { it.isRemote }.map { it.id }.toSet()
        mutate { s ->
            s.copy(
                messages = s.messages - ids, unread = s.unread - ids, missed = s.missed - ids,
                channels = s.channels.filterNot { it.isRemote }, groups = s.groups.filterNot { it.isRemote },
                selection = (s.selection as? Selection.Chan)?.takeIf { it.id in ids }?.let { Selection.All } ?: s.selection,
                peers = emptyList(),
            )
        }
    }

    private fun recentSharedMessages(): List<SharedMessage> {
        val s = snapshot
        return s.channels.filter { !it.isRemote && s.isShared(it) }.flatMap { ch ->
            (s.messages[ch.id] ?: emptyList()).takeLast(60).map { SharedMessage(it.id, ch.id, it.speaker, it.text, it.date, it.typed) }
        }
    }

    // MARK: Listening

    val listening get() = snapshot.isListening

    fun setMicGranted(granted: Boolean) {
        if (snapshot.micGranted != granted) mutate(false) { it.copy(micGranted = granted) }
    }

    fun setAppActive(active: Boolean) {
        if (snapshot.appActive == active) return
        mutate(false) { it.copy(appActive = active) }
        if (active) markRead(snapshot.selection)
    }

    fun toggleListening() {
        if (snapshot.isListening) {
            mutate(false) { it.copy(isListening = false, partials = emptyMap(), levels = emptyMap(), status = emptyMap()) }
        } else {
            // Speak as the channel being viewed, if it is one of ours.
            val viewed = (snapshot.selection as? Selection.Chan)?.let { snapshot.channel(it.id) }?.takeIf { !it.isRemote && it.enabled }
            if (viewed != null && viewed.id != snapshot.settings.speakingChannelID) updateSettings { it.copy(speakingChannelID = viewed.id) }
            mutate(false) { it.copy(isListening = true) }
        }
        syncSpeech()
    }

    /** Speak into a different channel; takes effect at once if already listening. */
    fun setSpeaking(id: UUID) {
        updateSettings { it.copy(speakingChannelID = id) }
    }

    private fun syncSpeech() {
        val sp = platform.speech ?: return
        val s = snapshot
        val ch = if (s.isListening) s.speakingChannel() else null
        val key = ch?.let { listOf(it.id, it.localeID, it.onDeviceOnly, s.settings.silenceCommit) }
        if (key == speechKey) return
        speechKey = key
        sp.stop()
        if (ch != null) sp.start(ch, s.settings.silenceCommit)
        else if (s.isListening) {
            mutate(false) { it.copy(isListening = false) }
            addLog("No channel to listen on. Add or unmute one of your channels.")
        }
    }

    val permissionProblem: String?
        get() = if (snapshot.isListening && !snapshot.micGranted) "Microphone access is off for CueComm." else null

    // MARK: Messages

    private fun pipePartial(cid: UUID, text: String, utt: UUID) {
        mutate(false) { it.copy(partials = it.partials + (cid to text)) }
        if (snapshot.settings.triggerOnPartials) runAutomations(cid, text, utt, commit = false)
    }

    private fun pipeCommit(cid: UUID, text: String, utt: UUID) {
        mutate(false) { it.copy(partials = it.partials - cid) }
        post(cid, text, utterance = utt)
    }

    fun post(
        channelID: UUID, raw: String, typed: Boolean = false, remote: Boolean = false, speaker: String? = null,
        id: UUID = UUID.randomUUID(), date: Instant = Instant.now(), utterance: UUID? = null,
    ) {
        val text = raw.trim()
        val ch = snapshot.channel(channelID)
        if (text.isEmpty() || ch == null) return
        val s = snapshot
        val msg = Message(
            id = id, channelID = channelID, date = date, speaker = speaker ?: (if (typed) s.settings.displayName else ch.name),
            text = text, typed = typed, remote = remote,
            keywordHit = s.highlightTerms(ch).any { Text.matches(text, it, true) },
        )
        val limit = s.settings.historyLimit
        val viewing = s.appActive && s.atBottom && channelID in s.channelIDs()
        mutate { st ->
            var list = (st.messages[channelID] ?: emptyList()) + msg
            if (list.size > limit) list = list.takeLast(limit)
            var next = st.copy(messages = st.messages + (channelID to list))
            if (viewing || (typed && !remote)) {
                if (channelID in st.channelIDs()) next = next.copy(scrollRequest = ScrollRequest(++scrollToken, false))
            } else {
                next = next.copy(
                    unread = st.unread + (channelID to ((st.unread[channelID] ?: 0) + 1)),
                    missed = if (msg.keywordHit) st.missed + channelID else st.missed,
                )
            }
            next
        }
        runAutomations(channelID, text, utterance, commit = true, remote = remote)
        if (!(typed && !remote)) enqueueTranslation(msg, ch)
        if (snapshot.isShared(ch) && !remote) {
            net.broadcast(NetFrame(type = "message", message = SharedMessage(msg.id, channelID, msg.speaker, text, date, typed)))
        }
    }

    fun send(text: String, channelID: UUID) = post(channelID, text, typed = true)

    fun markRead(sel: Selection) {
        val ids = snapshot.channelIDs(sel).toSet()
        if (ids.none { (snapshot.unread[it] ?: 0) > 0 || it in snapshot.missed }) return
        mutate(false) { it.copy(unread = it.unread - ids, missed = it.missed - ids) }
    }

    fun markAllRead() = mutate(false) { it.copy(unread = emptyMap(), missed = emptySet()) }

    fun clear(sel: Selection) {
        val ids = snapshot.channelIDs(sel).toSet()
        mutate { s -> s.copy(messages = s.messages + ids.associateWith { emptyList() }) }
        markRead(sel)
    }

    fun select(sel: Selection) {
        mutate(false) { it.copy(selection = sel, atBottom = true) }
        markRead(sel)
        requestScroll(false)
    }

    fun setAtBottom(v: Boolean) {
        if (snapshot.atBottom == v) return
        mutate(false) { it.copy(atBottom = v) }
        if (v) markRead(snapshot.selection)
    }

    fun requestScroll(top: Boolean) = mutate(false) { it.copy(scrollRequest = ScrollRequest(++scrollToken, top)) }

    fun setComposeTarget(id: UUID?) = mutate(false) { it.copy(composeTarget = id) }

    // MARK: Automations

    private fun runAutomations(channelID: UUID, text: String, utterance: UUID?, commit: Boolean, remote: Boolean = false) {
        val ch = snapshot.channel(channelID) ?: return
        for (a in snapshot.automations) {
            if (!a.enabled) continue
            // One spoken "go" must send one OSC message, not one per device in the session.
            if (remote && !a.trigger.includeRemote) continue
            if (a.trigger.channelID != null && a.trigger.channelID != channelID) continue
            var hit = ""
            when (a.trigger.kind) {
                TriggerKind.Keyword -> {
                    if (utterance != null && firedInUtterance[utterance]?.contains(a.id) == true) continue
                    val found = Text.split(a.trigger.phrase).firstOrNull { Text.matches(text, it, a.trigger.wholeWord) } ?: continue
                    hit = found
                    if (utterance != null) firedInUtterance.getOrPut(utterance) { mutableSetOf() } += a.id
                }
                TriggerKind.Message -> if (!commit) continue
                else -> continue
            }
            fire(a, ActionContext(text, ch.name, hit))
        }
        if (commit && utterance != null) firedInUtterance.remove(utterance)
    }

    private fun handleOSC(m: OscMessage, from: String) {
        addLog("OSC in: ${m.address} ${m.args.joinToString(" ") { it.text }}")
        for (a in snapshot.automations) {
            if (a.enabled && a.trigger.kind == TriggerKind.Osc && OscCodec.matches(m.address, a.trigger.oscAddress)) {
                fire(a, ActionContext(m.address, "", "", m.args.map { it.text }))
            }
        }
    }

    fun fire(a: Automation, ctx: ActionContext, force: Boolean = false) {
        val now = Instant.now()
        val last = snapshot.lastFired[a.id]
        if (!force && last != null && (now.toEpochMilli() - last.toEpochMilli()) < a.cooldown * 1000) return
        ctx.automationName = a.name
        mutate(false) { it.copy(lastFired = it.lastFired + (a.id to now)) }
        addLog("Triggered “${a.name}”" + if (ctx.keyword.isEmpty()) "" else " by “${ctx.keyword}”")
        showCueBanner(a, ctx)
        for (action in a.actions) {
            if (action.delay > 0) scope.launch(main) { delay((action.delay * 1000).toLong()); perform(action, ctx) }
            else perform(action, ctx)
        }
    }

    private fun showCueBanner(a: Automation, ctx: ActionContext) {
        val parts = a.actions.mapNotNull {
            when (it.kind) {
                ActionKind.SendOSC -> "OSC ${it.oscHost}:${it.oscPort} ${it.oscAddress}"
                ActionKind.FlashWindow -> "Flash"
                else -> null
            }
        }
        presentCue(CueEvent(
            title = ctx.keyword.ifEmpty { a.name }, detail = parts.joinToString("  ·  "),
            colorHex = a.actions.firstOrNull { it.kind == ActionKind.FlashWindow }?.flashColorHex ?: "FFE14D",
        ))
    }

    private fun presentCue(event: CueEvent) {
        mutate(false) { it.copy(lastCue = event) }
        scope.launch(main) {
            delay(4000)
            if (snapshot.lastCue?.id == event.id) mutate(false) { it.copy(lastCue = null) }
        }
    }

    /**
     * Flashes this screen. `coalesce` drops a repeat that lands within half a second of the last one, so a cue
     * that reaches this device twice (over the network and through its own automation) flashes once.
     */
    private fun flashScreen(hex: String, title: String, detail: String, coalesce: Boolean = false) {
        val now = System.currentTimeMillis()
        if (coalesce && now - lastFlash < 500) return
        lastFlash = now
        mutate(false) { it.copy(flash = FlashEvent(it.flash.token + 1, hex)) }
        if (snapshot.settings.vibrateOnCue) platform.vibrate()
        if (!snapshot.appActive) platform.notifyCue(title, detail, hex)
    }

    /** A cue word is a keyword automation that sends OSC and flashes the screen. */
    fun addCue(word: String): UUID? {
        val w = word.trim()
        if (w.isEmpty()) return null
        val s = snapshot.settings
        val osc = AutomationAction(kind = ActionKind.SendOSC, oscHost = s.oscDefaultHost, oscPort = s.oscDefaultPort, oscAddress = "/cue/" + Text.slug(w))
        val cue = Automation(
            name = "Cue: $w", trigger = AutomationTrigger(kind = TriggerKind.Keyword, phrase = w),
            actions = listOf(osc, AutomationAction(kind = ActionKind.FlashWindow)), cooldown = 1.5, isCue = true,
        )
        mutate { it.copy(automations = listOf(cue) + it.automations) }
        return cue.id
    }

    fun upsert(a: Automation) = mutate { s ->
        val i = s.automations.indexOfFirst { it.id == a.id }
        s.copy(automations = if (i >= 0) s.automations.toMutableList().also { it[i] = a } else s.automations + a)
    }

    fun deleteAutomation(id: UUID) = mutate { it.copy(automations = it.automations.filterNot { a -> a.id == id }) }

    private fun perform(a: AutomationAction, ctx: ActionContext) {
        when (a.kind) {
            ActionKind.SendMIDI -> addLog("MIDI is not available on Android")
            ActionKind.SendOSC -> osc.send(OscMessage(a.oscAddress, OscCodec.parseArgs(substitute(a.oscArgs, ctx))), a.oscHost, a.oscPort)
            ActionKind.FlashWindow -> {
                val title = ctx.keyword.ifEmpty { ctx.automationName.ifEmpty { "Cue" } }
                flashScreen(a.flashColorHex, title, if (ctx.channel.isEmpty()) "Cue" else ctx.channel)
                // The cue is heard on one device but the crew is spread over several: tell the others to flash too.
                if (a.flashEveryone && net.isRunning) net.broadcast(NetFrame(type = "cue", cue = SharedCue(title, ctx.channel, a.flashColorHex)))
            }
            ActionKind.ShowWindow, ActionKind.HideWindow -> {}
            ActionKind.SelectChannel -> a.targetChannelID?.let { id -> if (snapshot.channel(id) != null) select(Selection.Chan(id)) }
            ActionKind.ScrollTranscript -> requestScroll(a.scrollTarget == ScrollTarget.Top)
            ActionKind.ClearTranscript -> clear(a.targetChannelID?.let { Selection.Chan(it) } ?: snapshot.selection)
            ActionKind.MarkRead -> markRead(a.targetChannelID?.let { Selection.Chan(it) } ?: snapshot.selection)
            ActionKind.AdjustFontSize -> updateSettings { it.copy(fontSize = (it.fontSize + a.fontDelta).coerceIn(12.0, 48.0)) }
        }
    }

    private fun substitute(template: String, ctx: ActionContext): String {
        fun q(s: String) = "\"" + s.replace("\"", "'") + "\""
        var out = template.replace("{text}", q(ctx.text)).replace("{channel}", q(ctx.channel)).replace("{keyword}", q(ctx.keyword))
        ctx.args.forEachIndexed { i, arg -> out = out.replace("{arg${i + 1}}", arg) }
        return out
    }

    fun addLog(s: String) = mutate(false) { it.copy(log = (listOf(LogEntry(text = s)) + it.log).take(200)) }

    // MARK: Channels & groups

    fun upsert(ch: Channel) {
        mutate { s ->
            val i = s.channels.indexOfFirst { it.id == ch.id }
            s.copy(channels = if (i >= 0) s.channels.toMutableList().also { it[i] = ch } else s.channels + ch)
        }
        syncSpeech()
    }

    fun delete(id: UUID) {
        val ch = snapshot.channel(id) ?: return
        if (ch.isRemote) ignoredRemote += id else if (snapshot.isShared(ch)) net.broadcast(NetFrame(type = "removed", removedID = id))
        mutate { s ->
            s.copy(
                channels = s.channels.filterNot { it.id == id }, messages = s.messages - id, unread = s.unread - id, missed = s.missed - id,
                selection = if (s.selection == Selection.Chan(id)) Selection.All else s.selection,
            )
        }
        syncSpeech()
    }

    fun upsert(g: ChannelGroup) = mutate { s ->
        val i = s.groups.indexOfFirst { it.id == g.id }
        s.copy(groups = if (i >= 0) s.groups.toMutableList().also { it[i] = g } else s.groups + g)
    }

    fun deleteGroup(group: UUID) = mutate { s ->
        s.copy(
            channels = s.channels.map { if (it.groupID == group) it.copy(groupID = null) else it },
            groups = s.groups.filterNot { it.id == group },
            selection = if (s.selection == Selection.Group(group)) Selection.All else s.selection,
        )
    }

    /** A new channel, not yet added: the editor saves it. */
    fun newChannelDraft(): Channel {
        val s = snapshot
        return Channel(
            name = "Channel ${s.channels.size + 1}", colorHex = Palette.hexes[s.channels.size % Palette.hexes.size],
            groupID = (s.selection as? Selection.Group)?.id,
        )
    }

    // MARK: Network frames

    private fun sharedChannelMetas(): List<SharedChannel> {
        val s = snapshot
        return s.channels.filter { !it.isRemote && s.isShared(it) }.map {
            SharedChannel(it.id, it.name, it.colorHex, it.groupID, s.group(it.groupID)?.name, it.localeID)
        }
    }

    /** Tell the session about local channel changes. Call after editing channels. */
    fun broadcastChannels() {
        if (net.isRunning) net.broadcast(NetFrame(type = "channels", channels = sharedChannelMetas()))
    }

    private fun handleNet(f: NetFrame, peer: String) {
        when (f.type) {
            "hello", "channels" -> f.channels?.forEach { if (it.id !in ignoredRemote) applyRemote(it, peer) }
            "message" -> {
                val m = f.message ?: return
                if (snapshot.channel(m.channelID) == null || snapshot.messages[m.channelID]?.any { it.id == m.id } == true) return
                post(m.channelID, m.text, typed = m.typed, remote = true, speaker = m.speaker, id = m.id, date = m.date)
            }
            "history" -> f.messages?.sortedBy { it.date }?.forEach { backfill(it) }
            "removed" -> f.removedID?.let { if (snapshot.channel(it)?.isRemote == true) delete(it) }
            "cue" -> {
                val c = f.cue ?: return
                addLog("Cue “${c.title}” from $peer")
                if (!snapshot.settings.flashOnRemoteCues) return
                val detail = if (c.channel.isEmpty()) "From $peer" else "From $peer  ·  ${c.channel}"
                presentCue(CueEvent(title = c.title, detail = detail, colorHex = c.colorHex))
                flashScreen(c.colorHex, c.title, detail, coalesce = true)
            }
        }
    }

    /** Insert an earlier message from a peer without alerts, automations or unread counts. */
    private fun backfill(m: SharedMessage) {
        val ch = snapshot.channel(m.channelID) ?: return
        val list = snapshot.messages[m.channelID] ?: emptyList()
        if (list.any { it.id == m.id }) return
        val msg = Message(
            id = m.id, channelID = m.channelID, date = m.date, speaker = m.speaker, text = m.text, typed = m.typed, remote = true,
            keywordHit = snapshot.highlightTerms(ch).any { Text.matches(m.text, it, true) },
        )
        mutate { it.copy(messages = it.messages + (m.channelID to (list + msg).sortedBy { x -> x.date })) }
    }

    private fun applyRemote(meta: SharedChannel, owner: String) {
        mutate { s ->
            var groups = s.groups
            var gid: UUID? = null
            if (meta.groupID != null && meta.groupName != null) {
                gid = meta.groupID
                val existing = groups.firstOrNull { it.id == gid }
                groups = when {
                    existing == null -> groups + ChannelGroup(id = gid, name = meta.groupName, shared = true, isRemote = true)
                    existing.isRemote && existing.name != meta.groupName -> groups.map { if (it.id == gid) it.copy(name = meta.groupName) else it }
                    else -> groups
                }
            }
            val i = s.channels.indexOfFirst { it.id == meta.id }
            val channels = when {
                i < 0 -> s.channels + Channel(
                    id = meta.id, name = meta.name, colorHex = meta.colorHex, groupID = gid, localeID = meta.localeID,
                    enabled = false, shared = true, isRemote = true, ownerName = owner,
                )
                !s.channels[i].isRemote -> s.channels                                   // our own channel echoed back
                else -> s.channels.toMutableList().also { it[i] = it[i].copy(name = meta.name, colorHex = meta.colorHex, groupID = gid, ownerName = owner) }
            }
            s.copy(groups = groups, channels = channels)
        }
    }

    // MARK: Translation

    private fun enqueueTranslation(m: Message, ch: Channel) {
        val tr = platform.translator ?: return
        if (!ch.translate) return
        val source = ch.localeID.substringBefore('-').lowercase()
        val target = snapshot.settings.preferredLanguage.substringBefore('-').lowercase()
        if (source == target) return
        tr.translate(m.text, source, target) { result ->
            if (result != null) scope.launch(main) {
                mutate { s ->
                    val list = s.messages[m.channelID] ?: return@mutate s
                    val i = list.indexOfFirst { it.id == m.id }
                    if (i < 0) s else s.copy(messages = s.messages + (m.channelID to list.toMutableList().also { it[i] = it[i].copy(translation = result) }))
                }
            }
        }
    }

    // MARK: Export / import

    fun transcriptText(csv: Boolean): String {
        val s = snapshot
        val df = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.systemDefault())
        fun clean(t: String) = if (s.settings.filterOffensive) Offensive.mask(t) else t
        val msgs = s.visibleMessages()
        return if (csv) {
            fun q(t: String) = "\"" + t.replace("\"", "\"\"") + "\""
            "timestamp,channel,speaker,text,translation\n" + msgs.joinToString("\n") {
                listOf(df.format(it.date), s.channel(it.channelID)?.name ?: "", it.speaker, clean(it.text), clean(it.translation ?: "")).joinToString(",") { c -> q(c) }
            }
        } else {
            msgs.joinToString("\n") {
                "[${df.format(it.date)}] ${s.channel(it.channelID)?.name ?: "?"} — ${it.speaker}: ${clean(it.text)}" +
                    (it.translation?.let { t -> "\n    → ${clean(t)}" } ?: "")
            }
        }
    }

    fun exportConfiguration(): String {
        val s = snapshot
        return prettyJson.encodeToString(
            ConfigBundle.serializer(),
            ConfigBundle(channels = s.channels.filterNot { it.isRemote }, groups = s.groups.filterNot { it.isRemote }, automations = s.automations, settings = s.settings),
        )
    }

    /** Returns an error message, or null when the file was imported. */
    fun importConfiguration(text: String, replace: Boolean): String? {
        val b = runCatching { CueJson.decodeFromString(ConfigBundle.serializer(), text) }.getOrNull()
            ?: return "Not a CueComm setup file."
        if (replace) {
            mutate { s ->
                s.copy(
                    channels = s.channels.filter { it.isRemote } + b.channels, groups = s.groups.filter { it.isRemote } + b.groups,
                    automations = b.automations, selection = Selection.All,
                )
            }
            // Keep this device's own name and network choices: a setup file describes the show, not the phone.
            updateSettings { it.copy(fontSize = b.settings.fontSize, preferredLanguage = b.settings.preferredLanguage, silenceCommit = b.settings.silenceCommit,
                oscDefaultHost = b.settings.oscDefaultHost, oscDefaultPort = b.settings.oscDefaultPort) }
        } else {
            mutate { s ->
                s.copy(
                    groups = s.groups + b.groups.filter { g -> s.groups.none { it.id == g.id } },
                    channels = s.channels + b.channels.filter { c -> s.channels.none { it.id == c.id } },
                    automations = s.automations + b.automations.filter { a -> s.automations.none { it.id == a.id } },
                )
            }
        }
        addLog("Imported setup: ${b.channels.size} channels, ${b.automations.size} automations")
        syncSpeech()
        return null
    }

    // MARK: Persistence

    private fun saveSoon() {
        if (saveJob?.isActive == true) return
        saveJob = scope.launch(main) { delay(2000); save() }
    }

    /** Writes config and history now (the activity calls this when it stops). */
    fun save() {
        val s = snapshot
        val config = SavedConfig(instanceID, s.channels, s.groups, s.automations, s.settings, ignoredRemote.toList())
        val history = s.messages.entries.associate { it.key.toString().uppercase() to it.value.takeLast(300) }
        val files = dir
        scope.launch(kotlinx.coroutines.Dispatchers.IO) {
            runCatching {
                files.mkdirs()
                atomicWrite(File(files, "config.json"), CueJson.encodeToString(SavedConfig.serializer(), config))
                atomicWrite(File(files, "history.json"), historyJson.encodeToString(historySerializer, history))
            }
        }
    }

    private fun atomicWrite(f: File, text: String) {
        val tmp = File(f.parentFile, f.name + ".tmp")
        tmp.writeText(text)
        if (!tmp.renameTo(f)) { f.writeText(text); tmp.delete() }
    }

    private fun load() {
        val cfg = runCatching { CueJson.decodeFromString(SavedConfig.serializer(), File(dir, "config.json").readText()) }.getOrNull()
        if (cfg != null) {
            instanceID = cfg.instanceID.uppercase()
            ignoredRemote = cfg.ignoredRemote.toMutableSet()
            val settings = cfg.settings.let { if (it.displayName == "CueComm") it.copy(displayName = snapshot.settings.displayName) else it }
            _state.value = snapshot.copy(
                channels = cfg.channels.filterNot { it.isRemote }, groups = cfg.groups.filterNot { it.isRemote },
                automations = cfg.automations, settings = settings,
            )
        }
        val hist = runCatching { historyJson.decodeFromString(historySerializer, File(dir, "history.json").readText()) }.getOrNull()
        if (hist != null) {
            val known = snapshot.channels.map { it.id }.toSet()
            _state.value = snapshot.copy(messages = hist.mapKeys { UUID.fromString(it.key) }.filterKeys { it in known })
        }
    }

    /** Stops everything; used by tests and when the process ends. */
    fun shutdown() {
        save()
        net.stop()
        osc.stopListening()
        platform.speech?.stop()
    }
}
