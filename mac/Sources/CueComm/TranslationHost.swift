import SwiftUI
import Translation

/// Invisible view that hosts Apple's on-device Translation session and drains the store's job queue.
@available(macOS 15.0, *)
struct TranslationHost: View {
    @EnvironmentObject var store: AppStore
    @State private var config: TranslationSession.Configuration?
    @State private var busy = false

    var body: some View {
        Color.clear
            .frame(width: 0, height: 0)
            .translationTask(config) { session in
                await run(session)
            }
            .onChange(of: store.translationJobs) { _, _ in kick() }
    }

    private func kick() {
        guard !busy, let job = store.translationJobs.first else { return }
        busy = true
        let target = Locale(identifier: store.settings.preferredLanguage).language
        let new = TranslationSession.Configuration(source: Locale.Language(identifier: job.source), target: target)
        if config == new { config?.invalidate() } else { config = new }
    }

    private func run(_ session: TranslationSession) async {
        let source = session.sourceLanguage?.languageCode?.identifier ?? store.translationJobs.first?.source ?? ""
        let jobs = store.takeTranslationJobs(source: source)
        if !jobs.isEmpty {
            let requests = jobs.map { TranslationSession.Request(sourceText: $0.text, clientIdentifier: $0.id.uuidString) }
            do {
                for response in try await session.translations(from: requests) {
                    guard let idStr = response.clientIdentifier, let id = UUID(uuidString: idStr),
                          let job = jobs.first(where: { $0.id == id }) else { continue }
                    store.applyTranslation(id: id, channelID: job.channelID, text: response.targetText)
                }
            } catch {
                store.addLog("Translation unavailable: \(error.localizedDescription)")
            }
        }
        busy = false
        kick()
    }
}
