package de.kjell.zencompanion.data

import android.content.Context
import java.io.File
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject

/**
 * A page the user kept on this phone ("On this phone"). Lives only in the
 * app's own files, never in Zen's data.
 */
data class KeptPage(
    val id: String,
    val url: String,
    val title: String,
    val keptAt: Long,
    /** When the snapshot file was last written; 0 when there is none yet. */
    val shotAt: Long = 0L,
)

/**
 * The kept pages, newest first: one JSON file plus one snapshot per page under
 * [dir]. Deleted on sign-out.
 */
class KeptPagesStore(
    private val dir: File,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val _pages = MutableStateFlow(read())
    val pages: StateFlow<List<KeptPage>> = _pages

    fun page(id: String): KeptPage? = _pages.value.firstOrNull { it.id == id }

    fun keep(url: String, title: String): KeptPage {
        val page = KeptPage(id = UUID.randomUUID().toString(), url = url, title = title, keptAt = now())
        write { listOf(page) + it }
        return page
    }

    /** The page moved on. A blank title keeps the one it had. */
    fun update(id: String, url: String, title: String) {
        val current = page(id) ?: return
        val next = current.copy(url = url.ifEmpty { current.url }, title = title.ifBlank { current.title })
        if (next == current) return
        write { pages -> pages.map { if (it.id == id) next else it } }
    }

    fun remove(id: String) {
        if (page(id) == null) return
        write { pages -> pages.filterNot { it.id == id } }
        snapshotFile(id).delete()
    }

    fun clear() {
        synchronized(this) {
            _pages.value = emptyList()
            dir.deleteRecursively()
        }
    }

    fun snapshotFile(id: String): File = File(dir, "$id.jpg")

    /** A new snapshot was written for [id]; the list reloads its image. */
    fun snapshotSaved(id: String) {
        if (page(id) == null) {
            // Removed while the image was being written.
            snapshotFile(id).delete()
            return
        }
        val at = now()
        write { pages -> pages.map { if (it.id == id) it.copy(shotAt = at) else it } }
    }

    private fun write(change: (List<KeptPage>) -> List<KeptPage>) {
        synchronized(this) {
            val next = change(_pages.value)
            _pages.value = next
            dir.mkdirs()
            val temp = File(dir, "$FILE_NAME.tmp")
            temp.writeText(encode(next))
            if (!temp.renameTo(File(dir, FILE_NAME))) temp.delete()
        }
    }

    private fun read(): List<KeptPage> {
        val file = File(dir, FILE_NAME)
        if (!file.exists()) return emptyList()
        // A damaged file loses the list, not the app.
        return runCatching { decode(file.readText()) }.getOrDefault(emptyList())
    }

    companion object {
        private const val FILE_NAME = "pages.json"

        @Volatile private var shared: KeptPagesStore? = null

        fun shared(context: Context): KeptPagesStore =
            shared ?: synchronized(this) {
                shared ?: KeptPagesStore(File(context.applicationContext.filesDir, "kept_pages")).also { shared = it }
            }

        internal fun encode(pages: List<KeptPage>): String {
            val array = JSONArray()
            for (page in pages) {
                array.put(
                    JSONObject()
                        .put("id", page.id)
                        .put("url", page.url)
                        .put("title", page.title)
                        .put("keptAt", page.keptAt)
                        .put("shotAt", page.shotAt),
                )
            }
            return array.toString()
        }

        internal fun decode(text: String): List<KeptPage> {
            val array = JSONArray(text)
            return (0 until array.length()).mapNotNull { index ->
                val item = array.optJSONObject(index) ?: return@mapNotNull null
                val id = item.optString("id")
                val url = item.optString("url")
                if (id.isEmpty() || url.isEmpty()) return@mapNotNull null
                KeptPage(
                    id = id,
                    url = url,
                    title = item.optString("title"),
                    keptAt = item.optLong("keptAt"),
                    shotAt = item.optLong("shotAt"),
                )
            }
        }
    }
}

/** What leaving a page (close, switch, new page) does with it. */
enum class LeaveAction { UPDATE, KEEP, DISCARD }

object KeptPagesPolicy {
    /**
     * A kept page stays and takes its latest address. A throwaway page is
     * dropped unless "Keep every page" is on. Even then a page is not kept
     * when there is nothing worth reopening (no web address, a failed load),
     * when it was just pinned into a space, when the user stopped keeping it
     * during this visit, or when it is a space's tab that was opened and left
     * without browsing away from it.
     */
    fun onLeave(
        isKept: Boolean,
        keepEveryPage: Boolean,
        url: String,
        failed: Boolean,
        pinnedHere: Boolean,
        releasedHere: Boolean,
        openedFromSpace: Boolean,
        browsedAway: Boolean,
    ): LeaveAction = when {
        isKept -> LeaveAction.UPDATE
        !keepEveryPage -> LeaveAction.DISCARD
        !(url.startsWith("https://") || url.startsWith("http://")) -> LeaveAction.DISCARD
        failed || pinnedHere || releasedHere -> LeaveAction.DISCARD
        openedFromSpace && !browsedAway -> LeaveAction.DISCARD
        else -> LeaveAction.KEEP
    }
}
