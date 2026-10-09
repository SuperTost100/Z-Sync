package de.kjell.zencompanion.ui.screens

internal enum class BrowserLinkAction {
    LOAD_IN_WEBVIEW,
    OPEN_EXTERNALLY,

    /** A page tried to open another app without a tap; drop it, as Chrome does. */
    BLOCK,
}

private val WEB_SCHEMES = setOf("http", "https", "about", "data", "blob", "file", "javascript")

/** True when a URL with [scheme] loads in the WebView instead of another app. */
internal fun isWebScheme(scheme: String?): Boolean = scheme == null || scheme.lowercase() in WEB_SCHEMES

internal fun browserLinkAction(
    scheme: String?,
    isRedirect: Boolean,
    hasGesture: Boolean,
    resolvedPackage: String?,
    browserPackages: Set<String>,
    ownPackage: String,
): BrowserLinkAction {
    val normalizedScheme = scheme?.lowercase()
    if (normalizedScheme == null || normalizedScheme !in WEB_SCHEMES) {
        return if (hasGesture) BrowserLinkAction.OPEN_EXTERNALLY else BrowserLinkAction.BLOCK
    }
    if (normalizedScheme != "http" && normalizedScheme != "https") {
        return BrowserLinkAction.LOAD_IN_WEBVIEW
    }
    if (isRedirect || !hasGesture) {
        return BrowserLinkAction.LOAD_IN_WEBVIEW
    }
    if (resolvedPackage == null || resolvedPackage == ownPackage) {
        return BrowserLinkAction.LOAD_IN_WEBVIEW
    }
    if (resolvedPackage in browserPackages) {
        return BrowserLinkAction.LOAD_IN_WEBVIEW
    }
    return BrowserLinkAction.OPEN_EXTERNALLY
}
