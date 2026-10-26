import SwiftUI
import UIKit

/// "On this phone": the kept pages as a two-column grid of snapshots. Opens
/// from the browser's tabs button and from the count in the home search field.
/// `currentPageID` gets a ring: the page the browser is showing.
struct KeptPagesSheet: View {
    var currentPageID: String?
    let spaces: [ZenSpace]
    /// How a pin from here is written; folders are offered only for pins.
    let saveKind: SaveKind
    var onOpen: (KeptPage) -> Void
    var onNewPage: () -> Void

    @Environment(\.colorScheme) private var scheme
    @Environment(\.dismiss) private var dismiss
    @State private var confirmCloseAll = false
    @State private var pinError: String?

    private var store: KeptPagesStore { .shared }

    private let columns = [
        GridItem(.flexible(), spacing: 14),
        GridItem(.flexible(), spacing: 14),
    ]

    var body: some View {
        NavigationStack {
            Group {
                if store.pages.isEmpty {
                    emptyState
                } else {
                    ScrollView {
                        LazyVGrid(columns: columns, spacing: 14) {
                            ForEach(store.pages) { page in
                                card(page)
                            }
                        }
                        .padding(.horizontal, 20)
                        .padding(.vertical, 8)
                    }
                }
            }
            .frame(maxWidth: .infinity, maxHeight: .infinity)
            .background(Palette.paper(scheme).ignoresSafeArea())
            .safeAreaInset(edge: .bottom, spacing: 0) { footer }
            .navigationTitle(Text("kept.title"))
            .navigationBarTitleDisplayMode(.large)
            .toolbar {
                ToolbarItem(placement: .confirmationAction) {
                    ToolbarIconButton(systemName: "xmark", accessibilityKey: "common.done") {
                        dismiss()
                    }
                }
            }
            .confirmationDialog(
                Text("kept.close_all_title"),
                isPresented: $confirmCloseAll,
                titleVisibility: .visible
            ) {
                Button(role: .destructive) {
                    store.removeAll()
                    KeptPageHistory.removeAll()
                } label: {
                    Text("kept.close_all")
                }
            } message: {
                Text("kept.close_all_message")
            }
            .alert(
                Text("browser.pin_failed"),
                isPresented: Binding(get: { pinError != nil }, set: { if !$0 { pinError = nil } })
            ) {
                Button("common.done", role: .cancel) {}
            } message: {
                Text(pinError ?? "")
            }
        }
    }

    // MARK: - Footer

    private var footer: some View {
        HStack {
            if !store.pages.isEmpty {
                Button {
                    confirmCloseAll = true
                } label: {
                    Text("kept.close_all")
                        .font(.system(size: 16, weight: .medium, design: .rounded))
                        .foregroundStyle(Palette.ink(scheme))
                }
            }
            Spacer(minLength: 12)
            Button(action: onNewPage) {
                Label {
                    Text("kept.new_page")
                } icon: {
                    Image(systemName: "plus")
                }
                .font(.system(size: 16, weight: .semibold, design: .rounded))
                .foregroundStyle(Palette.coral(scheme))
            }
        }
        .frame(minHeight: 44)
        .padding(.horizontal, 20)
        .padding(.vertical, 6)
        .background(Palette.paper(scheme))
    }

    // MARK: - Empty

    private var emptyState: some View {
        VStack(spacing: 0) {
            Image(systemName: "plus.square.on.square")
                .font(.system(size: 28, weight: .semibold))
                .foregroundStyle(Palette.coral(scheme))
                .frame(width: 64, height: 64)
                .background(
                    Palette.lift(scheme),
                    in: RoundedRectangle(cornerRadius: 20, style: .continuous)
                )
                .glassRim(cornerRadius: 20)
            Text("kept.empty_title")
                .font(.system(size: 20, weight: .bold, design: .rounded))
                .foregroundStyle(Palette.ink(scheme))
                .padding(.top, 18)
            Text("kept.empty_message")
                .font(.system(size: 15.5, design: .rounded))
                .foregroundStyle(Palette.ink(scheme).opacity(0.65))
                .multilineTextAlignment(.center)
                .padding(.top, 8)
        }
        .padding(.horizontal, 36)
        .frame(maxWidth: .infinity, maxHeight: .infinity)
    }

    // MARK: - Card

    private func card(_ page: KeptPage) -> some View {
        let shape = RoundedRectangle(cornerRadius: Palette.liftCornerRadius, style: .continuous)
        let title = PageTitle.headline(page.title, url: page.url)
        let isCurrent = page.id == currentPageID
        return VStack(spacing: 0) {
            HStack(spacing: 7) {
                Favicon(urlString: page.url.absoluteString, size: 16)
                Text(title)
                    .font(.system(size: 13, weight: .semibold, design: .rounded))
                    .foregroundStyle(Palette.ink(scheme))
                    .lineLimit(1)
                Spacer(minLength: 0)
                Button {
                    close(page)
                } label: {
                    Image(systemName: "xmark")
                        .font(.system(size: 12, weight: .semibold))
                        .foregroundStyle(Palette.ink(scheme).opacity(0.55))
                        .frame(width: 32, height: 36)
                        .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
                .accessibilityLabel(Text("kept.close_page_named \(title)"))
            }
            .padding(.leading, 10)
            .frame(height: 36)

            Rectangle()
                .fill(Palette.ink(scheme).opacity(0.1))
                .frame(height: 0.5)

            KeptPageShot(page: page, store: store, scheme: scheme)
                .frame(height: 178)
                .clipped()
        }
        .background(Palette.lift(scheme), in: shape)
        .clipShape(shape)
        .glassRim(cornerRadius: Palette.liftCornerRadius)
        .overlay {
            if isCurrent {
                shape.strokeBorder(Palette.coral(scheme), lineWidth: 2.5)
            }
        }
        .contentShape(.contextMenuPreview, shape)
        .onTapGesture { onOpen(page) }
        .contextMenu { menu(for: page) }
        .accessibilityElement(children: .contain)
        .accessibilityLabel(isCurrent ? Text(verbatim: "\(title), \(String(localized: "kept.current_page"))") : Text(verbatim: title))
        .accessibilityAddTraits(.isButton)
    }

    /// Long press. Pin to space offers each space and, for a pin, its folders:
    /// the choice the browser's Pin offers.
    @ViewBuilder
    private func menu(for page: KeptPage) -> some View {
        Menu {
            ForEach(spaces) { space in
                let folders = saveKind == .pinned ? PinDestinationModel.folderEntries(in: space) : []
                if folders.isEmpty {
                    Button(space.name) { pin(page, to: PinDestination(spaceId: space.id)) }
                } else {
                    Menu(space.name) {
                        Button(space.name) { pin(page, to: PinDestination(spaceId: space.id)) }
                        ForEach(folders, id: \.folderId) { folder in
                            Button(String(repeating: "   ", count: folder.depth) + folder.name) {
                                pin(page, to: PinDestination(spaceId: space.id, folderId: folder.folderId))
                            }
                        }
                    }
                }
            }
        } label: {
            Label {
                Text("kept.pin_to_space")
            } icon: {
                Image(systemName: "pin")
            }
        }
        .disabled(spaces.isEmpty)

        Button {
            UIPasteboard.general.url = page.url
            AppServices.haptics.selectionChanged()
        } label: {
            Label {
                Text("kept.copy_link")
            } icon: {
                Image(systemName: "link")
            }
        }

        SwiftUI.ShareLink(item: page.url) {
            Label {
                Text("browser.share")
            } icon: {
                Image(systemName: "square.and.arrow.up")
            }
        }

        Divider()

        Button(role: .destructive) {
            close(page)
        } label: {
            Label {
                Text("kept.close_page")
            } icon: {
                Image(systemName: "xmark")
            }
        }
    }

    // MARK: - Actions

    private func close(_ page: KeptPage) {
        withAnimation(.spring(response: 0.32, dampingFraction: 0.86)) {
            store.remove(page.id)
        }
        KeptPageHistory.forget(page.id)
    }

    /// Once the page belongs to a space it leaves the list; a failed write
    /// leaves it where it was.
    private func pin(_ page: KeptPage, to destination: PinDestination) {
        let kind = saveKind
        Task { @MainActor in
            do {
                try await AppServices.pinWriter.addTab(
                    url: page.url,
                    title: PageTitle.headline(page.title, url: page.url),
                    to: destination.spaceId,
                    // Normal tabs are never placed in a folder.
                    folderId: kind == .normal ? nil : destination.folderId,
                    kind: kind
                )
                AppServices.haptics.pinSucceeded()
                withAnimation(.spring(response: 0.32, dampingFraction: 0.86)) {
                    store.remove(page.id)
                }
                KeptPageHistory.forget(page.id)
            } catch {
                pinError = error.zenUserMessage
            }
        }
    }
}

/// The page as it looked when it was last left; its favicon until then.
private struct KeptPageShot: View {
    let page: KeptPage
    let store: KeptPagesStore
    let scheme: ColorScheme

    @State private var image: UIImage?

    var body: some View {
        ZStack {
            Palette.paper(scheme)
            if let image {
                Image(uiImage: image)
                    .resizable()
                    .scaledToFill()
                    .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .top)
            } else {
                Favicon(urlString: page.url.absoluteString, size: 32)
            }
        }
        .task(id: page.shotAt) {
            guard page.shotAt != nil else { return }
            let file = store.snapshotURL(for: page.id)
            let data = await Task.detached(priority: .utility) { try? Data(contentsOf: file) }.value
            if let data, let loaded = UIImage(data: data) { image = loaded }
        }
    }
}
