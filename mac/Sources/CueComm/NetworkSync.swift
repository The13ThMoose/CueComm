import Foundation
import Network

struct SharedChannel: Codable {
    var id: UUID
    var name: String
    var colorHex: String
    var groupID: UUID?
    var groupName: String?
    var localeID: String
}

struct SharedMessage: Codable {
    var id: UUID
    var channelID: UUID
    var speaker: String
    var text: String
    var date: Date
    var typed: Bool
}

/// A cue that fired on one Mac and should be shown on the others.
struct SharedCue: Codable {
    var title: String
    var channel: String
    var colorHex: String
}

/// Newline-delimited JSON frames exchanged between CueComm machines.
struct NetFrame: Codable {
    var type: String                    // hello | welcome | channels | message | history | removed | cue
    var peerID: String?
    var peerName: String?
    var session: String?
    var channels: [SharedChannel]?
    var message: SharedMessage?
    var messages: [SharedMessage]?
    var removedID: UUID?
    var cue: SharedCue?
    var join: Bool?                     // hello only: "I am joining by address, tell me your session"
}

/// Where a manually entered address stands.
enum ManualLinkState: Equatable {
    case connecting
    case connected(String)      // the other Mac's name
    case failed(String)         // why, in words the crew can act on
}

/// Progress of "join by address" in the Session sheet.
enum AddressJoin: Equatable {
    case idle
    case joining(String)        // the address being asked
    case failed(String)         // why, in words the crew can act on
}

/// Parsing for addresses typed by hand: "host", "host:port" or "[ipv6]:port".
enum ManualAddress {
    static let defaultPort = 47800

    static func parse(_ text: String) -> (host: String, port: UInt16)? {
        let t = text.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !t.isEmpty, !t.contains(" ") else { return nil }
        var host = t, portText: Substring?
        if t.hasPrefix("[") {
            guard let close = t.firstIndex(of: "]") else { return nil }
            host = String(t[t.index(after: t.startIndex)..<close])
            let rest = t[t.index(after: close)...]
            if !rest.isEmpty { guard rest.hasPrefix(":") else { return nil }; portText = rest.dropFirst() }
        } else if t.filter({ $0 == ":" }).count == 1, let colon = t.firstIndex(of: ":") {
            host = String(t[..<colon]); portText = t[t.index(after: colon)...]
        }
        guard !host.isEmpty else { return nil }
        var port = UInt16(defaultPort)
        if let portText { guard let p = UInt16(portText), p > 0 else { return nil }; port = p }
        return (host, port)
    }

    /// The stored form, always with a port.
    static func normalised(_ text: String) -> String? {
        guard let a = parse(text) else { return nil }
        return a.host.contains(":") ? "[\(a.host)]:\(a.port)" : "\(a.host):\(a.port)"
    }
}

/// This Mac's addresses that another Mac could dial: the local network and any VPN (Tailscale, WireGuard…).
enum LocalAddress {
    struct Entry: Identifiable, Hashable { var id: String { kind + ip }; let kind: String; let ip: String }

    static func ipv4() -> [Entry] {
        var head: UnsafeMutablePointer<ifaddrs>?
        guard getifaddrs(&head) == 0, let first = head else { return [] }
        defer { freeifaddrs(head) }
        var out: [Entry] = []
        for p in sequence(first: first, next: { $0.pointee.ifa_next }) {
            let f = p.pointee
            guard let sa = f.ifa_addr, sa.pointee.sa_family == UInt8(AF_INET),
                  f.ifa_flags & UInt32(IFF_UP) != 0, f.ifa_flags & UInt32(IFF_LOOPBACK) == 0 else { continue }
            let name = String(cString: f.ifa_name)
            guard name.hasPrefix("en") || name.hasPrefix("utun") else { continue }
            var buf = [CChar](repeating: 0, count: Int(NI_MAXHOST))
            guard getnameinfo(sa, socklen_t(sa.pointee.sa_len), &buf, socklen_t(buf.count), nil, 0, NI_NUMERICHOST) == 0 else { continue }
            let ip = String(cString: buf)
            guard !ip.hasPrefix("169.254.") else { continue }
            let e = Entry(kind: name.hasPrefix("utun") ? "VPN" : "Local network", ip: ip)
            if !out.contains(e) { out.append(e) }
        }
        return out
    }
}

struct DiscoveredSession: Identifiable, Hashable {
    var id: String { name }
    var name: String
    var members: [String]               // display names of the Macs advertising it
}

/// Mesh of CueComm machines over TCP. Machines on the same network find each other with Bonjour;
/// machines on other networks are dialled by address. Peers connect only when they are in the same
/// session (an empty name is "open sharing"), and every pair keeps exactly one connection: the one
/// dialled by the machine with the smaller instance ID.
///
/// A listener never speaks first. The dialling side sends its hello, and we answer only after its
/// session name matches ours, so a port scan of an exposed port learns nothing.
final class NetworkSync {
    private final class Link {
        let conn: NWConnection
        let outbound: Bool
        let manualKey: String?
        var buffer = Data()
        var peerID: String?
        var peerName = "Unknown"
        var ready = false
        var sentHello = false
        init(_ c: NWConnection, outbound: Bool, manualKey: String?) { conn = c; self.outbound = outbound; self.manualKey = manualKey }
    }

    static let serviceType = "_cuecomm._tcp"
    private static let maxBuffer = 8 << 20
    private var listener: NWListener?
    private var browser: NWBrowser?
    private var retryTimer: Timer?
    private var links: [ObjectIdentifier: Link] = [:]
    private var dialing = Set<String>()
    private var manualState: [String: ManualLinkState] = [:]
    private var manualPeerID: [String: String] = [:]
    private var instanceID = ""
    private var displayName = ""
    private var session = ""
    private var port = ManualAddress.defaultPort
    private var advertising = false
    private let encoder: JSONEncoder = { let e = JSONEncoder(); e.dateEncodingStrategy = .iso8601; return e }()
    private let decoder: JSONDecoder = { let d = JSONDecoder(); d.dateDecodingStrategy = .iso8601; return d }()

    var helloChannels: (() -> [SharedChannel])?
    var helloHistory: (() -> [SharedMessage])?
    var onFrame: ((NetFrame, String) -> Void)?
    var onPeersChanged: (([String]) -> Void)?
    var onSessions: (([DiscoveredSession]) -> Void)?
    var onManualStatus: (([String: ManualLinkState]) -> Void)?
    var onListenPort: ((Int?) -> Void)?
    var onError: ((String) -> Void)?

    /// Addresses ("host:port") to dial while a session is live. Retried every few seconds until connected.
    var manualPeers: [String] = [] {
        didSet { if oldValue != manualPeers { syncManual() } }
    }

    var isRunning: Bool { browser != nil }

    /// Keep-alive so a dead internet path is noticed within about a minute; no-delay because cues are small and urgent.
    static func parameters() -> NWParameters {
        let tcp = NWProtocolTCP.Options()
        tcp.noDelay = true
        tcp.enableKeepalive = true
        tcp.keepaliveIdle = 20
        tcp.keepaliveInterval = 10
        tcp.keepaliveCount = 3
        tcp.connectionTimeout = 8
        return NWParameters(tls: nil, tcp: tcp)
    }

    /// `advertise: false` only browses, so the Session sheet can list sessions before you join one.
    func start(instanceID: String, name: String, session: String, advertise: Bool, port: Int = ManualAddress.defaultPort) {
        if isRunning, self.instanceID == instanceID, self.session == session, advertising == advertise, self.port == port {
            if displayName != name { updateName(name) }
            return
        }
        stop()
        self.instanceID = instanceID
        self.displayName = name
        self.session = session
        self.advertising = advertise
        self.port = port

        if advertise { startListener(fixedPort: true) }
        let b = NWBrowser(for: .bonjourWithTXTRecord(type: Self.serviceType, domain: nil), using: .tcp)
        b.browseResultsChangedHandler = { [weak self] results, _ in self?.found(results) }
        b.start(queue: .main)
        browser = b

        if advertise {
            retryTimer = Timer.scheduledTimer(withTimeInterval: 5, repeats: true) { [weak self] _ in self?.syncManual() }
            syncManual()
        }
    }

    /// Listens on the chosen port so other Macs can dial in. If that port is taken, falls back to any free
    /// port: Bonjour still works, but dialling by address needs the port shown in the Session sheet.
    private func startListener(fixedPort: Bool) {
        do {
            let l: NWListener
            if fixedPort, let p = NWEndpoint.Port(rawValue: UInt16(clamping: port)) {
                l = try NWListener(using: Self.parameters(), on: p)
            } else {
                l = try NWListener(using: Self.parameters())
            }
            l.service = NWListener.Service(name: instanceID, type: Self.serviceType,
                                           txtRecord: NWTXTRecord(["session": session, "name": displayName]))
            l.newConnectionHandler = { [weak self] conn in self?.adopt(conn, outbound: false) }
            l.stateUpdateHandler = { [weak self, weak l] state in
                guard let self, let l, l === self.listener else { return }
                switch state {
                case .ready:
                    self.onListenPort?(l.port.map { Int($0.rawValue) })
                case .failed(let e):
                    l.cancel()
                    self.listener = nil
                    if fixedPort {
                        self.onError?("Port \(self.port) is in use, so other Macs can only reach this one automatically. Pick another port in the Session sheet. (\(e.localizedDescription))")
                        self.startListener(fixedPort: false)
                    } else {
                        self.onListenPort?(nil)
                        self.onError?("Network listener failed: \(e.localizedDescription)")
                    }
                default: break
                }
            }
            l.start(queue: .main)
            listener = l
        } catch {
            onError?("Cannot start network sharing: \(error.localizedDescription)")
        }
    }

    func stop() {
        retryTimer?.invalidate(); retryTimer = nil
        browser?.cancel(); browser = nil
        listener?.cancel(); listener = nil
        links.values.forEach { $0.conn.cancel() }
        links = [:]
        dialing = []
        manualState = [:]
        manualPeerID = [:]
        advertising = false
        onPeersChanged?([])
        onSessions?([])
        onManualStatus?([:])
        onListenPort?(nil)
    }

    func updateName(_ name: String) {
        displayName = name
        // The TXT record is refreshed on the next restart; peers learn the new name from a fresh hello.
        broadcast(hello())
    }

    // MARK: Discover & connect

    private func found(_ results: Set<NWBrowser.Result>) {
        // CUECOMM_NO_DISCOVERY lets a test prove that dialling by address works without Bonjour.
        guard ProcessInfo.processInfo.environment["CUECOMM_NO_DISCOVERY"] == nil else { return }
        var bySession: [String: [String]] = [:]
        for r in results {
            guard case let .service(name, _, _, _) = r.endpoint else { continue }
            var peerSession = "", peerDisplay = name
            if case let .bonjour(txt) = r.metadata {
                peerSession = txt["session"] ?? ""
                peerDisplay = txt["name"] ?? name
            }
            if !peerSession.isEmpty { bySession[peerSession, default: []].append(name == instanceID ? displayName : peerDisplay) }

            guard advertising, name != instanceID, peerSession == session else { continue }
            // Only the smaller ID dials, so each pair has exactly one connection.
            guard instanceID < name, !dialing.contains(name), !links.values.contains(where: { $0.peerID == name }) else { continue }
            dialing.insert(name)
            adopt(NWConnection(to: r.endpoint, using: Self.parameters()), outbound: true, dialedName: name)
        }
        onSessions?(bySession.map { DiscoveredSession(name: $0.key, members: $0.value.sorted()) }.sorted { $0.name < $1.name })
    }

    // MARK: Manual addresses

    private func publishManual() { onManualStatus?(manualState) }

    /// Dial every listed address that has no live or pending connection; forget and drop the ones removed.
    private func syncManual() {
        guard advertising, isRunning else { return }
        var keys: [String] = []
        for m in manualPeers { if let k = ManualAddress.normalised(m), !keys.contains(k) { keys.append(k) } }
        for k in Array(manualState.keys) where !keys.contains(k) { manualState[k] = nil; manualPeerID[k] = nil }
        for l in links.values { if let k = l.manualKey, !keys.contains(k) { l.conn.cancel() } }

        for k in keys {
            if let pid = manualPeerID[k], let l = links.values.first(where: { $0.peerID == pid && $0.ready }) {
                manualState[k] = .connected(l.peerName)
                continue
            }
            manualPeerID[k] = nil
            if links.values.contains(where: { $0.manualKey == k }) { continue }
            guard let a = ManualAddress.parse(k), let p = NWEndpoint.Port(rawValue: a.port) else { continue }
            if manualState[k] == nil { manualState[k] = .connecting }
            adopt(NWConnection(host: NWEndpoint.Host(a.host), port: p, using: Self.parameters()), outbound: true, manualKey: k)
        }
        publishManual()
    }

    static func describe(_ e: NWError) -> String {
        switch e {
        case .posix(let code):
            switch code {
            case .ECONNREFUSED: return "Refused. Is CueComm running there, and is this the right port?"
            case .ETIMEDOUT: return "No answer. Check the address, the firewall and port forwarding."
            case .EHOSTUNREACH, .ENETUNREACH, .EHOSTDOWN: return "Cannot reach that address from here."
            default: break
            }
        case .dns: return "That address was not found."
        default: break
        }
        return e.localizedDescription
    }

    private func adopt(_ conn: NWConnection, outbound: Bool, dialedName: String? = nil, manualKey: String? = nil) {
        let link = Link(conn, outbound: outbound, manualKey: manualKey)
        links[ObjectIdentifier(conn)] = link
        conn.stateUpdateHandler = { [weak self, weak link] state in
            guard let self, let link else { return }
            switch state {
            case .ready:
                link.ready = true
                // The dialling side speaks first; a listener waits for a hello with a matching session.
                if link.outbound { self.sendHello(link) }
                self.receive(link)
                if !link.outbound {
                    DispatchQueue.main.asyncAfter(deadline: .now() + 10) { [weak link] in
                        if let link, link.peerID == nil { link.conn.cancel() }
                    }
                }
            case .waiting(let e):
                if let k = link.manualKey, self.manualPeerID[k] == nil { self.manualState[k] = .failed(Self.describe(e)); self.publishManual() }
            case .failed(let e):
                self.drop(link, dialedName: dialedName, reason: Self.describe(e))
            case .cancelled:
                self.drop(link, dialedName: dialedName, reason: nil)
            default: break
            }
        }
        conn.start(queue: .main)
    }

    private func drop(_ link: Link, dialedName: String?, reason: String?) {
        if let n = dialedName { dialing.remove(n) }
        links[ObjectIdentifier(link.conn)] = nil
        if let k = link.manualKey {
            let stillConnected = manualPeerID[k].map { pid in links.values.contains { $0.peerID == pid } } ?? false
            if !stillConnected {
                manualPeerID[k] = nil
                if manualState[k] != nil {
                    manualState[k] = .failed(reason ?? (link.peerID == nil
                        ? "The other Mac closed the connection. Check that it is in the same session."
                        : "Connection lost. Trying again."))
                }
            }
            publishManual()
        }
        publishPeers()
    }

    private func hello() -> NetFrame {
        NetFrame(type: "hello", peerID: instanceID, peerName: displayName, session: session, channels: helloChannels?() ?? [])
    }

    private func sendHello(_ link: Link) {
        link.sentHello = true
        send(hello(), to: link)
    }

    // MARK: Send / receive

    func broadcast(_ frame: NetFrame) {
        for link in links.values where link.ready && link.peerID != nil { send(frame, to: link) }
    }

    private func send(_ frame: NetFrame, to link: Link) {
        guard var data = try? encoder.encode(frame) else { return }
        data.append(0x0A)
        link.conn.send(content: data, completion: .contentProcessed { _ in })
    }

    private func receive(_ link: Link) {
        link.conn.receive(minimumIncompleteLength: 1, maximumLength: 1 << 16) { [weak self, weak link] data, _, done, error in
            guard let self, let link else { return }
            if let data, !data.isEmpty {
                link.buffer.append(data)
                if link.buffer.count > Self.maxBuffer { link.conn.cancel(); return }
                while let nl = link.buffer.firstIndex(of: 0x0A) {
                    let line = link.buffer[link.buffer.startIndex..<nl]
                    link.buffer = Data(link.buffer[(nl + 1)...])
                    if let frame = try? self.decoder.decode(NetFrame.self, from: line) { self.handle(frame, link) }
                }
            }
            if error != nil || done { link.conn.cancel() } else { self.receive(link) }
        }
    }

    private func handle(_ frame: NetFrame, _ link: Link) {
        if frame.type == "hello", frame.join == true {
            // Someone is joining by address and cannot know our session name yet. Tell them, then let go;
            // they reconnect as an ordinary member. Only a listener that is hosting answers.
            guard !link.outbound, advertising else { link.conn.cancel(); return }
            var w = NetFrame(type: "welcome", peerID: instanceID, peerName: displayName, session: session)
            w.join = true
            guard var data = try? encoder.encode(w) else { link.conn.cancel(); return }
            data.append(0x0A)
            link.conn.send(content: data, completion: .contentProcessed { [weak link] _ in link?.conn.cancel() })
            return
        }
        if frame.type == "hello" {
            guard let id = frame.peerID else { link.conn.cancel(); return }
            if id == instanceID {                                   // dialled our own address
                if let k = link.manualKey { manualState[k] = .failed("That address is this Mac."); publishManual() }
                link.conn.cancel(); return
            }
            guard (frame.session ?? "") == session else { link.conn.cancel(); return }

            if let k = link.manualKey { manualPeerID[k] = id }
            // Two connections to one peer (both sides dialled): keep the one dialled by the smaller ID, on both ends.
            if let existing = links.values.first(where: { $0 !== link && $0.peerID == id }) {
                let mine = link.outbound ? instanceID : id
                let theirs = existing.outbound ? instanceID : id
                if mine < theirs { existing.peerID = nil; existing.conn.cancel() } else {
                    if let k = link.manualKey { manualState[k] = .connected(existing.peerName); publishManual() }
                    link.conn.cancel(); return
                }
            }
            let first = link.peerID == nil
            link.peerID = id
            link.peerName = frame.peerName ?? "Peer"
            if !link.sentHello { sendHello(link) }
            if let k = link.manualKey { manualState[k] = .connected(link.peerName); publishManual() }
            publishPeers()
            // New arrivals catch up on what was said so far.
            if first, let history = helloHistory?(), !history.isEmpty {
                send(NetFrame(type: "history", messages: history), to: link)
            }
        }
        guard link.peerID != nil else { return }
        onFrame?(frame, link.peerName)
    }

    private func publishPeers() {
        onPeersChanged?(links.values.filter { $0.peerID != nil }.map(\.peerName).sorted())
    }
}

/// One question to a listening CueComm: "which session are you in?" Joining by address needs it because a
/// listener stays silent until the caller already knows the session name.
final class AddressProbe {
    struct Answer { let peerID: String; let name: String; let session: String }
    struct Failure: Error { let message: String }

    private let conn: NWConnection
    private var request = Data()
    private var buffer = Data()
    private var finished = false
    private let completion: (Result<Answer, Failure>) -> Void
    private let decoder: JSONDecoder = { let d = JSONDecoder(); d.dateDecodingStrategy = .iso8601; return d }()

    init?(host: String, port: UInt16, ownID: String, ownName: String, completion: @escaping (Result<Answer, Failure>) -> Void) {
        guard let p = NWEndpoint.Port(rawValue: port) else { return nil }
        conn = NWConnection(host: NWEndpoint.Host(host), port: p, using: NetworkSync.parameters())
        self.completion = completion
        var hello = NetFrame(type: "hello", peerID: ownID, peerName: ownName, session: "")
        hello.join = true
        guard var data = try? JSONEncoder().encode(hello) else { return nil }
        data.append(0x0A)
        request = data
    }

    func start() {
        conn.stateUpdateHandler = { [weak self] state in
            guard let self else { return }
            switch state {
            case .ready:
                self.conn.send(content: self.request, completion: .contentProcessed { _ in })
                self.receive()
            case .waiting(let e), .failed(let e):
                self.finish(.failure(Failure(message: NetworkSync.describe(e))))
            case .cancelled:
                self.finish(.failure(Failure(message: "The connection was closed before the other Mac answered.")))
            default: break
            }
        }
        conn.start(queue: .main)
        DispatchQueue.main.asyncAfter(deadline: .now() + 8) { [weak self] in
            self?.finish(.failure(Failure(message: "No answer. Check the address, the firewall and port forwarding.")))
        }
    }

    func cancel() { finished = true; conn.stateUpdateHandler = nil; conn.cancel() }

    private func receive() {
        conn.receive(minimumIncompleteLength: 1, maximumLength: 1 << 16) { [weak self] data, _, done, error in
            guard let self, !self.finished else { return }
            if let data { self.buffer.append(data) }
            if self.buffer.count > 1 << 20 { self.finish(.failure(Failure(message: "That address did not answer like CueComm."))); return }
            while let nl = self.buffer.firstIndex(of: 0x0A) {
                let line = self.buffer[self.buffer.startIndex..<nl]
                self.buffer = Data(self.buffer[(nl + 1)...])
                if let f = try? self.decoder.decode(NetFrame.self, from: line), f.type == "welcome", let id = f.peerID {
                    self.finish(.success(Answer(peerID: id, name: f.peerName ?? "Mac", session: f.session ?? "")))
                    return
                }
            }
            if error != nil || done {
                self.finish(.failure(Failure(message: "The other Mac closed the connection without answering. Check that it runs the latest CueComm and is hosting a session.")))
            } else { self.receive() }
        }
    }

    private func finish(_ result: Result<Answer, Failure>) {
        guard !finished else { return }
        finished = true
        conn.stateUpdateHandler = nil
        conn.cancel()
        completion(result)
    }
}
