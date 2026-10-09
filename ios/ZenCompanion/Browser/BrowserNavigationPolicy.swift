import Foundation

enum BrowserNavigationPolicy {
    private static let webSchemes: Set<String> = ["http", "https", "about", "data", "blob", "file", "javascript"]

    static func loadsInWebView(_ url: URL) -> Bool {
        guard let scheme = url.scheme?.lowercased() else { return true }
        return webSchemes.contains(scheme)
    }

    /// Another app opens only from a link the user tapped in the main page.
    /// Script, redirect and iframe navigations to app schemes are dropped,
    /// matching the Android policy.
    static func opensExternally(userTapped: Bool, isMainFrame: Bool) -> Bool {
        userTapped && isMainFrame
    }
}
