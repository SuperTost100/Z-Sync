package de.kjell.zencompanion.ui.screens

import androidx.compose.ui.graphics.Color
import de.kjell.zencompanion.data.SearchEngines

/**
 * Resolves raw user address/search input to a fully-qualified URL: a page
 * address when [typedUrl] recognizes one, otherwise a search.
 */
fun formatBrowserInput(query: String): String {
    val trimmed = query.trim()
    if (trimmed.isEmpty()) return ""
    return typedUrl(trimmed) ?: SearchEngines.current.formatQuery(trimmed)
}

/** Schemes a person may type as-is. Anything else (javascript:, file:, intent:, …) searches. */
private val TYPED_SCHEMES = setOf("http", "https", "about", "mailto", "tel")
private val SCHEME_PREFIX = Regex("^([A-Za-z][A-Za-z0-9+.-]*):")
private val LOCALHOST = Regex("^localhost(?::\\d{1,5})?(?:[/?#]\\S*)?$", RegexOption.IGNORE_CASE)
private val HOST_LIKE = Regex("^([^\\s/?#:.]+(?:\\.[^\\s/?#:.]+)+)(?::\\d{1,5})?(?:[/?#]\\S*)?$")
private val IPV4 = Regex("^\\d{1,3}(?:\\.\\d{1,3}){3}$")

/**
 * The URL that [text] names, or null when it should be searched. Same rules
 * as iOS `BrowserInput.typedURL`:
 * - an `http`, `https`, `about`, `mailto` or `tel` URL is taken as typed;
 * - `localhost` and IPv4 hosts (with optional port and path) get `http://`;
 * - `host.tld` with an optional port and path gets `https://` when the last
 *   label has a letter, so `3.14` and `site:example.com` search.
 */
internal fun typedUrl(text: String): String? {
    if (text.any { it.isWhitespace() }) return null
    val scheme = SCHEME_PREFIX.find(text)?.groupValues?.get(1)?.lowercase()
    if (scheme != null && scheme in TYPED_SCHEMES) return text
    if (LOCALHOST.matches(text)) return "http://$text"
    val hostname = HOST_LIKE.matchEntire(text)?.groupValues?.get(1) ?: return null
    if (IPV4.matches(hostname)) return "http://$text"
    if (hostname.substringAfterLast('.').none { it.isLetter() }) return null
    return "https://$text"
}

/**
 * Parses CSS hex, rgb(), and rgba() color strings extracted from the DOM into a Compose Color.
 */
fun parseCssColor(css: String?): Color? {
    if (css == null) return null
    val clean = css.replace("\"", "").replace("'", "").replace("\\", "").trim()
    if (clean.isEmpty() || clean == "null" || clean == "undefined" || clean == "transparent") return null

    return runCatching {
        if (clean.startsWith("#")) {
            val hex = clean.removePrefix("#")
            when (hex.length) {
                3 -> {
                    val r = hex[0].digitToInt(16) * 17
                    val g = hex[1].digitToInt(16) * 17
                    val b = hex[2].digitToInt(16) * 17
                    Color(red = r / 255f, green = g / 255f, blue = b / 255f)
                }
                6 -> {
                    val r = hex.substring(0, 2).toInt(16)
                    val g = hex.substring(2, 4).toInt(16)
                    val b = hex.substring(4, 6).toInt(16)
                    Color(red = r / 255f, green = g / 255f, blue = b / 255f)
                }
                8 -> {
                    val r = hex.substring(0, 2).toInt(16)
                    val g = hex.substring(2, 4).toInt(16)
                    val b = hex.substring(4, 6).toInt(16)
                    val a = hex.substring(6, 8).toInt(16) / 255f
                    Color(red = r / 255f, green = g / 255f, blue = b / 255f, alpha = a)
                }
                else -> null
            }
        } else if (clean.startsWith("rgb", ignoreCase = true)) {
            val numbers = clean.substringAfter("(").substringBefore(")").split(",")
            if (numbers.size >= 3) {
                val r = numbers[0].trim().toFloatOrNull()?.toInt() ?: 0
                val g = numbers[1].trim().toFloatOrNull()?.toInt() ?: 0
                val b = numbers[2].trim().toFloatOrNull()?.toInt() ?: 0
                val a = if (numbers.size >= 4) {
                    numbers[3].trim().toFloatOrNull() ?: 1f
                } else 1f
                if (a <= 0.05f) null else Color(red = r / 255f, green = g / 255f, blue = b / 255f, alpha = a)
            } else null
        } else {
            null
        }
    }.getOrNull()
}
