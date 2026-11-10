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
    private var pending: [AVAudioPCMBuffer] = []          // audio that arrived between two recognition tasks
    private var pendingFrames = 0
    private var consecutiveErrors = 0
    private var lastErrorAt = Date.distantPast

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
        pending.removeAll(); pendingFrames = 0
        lock.unlock()
        if !text.isEmpty { DispatchQueue.main.async { self.onCommit?(self.channelID, text, utt) } }
    }

    func append(_ buffer: AVAudioPCMBuffer) {
        lock.lock()
        guard running else { lock.unlock(); return }
        if let r = request { lock.unlock(); r.append(buffer); return }
        // Between two tasks (a restart after a pause or an error) the audio is kept and handed to the next one, so the
        // first words after a restart are not lost.
        pending.append(buffer)
        pendingFrames += Int(buffer.frameLength)
        let limit = Int(buffer.format.sampleRate * 3)
        while pendingFrames > limit, let first = pending.first { pendingFrames -= Int(first.frameLength); pending.removeFirst() }
        lock.unlock()
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
        for b in pending { req.append(b) }
        pending.removeAll(); pendingFrames = 0
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
        var report: String?
        if let error {
            // Quick first retry, then back off if it keeps failing. "No speech detected" and our own cancels are normal.
            let ns = error as NSError
            let benign = ns.domain == "kAFAssistantErrorDomain" && [1110, 216, 301].contains(ns.code)
            consecutiveErrors = Date().timeIntervalSince(lastErrorAt) < 5 ? consecutiveErrors + 1 : 0
            lastErrorAt = Date()
            restartDelay = min(2.0, 0.2 * pow(2, Double(consecutiveErrors)))
            if !benign && consecutiveErrors >= 2 { report = "Recogniser trouble: \(error.localizedDescription)" }
        }
        if restartDelay != nil { request = nil }        // from here until the next task starts, append() keeps the audio
        if let report { DispatchQueue.main.async { self.onStatus?(self.channelID, report) } }
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
    private var pipes: [UUID: (pipe: SpeechPipe, channel: Int, options: InputConditioner.Options)] = [:]
    private var conditioners: [UUID: (conditioner: InputConditioner, rate: Double)] = [:]
    private var lastLevelEmit = Date.distantPast
    private var lastHealthEmit = Date.distantPast
    private var observer: NSObjectProtocol?
    private var running = false
    private var lastSampleEnd: AVAudioFramePosition?
    private var dropoutsSinceReport = 0
    var onLevels: (([UUID: Float]) -> Void)?
    var onHealth: (([UUID: InputConditioner.Health], Int) -> Void)?
    var onError: ((String) -> Void)?

    init(deviceID: AudioDeviceID) { self.deviceID = deviceID }

    var isEmpty: Bool { lock.lock(); defer { lock.unlock() }; return pipes.isEmpty }

    func set(pipe: SpeechPipe, channel: Int, options: InputConditioner.Options) {
        lock.lock(); pipes[pipe.channelID] = (pipe, channel, options); lock.unlock()
    }
    func removePipe(_ id: UUID) -> SpeechPipe? {
        lock.lock(); defer { lock.unlock() }
        conditioners[id] = nil
        return pipes.removeValue(forKey: id)?.pipe
    }

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
            lastSampleEnd = nil
            input.installTap(onBus: 0, bufferSize: 2048, format: format) { [weak self] buffer, time in
                self?.ingest(buffer, time: time)
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
        lock.lock(); let all = pipes.values.map(\.pipe); pipes = [:]; conditioners = [:]; lock.unlock()
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

    /// One buffer from the device (every input of it). Each listening channel gets its own conditioned mono stream.
    /// `time` is used to notice lost buffers: consecutive buffers must be contiguous in sample time.
    func ingest(_ buffer: AVAudioPCMBuffer, time: AVAudioTime?) {
        guard let data = buffer.floatChannelData else { return }
        let frames = Int(buffer.frameLength)
        let count = Int(buffer.format.channelCount)
        guard frames > 0 else { return }
        if let time, time.isSampleTimeValid {
            if let expected = lastSampleEnd, abs(time.sampleTime - expected) > 8 { dropoutsSinceReport += 1 }
            lastSampleEnd = time.sampleTime + AVAudioFramePosition(frames)
        }
        lock.lock(); let snapshot = pipes; lock.unlock()
        guard let mono = AVAudioFormat(commonFormat: .pcmFormatFloat32, sampleRate: buffer.format.sampleRate, channels: 1, interleaved: false),
              let outFormat = AVAudioFormat(commonFormat: .pcmFormatFloat32, sampleRate: InputConditioner.rate, channels: 1, interleaved: false) else { return }
        let interleaved = buffer.format.isInterleaved
        var levels: [UUID: Float] = [:]
        var health: [UUID: InputConditioner.Health] = [:]

        for (id, entry) in snapshot {
            guard entry.channel < count,
                  let raw = AVAudioPCMBuffer(pcmFormat: mono, frameCapacity: AVAudioFrameCount(frames)),
                  let dst = raw.floatChannelData?[0] else { continue }
            raw.frameLength = AVAudioFrameCount(frames)
            var sum: Float = 0
            if interleaved {
                let src = data[0]
                for i in 0..<frames { let v = src[i * count + entry.channel]; dst[i] = v; sum += v * v }
            } else {
                let src = data[entry.channel]
                for i in 0..<frames { let v = src[i]; dst[i] = v; sum += v * v }
            }
            let rms = (sum / Float(frames)).squareRoot()
            levels[id] = max(0, min(1, (20 * log10(max(rms, 1e-7)) + 60) / 60))

            // The conditioner is rebuilt if the interface changes its sample rate under us.
            lock.lock()
            var entryC = conditioners[id]
            if entryC == nil || entryC!.rate != buffer.format.sampleRate,
               let c = InputConditioner(sampleRate: buffer.format.sampleRate, options: entry.options) {
                entryC = (c, buffer.format.sampleRate); conditioners[id] = entryC
            }
            lock.unlock()
            guard let cond = entryC?.conditioner else { continue }
            cond.update(entry.options)

            let samples = cond.process(raw)
            health[id] = cond.health
            if !samples.isEmpty, let out = AVAudioPCMBuffer(pcmFormat: outFormat, frameCapacity: AVAudioFrameCount(samples.count)),
               let o = out.floatChannelData?[0] {
                samples.withUnsafeBufferPointer { o.update(from: $0.baseAddress!, count: samples.count) }
                out.frameLength = AVAudioFrameCount(samples.count)
                entry.pipe.append(out)
            }
        }

        let now = Date()
        if now.timeIntervalSince(lastLevelEmit) > 0.1 {
            lastLevelEmit = now
            DispatchQueue.main.async { [weak self] in self?.onLevels?(levels) }
        }
        if now.timeIntervalSince(lastHealthEmit) > 1 {
            lastHealthEmit = now
            let dropouts = dropoutsSinceReport
            dropoutsSinceReport = 0
            DispatchQueue.main.async { [weak self] in self?.onHealth?(health, dropouts) }
        }
    }
}

/// Reconciles the desired set of listening channels with running device captures and speech pipes.
final class CaptureManager {
    private var captures: [AudioDeviceID: DeviceCapture] = [:]
    private var pipes: [UUID: (pipe: SpeechPipe, signature: String, device: AudioDeviceID)] = [:]
    private var base: [UUID: String] = [:]                  // "Listening · device ch N"
    private var shown: [UUID: String] = [:]
    private var problem: [UUID: (text: String, seen: Int)] = [:]
    private var deviceNames: [AudioDeviceID: String] = [:]

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
                publish(ch.id, "Input device not connected")
                continue
            }
            // An input number the device does not have is a setup mistake, not something to hide by listening to another
            // input: with "System default" it happens when the default switches to a microphone with one input.
            guard ch.inputChannel < device.inputChannels else {
                removePipe(ch.id)
                publish(ch.id, "Input \(ch.inputChannel + 1) is not on “\(device.name)” (it has \(device.inputChannels)). Choose the soundcard in the channel's settings.")
                continue
            }
            wantedIDs.insert(ch.id)
            let channel = ch.inputChannel
            let options = InputConditioner.Options(gainDB: Float(ch.inputGainDB), autoLevel: ch.autoLevel, gate: ch.speechGate)
            let signature = "\(device.id)|\(channel)|\(ch.localeID)|\(ch.onDeviceOnly)|\(silence)|\(options.gainDB)|\(options.autoLevel)|\(options.gate)"
            if let existing = pipes[ch.id], existing.signature == signature { continue }
            removePipe(ch.id)

            let pipe = SpeechPipe(channelID: ch.id, locale: Locale(identifier: ch.localeID), onDeviceOnly: ch.onDeviceOnly, silence: silence)
            pipe.onPartial = { [weak self] in self?.onPartial?($0, $1, $2) }
            pipe.onCommit = { [weak self] in self?.onCommit?($0, $1, $2) }
            pipe.onStatus = { [weak self] in self?.publish($0, $1) }
            let capture = captures[device.id] ?? {
                let c = DeviceCapture(deviceID: device.id)
                c.onLevels = { [weak self] in self?.onLevels?($0) }
                let id = device.id
                c.onError = { [weak self] msg in
                    self?.pipes.filter { $0.value.device == id }.keys.forEach { self?.publish($0, msg) }
                }
                c.onHealth = { [weak self] health, dropouts in self?.evaluate(device: id, health: health, dropouts: dropouts) }
                captures[device.id] = c
                return c
            }()
            deviceNames[device.id] = device.name
            capture.set(pipe: pipe, channel: channel, options: options)
            pipe.start()
            capture.start()
            pipes[ch.id] = (pipe, signature, device.id)
            base[ch.id] = "Listening · \(device.name) input \(channel + 1)"
            problem[ch.id] = nil
            publish(ch.id, base[ch.id]!)
        }

        for id in pipes.keys where !wantedIDs.contains(id) { removePipe(id) }
        for (id, capture) in captures where capture.isEmpty {
            capture.stop()
            captures[id] = nil
        }
        if !listening { onLevels?([:]) }
    }

    private func publish(_ id: UUID, _ text: String) {
        if shown[id] != text { shown[id] = text; onStatus?(id, text) }
    }

    /// Once a second, per channel: is the signal usable? A meter that moves does not say so, so say it in words, and only
    /// after the same finding has held for a few seconds, so the line does not flicker.
    private func evaluate(device: AudioDeviceID, health: [UUID: InputConditioner.Health], dropouts: Int) {
        let name = deviceNames[device] ?? "the soundcard"
        for (id, h) in health {
            guard pipes[id] != nil, let normal = base[id] else { continue }
            var finding: String?
            if dropouts >= 2 {
                finding = "Audio dropouts from “\(name)”. Raise its buffer or latency (for Dante Virtual Soundcard: the latency in its window), and close heavy apps."
            } else if h.peakDB < -85 {
                finding = "No signal on this input."
            } else if h.peakDB < -50 {
                finding = "Very quiet signal (peak \(Int(h.peakDB)) dBFS), raised \(Int(h.gainDB)) dB. A stronger send recognises better."
            } else if h.peakDB > -0.5 {
                finding = "The signal clips. Lower the send level at the source."
            }
            if let finding {
                let seen = (problem[id]?.text == finding ? problem[id]!.seen : 0) + 1
                problem[id] = (finding, seen)
                if seen >= 4 { publish(id, finding) }
            } else {
                problem[id] = nil
                publish(id, normal)
            }
        }
    }

    private func removePipe(_ id: UUID) {
        base[id] = nil; problem[id] = nil
        guard let entry = pipes.removeValue(forKey: id) else { return }
        _ = captures[entry.device]?.removePipe(id)
        entry.pipe.stop()
    }
}

/// Meters every input of one soundcard so the user can see which physical input carries signal
/// while patching a channel. Used only while the channel editor is open.
final class InputMonitor: ObservableObject {
    @Published var levels: [Float] = []
    /// Loudest sample of each input over the last ~1.5 s, in dBFS. This is what tells you whether a send is usable.
    @Published var peaksDB: [Float] = []
    @Published var error: String?
    private var engine: AVAudioEngine?
    private var smoothed: [Float] = []
    private var peakHold: [Float] = []
    private var peakAge: [Int] = []
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
        peakHold = Array(repeating: 0, count: Int(format.channelCount))
        peakAge = Array(repeating: 0, count: Int(format.channelCount))
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
        peaksDB = []
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
            var sum: Float = 0, peak: Float = 0
            for i in 0..<frames { let v = src[i * stride]; sum += v * v; peak = max(peak, abs(v)) }
            let rms = (sum / Float(frames)).squareRoot()
            let level = max(0, min(1, (20 * log10(max(rms, 1e-7)) + 60) / 60))
            smoothed[ch] = max(level, smoothed[ch] * 0.85)
            peakAge[ch] += 1
            if peak >= peakHold[ch] || peakAge[ch] > 32 { peakHold[ch] = peak; peakAge[ch] = 0 }   // ~1.5 s at 2048 frames / 48 kHz
        }
        let now = Date()
        guard now.timeIntervalSince(lastEmit) > 0.066 else { return }
        lastEmit = now
        let snapshot = smoothed
        let peaks = peakHold.map { 20 * log10(max($0, 1e-6)) }
        DispatchQueue.main.async { [weak self] in self?.levels = snapshot; self?.peaksDB = peaks }
    }

    deinit { stop() }
}
