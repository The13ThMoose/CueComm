import SwiftUI
import Combine
import AVFoundation
import Speech
import UniformTypeIdentifiers

enum Selection: Hashable {
    case all
    case group(UUID)
    case channel(UUID)
}

struct ScrollRequest: Equatable {
    var target: ScrollTarget
    var token = UUID()
}

struct EditorTarget: Identifiable {
    var id = UUID()
    var channel: Channel
    var isNew: Bool
}

struct LogEntry: Identifiable {
    let id = UUID()
    let date = Date()
    let text: String
}

struct TranslationJob: Identifiable, Equatable {
    var id: UUID            // message id
    var channelID: UUID
    var text: String
    var source: String      // language code
}

struct CueEvent: Identifiable, Equatable {
    let id = UUID()
    var title: String
    var detail: String
    var colorHex: String
}

struct ActionContext {
    var text = ""
    var channel = ""
    var keyword = ""
    var args: [String] = []
    var automationName = ""
}

final class LiveText: ObservableObject { @Published var partials: [UUID: String] = [:] }
final class Levels: ObservableObject { @Published var values: [UUID: Float] = [:] }

@MainActor
final class AppStore: ObservableObject {
    // Persisted
    @Published var channels: [Channel] = []
    @Published var groups: [ChannelGroup] = []
    @Published var automations: [Automation] = []
    @Published var settings = AppSettings()
    @Published var messages: [UUID: [Message]] = [:]

    // Live state
    @Published var selection: Selection = .all { didSet { if oldValue != selection { markRead(selection) } } }
    @Published var isListening = false
    @Published var devices: [AudioInputDevice] = []
    @Published var unread: [UUID: Int] = [:]
    @Published var missed: Set<UUID> = []
    @Published var status: [UUID: String] = [:]
    @Published var speechAuth = SFSpeechRecognizer.authorizationStatus()
    @Published var micAuth = AVCaptureDevice.authorizationStatus(for: .audio) == .authorized
    @Published var flashToken = 0
    @Published var scrollRequest: ScrollRequest?
    @Published var atBottom = true
    @Published var editor: EditorTarget?
    @Published var groupEditor: ChannelGroup?
    @Published var composeTarget: UUID?
    @Published var log: [LogEntry] = []
    @Published var peers: [String] = []
    @Published var midiDestinations: [String] = []
    @Published var midiSources: [String] = []
    @Published var translationJobs: [TranslationJob] = []
    @Published var learning: Bool = false
    @Published var settingsTab = "general"
    @Published var sessions: [DiscoveredSession] = []
    @Published var manualStatus: [String: ManualLinkState] = [:]
    @Published var activePort: Int?
    @Published var addressJoin: AddressJoin = .idle
    @Published var lastCue: CueEvent?
    @Published var flashColor = Color(hex: "FFE14D")
    @Published var showCues = false
    @Published var sessionSheet = false { didSet { refreshNetwork() } }

    let live = LiveText()
    let levels = Levels()
    weak var mainWindow: NSWindow? { didSet { applyWindowLevel() } }

    private let midi = MIDIManager()
    private let osc = OSCService()
    private let net = NetworkSync()
    private var probe: AddressProbe?
    private let capture = CaptureManager()
    private var ignoredRemote: Set<UUID> = []
    private var instanceID = UUID().uuidString
    private var firedInUtterance: [UUID: Set<UUID>] = [:]
    @Published private(set) var lastFired: [UUID: Date] = [:]
    private var lastFlash = Date.distantPast
    private var learnHandler: ((MIDIEvent) -> Void)?
    private var cancellables = Set<AnyCancellable>()

    // MARK: - Demo

    /// Fills the transcript with plausible crew chatter so the layout can be previewed without a live show.
    func loadDemo() {
        guard !channels.isEmpty else { return }
        let lines: [(Int, String, TimeInterval)] = [
            (0, "can I get more of me in the wedge please", 95), (1, "FOH copy, bringing the vocal up two", 88),
            (2, "standby lights cue forty two", 70), (2, "go lights forty two", 66),
            (0, "monitor mix four is feeling a bit hot on the hi-hat", 40), (1, "check one two, check", 31),
            (2, "standby video", 14), (0, "thanks, that's perfect now", 6)
        ]
        for (i, text, ago) in lines {
            let ch = channels[i % channels.count]
            var m = Message(channelID: ch.id, date: Date().addingTimeInterval(-ago), speaker: ch.name, text: text)
            m.keywordHit = highlightTerms(for: ch).contains { Self.matches(text, phrase: $0, wholeWord: true) }
            messages[ch.id, default: []].append(m)
        }
        if let first = channels.first { unread[first.id] = 2; missed.insert(first.id) }
        updateBadge()
    }

    // MARK: - Setup

    init() {
        load()
        if channels.isEmpty { seedDefaults() }
        selection = .all

        devices = AudioDevices.inputs()
        AudioDevices.watch { [weak self] in MainActor.assumeIsolated { self?.devices = AudioDevices.inputs() } }

        midi.onEvent = { [weak self] e in MainActor.assumeIsolated { self?.handleMIDI(e) } }
        midi.onSetupChanged = { [weak self] in MainActor.assumeIsolated { self?.refreshMIDILists() } }
        midi.start()
        refreshMIDILists()

        osc.onMessage = { [weak self] m, from in MainActor.assumeIsolated { self?.handleOSC(m, from: from) } }
        osc.onError = { [weak self] s in MainActor.assumeIsolated { self?.addLog(s) } }

        net.helloChannels = { [weak self] in MainActor.assumeIsolated { self?.sharedChannelMetas() ?? [] } }
        net.helloHistory = { [weak self] in MainActor.assumeIsolated { self?.recentSharedMessages() ?? [] } }
        net.onSessions = { [weak self] s in MainActor.assumeIsolated { self?.sessions = s } }
        net.onFrame = { [weak self] f, name in MainActor.assumeIsolated { self?.handleNet(f, from: name) } }
        net.onPeersChanged = { [weak self] p in MainActor.assumeIsolated { if self?.peers != p { self?.peers = p } } }
        net.onManualStatus = { [weak self] m in MainActor.assumeIsolated { if self?.manualStatus != m { self?.manualStatus = m } } }
        net.onListenPort = { [weak self] p in MainActor.assumeIsolated { if self?.activePort != p { self?.activePort = p } } }
        net.onError = { [weak self] s in MainActor.assumeIsolated { self?.addLog(s) } }

        capture.onPartial = { [weak self] c, t, u in MainActor.assumeIsolated { self?.pipePartial(c, t, u) } }
        capture.onCommit = { [weak self] c, t, u in MainActor.assumeIsolated { self?.pipeCommit(c, t, u) } }
        capture.onStatus = { [weak self] c, s in MainActor.assumeIsolated { self?.status[c] = s } }
        capture.onLevels = { [weak self] v in MainActor.assumeIsolated { self?.levels.values = v } }

        Publishers.CombineLatest4($channels, $isListening, $devices, $settings.map(\.silenceCommit).removeDuplicates())
            .debounce(for: .milliseconds(150), scheduler: RunLoop.main)
            .sink { [weak self] ch, listening, devs, silence in
                MainActor.assumeIsolated {
                    self?.capture.sync(listening: listening, channels: ch, devices: devs, silence: silence)
                    if !listening { self?.live.partials = [:] }
                }
            }.store(in: &cancellables)

        $settings.removeDuplicates().sink { [weak self] s in
            MainActor.assumeIsolated { self?.applySettings(s) }
        }.store(in: &cancellables)

        $channels.map { $0.filter { !$0.isRemote }.map { [$0.id, $0.name.hashValue, $0.shared.hashValue, $0.groupID.hashValue] as [AnyHashable] } }
            .removeDuplicates { $0.elementsEqual($1) }
            .dropFirst().debounce(for: .milliseconds(300), scheduler: RunLoop.main)
            .sink { [weak self] _ in MainActor.assumeIsolated { self?.broadcastChannels() } }.store(in: &cancellables)

        objectWillChange.debounce(for: .seconds(2), scheduler: RunLoop.main)
            .sink { [weak self] in MainActor.assumeIsolated { self?.save() } }.store(in: &cancellables)

        NotificationCenter.default.addObserver(forName: NSApplication.didBecomeActiveNotification, object: nil, queue: .main) { [weak self] _ in
            MainActor.assumeIsolated { if let s = self { s.markRead(s.selection) } }
        }
        NotificationCenter.default.addObserver(forName: NSApplication.willTerminateNotification, object: nil, queue: .main) { [weak self] _ in
            MainActor.assumeIsolated { self?.save() }
        }
        applySettings(settings)
        if let target = ProcessInfo.processInfo.environment["CUECOMM_JOIN"] {          // test hook: join by address at launch
            Task { @MainActor in
                try? await Task.sleep(nanoseconds: 700_000_000)
                self.joinByAddress(target)
                try? await Task.sleep(nanoseconds: 3_000_000_000)
                FileHandle.standardError.write(Data("JOIN-RESULT \(self.addressJoin) session=\(self.settings.sessionName) peers=\(self.peers)\n".utf8))
            }
        }
        if ProcessInfo.processInfo.environment["CUECOMM_DEMO"] != nil {
            loadDemo(); showCues = true
            switch ProcessInfo.processInfo.environment["CUECOMM_DEMO"] {
            case "editor": showCues = false; editor = EditorTarget(channel: channels[0], isNew: false)
            case "session", "manual": showCues = false; sessionSheet = true
            case "new": showCues = false; newChannel()
            default: break
            }
            Task { @MainActor in
                try? await Task.sleep(nanoseconds: 1_500_000_000)
                if let a = self.automations.first(where: { $0.isCue }) ?? self.automations.first { self.fire(a, ActionContext(text: "go", channel: "Show Caller", keyword: "go"), force: true) }
            }
        }
    }

    private func seedDefaults() {
        let audio = ChannelGroup(name: "Audio")
        let prod = ChannelGroup(name: "Production")
        groups = [audio, prod]
        channels = [
            Channel(name: "Monitors", colorHex: Palette.hexes[0], groupID: audio.id, pinned: true, keywords: ["monitor", "wedge"]),
            Channel(name: "FOH", colorHex: Palette.hexes[1], groupID: audio.id, keywords: ["more", "less"]),
            Channel(name: "Show Caller", colorHex: Palette.hexes[2], groupID: prod.id, pinned: true, keywords: ["standby"])
        ]
        channels[1].enabled = false
        channels[2].enabled = false
        automations = []
        addCue("go")
        addCue("standby")
    }

    private func applySettings(_ s: AppSettings) {
        midi.outputName = s.midiOutputName
        midi.inputEnabled = s.midiInputEnabled
        if s.oscListenEnabled { osc.listen(port: s.oscListenPort) } else { osc.stopListening() }
        refreshNetwork(s)
        applyWindowLevel()
    }

    private func applyWindowLevel() { mainWindow?.level = settings.alwaysOnTop ? .floating : .normal }

    // MARK: - Session & network

    var inSession: Bool { !settings.sessionName.isEmpty }

    /// Full networking while in a session or open-sharing; browse-only while the Session sheet is open.
    /// `s` is the settings being applied: `$settings` publishes before the property changes, so reading
    /// `settings` from that sink would still give the old value and a new session would not connect until later.
    func refreshNetwork(_ s: AppSettings? = nil) {
        let s = s ?? settings
        net.manualPeers = s.manualPeers
        if !s.sessionName.isEmpty || s.networkEnabled {
            net.start(instanceID: instanceID, name: s.displayName, session: s.sessionName, advertise: true, port: s.listenPort)
        } else if sessionSheet {
            net.start(instanceID: instanceID, name: s.displayName, session: "", advertise: false)
        } else if net.isRunning {
            net.stop()
        }
    }

    func startSession(_ name: String) {
        let n = name.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !n.isEmpty else { return }
        dropRemote()
        settings.sessionName = n
        addLog("Session “\(n)” started")
    }

    func joinSession(_ name: String) { startSession(name) }

    /// Joins the session of the Mac at this address, whatever its name: asks it which session it is in, then
    /// switches to that session and connects at once. The address is kept so a dropped link is redialled.
    func joinByAddress(_ text: String) {
        guard let a = ManualAddress.parse(text), let key = ManualAddress.normalised(text) else {
            addressJoin = .failed("Type an IP address or host name, like 203.0.113.5 or 203.0.113.5:47800.")
            return
        }
        probe?.cancel()
        addressJoin = .joining(key)
        let p = AddressProbe(host: a.host, port: a.port, ownID: instanceID, ownName: settings.displayName) { [weak self] result in
            MainActor.assumeIsolated { self?.finishJoin(key, result) }
        }
        probe = p
        if let p { p.start() } else { addressJoin = .failed("That address cannot be used.") }
    }

    private func finishJoin(_ key: String, _ result: Result<AddressProbe.Answer, AddressProbe.Failure>) {
        probe = nil
        switch result {
        case .failure(let f):
            addressJoin = .failed(f.message)
        case .success(let a):
            if a.peerID == instanceID { addressJoin = .failed("That address is this Mac."); return }
            if a.session.isEmpty {
                addressJoin = .failed("\(a.name) is not hosting a session yet. Start one there, then join it from here.")
                return
            }
            if inSession && settings.sessionName != a.session {
                addressJoin = .failed("\(a.name) is in “\(a.session)”. Leave “\(settings.sessionName)” first to join it.")
                return
            }
            if !inSession { dropRemote() }
            var s = settings                                    // one change, so networking restarts once
            s.sessionName = a.session
            if !s.manualPeers.contains(key) { s.manualPeers.append(key) }
            settings = s
            addressJoin = .idle
            addLog("Joined “\(a.session)” at \(a.name) (\(key))")
        }
    }

    func removeManualPeer(_ key: String) {
        settings.manualPeers.removeAll { $0 == key }
        addLog("Stopped connecting to \(key)")
    }

    func setListenPort(_ port: Int) {
        settings.listenPort = min(65535, max(1024, port))
    }

    func leaveSession() {
        addLog("Left session “\(settings.sessionName)”")
        var s = settings
        s.sessionName = ""
        s.manualPeers = []                                      // addresses belong to the session just left
        settings = s
        addressJoin = .idle
        dropRemote()
    }

    /// Forget channels, groups and transcripts that belong to other machines.
    private func dropRemote() {
        let ids = channels.filter(\.isRemote).map(\.id)
        for id in ids { messages[id] = nil; unread[id] = nil }
        channels.removeAll { $0.isRemote }
        groups.removeAll { $0.isRemote }
        if case .channel(let c) = selection, ids.contains(c) { selection = .all }
        peers = []
    }

    private func recentSharedMessages() -> [SharedMessage] {
        channels.filter { !$0.isRemote && isShared($0) }.flatMap { ch in
            (messages[ch.id] ?? []).suffix(60).map {
                SharedMessage(id: $0.id, channelID: ch.id, speaker: $0.speaker, text: $0.text, date: $0.date, typed: $0.typed)
            }
        }
    }

    func refreshMIDILists() {
        midiDestinations = midi.destinationNames
        midiSources = midi.sourceNames
    }

    // MARK: - Permissions & listening

    func toggleListening() {
        if isListening { isListening = false; return }
        SFSpeechRecognizer.requestAuthorization { [weak self] st in
            DispatchQueue.main.async { MainActor.assumeIsolated { self?.speechAuth = st } }
        }
        AVCaptureDevice.requestAccess(for: .audio) { [weak self] ok in
            DispatchQueue.main.async { MainActor.assumeIsolated { self?.micAuth = ok } }
        }
        isListening = true
    }

    var permissionProblem: String? {
        guard isListening else { return nil }
        if AVCaptureDevice.authorizationStatus(for: .audio) == .denied || AVCaptureDevice.authorizationStatus(for: .audio) == .restricted {
            return "Microphone access is off for CueComm."
        }
        if speechAuth == .denied || speechAuth == .restricted { return "Speech Recognition access is off for CueComm." }
        return nil
    }

    // MARK: - Lookup helpers

    func channel(_ id: UUID) -> Channel? { channels.first { $0.id == id } }
    func group(_ id: UUID?) -> ChannelGroup? { id.flatMap { gid in groups.first { $0.id == gid } } }

    func channelIDs(for sel: Selection) -> [UUID] {
        switch sel {
        case .all: return channels.map(\.id)
        case .group(let g): return channels.filter { $0.groupID == g }.map(\.id)
        case .channel(let c): return [c]
        }
    }

    func title(for sel: Selection) -> String {
        switch sel {
        case .all: return "All Channels"
        case .group(let g): return group(g)?.name ?? "Group"
        case .channel(let c): return channel(c)?.name ?? "Channel"
        }
    }

    func visibleMessages(_ sel: Selection? = nil) -> [Message] {
        let ids = channelIDs(for: sel ?? selection)
        if ids.count == 1 { return messages[ids[0]] ?? [] }
        return ids.flatMap { messages[$0] ?? [] }.sorted { $0.date < $1.date }.suffix(1500).map { $0 }
    }

    func isShared(_ ch: Channel) -> Bool {
        if ch.isRemote { return true }
        if inSession { return !ch.isPrivate }                 // sessions share everything unless marked private
        return ch.shared || (group(ch.groupID)?.shared ?? false)
    }

    func highlightTerms(for ch: Channel) -> [String] {
        var terms = ch.keywords
        for a in automations where a.enabled && a.trigger.kind == .keyword && (a.trigger.channelID == nil || a.trigger.channelID == ch.id) {
            terms += Self.split(a.trigger.phrase)
        }
        return terms.filter { !$0.isEmpty }
    }

    static func split(_ phrases: String) -> [String] {
        phrases.split(separator: ",").map { $0.trimmingCharacters(in: .whitespaces) }.filter { !$0.isEmpty }
    }

    static func matches(_ text: String, phrase: String, wholeWord: Bool) -> Bool {
        let p = phrase.trimmingCharacters(in: .whitespaces)
        guard !p.isEmpty else { return false }
        if !wholeWord { return text.range(of: p, options: [.caseInsensitive, .diacriticInsensitive]) != nil }
        let pattern = "(?<![\\p{L}\\p{N}])" + NSRegularExpression.escapedPattern(for: p) + "(?![\\p{L}\\p{N}])"
        return text.range(of: pattern, options: [.regularExpression, .caseInsensitive]) != nil
    }

    // MARK: - Messages

    private func pipePartial(_ cid: UUID, _ text: String, _ utt: UUID) {
        live.partials[cid] = text
        if settings.triggerOnPartials { runAutomations(channelID: cid, text: text, utterance: utt, commit: false) }
    }

    private func pipeCommit(_ cid: UUID, _ text: String, _ utt: UUID) {
        live.partials[cid] = nil
        post(channelID: cid, text: text, utterance: utt)
    }

    func post(channelID: UUID, text raw: String, typed: Bool = false, remote: Bool = false, speaker: String? = nil,
              id: UUID = UUID(), date: Date = Date(), utterance: UUID? = nil) {
        let text = raw.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !text.isEmpty, let ch = channel(channelID) else { return }
        var m = Message(id: id, channelID: channelID, date: date, speaker: speaker ?? (typed ? settings.displayName : ch.name), text: text)
        m.typed = typed
        m.remote = remote
        m.keywordHit = highlightTerms(for: ch).contains { Self.matches(text, phrase: $0, wholeWord: true) }

        var list = messages[channelID, default: []]
        list.append(m)
        if list.count > settings.historyLimit { list.removeFirst(list.count - settings.historyLimit) }
        messages[channelID] = list

        let viewing = NSApp.isActive && atBottom && channelIDs(for: selection).contains(channelID)
        if viewing || (typed && !remote) {
            if channelIDs(for: selection).contains(channelID) { scrollRequest = ScrollRequest(target: .current) }
        } else {
            unread[channelID, default: 0] += 1
            if m.keywordHit { missed.insert(channelID) }
            updateBadge()
        }

        runAutomations(channelID: channelID, text: text, utterance: utterance, commit: true, remote: remote)
        if !(typed && !remote) { enqueueTranslation(m, ch) }
        if isShared(ch) && !remote {
            net.broadcast(NetFrame(type: "message", message: SharedMessage(id: m.id, channelID: channelID, speaker: m.speaker, text: text, date: date, typed: typed)))
        }
    }

    func send(_ text: String, to channelID: UUID) {
        post(channelID: channelID, text: text, typed: true)
    }

    // MARK: Read state

    func markRead(_ sel: Selection) {
        for id in channelIDs(for: sel) { unread[id] = nil; missed.remove(id) }
        updateBadge()
    }

    func markAllRead() { unread = [:]; missed = []; updateBadge() }

    private func updateBadge() {
        let total = unread.values.reduce(0, +)
        NSApp.dockTile.badgeLabel = total > 0 ? "\(total)" : nil
    }

    func clear(_ sel: Selection) {
        for id in channelIDs(for: sel) { messages[id] = [] }
        markRead(sel)
    }

    // MARK: - Automations

    private func runAutomations(channelID: UUID, text: String, utterance: UUID?, commit: Bool, remote: Bool = false) {
        guard let ch = channel(channelID) else { return }
        for a in automations where a.enabled {
            // One spoken "go" must send one OSC message, not one per Mac in the session.
            if remote && !a.trigger.includeRemote { continue }
            guard a.trigger.channelID == nil || a.trigger.channelID == channelID else { continue }
            var hit = ""
            switch a.trigger.kind {
            case .keyword:
                if let u = utterance, firedInUtterance[u]?.contains(a.id) == true { continue }
                guard let found = Self.split(a.trigger.phrase).first(where: { Self.matches(text, phrase: $0, wholeWord: a.trigger.wholeWord) }) else { continue }
                hit = found
                if let u = utterance { firedInUtterance[u, default: []].insert(a.id) }
            case .message:
                guard commit else { continue }
            default:
                continue
            }
            fire(a, ActionContext(text: text, channel: ch.name, keyword: hit))
        }
        if commit, let u = utterance { firedInUtterance[u] = nil }
    }

    private func handleMIDI(_ e: MIDIEvent) {
        addLog("MIDI in: \(e.description)")
        if let h = learnHandler { learnHandler = nil; learning = false; h(e); return }
        for a in automations where a.enabled && a.trigger.kind == .midi {
            let t = a.trigger
            guard t.midiKind == e.kind, t.midiChannel == 0 || t.midiChannel == e.channel, t.data1 < 0 || t.data1 == e.data1 else { continue }
            fire(a, ActionContext(text: e.description, channel: "", keyword: "", args: [String(e.data1), String(e.data2)]))
        }
    }

    private func handleOSC(_ m: OSCMessage, from: String) {
        addLog("OSC in: \(m.address) \(m.args.map(\.text).joined(separator: " "))")
        for a in automations where a.enabled && a.trigger.kind == .osc && OSCCodec.matches(address: m.address, pattern: a.trigger.oscAddress) {
            fire(a, ActionContext(text: m.address, channel: "", keyword: "", args: m.args.map(\.text)))
        }
    }

    func startMIDILearn(_ handler: @escaping (MIDIEvent) -> Void) {
        learning = true
        learnHandler = handler
    }

    func cancelMIDILearn() { learning = false; learnHandler = nil }

    func fire(_ a: Automation, _ ctx: ActionContext, force: Bool = false) {
        if !force, let last = lastFired[a.id], Date().timeIntervalSince(last) < a.cooldown { return }
        var ctx = ctx
        ctx.automationName = a.name
        lastFired[a.id] = Date()
        addLog("Triggered “\(a.name)”" + (ctx.keyword.isEmpty ? "" : " by “\(ctx.keyword)”"))
        showCueBanner(a, ctx)
        for action in a.actions {
            if action.delay > 0 {
                Task { @MainActor in
                    try? await Task.sleep(nanoseconds: UInt64(action.delay * 1_000_000_000))
                    self.perform(action, ctx)
                }
            } else {
                perform(action, ctx)
            }
        }
    }

    private func showCueBanner(_ a: Automation, _ ctx: ActionContext) {
        let parts = a.actions.compactMap { act -> String? in
            switch act.kind {
            case .sendOSC: return "OSC \(act.oscHost):\(act.oscPort) \(act.oscAddress)"
            case .sendMIDI: return "MIDI \(act.midiKind.label) \(act.data1)"
            case .flashWindow: return "Flash"
            default: return nil
            }
        }
        presentCue(CueEvent(title: ctx.keyword.isEmpty ? a.name : ctx.keyword, detail: parts.joined(separator: "  ·  "),
                            colorHex: a.actions.first { $0.kind == .flashWindow }?.flashColorHex ?? "FFE14D"))
    }

    private func presentCue(_ event: CueEvent) {
        lastCue = event
        Task { @MainActor in
            try? await Task.sleep(nanoseconds: 4_000_000_000)
            if self.lastCue?.id == event.id { self.lastCue = nil }
        }
    }

    /// Flashes this window. `coalesce` drops a repeat that lands within half a second of the last one,
    /// so a cue that reaches this Mac twice (over the network and through its own automation) flashes once.
    private func flashScreen(hex: String, coalesce: Bool = false) {
        let now = Date()
        if coalesce, now.timeIntervalSince(lastFlash) < 0.5 { return }
        lastFlash = now
        flashColor = Color(hex: hex)
        flashToken += 1
        NSApp.requestUserAttention(.informationalRequest)
    }

    // MARK: Cue words

    /// A cue word is a keyword automation that sends OSC and flashes the screen.
    @discardableResult
    func addCue(_ word: String) -> UUID? {
        let w = word.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !w.isEmpty else { return nil }
        let slug = w.lowercased().components(separatedBy: CharacterSet.alphanumerics.inverted).filter { !$0.isEmpty }.joined(separator: "-")
        var osc = AutomationAction(kind: .sendOSC)
        osc.oscHost = settings.oscDefaultHost; osc.oscPort = settings.oscDefaultPort; osc.oscAddress = "/cue/" + slug
        var cue = Automation(name: "Cue: \(w)", trigger: AutomationTrigger(kind: .keyword, phrase: w), actions: [osc, AutomationAction(kind: .flashWindow)])
        cue.isCue = true
        cue.cooldown = 1.5
        automations.insert(cue, at: 0)
        return cue.id
    }

    private func perform(_ a: AutomationAction, _ ctx: ActionContext) {
        switch a.kind {
        case .sendMIDI:
            midi.send(a.midiKind, channel: a.midiChannel, data1: a.data1, data2: a.data2)
        case .sendOSC:
            osc.send(OSCMessage(address: a.oscAddress, args: OSCCodec.parseArgs(substitute(a.oscArgs, ctx))), host: a.oscHost, port: a.oscPort)
        case .flashWindow:
            flashScreen(hex: a.flashColorHex)
            // The cue is heard on one Mac but the crew is spread over several: tell the others to flash too.
            if a.flashEveryone, net.isRunning {
                let title = ctx.keyword.isEmpty ? (ctx.automationName.isEmpty ? "Cue" : ctx.automationName) : ctx.keyword
                net.broadcast(NetFrame(type: "cue", cue: SharedCue(title: title, channel: ctx.channel, colorHex: a.flashColorHex)))
            }
        case .showWindow:
            NSApp.unhide(nil)
            NSApp.activate(ignoringOtherApps: true)
            (mainWindow ?? NSApp.windows.first)?.makeKeyAndOrderFront(nil)
        case .hideWindow:
            NSApp.hide(nil)
        case .selectChannel:
            if let id = a.targetChannelID, channel(id) != nil { selection = .channel(id) }
        case .scrollTranscript:
            scrollRequest = ScrollRequest(target: a.scrollTarget)
        case .clearTranscript:
            clear(a.targetChannelID.map { .channel($0) } ?? selection)
        case .markRead:
            markRead(a.targetChannelID.map { .channel($0) } ?? selection)
        case .adjustFontSize:
            settings.fontSize = min(48, max(10, settings.fontSize + Double(a.fontDelta)))
        }
    }

    private func substitute(_ template: String, _ ctx: ActionContext) -> String {
        func q(_ s: String) -> String { "\"" + s.replacingOccurrences(of: "\"", with: "'") + "\"" }
        var out = template
            .replacingOccurrences(of: "{text}", with: q(ctx.text))
            .replacingOccurrences(of: "{channel}", with: q(ctx.channel))
            .replacingOccurrences(of: "{keyword}", with: q(ctx.keyword))
        for (i, arg) in ctx.args.enumerated() { out = out.replacingOccurrences(of: "{arg\(i + 1)}", with: arg) }
        return out
    }

    func addLog(_ s: String) {
        log.insert(LogEntry(text: s), at: 0)
        if log.count > 200 { log.removeLast(log.count - 200) }
    }

    // MARK: - Channels & groups

    func upsert(_ ch: Channel) {
        if let i = channels.firstIndex(where: { $0.id == ch.id }) { channels[i] = ch } else { channels.append(ch) }
    }

    func delete(_ id: UUID) {
        guard let ch = channel(id) else { return }
        if ch.isRemote { ignoredRemote.insert(id) } else if isShared(ch) { net.broadcast(NetFrame(type: "removed", removedID: id)) }
        channels.removeAll { $0.id == id }
        messages[id] = nil
        unread[id] = nil
        if selection == .channel(id) { selection = .all }
    }

    func upsert(_ g: ChannelGroup) {
        if let i = groups.firstIndex(where: { $0.id == g.id }) { groups[i] = g } else { groups.append(g) }
    }

    func delete(group id: UUID) {
        for i in channels.indices where channels[i].groupID == id { channels[i].groupID = nil }
        groups.removeAll { $0.id == id }
        if selection == .group(id) { selection = .all }
    }

    func newChannel() {
        var ch = Channel(name: "Channel \(channels.count + 1)", colorHex: Palette.hexes[channels.count % Palette.hexes.count])
        if case .group(let g) = selection { ch.groupID = g }
        editor = EditorTarget(channel: ch, isNew: true)
    }

    // MARK: - Network sharing

    private func sharedChannelMetas() -> [SharedChannel] {
        channels.filter { !$0.isRemote && isShared($0) }.map {
            SharedChannel(id: $0.id, name: $0.name, colorHex: $0.colorHex, groupID: $0.groupID,
                          groupName: group($0.groupID)?.name, localeID: $0.localeID)
        }
    }

    private func broadcastChannels() {
        guard net.isRunning else { return }
        net.broadcast(NetFrame(type: "channels", channels: sharedChannelMetas()))
    }

    private func handleNet(_ f: NetFrame, from peer: String) {
        switch f.type {
        case "hello", "channels":
            for meta in f.channels ?? [] where !ignoredRemote.contains(meta.id) { applyRemote(meta, owner: peer) }
        case "message":
            guard let m = f.message, channel(m.channelID) != nil, messages[m.channelID]?.contains(where: { $0.id == m.id }) != true else { return }
            post(channelID: m.channelID, text: m.text, typed: m.typed, remote: true, speaker: m.speaker, id: m.id, date: m.date)
        case "history":
            for m in (f.messages ?? []).sorted(by: { $0.date < $1.date }) { backfill(m) }
        case "removed":
            if let id = f.removedID, channel(id)?.isRemote == true { delete(id) }
        case "cue":
            guard let c = f.cue else { return }
            addLog("Cue “\(c.title)” from \(peer)")
            guard settings.flashOnRemoteCues else { return }
            presentCue(CueEvent(title: c.title, detail: c.channel.isEmpty ? "From \(peer)" : "From \(peer)  ·  \(c.channel)", colorHex: c.colorHex))
            flashScreen(hex: c.colorHex, coalesce: true)
        default: break
        }
    }

    /// Insert an earlier message from a peer without alerts, automations or unread counts.
    private func backfill(_ m: SharedMessage) {
        guard let ch = channel(m.channelID) else { return }
        var list = messages[m.channelID, default: []]
        guard !list.contains(where: { $0.id == m.id }) else { return }
        var msg = Message(id: m.id, channelID: m.channelID, date: m.date, speaker: m.speaker, text: m.text)
        msg.typed = m.typed; msg.remote = true
        msg.keywordHit = highlightTerms(for: ch).contains { Self.matches(m.text, phrase: $0, wholeWord: true) }
        list.append(msg)
        list.sort { $0.date < $1.date }
        messages[m.channelID] = list
    }

    private func applyRemote(_ meta: SharedChannel, owner: String) {
        var gid: UUID?
        if let g = meta.groupID, let gname = meta.groupName {
            gid = g
            if let existing = group(g) { if existing.isRemote, existing.name != gname { var e = existing; e.name = gname; upsert(e) } }
            else { upsert(ChannelGroup(id: g, name: gname, shared: true, isRemote: true)) }
        }
        if let i = channels.firstIndex(where: { $0.id == meta.id }) {
            guard channels[i].isRemote else { return }          // our own channel echoed back
            channels[i].name = meta.name; channels[i].colorHex = meta.colorHex
            channels[i].groupID = gid; channels[i].ownerName = owner
        } else {
            channels.append(Channel(id: meta.id, name: meta.name, colorHex: meta.colorHex, groupID: gid, localeID: meta.localeID,
                                    enabled: false, shared: true, isRemote: true, ownerName: owner))
        }
    }

    // MARK: - Translation

    private func enqueueTranslation(_ m: Message, _ ch: Channel) {
        guard ch.translate else { return }
        let source = Locale(identifier: ch.localeID).language.languageCode?.identifier ?? "en"
        let target = Locale(identifier: settings.preferredLanguage).language.languageCode?.identifier ?? settings.preferredLanguage
        guard source != target else { return }
        translationJobs.append(TranslationJob(id: m.id, channelID: m.channelID, text: m.text, source: source))
    }

    func takeTranslationJobs(source: String) -> [TranslationJob] {
        let batch = translationJobs.filter { $0.source == source }
        translationJobs.removeAll { $0.source == source }
        return batch
    }

    func applyTranslation(id: UUID, channelID: UUID, text: String) {
        guard let i = messages[channelID]?.firstIndex(where: { $0.id == id }) else { return }
        messages[channelID]?[i].translation = text
    }

    // MARK: - Export / import

    func exportTranscript(csv: Bool) {
        let msgs = visibleMessages()
        let df = DateFormatter(); df.dateFormat = "yyyy-MM-dd HH:mm:ss"
        func clean(_ s: String) -> String { settings.filterOffensive ? Offensive.mask(s) : s }
        let body: String
        if csv {
            func q(_ s: String) -> String { "\"" + s.replacingOccurrences(of: "\"", with: "\"\"") + "\"" }
            body = "timestamp,channel,speaker,text,translation\n" + msgs.map {
                [df.string(from: $0.date), channel($0.channelID)?.name ?? "", $0.speaker, clean($0.text), clean($0.translation ?? "")].map(q).joined(separator: ",")
            }.joined(separator: "\n")
        } else {
            body = msgs.map {
                "[\(df.string(from: $0.date))] \(channel($0.channelID)?.name ?? "?") — \($0.speaker): \(clean($0.text))" + ($0.translation.map { "\n    → \(clean($0))" } ?? "")
            }.joined(separator: "\n")
        }
        let panel = NSSavePanel()
        let stamp = { let f = DateFormatter(); f.dateFormat = "yyyy-MM-dd HHmm"; return f.string(from: Date()) }()
        panel.nameFieldStringValue = "\(title(for: selection)) \(stamp).\(csv ? "csv" : "txt")"
        panel.allowedContentTypes = [csv ? .commaSeparatedText : .plainText]
        if panel.runModal() == .OK, let url = panel.url {
            do { try body.write(to: url, atomically: true, encoding: .utf8) } catch { addLog("Export failed: \(error.localizedDescription)") }
        }
    }

    func exportConfiguration() {
        let bundle = ConfigBundle(channels: channels.filter { !$0.isRemote }, groups: groups.filter { !$0.isRemote },
                                  automations: automations, settings: settings)
        let enc = JSONEncoder(); enc.outputFormatting = [.prettyPrinted, .sortedKeys]
        let panel = NSSavePanel()
        panel.nameFieldStringValue = "CueComm Setup.cuecomm"
        panel.allowedContentTypes = [.json]
        if panel.runModal() == .OK, let url = panel.url {
            do { try enc.encode(bundle).write(to: url) } catch { addLog("Export failed: \(error.localizedDescription)") }
        }
    }

    func importConfiguration() {
        let panel = NSOpenPanel()
        panel.allowedContentTypes = [.json, UTType(filenameExtension: "cuecomm") ?? .json]
        guard panel.runModal() == .OK, let url = panel.url else { return }
        guard let data = try? Data(contentsOf: url), let bundle = try? JSONDecoder().decode(ConfigBundle.self, from: data) else {
            let a = NSAlert(); a.messageText = "Not a CueComm setup file"; a.runModal(); return
        }
        let alert = NSAlert()
        alert.messageText = "Import \(bundle.channels.count) channels and \(bundle.automations.count) automations?"
        alert.informativeText = "Merge adds them to your current setup. Replace discards your current channels, groups and automations."
        alert.addButton(withTitle: "Merge"); alert.addButton(withTitle: "Replace"); alert.addButton(withTitle: "Cancel")
        switch alert.runModal() {
        case .alertFirstButtonReturn:
            for g in bundle.groups where !groups.contains(where: { $0.id == g.id }) { groups.append(g) }
            for c in bundle.channels where !channels.contains(where: { $0.id == c.id }) { channels.append(c) }
            for a in bundle.automations where !automations.contains(where: { $0.id == a.id }) { automations.append(a) }
        case .alertSecondButtonReturn:
            channels = channels.filter(\.isRemote) + bundle.channels
            groups = groups.filter(\.isRemote) + bundle.groups
            automations = bundle.automations
            settings = bundle.settings
            selection = .all
        default: return
        }
        addLog("Imported setup from \(url.lastPathComponent)")
    }

    // MARK: - Persistence

    private var supportDir: URL {
        // CUECOMM_SUPPORT_DIR lets two copies run side by side on one Mac when testing a session.
        let dir = ProcessInfo.processInfo.environment["CUECOMM_SUPPORT_DIR"].map { URL(fileURLWithPath: $0, isDirectory: true) }
            ?? FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0].appendingPathComponent("CueComm", isDirectory: true)
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        return dir
    }

    private struct Saved: Codable {
        var instanceID: String
        var channels: [Channel]
        var groups: [ChannelGroup]
        var automations: [Automation]
        var settings: AppSettings
        var ignoredRemote: [UUID]
        var designVersion: Int?
    }

    func save() {
        let saved = Saved(instanceID: instanceID, channels: channels, groups: groups, automations: automations, settings: settings, ignoredRemote: Array(ignoredRemote), designVersion: 3)
        let history = messages.mapValues { Array($0.suffix(300)) }
        let dir = supportDir
        DispatchQueue.global(qos: .utility).async {
            if let d = try? JSONEncoder().encode(saved) { try? d.write(to: dir.appendingPathComponent("config.json"), options: .atomic) }
            let enc = JSONEncoder(); enc.dateEncodingStrategy = .iso8601
            if let d = try? enc.encode(history) { try? d.write(to: dir.appendingPathComponent("history.json"), options: .atomic) }
        }
    }

    private func load() {
        if let d = try? Data(contentsOf: supportDir.appendingPathComponent("config.json")),
           let s = try? JSONDecoder().decode(Saved.self, from: d) {
            instanceID = s.instanceID; channels = s.channels; groups = s.groups
            automations = s.automations; settings = s.settings; ignoredRemote = Set(s.ignoredRemote)
            let version = s.designVersion ?? 1
            if version < 3 {                                      // vibrant palette (v2), then reserved-colour palette (v3)
                for i in channels.indices {
                    let hex = channels[i].colorHex.uppercased()
                    if let k = (version < 2 ? Palette.legacy : Palette.v2).firstIndex(of: hex) { channels[i].colorHex = Palette.hexes[k % Palette.hexes.count] }
                }
                if settings.fontSize < 22 { settings.fontSize = 22 }
            }
        }
        let dec = JSONDecoder(); dec.dateDecodingStrategy = .iso8601
        if let d = try? Data(contentsOf: supportDir.appendingPathComponent("history.json")),
           let h = try? dec.decode([UUID: [Message]].self, from: d) { messages = h }
    }
}
