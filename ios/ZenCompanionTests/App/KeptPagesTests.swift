import XCTest

@testable import ZenCompanion

@MainActor
final class KeptPagesTests: XCTestCase {
    private func tempDirectory() -> URL {
        let url = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString, isDirectory: true)
        addTeardownBlock { try? FileManager.default.removeItem(at: url) }
        return url
    }

    private let a = URL(string: "https://a.example/")!
    private let b = URL(string: "https://b.example/")!

    func testKeptPagesListNewestFirstAndSurviveARestart() {
        let directory = tempDirectory()
        let store = KeptPagesStore(directory: directory)
        store.keep(url: a, title: "A")
        store.keep(url: b, title: "B")

        let reopened = KeptPagesStore(directory: directory)
        XCTAssertEqual(reopened.pages.map(\.title), ["B", "A"])
        XCTAssertEqual(reopened.pages, store.pages)
    }

    func testAnUpdateWithoutATitleKeepsTheOldOne() {
        let store = KeptPagesStore(directory: tempDirectory())
        let page = store.keep(url: a, title: "A")
        let next = URL(string: "https://a.example/next")!

        store.update(page.id, url: next, title: "")
        XCTAssertEqual(store.page(page.id)?.title, "A")
        XCTAssertEqual(store.page(page.id)?.url, next)

        store.update(page.id, url: nil, title: "Next")
        XCTAssertEqual(store.page(page.id)?.url, next)
        XCTAssertEqual(store.page(page.id)?.title, "Next")
    }

    func testRemovingAPageDeletesItsSnapshot() throws {
        let store = KeptPagesStore(directory: tempDirectory())
        let page = store.keep(url: a, title: "A")
        let file = store.snapshotURL(for: page.id)
        try Data("jpeg".utf8).write(to: file)
        store.snapshotSaved(page.id)
        XCTAssertNotNil(store.page(page.id)?.shotAt)

        store.remove(page.id)
        XCTAssertNil(store.page(page.id))
        XCTAssertFalse(FileManager.default.fileExists(atPath: file.path))
    }

    func testASnapshotForARemovedPageIsThrownAway() throws {
        let store = KeptPagesStore(directory: tempDirectory())
        let page = store.keep(url: a, title: "A")
        store.remove(page.id)
        let file = store.snapshotURL(for: page.id)
        try Data("jpeg".utf8).write(to: file)

        store.snapshotSaved(page.id)
        XCTAssertFalse(FileManager.default.fileExists(atPath: file.path))
        XCTAssertTrue(store.pages.isEmpty)
    }

    func testRemoveAllClearsTheDisk() {
        let directory = tempDirectory()
        let store = KeptPagesStore(directory: directory)
        store.keep(url: a, title: "A")

        store.removeAll()
        XCTAssertTrue(store.pages.isEmpty)
        XCTAssertFalse(FileManager.default.fileExists(atPath: directory.path))
        XCTAssertTrue(KeptPagesStore(directory: directory).pages.isEmpty)
    }

    func testADamagedFileStartsAnEmptyList() throws {
        let directory = tempDirectory()
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        try Data("{not json".utf8).write(to: directory.appendingPathComponent("pages.json"))
        XCTAssertTrue(KeptPagesStore(directory: directory).pages.isEmpty)
    }

    // MARK: - Leaving a page

    private func leave(
        isKept: Bool = false,
        keepEveryPage: Bool = true,
        url: URL? = URL(string: "https://a.example/"),
        failed: Bool = false,
        pinnedHere: Bool = false,
        releasedHere: Bool = false,
        openedFromSpace: Bool = false,
        browsedAway: Bool = false
    ) -> LeaveAction {
        KeptPagesPolicy.onLeave(
            isKept: isKept,
            keepEveryPage: keepEveryPage,
            url: url,
            failed: failed,
            pinnedHere: pinnedHere,
            releasedHere: releasedHere,
            openedFromSpace: openedFromSpace,
            browsedAway: browsedAway
        )
    }

    func testAKeptPageAlwaysStays() {
        XCTAssertEqual(leave(isKept: true, keepEveryPage: false), .update)
        XCTAssertEqual(leave(isKept: true, failed: true), .update)
    }

    func testThrowawayIsTheDefault() {
        XCTAssertEqual(leave(keepEveryPage: false), .discard)
    }

    func testKeepEveryPageSkipsPagesNotWorthReopening() {
        XCTAssertEqual(leave(), .keep)
        XCTAssertEqual(leave(url: nil), .discard)
        XCTAssertEqual(leave(url: URL(string: "about:blank")), .discard)
        XCTAssertEqual(leave(failed: true), .discard)
        XCTAssertEqual(leave(pinnedHere: true), .discard)
        XCTAssertEqual(leave(releasedHere: true), .discard)
        XCTAssertEqual(leave(openedFromSpace: true), .discard)
        XCTAssertEqual(leave(openedFromSpace: true, browsedAway: true), .keep)
    }
}
