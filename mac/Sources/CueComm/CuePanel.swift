import SwiftUI

/// Cue words: say a word, send OSC, flash the screen. Each cue is a keyword automation under the hood.
struct CuePanel: View {
    @EnvironmentObject var store: AppStore
    @State private var newWord = ""
    @State private var showTarget = false

    private var hasCues: Bool { store.automations.contains { $0.isCue } }
    private var listeningCount: Int { store.channels.filter { $0.enabled && !$0.isRemote }.count }

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            VStack(alignment: .leading, spacing: 14) {
                HStack(alignment: .top) {
                    VStack(alignment: .leading, spacing: 3) {
                        Text("Cue Words").font(.system(size: 26, weight: .bold))
                        Text("Say the word: OSC goes out and this window flashes.")
                            .font(.system(size: 14)).foregroundStyle(Theme.dim)
                    }
                    Spacer()
                    Button { store.showCues = false } label: { Image(systemName: "xmark") }
                        .buttonStyle(QuietButtonStyle(size: 14)).accessibilityLabel("Close cue words")
                }

                armedLine

                HStack(spacing: 8) {
                    TextField("New cue word, e.g. GO", text: $newWord)
                        .textFieldStyle(.plain).font(.system(size: 17))
                        .padding(.horizontal, 12).padding(.vertical, 9)
                        .background(RoundedRectangle(cornerRadius: 10, style: .continuous).fill(Theme.raised))
                        .overlay(RoundedRectangle(cornerRadius: 10, style: .continuous).stroke(Theme.hairline))
                        .onSubmit(add)
                    Button("Add", action: add)
                        .buttonStyle(ActionButtonStyle(fill: Theme.cue, size: 15))
                        .disabled(newWord.trimmingCharacters(in: .whitespaces).isEmpty)
                }

                DisclosureGroup(isExpanded: $showTarget) {
                    HStack(spacing: 8) {
                        LabeledField(title: "Host", text: $store.settings.oscDefaultHost)
                        LabeledField(title: "Port", value: $store.settings.oscDefaultPort).frame(width: 90)
                    }
                    .padding(.top, 8)
                    Text("New cue words start with this OSC target. Each cue can override it.")
                        .font(.system(size: 13)).foregroundStyle(Theme.faint).padding(.top, 4)
                } label: {
                    Text("Default OSC target: " + store.settings.oscDefaultHost + ":" + String(store.settings.oscDefaultPort))
                        .font(.system(size: 14, weight: .semibold)).foregroundStyle(Theme.dim)
                }
                .tint(Theme.dim)
            }
            .padding(20)

            Rectangle().fill(Theme.hairline).frame(height: 1)

            ScrollView {
                LazyVStack(spacing: 10) {
                    if !hasCues {
                        VStack(spacing: 12) {
                            Image(systemName: "bolt.fill").font(.system(size: 30)).foregroundStyle(Theme.cue)
                            Text("No cue words yet").font(.system(size: 19, weight: .bold))
                            Text("Add the words your crew says to fire a cue.").font(.system(size: 15)).foregroundStyle(Theme.dim)
                            HStack {
                                ForEach(["GO", "STANDBY", "CUE"], id: \.self) { w in
                                    Button(w) { store.addCue(w) }.buttonStyle(QuietButtonStyle(tint: Theme.cue, size: 14))
                                }
                            }
                        }
                        .frame(maxWidth: .infinity).padding(.top, 40)
                    }
                    ForEach($store.automations) { $a in
                        if a.isCue { CueCard(automation: $a) }
                    }
                }
                .padding(16)
            }
        }
        .background(Theme.panel)
    }

    /// Cues only fire while listening; say so, with the fix one click away.
    @ViewBuilder private var armedLine: some View {
        if store.isListening {
            Label("Armed: listening on \(listeningCount) channel\(listeningCount == 1 ? "" : "s")", systemImage: "checkmark.circle.fill")
                .font(.system(size: 15, weight: .semibold)).foregroundStyle(Theme.live)
        } else {
            HStack(spacing: 10) {
                Label("Not listening: cues will not fire", systemImage: "exclamationmark.triangle.fill")
                    .font(.system(size: 15, weight: .semibold)).foregroundStyle(Theme.orange)
                Spacer(minLength: 0)
                Button("Listen") { store.toggleListening() }.buttonStyle(ActionButtonStyle(size: 14))
            }
        }
    }

    private func add() {
        store.addCue(newWord)
        newWord = ""
    }
}

struct LabeledField: View {
    let title: String
    var text: Binding<String>?
    var value: Binding<Int>?
    var placeholder = ""
    var onSubmit: (() -> Void)?

    init(title: String, text: Binding<String>, placeholder: String = "", onSubmit: (() -> Void)? = nil) {
        self.title = title; self.text = text; self.placeholder = placeholder; self.onSubmit = onSubmit
    }
    init(title: String, value: Binding<Int>) { self.title = title; self.value = value }

    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            if !title.isEmpty { Text(title).font(.system(size: 13, weight: .semibold)).foregroundStyle(Theme.dim) }
            Group {
                if let text { TextField(placeholder, text: text).onSubmit { onSubmit?() } }
                else if let value { TextField("", value: value, format: .number.grouping(.never)) }
            }
            .textFieldStyle(.plain).font(.system(size: 16))
            .padding(.horizontal, 10).padding(.vertical, 7)
            .background(RoundedRectangle(cornerRadius: 9, style: .continuous).fill(Theme.ground))
            .overlay(RoundedRectangle(cornerRadius: 9, style: .continuous).stroke(Theme.hairline))
        }
    }
}

/// One cue: a single scannable row, expanded on demand to edit the OSC target and flash colour.
struct CueCard: View {
    @EnvironmentObject var store: AppStore
    @Binding var automation: Automation
    @State private var expanded = false

    private var oscIndex: Int? { automation.actions.firstIndex { $0.kind == .sendOSC } }
    private var flashIndex: Int? { automation.actions.firstIndex { $0.kind == .flashWindow } }

    private func osc<T>(_ kp: WritableKeyPath<AutomationAction, T>, _ fallback: T) -> Binding<T> {
        Binding(get: { oscIndex.map { automation.actions[$0][keyPath: kp] } ?? fallback },
                set: { v in if let i = oscIndex { automation.actions[i][keyPath: kp] = v } })
    }

    private var oscOn: Binding<Bool> {
        Binding(get: { oscIndex != nil }, set: { on in
            if on {
                guard oscIndex == nil else { return }
                var a = AutomationAction(kind: .sendOSC)
                a.oscHost = store.settings.oscDefaultHost; a.oscPort = store.settings.oscDefaultPort
                let slug = automation.trigger.phrase.lowercased().components(separatedBy: CharacterSet.alphanumerics.inverted).filter { !$0.isEmpty }.joined(separator: "-")
                a.oscAddress = "/cue/" + slug
                automation.actions.insert(a, at: 0)
            } else { automation.actions.removeAll { $0.kind == .sendOSC } }
        })
    }

    private var flashOn: Binding<Bool> {
        Binding(get: { flashIndex != nil }, set: { on in
            if on { if flashIndex == nil { automation.actions.append(AutomationAction(kind: .flashWindow)) } }
            else { automation.actions.removeAll { $0.kind == .flashWindow } }
        })
    }

    private var flashHex: Binding<String> {
        Binding(get: { flashIndex.map { automation.actions[$0].flashColorHex } ?? "FFE14D" },
                set: { v in if let i = flashIndex { automation.actions[i].flashColorHex = v } })
    }

    private var connected: Bool { store.inSession || !store.peers.isEmpty }

    private var flashEveryone: Binding<Bool> {
        Binding(get: { flashIndex.map { automation.actions[$0].flashEveryone } ?? true },
                set: { v in if let i = flashIndex { automation.actions[i].flashEveryone = v } })
    }

    private var summary: String {
        var parts: [String] = []
        if let i = oscIndex { let a = automation.actions[i]; parts.append("OSC \(a.oscAddress) → \(a.oscHost):\(a.oscPort)") }
        if let i = flashIndex { parts.append(connected && automation.actions[i].flashEveryone ? "Flash on all Macs" : "Flash") }
        return parts.isEmpty ? "Does nothing: turn on OSC or Flash" : parts.joined(separator: "  ·  ")
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            HStack(spacing: 10) {
                Button { withAnimation(.easeOut(duration: 0.15)) { expanded.toggle() } } label: {
                    Image(systemName: expanded ? "chevron.down" : "chevron.right").font(.system(size: 13, weight: .bold)).frame(width: 18)
                }
                .buttonStyle(.plain).accessibilityLabel(expanded ? "Collapse cue" : "Edit cue")

                VStack(alignment: .leading, spacing: 2) {
                    TextField("Cue word", text: $automation.trigger.phrase)
                        .textFieldStyle(.plain).font(.system(size: 24, weight: .bold))
                        .onChange(of: automation.trigger.phrase) { _, new in automation.name = "Cue: \(new)" }
                    Text(summary).font(.system(size: 13)).foregroundStyle(Theme.dim).lineLimit(1)
                }
                Spacer(minLength: 6)
                if flashIndex != nil { Circle().fill(Color(hex: flashHex.wrappedValue)).frame(width: 14, height: 14).accessibilityHidden(true) }
                Button { store.fire(automation, ActionContext(text: "test", channel: "Test", keyword: automation.trigger.phrase), force: true) } label: {
                    Image(systemName: "play.fill")
                }
                .buttonStyle(QuietButtonStyle(size: 14)).accessibilityLabel("Test cue").help("Fire this cue now")
                Toggle("", isOn: $automation.enabled).labelsHidden().toggleStyle(.switch).tint(Theme.orange)
                    .accessibilityLabel("Cue enabled")
            }

            if let t = store.lastFired[automation.id] {
                Text("Fired \(t.formatted(date: .omitted, time: .standard))")
                    .font(.system(size: 13, weight: .medium).monospacedDigit()).foregroundStyle(Theme.cue)
                    .padding(.leading, 28)
            }

            if expanded {
                VStack(alignment: .leading, spacing: 12) {
                    HStack(spacing: 8) {
                        Button { oscOn.wrappedValue.toggle() } label: { Label("Send OSC", systemImage: oscOn.wrappedValue ? "checkmark.circle.fill" : "circle") }
                            .buttonStyle(QuietButtonStyle(tint: Theme.orange, active: oscOn.wrappedValue, size: 14))
                        Button { flashOn.wrappedValue.toggle() } label: { Label("Flash", systemImage: flashOn.wrappedValue ? "checkmark.circle.fill" : "circle") }
                            .buttonStyle(QuietButtonStyle(tint: Theme.cue, active: flashOn.wrappedValue, size: 14))
                    }

                    if oscOn.wrappedValue {
                        HStack(spacing: 8) {
                            LabeledField(title: "Host", text: osc(\.oscHost, ""))
                            LabeledField(title: "Port", value: osc(\.oscPort, 8000)).frame(width: 84)
                        }
                        LabeledField(title: "OSC address", text: osc(\.oscAddress, "/cue"), placeholder: "/cue/go")
                        LabeledField(title: "Arguments (optional)", text: osc(\.oscArgs, ""), placeholder: "1  0.5  \"text\"  {text}")
                    }

                    if flashOn.wrappedValue {
                        HStack(spacing: 10) {
                            Text("Flash colour").font(.system(size: 13, weight: .semibold)).foregroundStyle(Theme.dim)
                            ForEach(["FFE14D", "FF4FA3", "39FF88", "35D8FF", "FF9F1C", "FFFFFF"], id: \.self) { hex in
                                SwatchButton(hex: hex, selected: flashHex.wrappedValue == hex, size: 22) { flashHex.wrappedValue = hex }
                            }
                        }
                        if connected {
                            Toggle(isOn: flashEveryone) {
                                VStack(alignment: .leading, spacing: 1) {
                                    Text("Flash every Mac in the session").font(.system(size: 14, weight: .semibold))
                                    Text("The Mac that hears the cue word tells the others to flash too. OSC still goes out from this Mac only.")
                                        .font(.system(size: 12)).foregroundStyle(Theme.faint)
                                }
                            }.toggleStyle(.switch).tint(Theme.orange)
                        }
                    }

                    HStack(spacing: 8) {
                        Text("Listens on").font(.system(size: 13, weight: .semibold)).foregroundStyle(Theme.dim)
                        Picker("Channel", selection: $automation.trigger.channelID) {
                            Text("Any channel").tag(UUID?.none)
                            ForEach(store.channels) { Text($0.name).tag(Optional($0.id)) }
                        }.labelsHidden()
                        Spacer(minLength: 0)
                    }

                    if store.inSession {
                        Toggle(isOn: $automation.trigger.includeRemote) {
                            VStack(alignment: .leading, spacing: 1) {
                                Text("Also fire for other Macs' speech").font(.system(size: 14, weight: .semibold))
                                Text("Off: only words heard on this Mac send OSC, so a lighting desk gets one GO, not one per Mac.")
                                    .font(.system(size: 12)).foregroundStyle(Theme.faint)
                            }
                        }.toggleStyle(.switch).tint(Theme.orange)
                    }

                    HStack {
                        Spacer()
                        Button(role: .destructive) { store.automations.removeAll { $0.id == automation.id } } label: { Label("Delete cue", systemImage: "trash") }
                            .buttonStyle(QuietButtonStyle(tint: Theme.danger, size: 14))
                    }
                }
                .padding(.top, 2)
            }
        }
        .padding(14)
        .background(RoundedRectangle(cornerRadius: 16, style: .continuous).fill(Theme.raised))
        .overlay(RoundedRectangle(cornerRadius: 16, style: .continuous).stroke(automation.enabled ? Theme.cue.opacity(0.55) : Theme.hairline, lineWidth: 1.5))
        .opacity(automation.enabled ? 1 : 0.7)
    }
}
