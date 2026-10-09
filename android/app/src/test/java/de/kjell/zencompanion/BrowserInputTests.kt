package de.kjell.zencompanion

import de.kjell.zencompanion.ui.screens.typedUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Typed address rules, shared case for case with the iOS `BrowserInputTests`:
 * page addresses resolve to a URL, everything else searches.
 */
class BrowserInputTests {
    @Test
    fun pageAddressesResolve() {
        val cases = mapOf(
            "example.com" to "https://example.com",
            "github.com/torvalds/linux" to "https://github.com/torvalds/linux",
            "example.com:8080/x" to "https://example.com:8080/x",
            "localhost" to "http://localhost",
            "localhost:3000/api" to "http://localhost:3000/api",
            "192.168.0.1:8080" to "http://192.168.0.1:8080",
            "https://example.com/path?q=1" to "https://example.com/path?q=1",
            "HTTP://Example.com" to "HTTP://Example.com",
            "mailto:support@kjell.cc" to "mailto:support@kjell.cc",
            "tel:+391234567" to "tel:+391234567",
            "about:blank" to "about:blank",
        )
        for ((input, expected) in cases) {
            assertEquals(input, expected, typedUrl(input))
        }
    }

    @Test
    fun everythingElseSearches() {
        val searches = listOf(
            "site:example.com",
            "javascript:alert(1)",
            "file:///etc/hosts",
            "intent://scan#Intent;scheme=zxing;end",
            "data:text/html,hi",
            "3.14",
            "hello.world foo",
            "zen companion",
        )
        for (input in searches) {
            assertNull(input, typedUrl(input))
        }
    }
}
