import SwiftUI

struct ContentView: View {
    @EnvironmentObject var store: AppStore

    var body: some View {
        HStack(spacing: 0) {
            SidebarView().frame(width: 300)
            Rectangle().fill(Theme.hairline).frame(width: 1)
            DetailView()
            if store.showCues {
                Rectangle().fill(Theme.hairline).frame(width: 1)
                CuePanel().frame(width: 380)
            }
        }
        .background(Theme.ground)
        .ignoresSafeArea()
        .preferredColorScheme(.dark)
        .overlay(FlashOverlay())
        .background(WindowAccessor { store.mainWindow = $0 })
        .background(translationHost)
        .sheet(item: $store.editor) { target in ChannelEditorView(target: target).id(target.id) }
        .sheet(item: $store.groupEditor) { g in GroupEditorView(group: g) }
        .sheet(isPresented: $store.sessionSheet) { SessionSheet() }
        .animation(.easeOut(duration: 0.2), value: store.showCues)
    }

    @ViewBuilder private var translationHost: some View {
        if #available(macOS 15.0, *) { TranslationHost() }
    }
}

// MARK: - Window access / flash

struct WindowAccessor: NSViewRepresentable {
    var onWindow: (NSWindow?) -> Void
    func makeNSView(context: Context) -> NSView {
        let v = NSView()
        DispatchQueue.main.async { onWindow(v.window) }
        return v
    }
    func updateNSView(_ nsView: NSView, context: Context) {}
}

struct FlashOverlay: View {
    @EnvironmentObject var store: AppStore
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @State private var on = false

    var body: some View {
        Rectangle()
            .fill(store.flashColor.opacity(on ? 0.28 : 0))
            .overlay(Rectangle().strokeBorder(store.flashColor.opacity(on ? 1 : 0), lineWidth: 14))
            .allowsHitTesting(false)
            .ignoresSafeArea()
            .accessibilityHidden(true)
            .onChange(of: store.flashToken) { _, _ in
                Task { @MainActor in
                    for _ in 0..<(reduceMotion ? 1 : 4) {
                        withAnimation(.easeOut(duration: 0.1)) { on = true }
                        try? await Task.sleep(nanoseconds: 190_000_000)
                        withAnimation(.easeIn(duration: 0.1)) { on = false }
                        try? await Task.sleep(nanoseconds: 190_000_000)
                    }
                }
            }
    }
}

// MARK: - Sidebar

struct SidebarView: View {
    @EnvironmentObject var store: AppStore

    private func ordered(_ list: [Channel]) -> [Channel] { list.sorted { ($0.pinned ? 0 : 1, $0.name) < ($1.pinned ? 0 : 1, $1.name) } }

    var body: some View {
        VStack(spacing: 0) {
            VStack(alignment: .leading, spacing: 14) {
                Text("CUECOMM").font(.system(size: 15, weight: .heavy)).tracking(3).foregroundStyle(Theme.text)
                SessionPill()
            }
            .padding(.horizontal, 16).padding(.top, 44).padding(.bottom, 14)

            ScrollView {
                LazyVStack(alignment: .leading, spacing: 4) {
                    AllChannelsRow()

                    let pinned = store.channels.filter(\.pinned)
                    if !pinned.isEmpty {
                        SectionLabel(text: "Pinned", size: 14).padding(.leading, 10).padding(.top, 14)
                        ForEach(ordered(pinned)) { ChannelRow(channel: $0) }
                    }
                    ForEach(store.groups) { g in
                        let members = ordered(store.channels.filter { $0.groupID == g.id && !$0.pinned })
                        if !members.isEmpty || !store.channels.contains(where: { $0.groupID == g.id }) {
                            GroupHeader(group: g).padding(.top, 14)
                            ForEach(members) { ChannelRow(channel: $0) }
                        }
                    }
                    let loose = ordered(store.channels.filter { $0.groupID == nil && !$0.pinned })
                    if !loose.isEmpty {
                        SectionLabel(text: "Channels", size: 14).padding(.leading, 10).padding(.top, 14)
                        ForEach(loose) { ChannelRow(channel: $0) }
                    }
                }
                .padding(.horizontal, 10).padding(.bottom, 12)
            }

            VStack(spacing: 12) {
                HStack(spacing: 8) {
                    Button { store.newChannel() } label: { Label("Channel", systemImage: "plus") }
                    Button { store.groupEditor = ChannelGroup(name: "New Group") } label: { Label("Group", systemImage: "folder.badge.plus") }
                }
                .buttonStyle(QuietButtonStyle(size: 14))
                BrandLogo()
            }
            .padding(14)
            .frame(maxWidth: .infinity)
            .background(Theme.panel)
        }
        .background(Theme.panel)
    }
}

struct BrandLogo: View {
    var body: some View {
        if let url = Bundle.main.url(forResource: "MeltveitLogo", withExtension: "png"), let image = NSImage(contentsOf: url) {
            Image(nsImage: image)
                .resizable().scaledToFit()
                .frame(width: 140)
                .accessibilityLabel("Meltveit Lydproduksjon")
        }
    }
}

struct SessionPill: View {
    @EnvironmentObject var store: AppStore

    private var tint: Color {
        guard store.inSession else { return Theme.faint }
        return store.peers.isEmpty ? Theme.orange : Theme.live
    }
    private var title: String { store.inSession ? store.settings.sessionName : "No session" }
    private var subtitle: String {
        guard store.inSession else { return store.settings.networkEnabled ? "Open sharing on" : "Host or join" }
        return store.peers.isEmpty ? "Waiting for others…" : "\(store.peers.count + 1) Macs online"
    }

    var body: some View {
        Button { store.sessionSheet = true } label: {
            HStack(spacing: 12) {
                Image(systemName: "network").font(.system(size: 18, weight: .semibold)).foregroundStyle(tint)
                VStack(alignment: .leading, spacing: 1) {
                    Text(title).font(.system(size: 16, weight: .bold)).lineLimit(1)
                    Text(subtitle).font(.system(size: 13)).foregroundStyle(tint == Theme.faint ? Theme.dim : tint)
                }
                Spacer(minLength: 0)
                Image(systemName: "chevron.right").font(.system(size: 12, weight: .bold)).foregroundStyle(Theme.faint)
            }
            .padding(12)
            .background(RoundedRectangle(cornerRadius: 12, style: .continuous).fill(Theme.raised))
            .overlay(RoundedRectangle(cornerRadius: 12, style: .continuous).stroke(store.inSession ? tint.opacity(0.7) : Theme.hairline, lineWidth: 1.5))
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityLabel("Session: \(title), \(subtitle)")
    }
}

struct RowBackground: View {
    let selected: Bool
    let tint: Color
    var body: some View {
        RoundedRectangle(cornerRadius: 12, style: .continuous)
            .fill(selected ? tint.opacity(0.16) : .clear)
            .overlay(RoundedRectangle(cornerRadius: 12, style: .continuous).stroke(selected ? tint : .clear, lineWidth: 1.5))
    }
}

struct UnreadBadge: View {
    let count: Int
    let missed: Bool
    var body: some View {
        HStack(spacing: 3) {
            if missed { Image(systemName: "flag.fill").font(.system(size: 12, weight: .bold)) }
            Text("\(count)").font(.system(size: 13, weight: .heavy).monospacedDigit())
        }
        .foregroundStyle(Theme.ink)
        .padding(.horizontal, 8).padding(.vertical, 3)
        .background(Capsule().fill(missed ? Theme.cue : Theme.text))
        .accessibilityLabel(missed ? "\(count) unread, includes a keyword" : "\(count) unread")
    }
}

struct AllChannelsRow: View {
    @EnvironmentObject var store: AppStore
    var body: some View {
        let unread = store.channels.reduce(0) { $0 + (store.unread[$1.id] ?? 0) }
        let missed = store.channels.contains { store.missed.contains($0.id) }
        Button { store.selection = .all } label: {
            HStack(spacing: 12) {
                StripBlock(colors: store.channels.map(\.color))
                VStack(alignment: .leading, spacing: 2) {
                    Text("All Channels").font(.system(size: 17, weight: .bold))
                    Text("\(store.channels.count) channels").font(.system(size: 13)).foregroundStyle(Theme.dim)
                }
                Spacer(minLength: 4)
                if unread > 0 { UnreadBadge(count: unread, missed: missed) }
            }
            .padding(8).background(RowBackground(selected: store.selection == .all, tint: Theme.text))
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
    }
}

struct GroupHeader: View {
    @EnvironmentObject var store: AppStore
    let group: ChannelGroup
    var body: some View {
        HStack(spacing: 6) {
            Button { store.selection = .group(group.id) } label: {
                Text(group.name)
                    .font(.system(size: 14, weight: .semibold))
                    .foregroundStyle(store.selection == .group(group.id) ? Theme.text : Theme.dim)
            }.buttonStyle(.plain)
            if group.isRemote { Image(systemName: "network").font(.system(size: 13)).foregroundStyle(Theme.faint) }
            Spacer()
        }
        .padding(.leading, 10)
        .contextMenu {
            Button("Show Group Transcript") { store.selection = .group(group.id) }
            Button("Mark Group as Read") { store.markRead(.group(group.id)) }
            Button("Clear Group Transcript") { store.clear(.group(group.id)) }
            Divider()
            Button("Edit Group…") { store.groupEditor = group }
            Button("Delete Group", role: .destructive) { store.delete(group: group.id) }
        }
    }
}

struct ChannelRow: View {
    @EnvironmentObject var store: AppStore
    @EnvironmentObject var levels: Levels
    @EnvironmentObject var live: LiveText
    let channel: Channel

    var body: some View {
        let unread = store.unread[channel.id] ?? 0
        let level = levels.values[channel.id] ?? 0
        let speaking = live.partials[channel.id] != nil || level > 0.55
        let selected = store.selection == .channel(channel.id)
        let capturing = store.isListening && channel.enabled && !channel.isRemote

        Button { store.selection = .channel(channel.id) } label: {
            HStack(spacing: 12) {
                HueBlock(color: channel.color, label: channel.initials, active: speaking)
                VStack(alignment: .leading, spacing: 3) {
                    HStack(spacing: 5) {
                        Text(channel.name).font(.system(size: 17, weight: .bold)).lineLimit(1)
                        if channel.pinned { Image(systemName: "pin.fill").font(.system(size: 13, weight: .bold)).foregroundStyle(Theme.orange).accessibilityLabel("Pinned") }
                        if store.isShared(channel) { Image(systemName: "network").font(.system(size: 13)).foregroundStyle(Theme.faint) }
                    }
                    if capturing {
                        SegmentMeter(level: level, color: channel.color)
                    } else {
                        Text(subtitle).font(.system(size: 13)).foregroundStyle(Theme.dim).lineLimit(1)
                    }
                }
                Spacer(minLength: 4)
                if unread > 0 { UnreadBadge(count: unread, missed: store.missed.contains(channel.id)) }
            }
            .padding(8)
            .background(RowBackground(selected: selected, tint: channel.color))
            .opacity(channel.enabled || channel.isRemote ? 1 : 0.72)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .contextMenu {
            Button("Edit Channel…") { store.editor = EditorTarget(channel: channel, isNew: false) }
            Button(channel.pinned ? "Unpin" : "Pin to Top") { var c = channel; c.pinned.toggle(); store.upsert(c) }
            if !channel.isRemote {
                Button(channel.enabled ? "Mute Capture" : "Enable Capture") { var c = channel; c.enabled.toggle(); store.upsert(c) }
                if store.inSession {
                    Button(channel.isPrivate ? "Share with Session" : "Keep Private to This Mac") { var c = channel; c.isPrivate.toggle(); store.upsert(c) }
                } else {
                    Button(channel.shared ? "Stop Sharing on Network" : "Share on Network") { var c = channel; c.shared.toggle(); store.upsert(c) }
                }
                Menu("Move to Group") {
                    Button("No Group") { var c = channel; c.groupID = nil; store.upsert(c) }
                    ForEach(store.groups.filter { !$0.isRemote }) { g in Button(g.name) { var c = channel; c.groupID = g.id; store.upsert(c) } }
                }
            }
            Divider()
            Button("Mark as Read") { store.markRead(.channel(channel.id)) }
            Button("Clear Transcript") { store.clear(.channel(channel.id)) }
            Button(channel.isRemote ? "Remove Network Channel" : "Delete Channel", role: .destructive) { store.delete(channel.id) }
        }
    }

    private var subtitle: String {
        if channel.isRemote { return "From \(channel.ownerName ?? "another Mac")" }
        if !channel.enabled { return "Muted" }
        if let s = store.status[channel.id], store.isListening { return s }
        return "Idle"
    }
}

// MARK: - Detail

struct DetailView: View {
    @EnvironmentObject var store: AppStore

    var body: some View {
        VStack(spacing: 0) {
            HeaderBar()
            if let problem = store.permissionProblem { PermissionBanner(text: problem) }
            if let cue = store.lastCue {
                CueBanner(cue: cue)
                    .padding(.horizontal, 24).padding(.bottom, 10)      // pushes the transcript down: never covers words
                    .transition(.move(edge: .top).combined(with: .opacity))
            }
            TranscriptView()
                .animation(.easeOut(duration: 0.2), value: store.lastCue)
            Rectangle().fill(Theme.hairline).frame(height: 1)
            ComposerView()
        }
        .background(Theme.ground)
        .animation(.easeOut(duration: 0.2), value: store.lastCue)
    }
}

struct HeaderBar: View {
    @EnvironmentObject var store: AppStore

    private var tint: Color {
        if case .channel(let id) = store.selection, let c = store.channel(id) { return c.color }
        return Theme.text
    }
    private var subtitle: String {
        let listening = store.channels.filter { $0.enabled && !$0.isRemote }.count
        switch store.selection {
        case .channel(let id):
            guard let c = store.channel(id) else { return "" }
            return c.isRemote ? "Shared by \(c.ownerName ?? "another Mac")" : (store.status[id] ?? (c.enabled ? "Ready to listen" : "Muted"))
        default:
            return store.isListening ? "Listening on \(listening) channels" : "\(store.channelIDs(for: store.selection).count) channels"
        }
    }

    var body: some View {
        HStack(spacing: 14) {
            VStack(alignment: .leading, spacing: 2) {
                Text(store.title(for: store.selection)).font(.system(size: 30, weight: .bold)).foregroundStyle(tint).lineLimit(1)
                Text(subtitle).font(.system(size: 15)).foregroundStyle(Theme.dim).lineLimit(1)
            }
            Spacer(minLength: 12)

            Button { store.toggleListening() } label: {
                HStack(spacing: 8) {
                    Image(systemName: store.isListening ? "stop.fill" : "mic.fill")
                    Text(store.isListening ? "LIVE · Stop" : "Listen")
                }
            }
            .buttonStyle(ActionButtonStyle(fill: store.isListening ? Theme.live : Theme.orange, size: 17))
            .help("Start or stop listening on all enabled channels (⌘L)")

            Button { store.showCues.toggle() } label: { Label("Cues", systemImage: "bolt.fill") }
                .buttonStyle(QuietButtonStyle(tint: Theme.cue, active: store.showCues))
                .help("Cue words: spoken triggers that send OSC and flash the screen (⌘U)")

            Menu {
                Button("Jump to Current") { store.scrollRequest = ScrollRequest(target: .current) }
                Button("Mark as Read") { store.markRead(store.selection) }
                Button("Clear Transcript") { store.clear(store.selection) }
                Divider()
                Toggle("Filter Offensive Language", isOn: $store.settings.filterOffensive)
                Button("Larger Text") { store.settings.fontSize = min(48, store.settings.fontSize + 2) }
                Button("Smaller Text") { store.settings.fontSize = max(14, store.settings.fontSize - 2) }
                Divider()
                Button("Export as Text…") { store.exportTranscript(csv: false) }
                Button("Export as CSV…") { store.exportTranscript(csv: true) }
                Divider()
                Button("Advanced Automations…") {
                    store.settingsTab = "automations"
                    NSApp.sendAction(Selector(("showSettingsWindow:")), to: nil, from: nil)
                }
                Button("Settings…") {
                    store.settingsTab = "general"
                    NSApp.sendAction(Selector(("showSettingsWindow:")), to: nil, from: nil)
                }
            } label: {
                Image(systemName: "ellipsis").font(.system(size: 16, weight: .bold)).frame(width: 22, height: 22)
            }
            .menuStyle(.borderlessButton).menuIndicator(.hidden)
            .padding(.horizontal, 8).padding(.vertical, 6)
            .background(RoundedRectangle(cornerRadius: 10).fill(Theme.raised))
            .overlay(RoundedRectangle(cornerRadius: 10).stroke(Theme.hairline))
            .fixedSize()
            .accessibilityLabel("More")
        }
        .padding(.horizontal, 24).padding(.top, 22).padding(.bottom, 14)
    }
}

struct CueBanner: View {
    let cue: CueEvent
    var body: some View {
        HStack(spacing: 12) {
            Image(systemName: "bolt.fill").font(.system(size: 22, weight: .bold))
            VStack(alignment: .leading, spacing: 1) {
                Text("CUE · \(cue.title.uppercased())").font(.system(size: 22, weight: .heavy))
                if !cue.detail.isEmpty { Text(cue.detail).font(.system(size: 15, weight: .semibold)) }
            }
            Spacer()
        }
        .foregroundStyle(Theme.ink)
        .padding(.horizontal, 18).padding(.vertical, 12)
        .background(RoundedRectangle(cornerRadius: 14, style: .continuous).fill(Color(hex: cue.colorHex)))
        .shadow(color: .black.opacity(0.5), radius: 12, y: 6)
        .accessibilityElement(children: .combine)
    }
}

struct PermissionBanner: View {
    let text: String
    var body: some View {
        HStack(spacing: 12) {
            Image(systemName: "exclamationmark.triangle.fill")
            Text(text).font(.system(size: 16, weight: .semibold))
            Spacer()
            Button("Open Privacy Settings") {
                NSWorkspace.shared.open(URL(string: "x-apple.systempreferences:com.apple.preference.security?Privacy")!)
            }.buttonStyle(ActionButtonStyle(fill: Theme.ink, ink: Theme.cue, size: 14))
        }
        .foregroundStyle(Theme.ink)
        .padding(.horizontal, 24).padding(.vertical, 10)
        .background(Theme.cue)
    }
}

// MARK: - Transcript

struct TranscriptView: View {
    @EnvironmentObject var store: AppStore
    @EnvironmentObject var live: LiveText
    @State private var sentinelVisible = true

    var body: some View {
        let msgs = store.visibleMessages()
        let ids = store.channelIDs(for: store.selection)
        ScrollViewReader { proxy in
            ScrollView {
                LazyVStack(alignment: .leading, spacing: 14) {
                    if msgs.isEmpty && ids.allSatisfy({ live.partials[$0] == nil }) { EmptyState() }
                    ForEach(msgs) { MessageRow(message: $0) }
                    ForEach(ids, id: \.self) { id in
                        if let text = live.partials[id], let ch = store.channel(id) { PartialRow(channel: ch, text: text) }
                    }
                    Color.clear.frame(height: 1).id("end")
                        .onAppear { sentinelVisible = true; store.atBottom = true; store.markRead(store.selection) }
                        .onDisappear {
                            sentinelVisible = false
                            Task { @MainActor in
                                try? await Task.sleep(nanoseconds: 300_000_000)
                                if !sentinelVisible { store.atBottom = false }
                            }
                        }
                }
                .padding(.horizontal, 24).padding(.vertical, 16)
            }
            .onChange(of: store.scrollRequest) { _, req in
                guard let req else { return }
                switch req.target {
                case .current: proxy.scrollTo("end", anchor: .bottom)
                case .top: if let first = msgs.first { proxy.scrollTo(first.id, anchor: .top) }
                }
            }
            .onChange(of: live.partials) { _, _ in if store.atBottom { proxy.scrollTo("end", anchor: .bottom) } }
            .onChange(of: store.selection) { _, _ in DispatchQueue.main.async { proxy.scrollTo("end", anchor: .bottom) } }
            .overlay(alignment: .bottomTrailing) {
                if !store.atBottom {
                    Button { proxy.scrollTo("end", anchor: .bottom) } label: { Label("Jump to current", systemImage: "arrow.down") }
                        .buttonStyle(ActionButtonStyle(fill: Theme.orange, size: 15))
                        .padding(18)
                }
            }
        }
    }
}

struct EmptyState: View {
    @EnvironmentObject var store: AppStore
    var body: some View {
        VStack(spacing: 14) {
            StripBlock(colors: store.channels.map(\.color), size: 72)
            Text("No transcript yet").font(.system(size: 26, weight: .bold))
            Text("Press Listen to turn speech on your channels into words,\nor type a message below.")
                .font(.system(size: 17)).foregroundStyle(Theme.dim).multilineTextAlignment(.center)
        }
        .frame(maxWidth: .infinity).padding(.top, 90)
    }
}

struct MessageRow: View {
    @EnvironmentObject var store: AppStore
    let message: Message

    var body: some View {
        let ch = store.channel(message.channelID)
        let color = ch?.color ?? Theme.faint
        let terms = ch.map(store.highlightTerms) ?? []
        let size = store.settings.fontSize

        HStack(alignment: .top, spacing: 14) {
            if store.settings.showTimestamps {
                Text(message.date.formatted(date: .omitted, time: .standard))
                    .font(.system(size: 14, weight: .bold).monospacedDigit())
                    .foregroundStyle(color)
                    .frame(width: 88, height: 28)
                    .background(RoundedRectangle(cornerRadius: 8, style: .continuous).fill(color.opacity(0.18)))
                    .padding(.top, 8)
            }
            VStack(alignment: .leading, spacing: 6) {
                HStack(spacing: 8) {
                    HueChip(name: ch?.name ?? "Channel", color: color)
                    if message.typed {
                        Label(message.speaker, systemImage: "keyboard").font(.system(size: 13, weight: .semibold)).foregroundStyle(Theme.dim)
                    }
                    if message.keywordHit {
                        Label("KEYWORD", systemImage: "flag.fill").font(.system(size: 12, weight: .heavy)).foregroundStyle(Theme.cue)
                    }
                }
                Text(attributed(message.text, terms))
                    .font(.system(size: size, weight: .medium))
                    .lineSpacing(size * 0.14)
                    .foregroundStyle(Theme.text)
                    .textSelection(.enabled)
                if let t = message.translation {
                    Label { Text(store.settings.filterOffensive ? Offensive.mask(t) : t) } icon: { Image(systemName: "globe") }
                        .font(.system(size: size * 0.82)).foregroundStyle(Theme.dim)
                }
            }
            .padding(.vertical, 8).padding(.horizontal, 12)
            .background(RoundedRectangle(cornerRadius: 14, style: .continuous).fill(message.keywordHit ? Theme.cue.opacity(0.11) : .clear))
            .overlay(RoundedRectangle(cornerRadius: 14, style: .continuous).stroke(message.keywordHit ? Theme.cue.opacity(0.8) : .clear, lineWidth: 1.5))
            Spacer(minLength: 0)
        }
        .accessibilityElement(children: .combine)
    }

    private func attributed(_ raw: String, _ terms: [String]) -> AttributedString {
        let text = store.settings.filterOffensive ? Offensive.mask(raw) : raw
        var a = AttributedString(text)
        for t in terms {
            var range = text.startIndex..<text.endIndex
            while let r = text.range(of: t, options: [.caseInsensitive, .diacriticInsensitive], range: range) {
                if let ar = Range(r, in: a) { a[ar].backgroundColor = Theme.cue; a[ar].foregroundColor = Theme.ink }
                range = r.upperBound..<text.endIndex
            }
        }
        return a
    }
}

struct PartialRow: View {
    @EnvironmentObject var store: AppStore
    let channel: Channel
    let text: String

    var body: some View {
        HStack(alignment: .top, spacing: 14) {
            if store.settings.showTimestamps {
                Image(systemName: "waveform").font(.system(size: 14, weight: .bold)).foregroundStyle(Theme.ink)
                    .frame(width: 88, height: 28)
                    .background(RoundedRectangle(cornerRadius: 8, style: .continuous).fill(channel.color))
                    .padding(.top, 8)
            }
            VStack(alignment: .leading, spacing: 6) {
                HueChip(name: channel.name, color: channel.color)
                Text(store.settings.filterOffensive ? Offensive.mask(text) : text)
                    .font(.system(size: store.settings.fontSize, weight: .medium))
                    .foregroundStyle(Theme.dim)
            }
            Spacer(minLength: 0)
        }
        .accessibilityLabel("\(channel.name), speaking: \(text)")
    }
}

// MARK: - Composer

struct ComposerView: View {
    @EnvironmentObject var store: AppStore
    @State private var text = ""

    private var target: UUID? {
        if case .channel(let id) = store.selection { return id }
        let ids = store.channelIDs(for: store.selection)
        if let t = store.composeTarget, ids.contains(t) { return t }
        return ids.first
    }

    var body: some View {
        HStack(spacing: 10) {
            if case .channel = store.selection {} else {
                Picker("To", selection: Binding(get: { target }, set: { store.composeTarget = $0 })) {
                    ForEach(store.channelIDs(for: store.selection), id: \.self) { id in Text(store.channel(id)?.name ?? "").tag(Optional(id)) }
                }
                .labelsHidden().frame(maxWidth: 180)
            }
            TextField(placeholder, text: $text)
                .textFieldStyle(.plain)
                .font(.system(size: 18))
                .padding(.horizontal, 14).padding(.vertical, 11)
                .background(RoundedRectangle(cornerRadius: 12, style: .continuous).fill(Theme.raised))
                .overlay(RoundedRectangle(cornerRadius: 12, style: .continuous).stroke(Theme.hairline))
                .onSubmit(send)
            Button("Send", action: send)
                .buttonStyle(ActionButtonStyle(size: 16))
                .keyboardShortcut(.defaultAction)
                .disabled(text.trimmingCharacters(in: .whitespaces).isEmpty || target == nil)
        }
        .padding(.horizontal, 24).padding(.vertical, 14)
        .background(Theme.panel)
    }

    private var placeholder: String {
        "Type a reply in " + (target.flatMap { store.channel($0)?.name } ?? "a channel") + "…"
    }

    private func send() {
        guard let t = target else { return }
        store.send(text, to: t)
        text = ""
    }
}
