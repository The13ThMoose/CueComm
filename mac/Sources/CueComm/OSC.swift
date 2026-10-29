import Foundation
import Network

enum OSCArg: Equatable {
    case int(Int32), float(Float), string(String)

    var text: String {
        switch self {
        case .int(let v): return String(v)
        case .float(let v): return String(v)
        case .string(let v): return v
        }
    }
}

struct OSCMessage {
    var address: String
    var args: [OSCArg] = []
}

enum OSCCodec {
    static func encode(_ m: OSCMessage) -> Data {
        var d = pad(m.address)
        var tags = ","
        for a in m.args {
            switch a {
            case .int: tags += "i"
            case .float: tags += "f"
            case .string: tags += "s"
            }
        }
        d.append(pad(tags))
        for a in m.args {
            switch a {
            case .int(let v): d.append(be(UInt32(bitPattern: v)))
            case .float(let v): d.append(be(v.bitPattern))
            case .string(let v): d.append(pad(v))
            }
        }
        return d
    }

    private static func pad(_ s: String) -> Data {
        var d = Data(s.utf8)
        d.append(0)
        while d.count % 4 != 0 { d.append(0) }
        return d
    }

    private static func be(_ v: UInt32) -> Data {
        withUnsafeBytes(of: v.bigEndian) { Data($0) }
    }

    /// Decodes a datagram (single message or bundle) into messages.
    static func decode(_ data: Data) -> [OSCMessage] {
        let bytes = [UInt8](data)
        return decode(bytes[...])
    }

    private static func decode(_ b: ArraySlice<UInt8>) -> [OSCMessage] {
        guard !b.isEmpty else { return [] }
        if b.starts(with: Array("#bundle".utf8)) {
            var out: [OSCMessage] = []
            var i = b.startIndex + 16                       // "#bundle\0" + 8-byte timetag
            while i + 4 <= b.endIndex {
                let len = Int(readU32(b, i)); i += 4
                guard len > 0, i + len <= b.endIndex else { break }
                out += decode(b[i..<(i + len)])
                i += len
            }
            return out
        }
        var i = b.startIndex
        guard let address = readString(b, &i), address.hasPrefix("/") else { return [] }
        var args: [OSCArg] = []
        if let tags = readString(b, &i), tags.hasPrefix(",") {
            for t in tags.dropFirst() {
                switch t {
                case "i": guard i + 4 <= b.endIndex else { return [OSCMessage(address: address, args: args)] }
                    args.append(.int(Int32(bitPattern: readU32(b, i)))); i += 4
                case "f": guard i + 4 <= b.endIndex else { return [OSCMessage(address: address, args: args)] }
                    args.append(.float(Float(bitPattern: readU32(b, i)))); i += 4
                case "s": if let s = readString(b, &i) { args.append(.string(s)) }
                case "T": args.append(.int(1))
                case "F": args.append(.int(0))
                default: break
                }
            }
        }
        return [OSCMessage(address: address, args: args)]
    }

    private static func readU32(_ b: ArraySlice<UInt8>, _ i: Int) -> UInt32 {
        (UInt32(b[i]) << 24) | (UInt32(b[i + 1]) << 16) | (UInt32(b[i + 2]) << 8) | UInt32(b[i + 3])
    }

    private static func readString(_ b: ArraySlice<UInt8>, _ i: inout Int) -> String? {
        guard i < b.endIndex, let end = b[i...].firstIndex(of: 0) else { return nil }
        let s = String(decoding: b[i..<end], as: UTF8.self)
        i = min(b.endIndex, ((end - b.startIndex) / 4 + 1) * 4 + b.startIndex)
        return s
    }

    /// "1 0.5 \"hello world\"" -> [.int(1), .float(0.5), .string("hello world")]
    static func parseArgs(_ s: String) -> [OSCArg] {
        var tokens: [(String, Bool)] = []
        var cur = "", quoted = false, wasQuoted = false
        for ch in s {
            if ch == "\"" { quoted.toggle(); wasQuoted = true; continue }
            if ch == " " && !quoted {
                if !cur.isEmpty || wasQuoted { tokens.append((cur, wasQuoted)) }
                cur = ""; wasQuoted = false
            } else { cur.append(ch) }
        }
        if !cur.isEmpty || wasQuoted { tokens.append((cur, wasQuoted)) }
        return tokens.map { tok, wasQuoted in
            if wasQuoted { return .string(tok) }
            if let i = Int32(tok) { return .int(i) }
            if let f = Float(tok) { return .float(f) }
            return .string(tok)
        }
    }

    /// Address pattern match with `*` wildcards.
    static func matches(address: String, pattern: String) -> Bool {
        let p = pattern.trimmingCharacters(in: .whitespaces)
        guard !p.isEmpty else { return false }
        if !p.contains("*") { return address == p }
        let escaped = NSRegularExpression.escapedPattern(for: p).replacingOccurrences(of: "\\*", with: ".*")
        return address.range(of: "^" + escaped + "$", options: .regularExpression) != nil
    }
}

final class OSCService {
    private var connections: [String: NWConnection] = [:]
    private var listener: NWListener?
    var onMessage: ((OSCMessage, String) -> Void)?
    var onError: ((String) -> Void)?

    func send(_ message: OSCMessage, host: String, port: Int) {
        guard let nwPort = NWEndpoint.Port(rawValue: UInt16(clamping: port)), !host.isEmpty else { return }
        let key = "\(host):\(port)"
        let conn: NWConnection
        if let existing = connections[key] {
            conn = existing
        } else {
            conn = NWConnection(host: NWEndpoint.Host(host), port: nwPort, using: .udp)
            conn.stateUpdateHandler = { [weak self, weak conn] state in
                switch state {
                case .failed, .cancelled: if let conn { self?.drop(key, conn) }
                default: break
                }
            }
            conn.start(queue: .main)
            connections[key] = conn
        }
        conn.send(content: OSCCodec.encode(message), completion: .contentProcessed { [weak self] error in
            if let error { DispatchQueue.main.async { self?.onError?("OSC send failed: \(error.localizedDescription)") } }
        })
    }

    private func drop(_ key: String, _ conn: NWConnection) {
        if connections[key] === conn { connections[key] = nil }
    }

    func listen(port: Int) {
        stopListening()
        guard let nwPort = NWEndpoint.Port(rawValue: UInt16(clamping: port)) else { return }
        do {
            let l = try NWListener(using: .udp, on: nwPort)
            l.newConnectionHandler = { [weak self] conn in
                conn.start(queue: .main)
                self?.receive(conn)
            }
            l.stateUpdateHandler = { [weak self] state in
                if case .failed(let e) = state { self?.onError?("OSC listener failed: \(e.localizedDescription)") }
            }
            l.start(queue: .main)
            listener = l
        } catch {
            onError?("Cannot listen for OSC on port \(port): \(error.localizedDescription)")
        }
    }

    func stopListening() {
        listener?.cancel()
        listener = nil
    }

    private func receive(_ conn: NWConnection) {
        conn.receiveMessage { [weak self] data, _, _, error in
            if let data, !data.isEmpty {
                var from = "?"
                if case let .hostPort(host, _) = conn.endpoint { from = "\(host)" }
                for m in OSCCodec.decode(data) { self?.onMessage?(m, from) }
            }
            if error == nil { self?.receive(conn) } else { conn.cancel() }
        }
    }
}
