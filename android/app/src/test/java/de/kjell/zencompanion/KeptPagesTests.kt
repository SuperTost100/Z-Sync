package de.kjell.zencompanion

import de.kjell.zencompanion.data.KeptPagesPolicy
import de.kjell.zencompanion.data.KeptPagesStore
import de.kjell.zencompanion.data.LeaveAction
import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class KeptPagesTests {
    private fun tempDir(): File = Files.createTempDirectory("kept").toFile()

    @Test
    fun keptPagesListNewestFirstAndSurviveARestart() {
        val dir = tempDir()
        var clock = 1L
        val store = KeptPagesStore(dir) { clock++ }
        store.keep("https://a.example/", "A")
        store.keep("https://b.example/", "B")

        val reopened = KeptPagesStore(dir)
        assertEquals(listOf("B", "A"), reopened.pages.value.map { it.title })
        assertEquals(store.pages.value, reopened.pages.value)
    }

    @Test
    fun anUpdateWithoutATitleKeepsTheOldOne() {
        val store = KeptPagesStore(tempDir())
        val page = store.keep("https://a.example/", "A")

        store.update(page.id, "https://a.example/next", "")
        assertEquals("A", store.page(page.id)?.title)
        assertEquals("https://a.example/next", store.page(page.id)?.url)

        store.update(page.id, "", "Next")
        assertEquals("https://a.example/next", store.page(page.id)?.url)
        assertEquals("Next", store.page(page.id)?.title)
    }

    @Test
    fun removingAPageDeletesItsSnapshot() {
        val store = KeptPagesStore(tempDir())
        val page = store.keep("https://a.example/", "A")
        store.snapshotFile(page.id).writeText("jpeg")
        store.snapshotSaved(page.id)
        assertTrue(store.page(page.id)!!.shotAt > 0)

        store.remove(page.id)
        assertNull(store.page(page.id))
        assertFalse(store.snapshotFile(page.id).exists())
    }

    @Test
    fun aSnapshotForARemovedPageIsThrownAway() {
        val store = KeptPagesStore(tempDir())
        val page = store.keep("https://a.example/", "A")
        store.remove(page.id)
        store.snapshotFile(page.id).writeText("jpeg")

        store.snapshotSaved(page.id)
        assertFalse(store.snapshotFile(page.id).exists())
        assertTrue(store.pages.value.isEmpty())
    }

    @Test
    fun clearRemovesEverythingOnDisk() {
        val dir = tempDir()
        val store = KeptPagesStore(dir)
        val page = store.keep("https://a.example/", "A")
        store.snapshotFile(page.id).writeText("jpeg")

        store.clear()
        assertTrue(store.pages.value.isEmpty())
        assertFalse(dir.exists())
        assertTrue(KeptPagesStore(dir).pages.value.isEmpty())
    }

    @Test
    fun aDamagedFileStartsAnEmptyList() {
        val dir = tempDir()
        File(dir, "pages.json").writeText("{not json")
        assertTrue(KeptPagesStore(dir).pages.value.isEmpty())
    }

    @Test
    fun entriesWithoutAnAddressAreSkipped() {
        val pages = KeptPagesStore.decode("""[{"id":"1","url":""},{"id":"2","url":"https://b.example/","title":"B"}]""")
        assertEquals(listOf("2"), pages.map { it.id })
    }

    private fun leave(
        isKept: Boolean = false,
        keepEveryPage: Boolean = true,
        url: String = "https://a.example/",
        failed: Boolean = false,
        pinnedHere: Boolean = false,
        releasedHere: Boolean = false,
        openedFromSpace: Boolean = false,
        browsedAway: Boolean = false,
    ) = KeptPagesPolicy.onLeave(isKept, keepEveryPage, url, failed, pinnedHere, releasedHere, openedFromSpace, browsedAway)

    @Test
    fun aKeptPageAlwaysStays() {
        assertEquals(LeaveAction.UPDATE, leave(isKept = true, keepEveryPage = false))
        assertEquals(LeaveAction.UPDATE, leave(isKept = true, failed = true))
    }

    @Test
    fun throwawayIsTheDefault() {
        assertEquals(LeaveAction.DISCARD, leave(keepEveryPage = false))
    }

    @Test
    fun keepEveryPageSkipsPagesNotWorthReopening() {
        assertEquals(LeaveAction.KEEP, leave())
        assertEquals(LeaveAction.DISCARD, leave(url = ""))
        assertEquals(LeaveAction.DISCARD, leave(url = "about:blank"))
        assertEquals(LeaveAction.DISCARD, leave(failed = true))
        assertEquals(LeaveAction.DISCARD, leave(pinnedHere = true))
        assertEquals(LeaveAction.DISCARD, leave(releasedHere = true))
        assertEquals(LeaveAction.DISCARD, leave(openedFromSpace = true))
        assertEquals(LeaveAction.KEEP, leave(openedFromSpace = true, browsedAway = true))
    }
}
