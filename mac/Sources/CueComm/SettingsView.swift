import SwiftUI
import AVFoundation
import Speech

struct SettingsView: View {
    @EnvironmentObject var store: AppStore

    var body: some View {
        TabView(selection: $store.settingsTab) {
            GeneralSettings().tabItem { Label("General", systemImage: "gearshape") }.tag("general")
            AutomationsView().tabItem { Label("Automations", systemImage: "bolt.fill") }.tag("automations")
            ControlSettings().tabItem { Label("MIDI & OSC", systemImage: "pianokeys") }.tag("control")
            NetworkSettings().tabItem { Label("Network", systemImage: "network") }.tag("network")
        }
        .frame(width: 780, height: 600)
    }
}

struct GeneralSettings: View {
    @EnvironmentObject var store: AppStore

    var body: some View {
        Form {
            Section("Transcript") {
                Slider(value: $store.settings.fontSize, in: 10...48, step: 1) { Text("Text size: \(Int(store.settings.fontSize)) pt") }
                Toggle("Show timestamps", isOn: $store.settings.showTimestamps)
                Toggle("Filter offensive language", isOn: $store.settings.filterOffensive)
                Stepper("History per channel: \(store.settings.historyLimit)", value: $store.settings.historyLimit, in: 100...10000, step: 100)
            }
            Section("Listening") {
                Slider(value: $store.settings.silenceCommit, in: 0.5...3, step: 0.1) {
                    Text("Pause that ends a message: \(store.settings.silenceCommit, specifier: "%.1f") s")
                }
                Toggle("Fire keyword automations before the sentence ends", isOn: $store.settings.triggerOnPartials)
                Text("Faster cues, but a word that is later corrected by the recogniser may still trigger.")
                    .font(.caption).foregroundStyle(.secondary)
            }
            Section("Translation") {
                Picker("Translate into", selection: $store.settings.preferredLanguage) {
                    ForEach(Languages.translation) { Text($0.name).tag($0.code) }
                }
                Text("Turn translation on per channel in the channel editor. Requires macOS 15; language packs download on first use.")
                    .font(.caption).foregroundStyle(.secondary)
            }
            Section("Window") {
                Toggle("Keep window on top of other apps", isOn: $store.settings.alwaysOnTop)
            }
            Section("Permissions") {
                LabeledContent("Microphone", value: micText)
                LabeledContent("Speech Recognition", value: speechText)
                Button("Open Privacy Settings…") {
                    NSWorkspace.shared.open(URL(string: "x-apple.systempreferences:com.apple.preference.security?Privacy")!)
                }
            }
        }
        .formStyle(.grouped)
    }

    private var micText: String {
        switch AVCaptureDevice.authorizationStatus(for: .audio) {
        case .authorized: return "Allowed"
        case .denied, .restricted: return "Denied"
        default: return "Not yet requested"
        }
    }
    private var speechText: String {
        switch store.speechAuth {
        case .authorized: return "Allowed"
        case .denied, .restricted: return "Denied"
        default: return "Not yet requested"
        }
    }
}

struct ControlSettings: View {
    @EnvironmentObject var store: AppStore

    var body: some View {
        Form {
            Section("MIDI") {
                Picker("Send MIDI to", selection: $store.settings.midiOutputName) {
                    Text("All destinations").tag("")
                    ForEach(store.midiDestinations, id: \.self) { Text($0).tag($0) }
                }
                Toggle("Receive MIDI from all sources", isOn: $store.settings.midiInputEnabled)
                LabeledContent("Sources", value: store.midiSources.isEmpty ? "None found" : store.midiSources.joined(separator: ", "))
                Text("CueComm also appears as a virtual MIDI port named “CueComm”, so DAWs and show-control software can connect to it directly.")
                    .font(.caption).foregroundStyle(.secondary)
                Button("Refresh") { store.refreshMIDILists() }
            }
            Section("OSC") {
                Toggle("Listen for incoming OSC (UDP)", isOn: $store.settings.oscListenEnabled)
                Stepper("Port: " + String(store.settings.oscListenPort), value: $store.settings.oscListenPort, in: 1024...65535)
                TextField("Default OSC host for new cue words", text: $store.settings.oscDefaultHost)
                Stepper("Default OSC port: " + String(store.settings.oscDefaultPort), value: $store.settings.oscDefaultPort, in: 1...65535)
                Text("Cue words use this target unless you change it on the cue. Hosts and ports are also set per action in Automations.").font(.caption).foregroundStyle(.secondary)
            }
        }
        .formStyle(.grouped)
    }
}

struct NetworkSettings: View {
    @EnvironmentObject var store: AppStore

    var body: some View {
        Form {
            Section("Session") {
                if store.inSession {
                    LabeledContent("In session", value: store.settings.sessionName)
                    LabeledContent("Other Macs", value: store.peers.isEmpty ? "None yet" : store.peers.joined(separator: ", "))
                    Button("Leave Session") { store.leaveSession() }
                } else {
                    Text("Not in a session.").foregroundStyle(.secondary)
                }
                Button("Host or Join a Session…") { store.sessionSheet = true }
                Text("Everyone in a session sees the same channels and transcript, except channels marked private. Machines find each other automatically on the local network.")
                    .font(.caption).foregroundStyle(.secondary)
            }
            Section("This Mac") {
                TextField("Name others see", text: $store.settings.displayName)
                Toggle("Open sharing without a session", isOn: $store.settings.networkEnabled).disabled(store.inSession)
                Toggle("Flash this screen when another Mac fires a cue", isOn: $store.settings.flashOnRemoteCues)
            }
            Section("Share your setup") {
                HStack {
                    Button("Export Setup…") { store.exportConfiguration() }
                    Button("Import Setup…") { store.importConfiguration() }
                }
                Text("A setup file holds your channels, groups and automations so crews can reuse proven configurations across venues and tours.")
                    .font(.caption).foregroundStyle(.secondary)
            }
        }
        .formStyle(.grouped)
    }
}
