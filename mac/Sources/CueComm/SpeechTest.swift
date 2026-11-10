import AVFoundation
import Foundation
import Speech

/// Measurement harness, started with `--speechtest <folder>`. The folder holds clips (`name.wav`, mono) and a `name.txt`
/// with what is said in each. It is not part of normal use: it exists so a change to the capture path can be judged by the
/// word error rate it gives on speech at a known level and noise, instead of by ear.
///
/// Options: `--locale nb-NO`  `--speed 6` (how much faster than real time audio is fed)  `--e2e` (also run the real
/// DeviceCapture/SpeechPipe path with an 8-input buffer at real time)  `--noise-only`.
enum SpeechTest {
    static var requested: Bool { CommandLine.arguments.contains("--speechtest") }

    private static func arg(_ name: String) -> String? {
        let a = CommandLine.arguments
        guard let i = a.firstIndex(of: name), i + 1 < a.count else { return nil }
        return a[i + 1]
    }
    private static func out(_ s: String) { print(s); fflush(stdout) }

    // MARK: Entry

    static func run(done: @escaping () -> Void) {
        guard let dir = arg("--speechtest") else { out("usage: --speechtest <folder>"); done(); return }
        let locale = Locale(identifier: arg("--locale") ?? "nb-NO")
        let speed = Double(arg("--speed") ?? "6") ?? 6
        DispatchQueue.global(qos: .userInitiated).async {
            defer { DispatchQueue.main.async { done() } }
            out("CueComm speech test · locale \(locale.identifier) · feed speed ×\(speed)")
            guard authorise() else { out("Speech recognition not authorised. Allow it in System Settings and run again."); return }
            guard let recognizer = SFSpeechRecognizer(locale: locale), recognizer.isAvailable else { out("Recogniser unavailable"); return }
            out("on-device supported: \(recognizer.supportsOnDeviceRecognition)")
            let clips = loadClips(URL(fileURLWithPath: dir))
            guard !clips.isEmpty else { out("No clips in \(dir)"); return }
            out("clips: \(clips.count)")

            if CommandLine.arguments.contains("--e2e-only") { endToEnd(clips: clips, locale: locale); out("\nDone."); return }
            let levels: [Float] = (arg("--levels") ?? "-20,-45,-60,-70").split(separator: ",").compactMap { Float($0) }
            let snrs: [Float?] = (arg("--snrs") ?? "none,15").split(separator: ",").map { $0 == "none" ? nil : Float($0) }
            let modes = ["legacy", "plain", "level", "full"]
            var totals: [String: (errors: Int, words: Int)] = [:]

            out("\nWORD ERROR RATE over \(clips.count) sentences, each its own recognition (lower is better).")
            out("legacy = raw 48 kHz as before · plain = 16 kHz + high-pass · level = + even level · full = + speech gate")
            for level in levels {
                for snr in snrs {
                    let label = "speech \(Int(level)) dBFS, " + (snr.map { "noise \(Int($0)) dB below" } ?? "no noise")
                    var row = label.padding(toLength: 40, withPad: " ", startingAt: 0)
                    for mode in modes {
                        var errors = 0, words = 0
                        for (k, clip) in clips.enumerated() {
                            let r = recognise(scene(clip: clip, level: level, snr: snr, seed: UInt64(7 + k)), mode: mode, recognizer: recognizer, speed: speed)
                            let e = edits(reference: clip.text, hypothesis: r.text)
                            errors += e.errors; words += e.words
                            if CommandLine.arguments.contains("--verbose") { out("   [\(mode)] \(r.text)") }
                        }
                        let t = totals[mode] ?? (0, 0)
                        totals[mode] = (t.errors + errors, t.words + words)
                        row += String(format: "  %@ %3.0f%%", mode, Double(errors) / Double(max(1, words)) * 100)
                    }
                    out(row)
                }
            }
            out("\nOVERALL")
            for mode in modes {
                let t = totals[mode] ?? (0, 1)
                out(String(format: "  %-8@ %5.1f%%  (%d errors in %d words)", mode, Double(t.errors) / Double(max(1, t.words)) * 100, t.errors, t.words))
            }

            if CommandLine.arguments.contains("--noise-only") {
                out("\nNOISE ONLY (25 s, nobody speaks). Words the recogniser invents:")
                for level in [Float(-50), -35, -20] {
                    var row = "noise \(Int(level)) dBFS".padding(toLength: 40, withPad: " ", startingAt: 0)
                    var rng = SplitMix(seed: 99)
                    let n = noise(count: 48_000 * 25, rms: powf(10, level / 20), rng: &rng)
                    for mode in modes {
                        let r = recognise(n, mode: mode, recognizer: recognizer, speed: speed)
                        let words = r.text.split(separator: " ").count
                        row += " \(mode) \(words)w"
                    }
                    out(row)
                }
            }

            if CommandLine.arguments.contains("--e2e") { endToEnd(clips: clips, locale: locale) }
            out("\nDone.")
        }
    }

    private static func authorise() -> Bool {
        var status = SFSpeechRecognizer.authorizationStatus()
        if status == .notDetermined {
            let sem = DispatchSemaphore(value: 0)
            SFSpeechRecognizer.requestAuthorization { s in status = s; sem.signal() }
            _ = sem.wait(timeout: .now() + 120)
        }
        return status == .authorized
    }

    // MARK: Signals

    struct Clip { let name: String; let samples: [Float]; let text: String }

    private static func loadClips(_ dir: URL) -> [Clip] {
        let files = ((try? FileManager.default.contentsOfDirectory(at: dir, includingPropertiesForKeys: nil)) ?? [])
            .filter { ["wav", "aiff", "aif"].contains($0.pathExtension.lowercased()) }.sorted { $0.lastPathComponent < $1.lastPathComponent }
        return files.compactMap { f in
            guard let text = try? String(contentsOf: f.deletingPathExtension().appendingPathExtension("txt"), encoding: .utf8),
                  let s = readMono48k(f) else { return nil }
            return Clip(name: f.lastPathComponent, samples: s, text: text.trimmingCharacters(in: .whitespacesAndNewlines))
        }
    }

    private static func readMono48k(_ url: URL) -> [Float]? {
        guard let file = try? AVAudioFile(forReading: url),
              let buf = AVAudioPCMBuffer(pcmFormat: file.processingFormat, frameCapacity: AVAudioFrameCount(file.length)),
              (try? file.read(into: buf)) != nil, let ch = buf.floatChannelData else { return nil }
        let raw = Array(UnsafeBufferPointer(start: ch[0], count: Int(buf.frameLength)))
        let rate = file.processingFormat.sampleRate
        if abs(rate - 48_000) < 1 { return raw }
        // linear resample is enough for synthetic test speech
        let n = Int(Double(raw.count) * 48_000 / rate)
        return (0..<n).map { i in
            let p = Double(i) * rate / 48_000, j = Int(p), f = Float(p - Double(j))
            let a = raw[min(j, raw.count - 1)], b = raw[min(j + 1, raw.count - 1)]
            return a + (b - a) * f
        }
    }

    struct SplitMix {
        var state: UInt64
        init(seed: UInt64) { state = seed }
        mutating func next() -> UInt64 {
            state &+= 0x9E3779B97F4A7C15
            var z = state
            z = (z ^ (z >> 30)) &* 0xBF58476D1CE4E5B9
            z = (z ^ (z >> 27)) &* 0x94D049BB133111EB
            return z ^ (z >> 31)
        }
        mutating func unit() -> Float { Float(next() >> 11) / Float(1 << 53) }
    }

    /// Pink noise (Kellet filter), normalised to a given RMS.
    private static func noise(count: Int, rms: Float, rng: inout SplitMix) -> [Float] {
        var b0: Float = 0, b1: Float = 0, b2: Float = 0, b3: Float = 0, b4: Float = 0, b5: Float = 0, b6: Float = 0
        var v = [Float](repeating: 0, count: count)
        for i in 0..<count {
            let w = rng.unit() * 2 - 1
            b0 = 0.99886 * b0 + w * 0.0555179; b1 = 0.99332 * b1 + w * 0.0750759; b2 = 0.96900 * b2 + w * 0.1538520
            b3 = 0.86650 * b3 + w * 0.3104856; b4 = 0.55000 * b4 + w * 0.5329522; b5 = -0.7616 * b5 - w * 0.0168980
            v[i] = b0 + b1 + b2 + b3 + b4 + b5 + b6 + w * 0.5362
            b6 = w * 0.115926
        }
        let cur = (v.reduce(0) { $0 + $1 * $1 } / Float(count)).squareRoot()
        let g = rms / max(cur, 1e-9)
        return v.map { $0 * g }
    }

    /// RMS over the active part of a clip (blocks within 25 dB of the loudest), the level a person would call "the level".
    private static func activeRMS(_ s: [Float]) -> Float {
        let block = 480
        var rmsList: [Float] = []
        var i = 0
        while i + block <= s.count { var q: Float = 0; for k in i..<(i + block) { q += s[k] * s[k] }; rmsList.append(q / Float(block)); i += block }
        guard let mx = rmsList.max(), mx > 0 else { return 1e-6 }
        let keep = rmsList.filter { $0 > mx * 0.003 }
        return (keep.reduce(0, +) / Float(max(1, keep.count))).squareRoot()
    }

    /// One clip scaled to `level` dBFS active RMS, with 1.5 s before and 2 s after, and pink noise `snr` dB below the speech.
    private static func scene(clip: Clip, level: Float, snr: Float?, seed: UInt64 = 7) -> [Float] {
        let g = powf(10, level / 20) / activeRMS(clip.samples)
        var signal = [Float](repeating: 0, count: 72_000) + clip.samples.map { $0 * g } + [Float](repeating: 0, count: 96_000)
        if let snr {
            var rng = SplitMix(seed: seed)
            let n = noise(count: signal.count, rms: powf(10, (level - snr) / 20), rng: &rng)
            for i in 0..<signal.count { signal[i] += n[i] }
        }
        return signal
    }

    /// All clips in a row with 2.5 s pauses (for the real-time end-to-end run).
    private static func sequence(clips: [Clip], level: Float) -> (signal: [Float], reference: String) {
        var speech = [Float](repeating: 0, count: 48_000)
        for c in clips {
            let g = powf(10, level / 20) / activeRMS(c.samples)
            speech += c.samples.map { $0 * g }
            speech += [Float](repeating: 0, count: 120_000)
        }
        return (speech, clips.map(\.text).joined(separator: " "))
    }

    // MARK: Recognition

    private static func recognise(_ signal: [Float], mode: String, recognizer: SFSpeechRecognizer, speed: Double) -> (text: String, seconds: Double) {
        let request = SFSpeechAudioBufferRecognitionRequest()
        request.shouldReportPartialResults = true
        request.addsPunctuation = false
        request.taskHint = .dictation
        if recognizer.supportsOnDeviceRecognition { request.requiresOnDeviceRecognition = true }
        var best = ""
        let finished = DispatchSemaphore(value: 0)
        let task = recognizer.recognitionTask(with: request) { result, error in
            if let result { best = result.bestTranscription.formattedString; if result.isFinal { finished.signal() } }
            else if error != nil { finished.signal() }
        }

        let options: InputConditioner.Options? = {
            switch mode {
            case "plain": return .init(gainDB: 0, autoLevel: false, gate: false)
            case "level": return .init(gainDB: 0, autoLevel: true, gate: false)
            case "full": return .init(gainDB: 0, autoLevel: true, gate: true)
            default: return nil
            }
        }()
        let conditioner = options.flatMap { InputConditioner(sampleRate: 48_000, options: $0) }
        let in48 = AVAudioFormat(commonFormat: .pcmFormatFloat32, sampleRate: 48_000, channels: 1, interleaved: false)!
        let out16 = AVAudioFormat(commonFormat: .pcmFormatFloat32, sampleRate: 16_000, channels: 1, interleaved: false)!
        let started = Date()
        var i = 0
        let frames = 2048
        while i < signal.count {
            let n = min(frames, signal.count - i)
            let buf = AVAudioPCMBuffer(pcmFormat: in48, frameCapacity: AVAudioFrameCount(n))!
            buf.frameLength = AVAudioFrameCount(n)
            signal.withUnsafeBufferPointer { buf.floatChannelData![0].update(from: $0.baseAddress! + i, count: n) }
            if let conditioner {
                let s = conditioner.process(buf)
                if !s.isEmpty, let o = AVAudioPCMBuffer(pcmFormat: out16, frameCapacity: AVAudioFrameCount(s.count)) {
                    s.withUnsafeBufferPointer { o.floatChannelData![0].update(from: $0.baseAddress!, count: s.count) }
                    o.frameLength = AVAudioFrameCount(s.count)
                    request.append(o)
                }
            } else {
                request.append(buf)
            }
            i += n
            if speed < 100 { Thread.sleep(forTimeInterval: Double(n) / 48_000 / speed) }
        }
        request.endAudio()
        if finished.wait(timeout: .now() + 60) == .timedOut { task.cancel() }
        return (best, Date().timeIntervalSince(started))
    }

    // MARK: Word error rate

    private static func words(_ s: String) -> [String] {
        s.lowercased().unicodeScalars.map { CharacterSet.letters.union(.decimalDigits).contains($0) ? Character($0) : " " }
            .reduce(into: "") { $0.append($1) }.split(separator: " ").map(String.init)
    }

    static func edits(reference: String, hypothesis: String) -> (errors: Int, words: Int) {
        let r = words(reference), h = words(hypothesis)
        guard !r.isEmpty else { return (h.count, 0) }
        var prev = Array(0...h.count)
        for i in 1...r.count {
            var cur = [i] + [Int](repeating: 0, count: h.count)
            if h.count > 0 {
                for j in 1...h.count {
                    cur[j] = min(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + (r[i - 1] == h[j - 1] ? 0 : 1))
                }
            }
            prev = cur
        }
        return (prev[h.count], r.count)
    }

    static func wer(reference: String, hypothesis: String) -> Double {
        let e = edits(reference: reference, hypothesis: hypothesis)
        return e.words == 0 ? (e.errors == 0 ? 0 : 1) : Double(e.errors) / Double(e.words)
    }

    // MARK: Real path

    /// The production path: an 8-input device buffer goes through DeviceCapture.ingest into a SpeechPipe, at real time.
    /// Speech sits on input 3 at -42 dBFS; the other inputs carry noise, to prove the right one is read.
    private static func endToEnd(clips: [Clip], locale: Locale) {
        out("\nEND TO END (real time, DeviceCapture → InputConditioner → SpeechPipe, 8 inputs, speech on input 3 at -42 dBFS)")
        let (signal, reference) = sequence(clips: clips, level: -42)
        let id = UUID()
        let pipe = SpeechPipe(channelID: id, locale: locale, onDeviceOnly: true, silence: 1.2)
        let capture = DeviceCapture(deviceID: 0)
        let lock = NSLock()
        var commits: [String] = []
        pipe.onCommit = { _, text, _ in lock.lock(); commits.append(text); lock.unlock() }
        pipe.onStatus = { _, s in out("   status: \(s)") }
        capture.set(pipe: pipe, channel: 2, options: .init())
        pipe.start()

        // More than two channels need an explicit layout, like a real multichannel soundcard reports.
        let layout = AVAudioChannelLayout(layoutTag: kAudioChannelLayoutTag_DiscreteInOrder | 8)!
        let format = AVAudioFormat(standardFormatWithSampleRate: 48_000, channelLayout: layout)
        var rng = SplitMix(seed: 5)
        let hiss = noise(count: signal.count, rms: powf(10, -45.0 / 20), rng: &rng)
        var i = 0, sampleTime: AVAudioFramePosition = 0
        let frames = 2048
        let t0 = Date()
        while i < signal.count {
            let n = min(frames, signal.count - i)
            let buf = AVAudioPCMBuffer(pcmFormat: format, frameCapacity: AVAudioFrameCount(n))!
            buf.frameLength = AVAudioFrameCount(n)
            for c in 0..<8 {
                let p = buf.floatChannelData![c]
                for k in 0..<n { p[k] = c == 2 ? signal[i + k] : hiss[i + k] }
            }
            capture.ingest(buf, time: AVAudioTime(sampleTime: sampleTime, atRate: 48_000))
            sampleTime += AVAudioFramePosition(n)
            i += n
            let due = t0.addingTimeInterval(Double(i) / 48_000)
            let wait = due.timeIntervalSinceNow
            if wait > 0 { Thread.sleep(forTimeInterval: wait) }
        }
        Thread.sleep(forTimeInterval: 3)
        pipe.stop()
        Thread.sleep(forTimeInterval: 0.5)
        lock.lock(); let text = commits.joined(separator: " "); let n = commits.count; lock.unlock()
        out("   commits: \(n)")
        out("   heard:    \(text)")
        out("   expected: \(reference)")
        out(String(format: "   word error rate: %.0f%%", wer(reference: reference, hypothesis: text) * 100))
    }
}
