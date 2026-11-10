import AVFoundation
import Foundation

/// Prepares one channel's audio for the speech recogniser.
///
/// A console or Dante send is not a microphone: it can be 30 dB quieter, it carries hum and room noise, and the sample
/// rate can differ from one launch to the next. The recogniser copes badly with all three, and a meter that moves says
/// nothing about whether the level is usable. So each channel's audio is
///   1. resampled to a fixed 16 kHz mono stream (what the recogniser's models are built for, and a format that never changes
///      under it when the interface changes rate),
///   2. high-passed at 100 Hz (rumble, hum, handling noise),
///   3. gated: speech passes with a lead-in, the noise between speech is dropped, so a quiet room is not "recognised",
///   4. levelled: the speech is brought to a steady level with a limiter, so a -45 dBFS send and a -10 dBFS send both work.
final class InputConditioner {
    struct Options: Equatable {
        var gainDB: Float = 0
        var autoLevel = true
        var gate = true
    }

    /// What the conditioner currently sees, for status messages. Levels are dBFS before any gain.
    struct Health {
        var rmsDB: Float = -120          // recent speech-band level
        var peakDB: Float = -120         // loudest sample of the last second
        var floorDB: Float = -120        // estimated noise floor
        var gainDB: Float = 0            // gain currently applied
        var gateOpen = false
    }

    static let rate = 16_000.0
    private static let block = 160                   // 10 ms at 16 kHz
    private static let prerollBlocks = 50            // 500 ms lead-in
    private static let hangBlocks = 70               // 700 ms tail after the last speech
    private static let targetDB: Float = -24         // speech level the recogniser gets

    private let inFormat: AVAudioFormat
    private let outFormat: AVAudioFormat
    private let converter: AVAudioConverter?
    private var opts: Options
    private let lock = NSLock()
    private var snapshot = Health()

    // 100 Hz second-order high-pass (Butterworth), direct form I.
    private let b0: Float, b1: Float, b2: Float, a1: Float, a2: Float
    private var x1: Float = 0, x2: Float = 0, y1: Float = 0, y2: Float = 0

    private var carry: [Float] = []
    private var levels: [Float] = []                 // recent block levels, for the noise floor
    private var blocksSinceFloor = 0
    private var floorDB: Float = -90
    private var speechDB: Float?
    private var open = false
    private var aboveRun = 0
    private var hang = 0
    private var preroll: [[Float]] = []
    private var gain: Float = 1
    private var peakHold: Float = 0
    private var peakBlocks = 0
    private var peakDB: Float = -120
    private var rmsSmooth: Float = -120

    init?(sampleRate: Double, options: Options) {
        guard sampleRate > 0,
              let i = AVAudioFormat(commonFormat: .pcmFormatFloat32, sampleRate: sampleRate, channels: 1, interleaved: false),
              let o = AVAudioFormat(commonFormat: .pcmFormatFloat32, sampleRate: Self.rate, channels: 1, interleaved: false) else { return nil }
        inFormat = i
        outFormat = o
        converter = abs(sampleRate - Self.rate) < 1 ? nil : AVAudioConverter(from: i, to: o)
        opts = options
        let w0 = 2 * Float.pi * 100 / Float(Self.rate)
        let c = cos(w0), alpha = sin(w0) / (2 * 0.70710678)
        let a0 = 1 + alpha
        b0 = ((1 + c) / 2) / a0; b1 = (-(1 + c)) / a0; b2 = ((1 + c) / 2) / a0
        a1 = (-2 * c) / a0; a2 = (1 - alpha) / a0
        guard sampleRate == Self.rate || converter != nil else { return nil }
    }

    var health: Health { lock.lock(); defer { lock.unlock() }; return snapshot }

    func update(_ options: Options) { lock.lock(); opts = options; lock.unlock() }

    /// Takes a mono buffer at the device rate; returns conditioned 16 kHz mono samples (empty while the gate is closed).
    func process(_ buffer: AVAudioPCMBuffer) -> [Float] {
        guard let samples = resample(buffer) else { return [] }
        lock.lock(); let o = opts; lock.unlock()
        carry.append(contentsOf: samples)
        var out: [Float] = []
        var start = 0
        while carry.count - start >= Self.block {
            var blk = Array(carry[start..<(start + Self.block)])
            start += Self.block
            highPass(&blk)
            handle(&blk, o, into: &out)
        }
        if start > 0 { carry.removeFirst(start) }
        publish()
        return out
    }

    // MARK: Resampling

    private func resample(_ buffer: AVAudioPCMBuffer) -> [Float]? {
        guard let src = buffer.floatChannelData?[0], buffer.frameLength > 0 else { return nil }
        let frames = Int(buffer.frameLength)
        guard let converter else { return Array(UnsafeBufferPointer(start: src, count: frames)) }
        let cap = AVAudioFrameCount(Double(frames) * Self.rate / inFormat.sampleRate) + 32
        guard let out = AVAudioPCMBuffer(pcmFormat: outFormat, frameCapacity: cap) else { return nil }
        var supplied = false
        var error: NSError?
        let status = converter.convert(to: out, error: &error) { _, inputStatus in
            if supplied { inputStatus.pointee = .noDataNow; return nil }
            supplied = true
            inputStatus.pointee = .haveData
            return buffer
        }
        guard status != .error, let data = out.floatChannelData?[0] else { return nil }
        return Array(UnsafeBufferPointer(start: data, count: Int(out.frameLength)))
    }

    // MARK: Per block

    private func highPass(_ blk: inout [Float]) {
        for i in 0..<blk.count {
            let x = blk[i]
            let y = b0 * x + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2
            x2 = x1; x1 = x; y2 = y1; y1 = y
            blk[i] = y
        }
    }

    private static func db(_ meanSquare: Float) -> Float { 10 * log10(meanSquare + 1e-12) }

    private func handle(_ blk: inout [Float], _ o: Options, into out: inout [Float]) {
        var sum: Float = 0, peak: Float = 0
        for v in blk { sum += v * v; peak = max(peak, abs(v)) }
        let level = Self.db(sum / Float(blk.count))

        peakHold = max(peakHold, peak)
        peakBlocks += 1
        if peakBlocks >= 100 { peakDB = 20 * log10(peakHold + 1e-9); peakHold = 0; peakBlocks = 0 }
        rmsSmooth = rmsSmooth < -119 ? level : rmsSmooth * 0.9 + level * 0.1

        // Noise floor: a low percentile of the last five seconds follows both a noisy room and a quiet one, and speech pauses
        // keep it honest because speech is never continuous.
        levels.append(level)
        if levels.count > 500 { levels.removeFirst(levels.count - 500) }
        blocksSinceFloor += 1
        if blocksSinceFloor >= 20 || levels.count == 30 {
            blocksSinceFloor = 0
            let sorted = levels.sorted()
            let pct = sorted[min(sorted.count - 1, Int(Float(sorted.count) * 0.15))]
            floorDB = levels.count < 30 ? pct : floorDB * 0.6 + pct * 0.4
        }

        let openAt = max(floorDB + 10, -75)
        let closeAt = max(floorDB + 6, -78)
        let wasOpen = open

        if !o.gate {
            open = true
        } else if open {
            if level < closeAt { hang += 1; if hang > Self.hangBlocks { open = false; aboveRun = 0 } } else { hang = 0 }
        } else {
            aboveRun = level > openAt ? aboveRun + 1 : 0
            if aboveRun >= 2 { open = true; hang = 0 }
        }

        // Level: follow the speech, not the silence.
        if o.autoLevel {
            if open && level > openAt {
                speechDB = speechDB.map { $0 * 0.97 + level * 0.03 } ?? level
            }
            let want = (speechDB.map { Self.targetDB - $0 } ?? 0) + o.gainDB
            let wantDB = max(-12, min(36, want))
            let wantLin = powf(10, wantDB / 20)
            gain = (!wasOpen && open) ? wantLin : gain + (wantLin - gain) * 0.05
        } else {
            gain = powf(10, o.gainDB / 20)
        }

        if open {
            if !wasOpen && o.gate {
                for b in preroll { out.append(contentsOf: apply(b)) }
                preroll.removeAll(keepingCapacity: true)
            }
            out.append(contentsOf: apply(blk))
        } else {
            preroll.append(blk)
            if preroll.count > Self.prerollBlocks { preroll.removeFirst(preroll.count - Self.prerollBlocks) }
        }
    }

    /// Gain and a soft limiter above 0.8, so a loud word cannot clip after a quiet stretch raised the gain.
    private func apply(_ blk: [Float]) -> [Float] {
        let g = gain
        return blk.map { v in
            let y = v * g
            let a = abs(y)
            if a <= 0.8 { return y }
            let limited = 0.8 + 0.2 * tanhf((a - 0.8) / 0.2)
            return y < 0 ? -limited : limited
        }
    }

    private func publish() {
        lock.lock()
        snapshot = Health(rmsDB: rmsSmooth, peakDB: peakDB, floorDB: floorDB, gainDB: 20 * log10(max(gain, 1e-6)), gateOpen: open)
        lock.unlock()
    }
}
