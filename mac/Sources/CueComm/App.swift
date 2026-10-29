import SwiftUI

final class AppDelegate: NSObject, NSApplicationDelegate {
    func applicationDidFinishLaunching(_ notification: Notification) {
        NSApp.setActivationPolicy(.regular)
        NSApp.activate(ignoringOtherApps: true)
    }
    // Keep running (and keep listening) after the window is closed, like a show-control tool should.
    func applicationShouldTerminateAfterLastWindowClosed(_ sender: NSApplication) -> Bool { false }
}

@main
struct CueCommApp: App {
    @NSApplicationDelegateAdaptor(AppDelegate.self) private var delegate
    @StateObject private var store = AppStore()

    var body: some Scene {
        WindowGroup {
            ContentView()
                .environmentObject(store)
                .environmentObject(store.live)
                .environmentObject(store.levels)
                .frame(minWidth: store.showCues ? 1280 : 900, minHeight: 680)
        }
        .windowStyle(.hiddenTitleBar)
        .commands {
            CommandGroup(replacing: .newItem) {
                Button("New Channel…") { store.newChannel() }.keyboardShortcut("n")
                Button("New Group…") { store.groupEditor = ChannelGroup(name: "New Group") }.keyboardShortcut("n", modifiers: [.command, .shift])
            }
            CommandGroup(after: .newItem) {
                Divider()
                Button("Export Transcript as Text…") { store.exportTranscript(csv: false) }.keyboardShortcut("e")
                Button("Export Transcript as CSV…") { store.exportTranscript(csv: true) }
                Divider()
                Button("Export Setup…") { store.exportConfiguration() }
                Button("Import Setup…") { store.importConfiguration() }
            }
            CommandMenu("Session") {
                Button("Host or Join a Session…") { store.sessionSheet = true }.keyboardShortcut("s", modifiers: [.command, .shift])
                Button("Cue Words") { store.showCues.toggle() }.keyboardShortcut("u")
                if store.inSession { Button("Leave Session") { store.leaveSession() } }
            }
            CommandMenu("Transcript") {
                Button(store.isListening ? "Stop Listening" : "Start Listening") { store.toggleListening() }.keyboardShortcut("l")
                Button("Jump to Current") { store.scrollRequest = ScrollRequest(target: .current) }.keyboardShortcut("j")
                Button("Clear Transcript") { store.clear(store.selection) }.keyboardShortcut("k")
                Button("Fill with Demo Chatter") { store.loadDemo() }
                Button("Mark All as Read") { store.markAllRead() }.keyboardShortcut("r", modifiers: [.command, .shift])
                Divider()
                Toggle("Filter Offensive Language", isOn: $store.settings.filterOffensive).keyboardShortcut("f", modifiers: [.command, .shift])
                Button("Larger Text") { store.settings.fontSize = min(48, store.settings.fontSize + 2) }.keyboardShortcut("+")
                Button("Smaller Text") { store.settings.fontSize = max(14, store.settings.fontSize - 2) }.keyboardShortcut("-")
            }
        }

        Settings {
            SettingsView()
                .environmentObject(store)
                .preferredColorScheme(.dark)
        }
    }
}
