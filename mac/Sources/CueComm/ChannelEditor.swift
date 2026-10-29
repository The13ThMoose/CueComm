import SwiftUI
import AVFoundation
import Speech

private struct EditorCard<Content: View>: View {
    let title: String
    @ViewBuilder var content: Content
    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            SectionLabel(text: title)
            content
        }
        .padding(16)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(RoundedRectangle(cornerRadius: 14, style: .continuous).fill(Theme.raised))
    }
}

struct ChannelEditorView: View {
    @EnvironmentObject var store: AppStore
    @Environment(\.dismiss) private var dismiss
    @StateObject private var monitor = InputMonitor()
    let target: EditorTarget
    @State private var ch: Channel
    @State private var keywords: String
    @State private var newGroupName = ""

    init(target: EditorTarget) {
        self.target = target
        _ch = State(initialValue: target.channel)
        _keywords = State(initialValue: target.channel.keywords.joined(separator: ", "))
    }

    private var device: AudioInputDevice? {
        if let uid = ch.deviceUID { return store.devices.first { $0.uid == uid } }
        return AudioDevices.defaultInputID().flatMap { id in store.devices.first { $0.id == id } }
    }

    private static let locales: [(id: String, name: String)] = {
        SFSpeechRecognizer.supportedLocales().map { ($0.identifier, Locale.current.localizedString(forIdentifier: $0.identifier) ?? $0.identifier) }
            .sorted { $0.name < $1.name }
    }()

    var body: some View {
        VStack(spacing: 0) {
            HStack(spacing: 14) {
                HueBlock(color: ch.color, label: ch.initials, active: true, size: 52)
                Text(target.isNew ? "New channel" : "Edit channel").font(.system(size: 28, weight: .bold))
                Spacer()
            }
            .padding(.horizontal, 24).padding(.top, 22).padding(.bottom, 14)

            ScrollView {
                VStack(spacing: 14) {
                    nameCard
                    if !ch.isRemote { inputCard; languageCard } else { remoteCard }
                    keywordCard
                    if !ch.isRemote { networkCard }
                }
                .padding(.horizontal, 24).padding(.bottom, 20)
            }

            Rectangle().fill(Theme.hairline).frame(height: 1)
            HStack {
                if !target.isNew {
                    Button("Delete", role: .destructive) { store.delete(ch.id); dismiss() }
                        .buttonStyle(QuietButtonStyle(tint: Theme.danger))
                }
                Spacer()
                Button("Cancel") { dismiss() }.buttonStyle(QuietButtonStyle()).keyboardShortcut(.cancelAction)
                Button(target.isNew ? "Add Channel" : "Save") { save() }
                    .buttonStyle(ActionButtonStyle(size: 16)).keyboardShortcut(.defaultAction)
            }
            .padding(18)
        }
        .frame(width: 600, height: 640)
        .background(Theme.panel)
        .preferredColorScheme(.dark)
        .onAppear { startMonitor() }
        .onChange(of: ch.deviceUID) { _, _ in
            if let d = device { ch.inputChannel = min(ch.inputChannel, d.inputChannels - 1) }
            startMonitor()
        }
        .onDisappear { monitor.stop() }
    }

    // MARK: Cards

    private var nameCard: some View {
        EditorCard(title: "Name and colour") {
            LabeledField(title: "Channel name", text: $ch.name)
            HStack(spacing: 12) {
                ForEach(Palette.hexes, id: \.self) { hex in
                    SwatchButton(hex: hex, selected: ch.colorHex.uppercased() == hex) { ch.colorHex = hex }
                }
            }
            .padding(.vertical, 4)
            HStack(alignment: .center, spacing: 8) {
                Text("Group").font(.system(size: 13, weight: .semibold)).foregroundStyle(Theme.dim)
                Picker("Group", selection: $ch.groupID) {
                    Text("No group").tag(UUID?.none)
                    ForEach(store.groups.filter { !$0.isRemote || ch.isRemote }) { Text($0.name).tag(Optional($0.id)) }
                }
                .labelsHidden().frame(maxWidth: 160)
                LabeledField(title: "", text: $newGroupName, placeholder: "New group", onSubmit: nil)
                Button("Add") {
                    let g = ChannelGroup(name: newGroupName)
                    store.upsert(g); ch.groupID = g.id; newGroupName = ""
                }.buttonStyle(QuietButtonStyle(size: 14)).disabled(newGroupName.trimmingCharacters(in: .whitespaces).isEmpty)
            }
            Button { ch.pinned.toggle() } label: {
                Label(ch.pinned ? "Pinned to the top of the list" : "Pin to the top of the list", systemImage: ch.pinned ? "pin.fill" : "pin")
            }
            .buttonStyle(QuietButtonStyle(tint: Theme.orange, active: ch.pinned, size: 15))
            .accessibilityValue(ch.pinned ? "On" : "Off")
        }
    }

    private var inputCard: some View {
        EditorCard(title: "Soundcard input") {
            Picker("Soundcard", selection: $ch.deviceUID) {
                Text("System default" + (AudioDevices.defaultInputID().flatMap { id in store.devices.first { $0.id == id }?.name }.map { " (\($0))" } ?? "")).tag(String?.none)
                ForEach(store.devices) { Text("\($0.name)  ·  \($0.inputChannels) in").tag(Optional($0.uid)) }
            }
            if let device {
                Text("Pick the input this channel listens to. Speak or play into an input and watch its meter move.")
                    .font(.system(size: 14)).foregroundStyle(Theme.dim)
                ScrollView {
                    VStack(spacing: 4) {
                        ForEach(0..<device.inputChannels, id: \.self) { i in
                            InputRow(index: i, name: device.name(ofInput: i), level: monitor.levels[safe: i] ?? 0,
                                     color: ch.color, selected: ch.inputChannel == i) { ch.inputChannel = i }
                        }
                    }
                }
                .frame(maxHeight: 250)
                if let err = monitor.error { Text(err).font(.system(size: 13)).foregroundStyle(Theme.danger) }
            } else {
                Text("No input device found.").font(.system(size: 15)).foregroundStyle(Theme.danger)
            }
            Toggle("Capture this channel when listening", isOn: $ch.enabled).toggleStyle(.switch).tint(Theme.orange)
        }
    }

    private var languageCard: some View {
        EditorCard(title: "Language") {
            Picker("Spoken language", selection: $ch.localeID) {
                ForEach(Self.locales, id: \.id) { Text($0.name).tag($0.id) }
            }
            Toggle("Translate to \(Locale.current.localizedString(forIdentifier: store.settings.preferredLanguage) ?? store.settings.preferredLanguage)", isOn: $ch.translate)
                .toggleStyle(.switch).tint(Theme.orange)
            Toggle("Use on-device recognition only", isOn: $ch.onDeviceOnly).toggleStyle(.switch).tint(Theme.orange)
        }
    }

    private var remoteCard: some View {
        EditorCard(title: "Network channel") {
            Text("Shared by \(ch.ownerName ?? "another Mac"). Messages you type here go back to everyone sharing it.")
                .font(.system(size: 15)).foregroundStyle(Theme.dim)
            Toggle("Translate incoming messages", isOn: $ch.translate).toggleStyle(.switch).tint(Theme.orange)
        }
    }

    private var keywordCard: some View {
        EditorCard(title: "Keywords") {
            LabeledField(title: "Words to flag on this channel (comma separated)", text: $keywords)
            Text("Matches are marked yellow, and the channel badge turns yellow with a flag if one arrives while you are looking elsewhere. To send OSC when a word is said, add it under Cues.")
                .font(.system(size: 13)).foregroundStyle(Theme.faint)
        }
    }

    private var networkCard: some View {
        EditorCard(title: "Network") {
            if store.inSession {
                Toggle("Keep this channel private to this Mac", isOn: $ch.isPrivate).toggleStyle(.switch).tint(Theme.orange)
                Text("Otherwise it is shared with session “\(store.settings.sessionName)”.").font(.system(size: 13)).foregroundStyle(Theme.faint)
            } else {
                Toggle("Share this channel on the local network", isOn: $ch.shared).toggleStyle(.switch).tint(Theme.orange)
                if !store.settings.networkEnabled && ch.shared {
                    Text("Open sharing is off. Turn it on, or start a session, from the Session pill.").font(.system(size: 13)).foregroundStyle(Theme.cue)
                }
            }
        }
    }

    // MARK: Actions

    private func save() {
        ch.keywords = AppStore.split(keywords)
        if ch.name.trimmingCharacters(in: .whitespaces).isEmpty { ch.name = "Channel" }
        if let d = device { ch.inputChannel = min(ch.inputChannel, d.inputChannels - 1) }
        store.upsert(ch)
        if target.isNew { store.selection = .channel(ch.id) }
        dismiss()
    }

    private func startMonitor() {
        guard !ch.isRemote else { return }
        let id = device?.id
        AVCaptureDevice.requestAccess(for: .audio) { ok in
            DispatchQueue.main.async {
                if ok { monitor.start(deviceID: id) } else { monitor.error = "Microphone access is off, so inputs cannot be metered." }
            }
        }
    }
}

struct InputRow: View {
    let index: Int
    let name: String
    let level: Float
    let color: Color
    let selected: Bool
    let choose: () -> Void

    var body: some View {
        Button(action: choose) {
            HStack(spacing: 12) {
                Image(systemName: selected ? "largecircle.fill.circle" : "circle")
                    .font(.system(size: 17)).foregroundStyle(selected ? color : Theme.faint)
                Text(name).font(.system(size: 16, weight: selected ? .bold : .regular)).lineLimit(1)
                Spacer(minLength: 8)
                SegmentMeter(level: level, color: color, segments: 16, height: 8).frame(width: 170)
            }
            .padding(.horizontal, 10).padding(.vertical, 8)
            .background(RoundedRectangle(cornerRadius: 10, style: .continuous).fill(selected ? color.opacity(0.18) : .clear))
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityLabel("Input \(index + 1), \(name)\(selected ? ", selected" : "")")
    }
}

struct GroupEditorView: View {
    @EnvironmentObject var store: AppStore
    @Environment(\.dismiss) private var dismiss
    @State private var group: ChannelGroup

    init(group: ChannelGroup) { _group = State(initialValue: group) }

    private var exists: Bool { store.groups.contains { $0.id == group.id } }

    var body: some View {
        VStack(alignment: .leading, spacing: 16) {
            Text(exists ? "Edit group" : "New group").font(.system(size: 26, weight: .bold))
            LabeledField(title: "Group name", text: $group.name)
            if !store.inSession {
                Toggle("Share every channel in this group on the network", isOn: $group.shared)
                    .toggleStyle(.switch).tint(Theme.orange).disabled(group.isRemote)
            }
            HStack {
                if exists && !group.isRemote {
                    Button("Delete Group", role: .destructive) { store.delete(group: group.id); dismiss() }.buttonStyle(QuietButtonStyle(tint: Theme.danger))
                }
                Spacer()
                Button("Cancel") { dismiss() }.buttonStyle(QuietButtonStyle()).keyboardShortcut(.cancelAction)
                Button("Save") {
                    if group.name.trimmingCharacters(in: .whitespaces).isEmpty { group.name = "Group" }
                    store.upsert(group); dismiss()
                }.buttonStyle(ActionButtonStyle(size: 16)).keyboardShortcut(.defaultAction)
            }
        }
        .padding(24).frame(width: 440)
        .background(Theme.panel).preferredColorScheme(.dark)
    }
}
