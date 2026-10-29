import SwiftUI

// MARK: - Tolerant decoding (older / shared configs may lack newer keys)

struct AnyKey: CodingKey {
    var stringValue: String
    var intValue: Int? { nil }
    init(_ s: String) { stringValue = s }
    init?(stringValue: String) { self.stringValue = stringValue }
    init?(intValue: Int) { return nil }
}

extension KeyedDecodingContainer where Key == AnyKey {
    func get<T: Decodable>(_ key: String, _ def: T) -> T {
        (try? decodeIfPresent(T.self, forKey: AnyKey(key))) ?? def
    }
}

// MARK: - Colour

enum Palette {
    // Orange (action), green (live) and yellow (cue) are reserved, so channels never use them.
    static let hexes = ["FF5F4A", "FF4FA3", "E23DFF", "8F7BFF", "4D8DFF", "35D8FF", "22C7B8", "A8B4FF"]
    static let v2 = ["FF9F1C", "FF5F4A", "FF4FA3", "C86BFF", "4D8DFF", "35D8FF", "2CF5C8", "B6F23A"]
    /// Colours used before the vibrant redesign; migrated to the same index in `hexes`.
    static let legacy = ["5AC8FA", "FF7A59", "7BE07B", "D9A7FF", "FFD24A", "FF6FA8", "6FE0C5", "9AA7FF", "FF9F0A", "C9A27A"]
}

extension Color {
    init(hex: String) {
        var v: UInt64 = 0
        Scanner(string: hex).scanHexInt64(&v)
        self.init(red: Double((v >> 16) & 255) / 255, green: Double((v >> 8) & 255) / 255, blue: Double(v & 255) / 255)
    }
}

// MARK: - Channels & groups

struct Channel: Identifiable, Codable, Hashable {
    var id = UUID()
    var name = "Channel"
    var colorHex = Palette.hexes[0]
    var groupID: UUID?
    var pinned = false
    var deviceUID: String?          // nil = system default input
    var inputChannel = 0            // 0-based channel on the device
    var localeID = "en-US"          // spoken language
    var keywords: [String] = []
    var translate = false
    var enabled = true              // capture this channel when listening
    var shared = false              // share on the LAN (open sharing, no session)
    var isPrivate = false           // in a session: keep this channel on this Mac only
    var onDeviceOnly = true
    var isRemote = false            // owned by another machine
    var ownerName: String?

    var color: Color { Color(hex: colorHex) }

    /// Two letters shown in the channel's colour block: initials of the first two words, or the first two letters.
    var initials: String {
        let parts = name.split(separator: " ")
        let s = parts.count > 1 ? parts.prefix(2).compactMap { $0.first }.map(String.init).joined() : String(name.prefix(2))
        return s.uppercased()
    }
}

extension Channel {
    init(from d: Decoder) throws {
        self.init()
        let c = try d.container(keyedBy: AnyKey.self)
        id = c.get("id", id); name = c.get("name", name); colorHex = c.get("colorHex", colorHex)
        groupID = c.get("groupID", groupID); pinned = c.get("pinned", pinned)
        deviceUID = c.get("deviceUID", deviceUID); inputChannel = c.get("inputChannel", inputChannel)
        localeID = c.get("localeID", localeID); keywords = c.get("keywords", keywords)
        translate = c.get("translate", translate); enabled = c.get("enabled", enabled)
        shared = c.get("shared", shared); isPrivate = c.get("isPrivate", isPrivate); onDeviceOnly = c.get("onDeviceOnly", onDeviceOnly)
        isRemote = c.get("isRemote", isRemote); ownerName = c.get("ownerName", ownerName)
    }
}

struct ChannelGroup: Identifiable, Codable, Hashable {
    var id = UUID()
    var name = "Group"
    var shared = false
    var isRemote = false
}

extension ChannelGroup {
    init(from d: Decoder) throws {
        self.init()
        let c = try d.container(keyedBy: AnyKey.self)
        id = c.get("id", id); name = c.get("name", name); shared = c.get("shared", shared); isRemote = c.get("isRemote", isRemote)
    }
}

struct Message: Identifiable, Codable, Hashable {
    var id = UUID()
    var channelID: UUID
    var date = Date()
    var speaker: String
    var text: String
    var translation: String?
    var typed = false
    var remote = false
    var keywordHit = false
}

// MARK: - Settings

struct AppSettings: Codable, Equatable {
    var fontSize = 22.0
    var filterOffensive = false
    var showTimestamps = true
    var preferredLanguage = "en"       // translation target
    var silenceCommit = 1.2            // seconds of silence that ends a message
    var triggerOnPartials = true       // fire keyword automations before the sentence is finished
    var alwaysOnTop = false
    var displayName = Host.current().localizedName ?? "CueComm"
    var midiOutputName = ""            // "" = all destinations
    var midiInputEnabled = true
    var oscListenEnabled = false
    var oscListenPort = 9000
    var networkEnabled = false         // open sharing of flagged channels with every Mac on the LAN
    var sessionName = ""               // non-empty = in a named session
    var oscDefaultHost = "127.0.0.1"
    var oscDefaultPort = 8000
    var historyLimit = 1000
    var flashOnRemoteCues = true       // flash this screen when a cue fires on another Mac in the session
    var listenPort = ManualAddress.defaultPort   // TCP port other Macs dial to reach this one
    var manualPeers: [String] = []     // "host:port" of Macs on other networks, dialled while a session is live
}

extension AppSettings {
    init(from d: Decoder) throws {
        self.init()
        let c = try d.container(keyedBy: AnyKey.self)
        fontSize = c.get("fontSize", fontSize); filterOffensive = c.get("filterOffensive", filterOffensive)
        showTimestamps = c.get("showTimestamps", showTimestamps); preferredLanguage = c.get("preferredLanguage", preferredLanguage)
        silenceCommit = c.get("silenceCommit", silenceCommit); triggerOnPartials = c.get("triggerOnPartials", triggerOnPartials)
        alwaysOnTop = c.get("alwaysOnTop", alwaysOnTop); displayName = c.get("displayName", displayName)
        midiOutputName = c.get("midiOutputName", midiOutputName); midiInputEnabled = c.get("midiInputEnabled", midiInputEnabled)
        oscListenEnabled = c.get("oscListenEnabled", oscListenEnabled); oscListenPort = c.get("oscListenPort", oscListenPort)
        networkEnabled = c.get("networkEnabled", networkEnabled); sessionName = c.get("sessionName", sessionName)
        oscDefaultHost = c.get("oscDefaultHost", oscDefaultHost); oscDefaultPort = c.get("oscDefaultPort", oscDefaultPort); historyLimit = c.get("historyLimit", historyLimit)
        flashOnRemoteCues = c.get("flashOnRemoteCues", flashOnRemoteCues)
        listenPort = c.get("listenPort", listenPort); manualPeers = c.get("manualPeers", manualPeers)
    }
}

// MARK: - Automations

enum MIDIKind: String, Codable, CaseIterable, Identifiable {
    case noteOn, noteOff, controlChange, programChange
    var id: String { rawValue }
    var label: String {
        switch self {
        case .noteOn: return "Note On"
        case .noteOff: return "Note Off"
        case .controlChange: return "Control Change"
        case .programChange: return "Program Change"
        }
    }
    var data1Label: String {
        switch self {
        case .noteOn, .noteOff: return "Note"
        case .controlChange: return "Controller"
        case .programChange: return "Program"
        }
    }
    var data2Label: String { self == .controlChange ? "Value" : "Velocity" }
}

enum TriggerKind: String, Codable, CaseIterable, Identifiable {
    case keyword, message, midi, osc
    var id: String { rawValue }
    var label: String {
        switch self {
        case .keyword: return "Keyword or phrase heard"
        case .message: return "Any message arrives"
        case .midi: return "MIDI message received"
        case .osc: return "OSC message received"
        }
    }
}

enum ActionKind: String, Codable, CaseIterable, Identifiable {
    case sendMIDI, sendOSC, flashWindow, showWindow, hideWindow, selectChannel
    case scrollTranscript, clearTranscript, markRead, adjustFontSize
    var id: String { rawValue }
    var label: String {
        switch self {
        case .sendMIDI: return "Send MIDI Command"
        case .sendOSC: return "Send OSC Message"
        case .flashWindow: return "Flash Window"
        case .showWindow: return "Show Window"
        case .hideWindow: return "Hide Window"
        case .selectChannel: return "Select Channel"
        case .scrollTranscript: return "Scroll Transcript"
        case .clearTranscript: return "Clear Transcript"
        case .markRead: return "Mark Transcript As Read"
        case .adjustFontSize: return "Adjust Font Size"
        }
    }
}

enum ScrollTarget: String, Codable, CaseIterable, Identifiable {
    case current, top
    var id: String { rawValue }
    var label: String { self == .current ? "Jump to current" : "Scroll to top" }
}

struct AutomationTrigger: Codable, Hashable {
    var kind: TriggerKind = .keyword
    var phrase = ""                 // comma separated alternatives
    var wholeWord = true
    var channelID: UUID?            // nil = any channel
    var midiKind: MIDIKind = .noteOn
    var midiChannel = 0             // 0 = any
    var data1 = -1                  // -1 = any
    var oscAddress = ""             // supports * wildcard
    var includeRemote = false       // also fire for speech heard on other Macs in the session
}

extension AutomationTrigger {
    init(from d: Decoder) throws {
        self.init()
        let c = try d.container(keyedBy: AnyKey.self)
        kind = c.get("kind", kind); phrase = c.get("phrase", phrase); wholeWord = c.get("wholeWord", wholeWord)
        channelID = c.get("channelID", channelID); midiKind = c.get("midiKind", midiKind)
        midiChannel = c.get("midiChannel", midiChannel); data1 = c.get("data1", data1); oscAddress = c.get("oscAddress", oscAddress)
        includeRemote = c.get("includeRemote", includeRemote)
    }
}

struct AutomationAction: Identifiable, Codable, Hashable {
    var id = UUID()
    var kind: ActionKind = .flashWindow
    var midiKind: MIDIKind = .noteOn
    var midiChannel = 1
    var data1 = 60
    var data2 = 127
    var oscHost = "127.0.0.1"
    var oscPort = 8000
    var oscAddress = "/cue"
    var oscArgs = ""                // {text} {channel} {keyword} are substituted
    var targetChannelID: UUID?      // nil = current selection
    var scrollTarget: ScrollTarget = .current
    var fontDelta = 2
    var delay = 0.0
    var flashColorHex = "FFE14D"
    var flashEveryone = true        // a flash also fires on every other Mac in the session
}

extension AutomationAction {
    init(from d: Decoder) throws {
        self.init()
        let c = try d.container(keyedBy: AnyKey.self)
        id = c.get("id", id); kind = c.get("kind", kind); midiKind = c.get("midiKind", midiKind)
        midiChannel = c.get("midiChannel", midiChannel); data1 = c.get("data1", data1); data2 = c.get("data2", data2)
        oscHost = c.get("oscHost", oscHost); oscPort = c.get("oscPort", oscPort); oscAddress = c.get("oscAddress", oscAddress)
        oscArgs = c.get("oscArgs", oscArgs); targetChannelID = c.get("targetChannelID", targetChannelID)
        flashColorHex = c.get("flashColorHex", flashColorHex); flashEveryone = c.get("flashEveryone", flashEveryone)
        scrollTarget = c.get("scrollTarget", scrollTarget); fontDelta = c.get("fontDelta", fontDelta); delay = c.get("delay", delay)
    }
}

struct Automation: Identifiable, Codable, Hashable {
    var id = UUID()
    var name = "New automation"
    var enabled = true
    var trigger = AutomationTrigger()
    var actions: [AutomationAction] = [AutomationAction()]
    var cooldown = 1.0              // seconds before it may fire again
    var isCue = false               // created from the Cue Words panel
}

extension Automation {
    init(from d: Decoder) throws {
        self.init()
        let c = try d.container(keyedBy: AnyKey.self)
        id = c.get("id", id); name = c.get("name", name); enabled = c.get("enabled", enabled)
        trigger = c.get("trigger", trigger); actions = c.get("actions", actions); cooldown = c.get("cooldown", cooldown)
        isCue = c.get("isCue", isCue)
    }
}

struct ConfigBundle: Codable {
    var version = 1
    var channels: [Channel]
    var groups: [ChannelGroup]
    var automations: [Automation]
    var settings: AppSettings
}

// MARK: - Misc

struct LanguageOption: Identifiable, Hashable {
    var id: String { code }
    var code: String
    var name: String
}

enum Languages {
    /// Languages Apple's Translation framework can target.
    static let translation: [LanguageOption] = ["en", "es", "fr", "de", "it", "pt", "nl", "pl", "ru", "uk", "tr", "ar", "hi", "id", "th", "vi", "ja", "ko", "zh-Hans", "zh-Hant"].map {
        LanguageOption(code: $0, name: Locale.current.localizedString(forIdentifier: $0) ?? $0)
    }
}

enum Offensive {
    static let words = ["fuck", "fucking", "shit", "bullshit", "bitch", "asshole", "bastard", "damn", "crap", "piss", "dick", "cunt"]
    static let regex = try? NSRegularExpression(pattern: "\\b(" + words.joined(separator: "|") + ")\\b", options: .caseInsensitive)

    static func mask(_ s: String) -> String {
        guard let regex else { return s }
        var out = s
        for m in regex.matches(in: s, range: NSRange(s.startIndex..., in: s)).reversed() {
            guard let r = Range(m.range, in: out) else { continue }
            out.replaceSubrange(r, with: String(repeating: "•", count: out[r].count))
        }
        return out
    }
}
