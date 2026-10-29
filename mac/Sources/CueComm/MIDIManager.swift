import CoreMIDI
import Foundation

struct MIDIEvent: Equatable {
    var kind: MIDIKind
    var channel: Int        // 1...16
    var data1: Int
    var data2: Int
    var description: String {
        "\(kind.label) ch\(channel) \(kind.data1Label.lowercased()) \(data1)" + (kind == .programChange ? "" : " \(kind.data2Label.lowercased()) \(data2)")
    }
}

final class MIDIManager {
    private var client = MIDIClientRef()
    private var outPort = MIDIPortRef()
    private var inPort = MIDIPortRef()
    private var virtualSource = MIDIEndpointRef()
    private var virtualDest = MIDIEndpointRef()
    private var started = false

    var onEvent: ((MIDIEvent) -> Void)?
    var onSetupChanged: (() -> Void)?
    var outputName = ""             // "" = every destination
    var inputEnabled = true { didSet { connectSources() } }

    func start() {
        guard !started else { return }
        started = true
        MIDIClientCreateWithBlock("CueComm" as CFString, &client) { [weak self] _ in
            DispatchQueue.main.async {
                self?.connectSources()
                self?.onSetupChanged?()
            }
        }
        MIDIOutputPortCreate(client, "CueComm Out" as CFString, &outPort)
        MIDIInputPortCreateWithBlock(client, "CueComm In" as CFString, &inPort) { [weak self] list, _ in
            self?.receive(list)
        }
        MIDISourceCreate(client, "CueComm" as CFString, &virtualSource)
        MIDIDestinationCreateWithBlock(client, "CueComm" as CFString, &virtualDest) { [weak self] list, _ in
            self?.receive(list)
        }
        connectSources()
    }

    // MARK: Endpoints

    static func name(_ e: MIDIEndpointRef) -> String {
        var ref: Unmanaged<CFString>?
        MIDIObjectGetStringProperty(e, kMIDIPropertyDisplayName, &ref)
        return ref?.takeRetainedValue() as String? ?? "Unknown"
    }

    var destinationNames: [String] {
        (0..<MIDIGetNumberOfDestinations()).map { MIDIGetDestination($0) }.filter { $0 != virtualDest }.map(Self.name)
    }

    var sourceNames: [String] {
        (0..<MIDIGetNumberOfSources()).map { MIDIGetSource($0) }.filter { $0 != virtualSource }.map(Self.name)
    }

    private func connectSources() {
        guard started else { return }
        for i in 0..<MIDIGetNumberOfSources() {
            let src = MIDIGetSource(i)
            guard src != virtualSource else { continue }    // never listen to ourselves
            if inputEnabled { MIDIPortConnectSource(inPort, src, nil) } else { MIDIPortDisconnectSource(inPort, src) }
        }
    }

    // MARK: Receive

    private func receive(_ list: UnsafePointer<MIDIPacketList>) {
        var events: [MIDIEvent] = []
        for packet in list.unsafeSequence() {
            let length = Int(packet.pointee.length)
            let bytes = withUnsafeBytes(of: packet.pointee.data) { Array($0.prefix(length)) }
            events += Self.parse(bytes)
        }
        guard !events.isEmpty else { return }
        DispatchQueue.main.async { [weak self] in events.forEach { self?.onEvent?($0) } }
    }

    static func parse(_ b: [UInt8]) -> [MIDIEvent] {
        var out: [MIDIEvent] = []
        var i = 0
        while i < b.count {
            let status = b[i]
            guard status >= 0x80 else { i += 1; continue }
            let ch = Int(status & 0x0F) + 1
            switch status & 0xF0 {
            case 0x80, 0x90, 0xB0:
                guard i + 2 < b.count else { return out }
                let d1 = Int(b[i + 1]), d2 = Int(b[i + 2])
                let kind: MIDIKind = status & 0xF0 == 0xB0 ? .controlChange : (status & 0xF0 == 0x80 || d2 == 0 ? .noteOff : .noteOn)
                out.append(MIDIEvent(kind: kind, channel: ch, data1: d1, data2: d2))
                i += 3
            case 0xC0:
                guard i + 1 < b.count else { return out }
                out.append(MIDIEvent(kind: .programChange, channel: ch, data1: Int(b[i + 1]), data2: 0))
                i += 2
            case 0xA0, 0xE0: i += 3
            case 0xD0: i += 2
            default: i += 1
            }
        }
        return out
    }

    // MARK: Send

    func send(_ kind: MIDIKind, channel: Int, data1: Int, data2: Int) {
        let ch = UInt8(max(1, min(16, channel)) - 1)
        let d1 = UInt8(max(0, min(127, data1))), d2 = UInt8(max(0, min(127, data2)))
        switch kind {
        case .noteOn: send([0x90 | ch, d1, d2])
        case .noteOff: send([0x80 | ch, d1, d2])
        case .controlChange: send([0xB0 | ch, d1, d2])
        case .programChange: send([0xC0 | ch, d1])
        }
    }

    private func send(_ bytes: [UInt8]) {
        guard started else { return }
        var buffer = [UInt8](repeating: 0, count: 1024)
        buffer.withUnsafeMutableBytes { raw in
            let list = raw.baseAddress!.assumingMemoryBound(to: MIDIPacketList.self)
            let packet = MIDIPacketListInit(list)
            _ = MIDIPacketListAdd(list, 1024, packet, 0, bytes.count, bytes)
            MIDIReceived(virtualSource, list)                // apps connected to the "CueComm" virtual port
            for i in 0..<MIDIGetNumberOfDestinations() {
                let dest = MIDIGetDestination(i)
                guard dest != virtualDest else { continue }
                if outputName.isEmpty || Self.name(dest) == outputName { MIDISend(outPort, dest, list) }
            }
        }
    }
}
