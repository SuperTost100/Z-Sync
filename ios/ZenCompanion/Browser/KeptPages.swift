import Foundation
import UIKit
import WebKit

/// A page the user kept on this phone ("On this phone"). Lives only in the
/// app's own files, never in Zen's data.
struct KeptPage: Codable, Identifiable, Equatable {
    let id: String
    var url: URL
    var title: String
    let keptAt: Date
    /// When the snapshot file was last written; nil until there is one.
    var shotAt: Date?
}

/// The kept pages, newest first: one JSON file plus one snapshot per page in
/// Application Support. Deleted on sign-out.
@MainActor
@Observable
final class KeptPagesStore {
    static let shared = KeptPagesStore(
        directory: FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
            .appendingPathComponent("KeptPages", isDirectory: true)
    )

    private(set) var pages: [KeptPage] = []

    @ObservationIgnored private let directory: URL
    @ObservationIgnored private let now: () -> Date

    init(directory: URL, now: @escaping () -> Date = Date.init) {
        self.directory = directory
        self.now = now
        self.pages = Self.read(from: directory)
    }

    func page(_ id: String) -> KeptPage? {
        pages.first { $0.id == id }
    }

    @discardableResult
    func keep(url: URL, title: String) -> KeptPage {
        let page = KeptPage(id: UUID().uuidString, url: url, title: title, keptAt: now(), shotAt: nil)
        write([page] + pages)
        return page
    }

    /// The page moved on. A blank title keeps the one it had.
    func update(_ id: String, url: URL?, title: String) {
        guard var page = page(id) else { return }
        let before = page
        if let url { page.url = url }
        if !title.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty { page.title = title }
        guard page != before else { return }
        write(pages.map { $0.id == id ? page : $0 })
    }

    func remove(_ id: String) {
        guard page(id) != nil else { return }
        write(pages.filter { $0.id != id })
        try? FileManager.default.removeItem(at: snapshotURL(for: id))
    }

    func removeAll() {
        pages = []
        try? FileManager.default.removeItem(at: directory)
    }

    func snapshotURL(for id: String) -> URL {
        directory.appendingPathComponent("\(id).jpg")
    }

    /// A new snapshot was written for `id`; the list reloads its image.
    func snapshotSaved(_ id: String) {
        guard page(id) != nil else {
            // Removed while the image was being written.
            try? FileManager.default.removeItem(at: snapshotURL(for: id))
            return
        }
        let at = now()
        write(pages.map { page in
            guard page.id == id else { return page }
            var next = page
            next.shotAt = at
            return next
        })
    }

    private func write(_ next: [KeptPage]) {
        pages = next
        do {
            try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
            let data = try JSONEncoder().encode(next)
            try data.write(to: directory.appendingPathComponent(Self.fileName), options: .atomic)
        } catch {
            // The list in memory still holds; the next write tries again.
        }
    }

    private static let fileName = "pages.json"

    private static func read(from directory: URL) -> [KeptPage] {
        guard let data = try? Data(contentsOf: directory.appendingPathComponent(fileName)) else { return [] }
        // A damaged file loses the list, not the app.
        return (try? JSONDecoder().decode([KeptPage].self, from: data)) ?? []
    }
}

/// What leaving a page (close, switch, new page) does with it.
enum LeaveAction: Equatable {
    case update
    case keep
    case discard
}

enum KeptPagesPolicy {
    /// A kept page stays and takes its latest address. A throwaway page is
    /// dropped unless "Keep every page" is on. Even then a page is not kept
    /// when there is nothing worth reopening (no web address, a failed load),
    /// when it was just pinned into a space, when the user stopped keeping it
    /// during this visit, or when it is a space's tab that was opened and left
    /// without browsing away from it.
    static func onLeave(
        isKept: Bool,
        keepEveryPage: Bool,
        url: URL?,
        failed: Bool,
        pinnedHere: Bool,
        releasedHere: Bool,
        openedFromSpace: Bool,
        browsedAway: Bool
    ) -> LeaveAction {
        if isKept { return .update }
        guard keepEveryPage else { return .discard }
        guard let scheme = url?.scheme?.lowercased(), scheme == "https" || scheme == "http" else { return .discard }
        if failed || pinnedHere || releasedHere { return .discard }
        if openedFromSpace && !browsedAway { return .discard }
        return .keep
    }
}

/// Back and forward history of kept pages, so switching between them keeps
/// each page's own Back.
///
/// shortcut: kept in memory only, to match Android, where the WebView state is
/// not meant for disk. After the app ends a kept page reopens at its address
/// without its history; upgrade both platforms together if that is missed.
@MainActor
enum KeptPageHistory {
    private static var states: [String: Any] = [:]

    static func save(_ id: String, from webView: WKWebView) {
        if let state = webView.interactionState { states[id] = state }
    }

    static func state(for id: String) -> Any? { states[id] }

    static func forget(_ id: String) { states[id] = nil }

    static func removeAll() { states = [:] }
}

/// The small picture of a kept page in "On this phone".
@MainActor
enum KeptPageSnapshot {
    /// Points; wide enough for a two-column grid on a large phone.
    private static let width: CGFloat = 200

    /// Pictures what the page shows right now. Only the web view is drawn, so
    /// the bar and menus never end up in it.
    static func capture(_ webView: WKWebView, id: String, store: KeptPagesStore) async {
        guard webView.bounds.width > 0, webView.bounds.height > 0 else { return }
        let configuration = WKSnapshotConfiguration()
        configuration.snapshotWidth = NSNumber(value: Double(width))
        guard let image = try? await webView.takeSnapshot(configuration: configuration),
              let data = image.jpegData(compressionQuality: 0.72)
        else { return }
        let target = store.snapshotURL(for: id)
        let written = await Task.detached(priority: .utility) {
            do {
                try FileManager.default.createDirectory(
                    at: target.deletingLastPathComponent(),
                    withIntermediateDirectories: true
                )
                try data.write(to: target, options: .atomic)
                return true
            } catch {
                return false
            }
        }.value
        if written { store.snapshotSaved(id) }
    }
}
