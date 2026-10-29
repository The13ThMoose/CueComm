import CoreAudio
import Foundation

struct AudioInputDevice: Identifiable, Hashable {
    var id: AudioDeviceID
    var uid: String
    var name: String
    var inputChannels: Int
    /// Names the interface reports for its inputs ("Analog 1", "Mic/Line 3"…), or "Input N".
    var channelNames: [String] = []

    func name(ofInput i: Int) -> String {
        i < channelNames.count && !channelNames[i].isEmpty ? channelNames[i] : "Input \(i + 1)"
    }
}

enum AudioDevices {
    private static let system = AudioObjectID(kAudioObjectSystemObject)

    static func inputs() -> [AudioInputDevice] {
        var addr = AudioObjectPropertyAddress(mSelector: kAudioHardwarePropertyDevices,
                                              mScope: kAudioObjectPropertyScopeGlobal,
                                              mElement: kAudioObjectPropertyElementMain)
        var size: UInt32 = 0
        guard AudioObjectGetPropertyDataSize(system, &addr, 0, nil, &size) == noErr else { return [] }
        var ids = [AudioDeviceID](repeating: 0, count: Int(size) / MemoryLayout<AudioDeviceID>.size)
        guard AudioObjectGetPropertyData(system, &addr, 0, nil, &size, &ids) == noErr else { return [] }
        return ids.compactMap { id in
            let channels = inputChannelCount(id)
            guard channels > 0, let uid = stringProperty(id, kAudioDevicePropertyDeviceUID) else { return nil }
            return AudioInputDevice(id: id, uid: uid, name: stringProperty(id, kAudioObjectPropertyName) ?? uid, inputChannels: channels,
                                    channelNames: (0..<channels).map { elementName(id, element: $0 + 1) ?? "" })
        }
    }

    static func defaultInputID() -> AudioDeviceID? {
        var addr = AudioObjectPropertyAddress(mSelector: kAudioHardwarePropertyDefaultInputDevice,
                                              mScope: kAudioObjectPropertyScopeGlobal,
                                              mElement: kAudioObjectPropertyElementMain)
        var id = AudioDeviceID(0)
        var size = UInt32(MemoryLayout<AudioDeviceID>.size)
        guard AudioObjectGetPropertyData(system, &addr, 0, nil, &size, &id) == noErr, id != 0 else { return nil }
        return id
    }

    private static func inputChannelCount(_ id: AudioDeviceID) -> Int {
        var addr = AudioObjectPropertyAddress(mSelector: kAudioDevicePropertyStreamConfiguration,
                                              mScope: kAudioObjectPropertyScopeInput,
                                              mElement: kAudioObjectPropertyElementMain)
        var size: UInt32 = 0
        guard AudioObjectGetPropertyDataSize(id, &addr, 0, nil, &size) == noErr, size > 0 else { return 0 }
        let raw = UnsafeMutableRawPointer.allocate(byteCount: Int(size), alignment: MemoryLayout<AudioBufferList>.alignment)
        defer { raw.deallocate() }
        guard AudioObjectGetPropertyData(id, &addr, 0, nil, &size, raw) == noErr else { return 0 }
        let list = UnsafeMutableAudioBufferListPointer(raw.assumingMemoryBound(to: AudioBufferList.self))
        return list.reduce(0) { $0 + Int($1.mNumberChannels) }
    }

    private static func elementName(_ id: AudioObjectID, element: Int) -> String? {
        var addr = AudioObjectPropertyAddress(mSelector: kAudioObjectPropertyElementName,
                                              mScope: kAudioObjectPropertyScopeInput,
                                              mElement: AudioObjectPropertyElement(element))
        var ref: Unmanaged<CFString>?
        var size = UInt32(MemoryLayout<Unmanaged<CFString>?>.size)
        guard AudioObjectGetPropertyData(id, &addr, 0, nil, &size, &ref) == noErr, let ref else { return nil }
        let s = (ref.takeRetainedValue() as String).trimmingCharacters(in: .whitespaces)
        // Many cards report just "1", "2"…; that carries no information beyond the number.
        return s.isEmpty || s == String(element) ? nil : s
    }

    private static func stringProperty(_ id: AudioObjectID, _ selector: AudioObjectPropertySelector) -> String? {
        var addr = AudioObjectPropertyAddress(mSelector: selector, mScope: kAudioObjectPropertyScopeGlobal, mElement: kAudioObjectPropertyElementMain)
        var ref: Unmanaged<CFString>?
        var size = UInt32(MemoryLayout<Unmanaged<CFString>?>.size)
        guard AudioObjectGetPropertyData(id, &addr, 0, nil, &size, &ref) == noErr, let ref else { return nil }
        return ref.takeRetainedValue() as String
    }

    /// Calls `handler` on the main queue whenever devices are added/removed or the default input changes.
    static func watch(_ handler: @escaping () -> Void) {
        for selector in [kAudioHardwarePropertyDevices, kAudioHardwarePropertyDefaultInputDevice] {
            var addr = AudioObjectPropertyAddress(mSelector: selector, mScope: kAudioObjectPropertyScopeGlobal, mElement: kAudioObjectPropertyElementMain)
            AudioObjectAddPropertyListenerBlock(system, &addr, DispatchQueue.main) { _, _ in handler() }
        }
    }
}
