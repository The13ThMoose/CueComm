import AVFoundation
import CoreAudio
import Speech

/// One speech-recognition stream for one channel. Audio goes in via `append`; finished
/// "chat messages" come out when the speaker pauses.
final class SpeechPipe {
    let channelID: UUID
    private let recognizer: SFSpeechRecognizer?
    private let onDevice: Bool
    private let silence: TimeInterval
    private let lock = NSLock()
    private var request: SFSpeechAudioBufferRecognitionRequest?
    private var task: SFSpeechRecognitionTask?
    private var generation = 0
    private var running = false
    private var lastPartial = ""
    private var lastChange = Date()
    private var utterance = UUID()
    private var timer: DispatchSourceTimer?

    /// All callbacks arrive on the main queue.
    var onPartial: ((UUID, String, UUID) -> Void)?
    var onCommit: ((UUID, String, UUID) -> Void)?
    var onStatus: ((UUID, String) -> Void)?

    init(channelID: UUID, locale: Locale, onDeviceOnly: Bool, silence: TimeInterval) {
        self.channelID = channelID
        self.recognizer = SFSpeechRecognizer(locale: locale)
        self.onDevice = onDeviceOnly
        self.silence = max(0.4, silence)
    }

    func start() {
        lock.lock(); defer { lock.unlock() }
        guard !running else { return }
        running = true
        beginTaskLocked()
        let t = DispatchSource.makeTimerSource(queue: .global(qos: .userInitiated))
        t.schedule(deadline: .now() + 0.25, repeating: 0.25)
        t.setEventHandler { [weak self] in self?.tick() }
        t.resume()
        timer = t
    }

    func stop() {
        lock.lock()
        running = false
        timer?.cancel(); timer = nil
        let text = lastPartial, utt = utterance
        lastPartial = ""
        request?.endAudio(); task?.cancel()
        request = nil; task = nil; generation += 1
        lock.unlock()
        if !text.isEmpty { DispatchQueue.main.async { self.onCommit?(self.channelID, text, utt) } }
    }

    func append(_ buffer: AVAudioPCMBuffer) {
        lock.lock(); let r = request; lock.unlock()
        r?.append(buffer)
    }

    // MARK: internals

    private func beginTaskLocked() {
        guard running else { return }
        guard let recognizer, recognizer.isAvailable else {
            report("Speech recognizer unavailable for this language")
            return
        }
        generation += 1
        let gen = generation
        let req = SFSpeechAudioBufferRecognitionRequest()
        req.shouldReportPartialResults = true
        req.addsPunctuation = true
        req.taskHint = .dictation
        if onDevice && recognizer.supportsOnDeviceRecognition { req.requiresOnDeviceRecognition = true }
        request = req
        task = recognizer.recognitionTask(with: req) { [weak self] result, error in
            self?.handle(gen, result, error)
        }
    }

    private func handle(_ gen: Int, _ result: SFSpeechRecognitionResult?, _ error: Error?) {
        lock.lock()
        guard gen == generation, running else { lock.unlock(); return }
        var partial: (String, UUID)?
        var restartDelay: TimeInterval?
        if let result {
            let text = result.bestTranscription.formattedString.trimmingCharacters(in: .whitespacesAndNewlines)
            if !text.isEmpty && text != lastPartial {
                lastPartial = text; lastChange = Date()
                partial = (text, utterance)
            }
            if result.isFinal { restartDelay = 0.05 }
        }
        if error != nil { restartDelay = 0.6 }          // e.g. "no speech detected" or service timeout
        lock.unlock()

        if let (t, u) = partial { DispatchQueue.main.async { self.onPartial?(self.channelID, t, u) } }
        if let d = restartDelay {
            let g = gen
            DispatchQueue.global().asyncAfter(deadline: .now() + d) { [weak self] in self?.cycle(ifGeneration: g) }
        }
    }

    private func tick() {
        lock.lock()
        let due = !lastPartial.isEmpty && Date().timeIntervalSince(lastChange) > silence
        let gen = generation
        lock.unlock()
        if due { cycle(ifGeneration: gen) }
    }

    /// Finish the current utterance (emit it as a message) and open a fresh recognition request.
    private func cycle(ifGeneration gen: Int) {
        lock.lock()
        guard running, gen == generation else { lock.unlock(); return }
        let text = lastPartial, utt = utterance
        lastPartial = ""
        utterance = UUID()
        request?.endAudio(); task?.cancel()
        request = nil; task = nil
        beginTaskLocked()
        lock.unlock()
        if !text.isEmpty { DispatchQueue.main.async { self.onCommit?(self.channelID, text, utt) } }
    }

    private func report(_ s: String) {
        DispatchQueue.main.async { self.onStatus?(self.channelID, s) }
    }
}

/// Owns one AVAudioEngine on one hardware device and fans its channels out to speech pipes.
final class DeviceCapture {
    let deviceID: AudioDeviceID
    private let engine = AVAudioEngine()
    private let lock = NSLock()
    private var pipes: [UUID: (pipe: SpeechPipe, channel: Int)] = [:]
    private var lastLevelEmit = Date.distantPast
    private var observer: NSObjectProtocol?
    private var running = false
    var onLevels: (([UUID: Float]) -> Void)?
    var onError: ((String) -> Void)?

    init(deviceID: AudioDeviceID) { self.deviceID = deviceID }

    var isEmpty: Bool { lock.lock(); defer { lock.unlock() }; return pipes.isEmpty }

    func set(pipe: SpeechPipe, channel: Int) { lock.lock(); pipes[pipe.channelID] = (pipe, channel); lock.unlock() }
    func removePipe(_ id: UUID) -> SpeechPipe? { lock.lock(); defer { lock.unlock() }; return pipes.removeValue(forKey: id)?.pipe }

    func start() {
        guard !running else { return }
        do {
            try configureDevice()
            let input = engine.inputNode
            let format = input.outputFormat(forBus: 0)
            guard format.sampleRate > 0, format.channelCount > 0 else {
                onError?("Input device reports no valid format")
                return
            }
            input.removeTap(onBus: 0)
            input.installTap(onBus: 0, bufferSize: 2048, format: format) { [weak self] buffer, _ in
                self?.process(buffer)
            }
            engine.prepare()
            try engine.start()
            running = true
            observer = NotificationCenter.default.addObserver(forName: .AVAudioEngineConfigurationChange, object: engine, queue: .main) { [weak self] _ in
                self?.restart()
            }
        } catch {
            onError?("Audio start failed: \(error.localizedDescription)")
        }
    }

    func stop() {
        if let observer { NotificationCenter.default.removeObserver(observer) }
        observer = nil
        if running { engine.inputNode.removeTap(onBus: 0); engine.stop() }
        running = false
        lock.lock(); let all = pipes.values.map(\.pipe); pipes = [:]; lock.unlock()
        all.forEach { $0.stop() }
    }

    private func restart() {
        guard running else { return }
        engine.inputNode.removeTap(onBus: 0)
        engine.stop()
        running = false
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.5) { [weak self] in self?.start() }
    }

    private func configureDevice() throws {
        guard let unit = engine.inputNode.audioUnit else { return }
        var dev = deviceID
        let status = AudioUnitSetProperty(unit, kAudioOutputUnitProperty_CurrentDevice, kAudioUnitScope_Global, 0,
                                          &dev, UInt32(MemoryLayout<AudioDeviceID>.size))
        if status != noErr { throw NSError(domain: NSOSStatusErrorDomain, code: Int(status)) }
    }

    private func process(_ buffer: AVAudioPCMBuffer) {
        guard let data = buffer.floatChannelData else { return }
        let frames = Int(buffer.frameLength)
        let count = Int(buffer.format.channelCount)
        guard frames > 0 else { return }
        lock.lock(); let snapshot = pipes; lock.unlock()
        guard let mono = AVAudioFormat(commonFormat: .pcmFormatFloat32, sampleRate: buffer.format.sampleRate, channels: 1, interleaved: false) else { return }
        let interleaved = buffer.format.isInterleaved
        var levels: [UUID: Float] = [:]

        for (id, entry) in snapshot {
            let ch = min(entry.channel, count - 1)
            guard let out = AVAudioPCMBuffer(pcmFormat: mono, frameCapacity: AVAudioFrameCount(frames)),
                  let dst = out.floatChannelData?[0] else { continue }
            out.frameLength = AVAudioFrameCount(frames)
            var sum: Float = 0
            if interleaved {
                let src = data[0]
                for i in 0..<frames { let v = src[i * count + ch]; dst[i] = v; sum += v * v }
            } else {
                let src = data[ch]
                for i in 0..<frames { let v = src[i]; dst[i] = v; sum += v * v }
            }
            let rms = (sum / Float(frames)).squareRoot()
            levels[id] = max(0, min(1, (20 * log10(max(rms, 1e-7)) + 60) / 60))
            entry.pipe.append(out)
        }

        let now = Date()
        if now.timeIntervalSince(lastLevelEmit) > 0.1 {
            lastLevelEmit = now
            DispatchQueue.main.async { [weak self] in self?.onLevels?(levels) }
        }
    }
}

/// Reconciles the desired set of listening channels with running device captures and speech pipes.
final class CaptureManager {
    private var captures: [AudioDeviceID: DeviceCapture] = [:]
    private var pipes: [UUID: (pipe: SpeechPipe, signature: String, device: AudioDeviceID)] = [:]

    var onPartial: ((UUID, String, UUID) -> Void)?
    var onCommit: ((UUID, String, UUID) -> Void)?
    var onStatus: ((UUID, String) -> Void)?
    var onLevels: (([UUID: Float]) -> Void)?

    func sync(listening: Bool, channels: [Channel], devices: [AudioInputDevice], silence: TimeInterval) {
        let wanted = listening ? channels.filter { $0.enabled && !$0.isRemote } : []
        var wantedIDs = Set<UUID>()

        for ch in wanted {
            let device: AudioInputDevice?
            if let uid = ch.deviceUID {
                device = devices.first { $0.uid == uid }
            } else if let def = AudioDevices.defaultInputID() {
                device = devices.first { $0.id == def }
            } else { device = devices.first }
            guard let device else {
                removePipe(ch.id)
                onStatus?(ch.id, "Input device not connected")
                continue
            }
            wantedIDs.insert(ch.id)
            let channel = min(ch.inputChannel, device.inputChannels - 1)
            let signature = "\(device.id)|\(channel)|\(ch.localeID)|\(ch.onDeviceOnly)|\(silence)"
            if let existing = pipes[ch.id], existing.signature == signature { continue }
            removePipe(ch.id)

            let pipe = SpeechPipe(channelID: ch.id, locale: Locale(identifier: ch.localeID), onDeviceOnly: ch.onDeviceOnly, silence: silence)
            pipe.onPartial = { [weak self] in self?.onPartial?($0, $1, $2) }
            pipe.onCommit = { [weak self] in self?.onCommit?($0, $1, $2) }
            pipe.onStatus = { [weak self] in self?.onStatus?($0, $1) }
            let capture = captures[device.id] ?? {
                let c = DeviceCapture(deviceID: device.id)
                c.onLevels = { [weak self] in self?.onLevels?($0) }
                let id = device.id
                c.onError = { [weak self] msg in
                    self?.pipes.filter { $0.value.device == id }.keys.forEach { self?.onStatus?($0, msg) }
                }
                captures[device.id] = c
                return c
            }()
            capture.set(pipe: pipe, channel: channel)
            pipe.start()
            capture.start()
            pipes[ch.id] = (pipe, signature, device.id)
            onStatus?(ch.id, "Listening · \(device.name) ch \(channel + 1)")
        }

        for id in pipes.keys where !wantedIDs.contains(id) { removePipe(id) }
        for (id, capture) in captures where capture.isEmpty {
            capture.stop()
            captures[id] = nil
        }
        if !listening { onLevels?([:]) }
    }

    private func removePipe(_ id: UUID) {
        guard let entry = pipes.removeValue(forKey: id) else { return }
        _ = captures[entry.device]?.removePipe(id)
        entry.pipe.stop()
    }
}

/// Meters every input of one soundcard so the user can see which physical input carries signal
/// while patching a channel. Used only while the channel editor is open.
final class InputMonitor: ObservableObject {
    @Published var levels: [Float] = []
    @Published var error: String?
    private var engine: AVAudioEngine?
    private var smoothed: [Float] = []
    private var lastEmit = Date.distantPast

    func start(deviceID: AudioDeviceID?) {
        stop()
        guard let deviceID else { return }
        let engine = AVAudioEngine()
        guard let unit = engine.inputNode.audioUnit else { return }
        var dev = deviceID
        let status = AudioUnitSetProperty(unit, kAudioOutputUnitProperty_CurrentDevice, kAudioUnitScope_Global, 0,
                                          &dev, UInt32(MemoryLayout<AudioDeviceID>.size))
        guard status == noErr else { error = "Could not open this input device"; return }
        let format = engine.inputNode.outputFormat(forBus: 0)
        guard format.sampleRate > 0, format.channelCount > 0 else { error = "Input device reports no valid format"; return }
        smoothed = Array(repeating: 0, count: Int(format.channelCount))
        engine.inputNode.installTap(onBus: 0, bufferSize: 2048, format: format) { [weak self] buffer, _ in
            self?.process(buffer)
        }
        engine.prepare()
        do {
            try engine.start()
            self.engine = engine
            error = nil
        } catch {
            self.error = "Could not start metering: \(error.localizedDescription)"
        }
    }

    func stop() {
        if let engine {
            engine.inputNode.removeTap(onBus: 0)
            engine.stop()
        }
        engine = nil
        levels = []
    }

    private func process(_ buffer: AVAudioPCMBuffer) {
        guard let data = buffer.floatChannelData else { return }
        let frames = Int(buffer.frameLength)
        let count = min(Int(buffer.format.channelCount), smoothed.count)
        guard frames > 0, count > 0 else { return }
        let interleaved = buffer.format.isInterleaved
        let stride = interleaved ? Int(buffer.format.channelCount) : 1
        for ch in 0..<count {
            let src = interleaved ? data[0] + ch : data[ch]
            var sum: Float = 0
            for i in 0..<frames { let v = src[i * stride]; sum += v * v }
            let rms = (sum / Float(frames)).squareRoot()
            let level = max(0, min(1, (20 * log10(max(rms, 1e-7)) + 60) / 60))
            smoothed[ch] = max(level, smoothed[ch] * 0.85)
        }
        let now = Date()
        guard now.timeIntervalSince(lastEmit) > 0.066 else { return }
        lastEmit = now
        let snapshot = smoothed
        DispatchQueue.main.async { [weak self] in self?.levels = snapshot }
    }

    deinit { stop() }
}
