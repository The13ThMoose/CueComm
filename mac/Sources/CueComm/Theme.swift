import SwiftUI

/// Matte black ground, one vibrant hue per channel. Green is reserved for "live", yellow for "cue".
enum Theme {
    static let ground = Color(hex: "09090B")
    static let panel = Color(hex: "131318")
    static let raised = Color(hex: "1C1C23")
    static let hairline = Color(hex: "2C2C35")

    static let text = Color.white
    static let dim = Color(hex: "C7CAD4")       // 12:1 on ground
    static let faint = Color(hex: "9EA3B0")     // 7.8:1 on ground, the lowest text colour allowed
    static let ink = Color(hex: "0B0B0E")       // text on a hue fill

    static let orange = Color(hex: "FF9F1C")    // primary action (from the Meltveit mark)
    static let live = Color(hex: "39FF88")      // connected / listening (the mark's playhead)
    static let cue = Color(hex: "FFE14D")       // keyword hit, cue fired
    static let pink = Color(hex: "FF4FA3")
    static let danger = Color(hex: "FF5F4A")
}

/// Filled primary button. Disabled, hover and keyboard focus are drawn here so every button shares them.
struct ActionButtonStyle: ButtonStyle {
    var fill: Color = Theme.orange
    var ink: Color = Theme.ink
    var size: CGFloat = 16

    func makeBody(configuration: Configuration) -> some View {
        ActionButtonBody(configuration: configuration, fill: fill, ink: ink, size: size)
    }
}

private struct ActionButtonBody: View {
    let configuration: ButtonStyleConfiguration
    let fill: Color
    let ink: Color
    let size: CGFloat
    @Environment(\.isEnabled) private var enabled
    @Environment(\.isFocused) private var focused
    @State private var hover = false

    var body: some View {
        configuration.label
            .font(.system(size: size, weight: .bold))
            .foregroundStyle(enabled ? ink : Theme.faint)
            .padding(.horizontal, 16).padding(.vertical, 9)
            .background(RoundedRectangle(cornerRadius: 11, style: .continuous)
                .fill(enabled ? fill.opacity(configuration.isPressed ? 0.7 : (hover ? 0.88 : 1)) : Theme.raised))
            .overlay(RoundedRectangle(cornerRadius: 11, style: .continuous).stroke(focused ? Theme.text : .clear, lineWidth: 2).padding(-3))
            .focusEffectDisabled()
            .onHover { hover = $0 }
            .contentShape(Rectangle())
    }
}

struct QuietButtonStyle: ButtonStyle {
    var tint: Color = Theme.text
    var active = false
    var size: CGFloat = 15

    func makeBody(configuration: Configuration) -> some View {
        QuietButtonBody(configuration: configuration, tint: tint, active: active, size: size)
    }
}

private struct QuietButtonBody: View {
    let configuration: ButtonStyleConfiguration
    let tint: Color
    let active: Bool
    let size: CGFloat
    @Environment(\.isEnabled) private var enabled
    @Environment(\.isFocused) private var focused
    @State private var hover = false

    var body: some View {
        configuration.label
            .font(.system(size: size, weight: .semibold))
            .foregroundStyle(!enabled ? Theme.faint : (active ? Theme.ink : tint))
            .padding(.horizontal, 13).padding(.vertical, 8)
            .background(RoundedRectangle(cornerRadius: 10, style: .continuous)
                .fill(active && enabled ? tint : (hover && enabled ? Theme.hairline : Theme.raised)))
            .overlay(RoundedRectangle(cornerRadius: 10, style: .continuous).stroke(focused ? Theme.text : (active ? .clear : Theme.hairline), lineWidth: focused ? 2 : 1))
            .opacity(configuration.isPressed ? 0.7 : 1)
            .focusEffectDisabled()
            .onHover { hover = $0 }
            .contentShape(Rectangle())
    }
}

/// Solid hue with near-black ink: a channel's name, readable at a glance.
struct HueChip: View {
    let name: String
    let color: Color
    var size: CGFloat = 13

    var body: some View {
        Text(name.uppercased())
            .font(.system(size: size, weight: .heavy))
            .tracking(0.8)
            .foregroundStyle(Theme.ink)
            .lineLimit(1)
            .padding(.horizontal, 9).padding(.vertical, 3)
            .background(RoundedRectangle(cornerRadius: 6, style: .continuous).fill(color))
    }
}

/// The signature: a colour block that sits dim while its channel is silent and lights to full hue when it speaks.
struct HueBlock: View {
    let color: Color
    let label: String
    var active = false
    var size: CGFloat = 42
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    var body: some View {
        RoundedRectangle(cornerRadius: size * 0.24, style: .continuous)
            .fill(color.opacity(active ? 1 : 0.22))
            .frame(width: size, height: size)
            .overlay(
                Text(label)
                    .font(.system(size: size * 0.38, weight: .heavy))
                    .foregroundStyle(active ? Theme.ink : color)
            )
            .shadow(color: color.opacity(active ? 0.55 : 0), radius: active ? 10 : 0, y: 2)
            .animation(reduceMotion ? nil : .easeOut(duration: 0.15), value: active)
    }
}

/// Four coded strips for "All Channels".
struct StripBlock: View {
    let colors: [Color]
    var size: CGFloat = 42

    var body: some View {
        HStack(spacing: size * 0.07) {
            ForEach(Array(colors.prefix(4).enumerated()), id: \.offset) { _, c in
                RoundedRectangle(cornerRadius: 3, style: .continuous).fill(c)
            }
        }
        .padding(size * 0.14)
        .frame(width: size, height: size)
        .background(RoundedRectangle(cornerRadius: size * 0.24, style: .continuous).fill(Theme.raised))
    }
}

/// Segmented level strip.
struct SegmentMeter: View {
    let level: Float
    let color: Color
    var segments = 14
    var height: CGFloat = 6

    var body: some View {
        let lit = Int((level * Float(segments)).rounded())
        HStack(spacing: 2) {
            ForEach(0..<segments, id: \.self) { i in
                RoundedRectangle(cornerRadius: 1.5).fill(i < lit ? color : color.opacity(0.18))
            }
        }
        .frame(height: height)
        .accessibilityHidden(true)
    }
}

/// Section heading: sentence case, at least 14pt, never the faint caps kicker.
struct SectionLabel: View {
    let text: String
    var size: CGFloat = 17
    var body: some View {
        Text(text)
            .font(.system(size: size, weight: .semibold))
            .foregroundStyle(size >= 17 ? Theme.text : Theme.dim)
    }
}

/// Colour choice that works from the keyboard and reads its name to VoiceOver.
struct SwatchButton: View {
    let hex: String
    let selected: Bool
    var size: CGFloat = 30
    let choose: () -> Void

    static let names = ["FF5F4A": "Coral", "FF4FA3": "Pink", "E23DFF": "Magenta", "8F7BFF": "Violet", "4D8DFF": "Blue", "35D8FF": "Cyan",
                        "22C7B8": "Teal", "A8B4FF": "Periwinkle", "FFE14D": "Yellow", "39FF88": "Green", "FF9F1C": "Orange", "FFFFFF": "White"]

    var body: some View {
        Button(action: choose) {
            Circle().fill(Color(hex: hex)).frame(width: size, height: size)
                .overlay(Circle().stroke(Theme.text, lineWidth: selected ? 3 : 0).padding(-4))
        }
        .buttonStyle(.plain)
        .accessibilityLabel(Self.names[hex] ?? hex)
        .accessibilityAddTraits(selected ? .isSelected : [])
    }
}

extension Array {
    subscript(safe i: Int) -> Element? { indices.contains(i) ? self[i] : nil }
}
