import SwiftUI

/// Host a named session, or join one found on the network. Everyone in a session sees the same
/// channels and transcript, except channels marked private.
struct SessionSheet: View {
    @EnvironmentObject var store: AppStore
    @Environment(\.dismiss) private var dismiss
    @State private var name = ""

    var body: some View {
        VStack(alignment: .leading, spacing: 18) {
            HStack {
                Text("Session").font(.system(size: 30, weight: .bold))
                Spacer()
                if store.inSession {
                    Button("Done") { dismiss() }.buttonStyle(ActionButtonStyle(size: 16)).keyboardShortcut(.defaultAction)
                } else {
                    Button("Done") { dismiss() }.buttonStyle(QuietButtonStyle(size: 16)).keyboardShortcut(.cancelAction)
                }
            }

            ScrollViewReader { proxy in
                ScrollView {
                    VStack(alignment: .leading, spacing: 22) {
                        if store.inSession {
                            current
                            Rectangle().fill(Theme.hairline).frame(height: 1)
                            JoinByAddressSection().id("address")
                            Rectangle().fill(Theme.hairline).frame(height: 1)
                            ShareAddressSection()
                        } else {
                            start
                            join
                            Rectangle().fill(Theme.hairline).frame(height: 1)
                            JoinByAddressSection().id("address")
                        }

                        Rectangle().fill(Theme.hairline).frame(height: 1)

                        thisMac
                    }
                    .padding(.trailing, 14)
                }
                .onAppear {
                    if ProcessInfo.processInfo.environment["CUECOMM_DEMO"] == "manual" { proxy.scrollTo("address", anchor: .top) }
                }
            }
        }
        .padding(28)
        .frame(width: 620, height: 680)
        .background(Theme.panel)
        .preferredColorScheme(.dark)
    }

    private var thisMac: some View {
            VStack(alignment: .leading, spacing: 10) {
                SectionLabel(text: "This Mac")
                LabeledField(title: "Name others see", text: $store.settings.displayName)
                Toggle(isOn: $store.settings.networkEnabled) {
                    VStack(alignment: .leading, spacing: 2) {
                        Text("Open sharing (no session)").font(.system(size: 16, weight: .semibold))
                        Text("Share only the channels you flag with every Mac that has open sharing on.")
                            .font(.system(size: 13)).foregroundStyle(Theme.dim)
                    }
                }
                .toggleStyle(.switch).tint(Theme.orange).disabled(store.inSession)
            }
    }

    // MARK: Current session

    /// An address is being dialled and has not failed, so "waiting" would be the wrong thing to say.
    private var linking: Bool {
        store.settings.manualPeers.contains { key in
            if case .failed = store.manualStatus[key] ?? .connecting { return false }
            return true
        }
    }

    private var current: some View {
        VStack(alignment: .leading, spacing: 16) {
            VStack(alignment: .leading, spacing: 8) {
                Text(store.settings.sessionName).font(.system(size: 34, weight: .heavy))
                HStack(spacing: 8) {
                    Circle().fill(store.peers.isEmpty ? Theme.orange : Theme.live).frame(width: 12, height: 12)
                    Text(store.peers.isEmpty ? (linking ? "Connecting…" : "Waiting for other Macs to join…") : "Live on the network")
                        .font(.system(size: 16, weight: .semibold))
                        .foregroundStyle(store.peers.isEmpty ? Theme.orange : Theme.live)
                }
            }

            VStack(alignment: .leading, spacing: 8) {
                SectionLabel(text: "In this session")
                MemberRow(name: store.settings.displayName + " (this Mac)", online: true)
                ForEach(store.peers, id: \.self) { MemberRow(name: $0, online: true) }
            }

            Text("Every channel is shared with the session unless you mark it private in its menu. Late joiners get the recent transcript.")
                .font(.system(size: 14)).foregroundStyle(Theme.dim)

            Button { store.leaveSession() } label: { Label("Leave session", systemImage: "rectangle.portrait.and.arrow.right") }
                .buttonStyle(QuietButtonStyle(tint: Theme.danger))
        }
    }

    // MARK: Start / join

    private var start: some View {
        VStack(alignment: .leading, spacing: 10) {
            SectionLabel(text: "Host a session")
            HStack(spacing: 8) {
                LabeledField(title: "Session name", text: $name, placeholder: "e.g. Sunday Service", onSubmit: { store.startSession(name) })
                Button("Start") { store.startSession(name) }
                    .buttonStyle(ActionButtonStyle(size: 16))
                    .keyboardShortcut(.defaultAction)
                    .disabled(name.trimmingCharacters(in: .whitespaces).isEmpty)
                    .padding(.top, 19)
            }
        }
    }

    private var join: some View {
        VStack(alignment: .leading, spacing: 10) {
            SectionLabel(text: "Join a session on this network")
            if store.sessions.isEmpty {
                HStack(spacing: 10) {
                    ProgressView().controlSize(.small)
                    Text("Looking for sessions…").font(.system(size: 15)).foregroundStyle(Theme.dim)
                }
                .padding(.vertical, 6)
            }
            ForEach(store.sessions) { s in
                HStack(spacing: 12) {
                    Circle().fill(Theme.live).frame(width: 11, height: 11)
                    VStack(alignment: .leading, spacing: 1) {
                        Text(s.name).font(.system(size: 18, weight: .bold))
                        Text(s.members.joined(separator: ", ")).font(.system(size: 13)).foregroundStyle(Theme.dim).lineLimit(1)
                    }
                    Spacer()
                    Button("Join") { store.joinSession(s.name) }.buttonStyle(ActionButtonStyle(fill: Theme.live, size: 15))
                }
                .padding(12)
                .background(RoundedRectangle(cornerRadius: 12, style: .continuous).fill(Theme.raised))
            }
        }
    }
}

/// Join the session of a Mac on another network or subnet by typing its address. Bonjour cannot cross a
/// subnet, so the address is asked which session it is in and this Mac joins that session at once.
struct JoinByAddressSection: View {
    @EnvironmentObject var store: AppStore
    @State private var address = ""

    private var joining: Bool { if case .joining = store.addressJoin { return true } else { return false } }

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            SectionLabel(text: store.inSession ? "Add a Mac by address" : "Join by IP address")
            Text(store.inSession
                 ? "A Mac on another network or subnet, in this session. Type its address to connect to it."
                 : "For a Mac on another network or subnet. Type the address of any Mac that is hosting a session and you join that session straight away.")
                .font(.system(size: 14)).foregroundStyle(Theme.dim)

            HStack(alignment: .bottom, spacing: 8) {
                LabeledField(title: "Address", text: $address, placeholder: "203.0.113.5 or 203.0.113.5:47800", onSubmit: go)
                Button(action: go) { Text(store.inSession ? "Connect" : "Join") }
                    .buttonStyle(ActionButtonStyle(fill: Theme.live, size: 16))
                    .disabled(address.trimmingCharacters(in: .whitespaces).isEmpty || joining)
            }

            switch store.addressJoin {
            case .idle: EmptyView()
            case .joining(let key):
                HStack(spacing: 8) {
                    ProgressView().controlSize(.small)
                    Text("Joining \(key)…").font(.system(size: 14, weight: .semibold)).foregroundStyle(Theme.dim)
                }
            case .failed(let why):
                Label(why, systemImage: "exclamationmark.triangle.fill")
                    .font(.system(size: 14, weight: .semibold)).foregroundStyle(Theme.danger)
                    .fixedSize(horizontal: false, vertical: true)
            }

            if store.inSession {
                ForEach(store.settings.manualPeers, id: \.self) { ManualPeerRow(key: $0) }
            }
        }
        .onChange(of: store.addressJoin) { _, new in if new == .idle { address = "" } }
        .onAppear {
            if let a = ProcessInfo.processInfo.environment["CUECOMM_DEMO_ADDRESS"] { address = a }
        }
    }

    private func go() {
        let t = address.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !t.isEmpty, !joining else { return }
        store.joinByAddress(t)
    }
}

/// What to tell a Mac on another network so it can join this session: this Mac's addresses and listening port.
struct ShareAddressSection: View {
    @EnvironmentObject var store: AppStore
    @State private var problem: String?
    @State private var portText = ""
    @State private var addresses: [LocalAddress.Entry] = []
    @State private var copied: String?

    private var port: Int { store.activePort ?? store.settings.listenPort }

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            SectionLabel(text: "Others can join this session at")
            if addresses.isEmpty {
                Text("No network address found. Connect to a network first.").font(.system(size: 14)).foregroundStyle(Theme.faint)
            }
            ForEach(addresses) { a in
                let full = a.ip + ":" + String(port)
                HStack(spacing: 10) {
                    Text(full).font(.system(size: 17, weight: .bold, design: .monospaced))
                    Text(a.kind).font(.system(size: 13)).foregroundStyle(Theme.dim)
                    Spacer()
                    Button {
                        NSPasteboard.general.clearContents(); NSPasteboard.general.setString(full, forType: .string)
                        copied = full
                    } label: { Label(copied == full ? "Copied" : "Copy", systemImage: copied == full ? "checkmark" : "doc.on.doc") }
                    .buttonStyle(QuietButtonStyle(size: 13))
                }
                .padding(.horizontal, 12).padding(.vertical, 8)
                .background(RoundedRectangle(cornerRadius: 10, style: .continuous).fill(Theme.raised))
            }
            HStack(spacing: 8) {
                Text("Listening port").font(.system(size: 13, weight: .semibold)).foregroundStyle(Theme.dim)
                TextField("", text: $portText)
                    .textFieldStyle(.plain).font(.system(size: 15, design: .monospaced))
                    .frame(width: 64)
                    .padding(.horizontal, 8).padding(.vertical, 5)
                    .background(RoundedRectangle(cornerRadius: 8, style: .continuous).fill(Theme.ground))
                    .overlay(RoundedRectangle(cornerRadius: 8, style: .continuous).stroke(Theme.hairline))
                    .onSubmit(applyPort)
                if portText != String(store.settings.listenPort) {
                    Button("Apply", action: applyPort).buttonStyle(QuietButtonStyle(size: 13))
                }
                Spacer()
            }
            if let active = store.activePort, active != store.settings.listenPort {
                Label("Port \(String(store.settings.listenPort)) is busy, so this Mac is listening on \(String(active)). Pick a free port.", systemImage: "exclamationmark.triangle.fill")
                    .font(.system(size: 13, weight: .semibold)).foregroundStyle(Theme.orange)
            }
            if let problem {
                Label(problem, systemImage: "exclamationmark.triangle.fill").font(.system(size: 13, weight: .semibold)).foregroundStyle(Theme.danger)
            }
            Text("They type this under Join by IP address. Over the internet, forward TCP port \(String(port)) on this Mac's router, or put both Macs on a VPN such as Tailscale and use its address. Connections are not encrypted, so use a VPN on networks you do not trust. Anyone who can reach the port can see the session name.")
                .font(.system(size: 13)).foregroundStyle(Theme.faint)
        }
        .onAppear { addresses = LocalAddress.ipv4(); portText = String(store.settings.listenPort) }
        .onChange(of: store.settings.listenPort) { _, new in portText = String(new) }
    }

    private func applyPort() {
        guard let p = Int(portText.trimmingCharacters(in: .whitespaces)), (1024...65535).contains(p) else {
            problem = "Use a port between 1024 and 65535."
            portText = String(store.settings.listenPort)
            return
        }
        problem = nil
        store.setListenPort(p)
    }
}

/// A Mac reached by address: its state, and a way to stop connecting to it.
struct ManualPeerRow: View {
    @EnvironmentObject var store: AppStore
    let key: String

    var body: some View {
        HStack(spacing: 12) {
            VStack(alignment: .leading, spacing: 3) {
                Text(key).font(.system(size: 17, weight: .bold, design: .monospaced))
                status
            }
            Spacer()
            Button { store.removeManualPeer(key) } label: { Image(systemName: "xmark") }
                .buttonStyle(QuietButtonStyle(size: 13)).accessibilityLabel("Remove \(key)").help("Stop connecting to this address")
        }
        .padding(12)
        .background(RoundedRectangle(cornerRadius: 12, style: .continuous).fill(Theme.raised))
    }

    @ViewBuilder private var status: some View {
        switch store.manualStatus[key] ?? .connecting {
        case .connecting:
            HStack(spacing: 6) { ProgressView().controlSize(.small); Text("Connecting…").font(.system(size: 14)).foregroundStyle(Theme.dim) }
        case .connected(let name):
            Label("Connected to \(name)", systemImage: "checkmark.circle.fill").font(.system(size: 14, weight: .semibold)).foregroundStyle(Theme.live)
        case .failed(let why):
            Label("\(why) Retrying…", systemImage: "exclamationmark.triangle.fill").font(.system(size: 14, weight: .semibold)).foregroundStyle(Theme.danger)
                .fixedSize(horizontal: false, vertical: true)
        }
    }
}

struct MemberRow: View {
    let name: String
    let online: Bool
    var body: some View {
        HStack(spacing: 10) {
            Image(systemName: "desktopcomputer").foregroundStyle(Theme.dim)
            Text(name).font(.system(size: 16, weight: .semibold))
            Spacer()
            Circle().fill(online ? Theme.live : Theme.faint).frame(width: 10, height: 10)
        }
        .padding(.horizontal, 12).padding(.vertical, 9)
        .background(RoundedRectangle(cornerRadius: 10, style: .continuous).fill(Theme.raised))
    }
}
