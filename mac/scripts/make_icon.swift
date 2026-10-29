import AppKit

func render(_ size: Int) -> Data {
    let s = CGFloat(size)
    let img = NSImage(size: NSSize(width: s, height: s), flipped: false) { rect in
        let r = rect.insetBy(dx: s * 0.06, dy: s * 0.06)
        let path = NSBezierPath(roundedRect: r, xRadius: s * 0.22, yRadius: s * 0.22)
        NSGradient(colors: [NSColor(red: 0.07, green: 0.09, blue: 0.13, alpha: 1), NSColor(red: 0.02, green: 0.03, blue: 0.05, alpha: 1)])!.draw(in: path, angle: -90)
        // waveform bars
        let heights: [CGFloat] = [0.18, 0.34, 0.52, 0.30, 0.62, 0.40, 0.22]
        let colors = [NSColor(red: 0.35, green: 0.78, blue: 0.98, alpha: 1), NSColor(red: 1, green: 0.48, blue: 0.35, alpha: 1)]
        let barW = s * 0.068, gap = s * 0.036
        let total = CGFloat(heights.count) * barW + CGFloat(heights.count - 1) * gap
        var x = (s - total) / 2
        for (i, h) in heights.enumerated() {
            let bh = s * h
            let bar = NSBezierPath(roundedRect: NSRect(x: x, y: (s - bh) / 2, width: barW, height: bh), xRadius: barW / 2, yRadius: barW / 2)
            colors[i == 4 ? 1 : 0].setFill(); bar.fill()
            x += barW + gap
        }
        return true
    }
    let rep = NSBitmapImageRep(data: img.tiffRepresentation!)!
    return rep.representation(using: .png, properties: [:])!
}

let dir = CommandLine.arguments[1]
try? FileManager.default.createDirectory(atPath: dir, withIntermediateDirectories: true)
for (name, px) in [("16x16", 16), ("16x16@2x", 32), ("32x32", 32), ("32x32@2x", 64), ("128x128", 128), ("128x128@2x", 256),
                   ("256x256", 256), ("256x256@2x", 512), ("512x512", 512), ("512x512@2x", 1024)] {
    try! render(px).write(to: URL(fileURLWithPath: "\(dir)/icon_\(name).png"))
}
