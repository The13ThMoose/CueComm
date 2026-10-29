import SwiftUI

struct AutomationsView: View {
    @EnvironmentObject var store: AppStore
    @State private var selected: UUID?

    private var index: Int? { store.automations.firstIndex { $0.id == selected } }

    var body: some View {
        HSplitView {
            VStack(spacing: 0) {
                List(selection: $selected) {
                    ForEach($store.automations) { $a in
                        HStack {
                            Toggle("", isOn: $a.enabled).labelsHidden().toggleStyle(.switch).controlSize(.mini)
                            VStack(alignment: .leading) {
                                Text(a.name).lineLimit(1)
                                Text(summary(a)).font(.caption).foregroundStyle(.secondary).lineLimit(1)
                            }
                        }.tag(a.id)
                    }
                    .onDelete { store.automations.remove(atOffsets: $0) }
                    .onMove { store.automations.move(fromOffsets: $0, toOffset: $1) }
                }
                HStack {
                    Button { let a = Automation(); store.automations.append(a); selected = a.id } label: { Image(systemName: "plus") }
                    Button {
                        if let i = index { var c = store.automations[i]; c.id = UUID(); c.name += " copy"; c.actions = c.actions.map { var x = $0; x.id = UUID(); return x }
                            store.automations.insert(c, at: i + 1); selected = c.id }
                    } label: { Image(systemName: "plus.square.on.square") }.disabled(index == nil).help("Duplicate")
                    Button { if let i = index { store.automations.remove(at: i); selected = nil } } label: { Image(systemName: "minus") }.disabled(index == nil)
                    Spacer()
                }.buttonStyle(.borderless).padding(8)
            }
            .frame(minWidth: 220, maxWidth: 280)

            VStack(spacing: 0) {
                if let i = index {
                    AutomationEditor(automation: $store.automations[i])
                } else {
                    Text("Select or add an automation").foregroundStyle(.secondary).frame(maxWidth: .infinity, maxHeight: .infinity)
                }
                Divider()
                EventLog()
            }
        }
        .onAppear { selected = selected ?? store.automations.first?.id }
    }

    private func summary(_ a: Automation) -> String {
        let t: String
        switch a.trigger.kind {
        case .keyword: t = "“\(a.trigger.phrase)”"
        case .message: t = "any message"
        case .midi: t = "MIDI " + a.trigger.midiKind.label
        case .osc: t = "OSC " + a.trigger.oscAddress
        }
        return "\(t) → \(a.actions.map { $0.kind.label }.joined(separator: ", "))"
    }
}

struct EventLog: View {
    @EnvironmentObject var store: AppStore
    var body: some View {
        VStack(alignment: .leading, spacing: 2) {
            Text("Event log").font(.caption.bold()).foregroundStyle(.secondary)
            ScrollView {
                VStack(alignment: .leading, spacing: 1) {
                    ForEach(store.log.prefix(40)) {
                        Text("\($0.date.formatted(date: .omitted, time: .standard))  \($0.text)").font(.system(size: 11, design: .monospaced))
                    }
                }.frame(maxWidth: .infinity, alignment: .leading)
            }
        }
        .padding(8).frame(height: 110)
    }
}

struct AutomationEditor: View {
    @EnvironmentObject var store: AppStore
    @Binding var automation: Automation

    var body: some View {
        Form {
            Section {
                TextField("Name", text: $automation.name)
                Stepper("Cooldown: \(automation.cooldown, specifier: "%.1f") s", value: $automation.cooldown, in: 0...60, step: 0.5)
            }
            Section("When…") { TriggerEditor(trigger: $automation.trigger) }
            Section("Then…") {
                ForEach($automation.actions) { $action in
                    ActionEditor(action: $action) { automation.actions.removeAll { $0.id == action.id } }
                }
                HStack {
                    Button { automation.actions.append(AutomationAction()) } label: { Label("Add Action", systemImage: "plus") }
                    Spacer()
                    Button { store.fire(automation, ActionContext(text: "test", channel: "Test", keyword: "test"), force: true) } label: {
                        Label("Test", systemImage: "play.fill")
                    }
                }
            }
        }
        .formStyle(.grouped)
    }
}

struct TriggerEditor: View {
    @EnvironmentObject var store: AppStore
    @Binding var trigger: AutomationTrigger

    var body: some View {
        Picker("Trigger", selection: $trigger.kind) {
            ForEach(TriggerKind.allCases) { Text($0.label).tag($0) }
        }
        switch trigger.kind {
        case .keyword, .message:
            if trigger.kind == .keyword {
                TextField("Words or phrases (comma separated)", text: $trigger.phrase)
                Toggle("Match whole words only", isOn: $trigger.wholeWord)
            }
            Picker("On channel", selection: $trigger.channelID) {
                Text("Any channel").tag(UUID?.none)
                ForEach(store.channels) { Text($0.name).tag(Optional($0.id)) }
            }
        case .midi:
            Picker("Message", selection: $trigger.midiKind) { ForEach(MIDIKind.allCases) { Text($0.label).tag($0) } }
            Stepper(trigger.midiChannel == 0 ? "MIDI channel: any" : "MIDI channel: \(trigger.midiChannel)", value: $trigger.midiChannel, in: 0...16)
            Stepper(trigger.data1 < 0 ? "\(trigger.midiKind.data1Label): any" : "\(trigger.midiKind.data1Label): \(trigger.data1)", value: $trigger.data1, in: -1...127)
            HStack {
                Button(store.learning ? "Waiting for MIDI… (click to cancel)" : "MIDI Learn") {
                    if store.learning { store.cancelMIDILearn() } else {
                        store.startMIDILearn { e in trigger.midiKind = e.kind == .noteOff ? .noteOn : e.kind; trigger.midiChannel = e.channel; trigger.data1 = e.data1 }
                    }
                }
                Text("Move a control on your MIDI device").font(.caption).foregroundStyle(.secondary)
            }
        case .osc:
            TextField("OSC address (e.g. /cue/go or /cue/*)", text: $trigger.oscAddress)
            Text("Arguments of the incoming message are available to actions as {arg1}, {arg2}, …").font(.caption).foregroundStyle(.secondary)
        }
    }
}

struct ActionEditor: View {
    @EnvironmentObject var store: AppStore
    @Binding var action: AutomationAction
    var remove: () -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            HStack {
                Picker("", selection: $action.kind) { ForEach(ActionKind.allCases) { Text($0.label).tag($0) } }.labelsHidden()
                Spacer()
                Button(role: .destructive, action: remove) { Image(systemName: "xmark.circle.fill") }.buttonStyle(.borderless)
            }
            switch action.kind {
            case .sendMIDI:
                Picker("Message", selection: $action.midiKind) { ForEach(MIDIKind.allCases) { Text($0.label).tag($0) } }
                HStack {
                    Stepper("Ch \(action.midiChannel)", value: $action.midiChannel, in: 1...16)
                    Stepper("\(action.midiKind.data1Label) \(action.data1)", value: $action.data1, in: 0...127)
                    if action.midiKind != .programChange { Stepper("\(action.midiKind.data2Label) \(action.data2)", value: $action.data2, in: 0...127) }
                }
            case .sendOSC:
                HStack {
                    TextField("Host", text: $action.oscHost)
                    TextField("Port", value: $action.oscPort, format: .number.grouping(.never)).frame(width: 80)
                }
                TextField("Address", text: $action.oscAddress)
                TextField("Arguments (e.g. 1 0.5 \"text\" {text})", text: $action.oscArgs)
                Text("Placeholders: {text} {channel} {keyword}").font(.caption).foregroundStyle(.secondary)
            case .selectChannel:
                Picker("Channel", selection: $action.targetChannelID) {
                    Text("—").tag(UUID?.none)
                    ForEach(store.channels) { Text($0.name).tag(Optional($0.id)) }
                }
            case .clearTranscript, .markRead:
                Picker("Channel", selection: $action.targetChannelID) {
                    Text("Current view").tag(UUID?.none)
                    ForEach(store.channels) { Text($0.name).tag(Optional($0.id)) }
                }
            case .scrollTranscript:
                Picker("Scroll", selection: $action.scrollTarget) { ForEach(ScrollTarget.allCases) { Text($0.label).tag($0) } }
            case .adjustFontSize:
                Stepper("Change by \(action.fontDelta > 0 ? "+" : "")\(action.fontDelta) pt", value: $action.fontDelta, in: -12...12)
            case .flashWindow, .showWindow, .hideWindow:
                EmptyView()
            }
            Stepper("Delay: \(action.delay, specifier: "%.1f") s", value: $action.delay, in: 0...30, step: 0.5)
        }
        .padding(.vertical, 4)
    }
}
