package de.kjell.zencompanion.ui.browser

import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Bundle
import android.webkit.WebView
import de.kjell.zencompanion.data.KeptPagesStore
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Back and forward history of kept pages, so switching between them keeps
 * each page's own Back.
 *
 * shortcut: kept in memory only, because `WebView.saveState` is a Parcel-backed
 * Bundle that is not meant for disk. After the app process ends a kept page
 * reopens at its address without its history; upgrade if that is missed.
 */
object KeptPageHistory {
    private val states = HashMap<String, Bundle>()

    @Synchronized
    fun save(id: String, webView: WebView) {
        val bundle = Bundle()
        if (webView.saveState(bundle) != null) states[id] = bundle
    }

    @Synchronized
    fun take(id: String): Bundle? = states[id]

    @Synchronized
    fun forget(id: String) {
        states.remove(id)
    }

    @Synchronized
    fun clear() = states.clear()
}

/** The small picture of a kept page in "On this phone". */
object KeptPageSnapshot {
    /** Wide enough for a two-column grid on a large phone. */
    private const val WIDTH_PX = 480

    private val io = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * Draws what the page shows right now and writes it in the background.
     * Only the WebView is drawn, so the bar and menus never end up in it.
     */
    fun capture(webView: WebView, id: String, store: KeptPagesStore) {
        val width = webView.width
        val height = webView.height
        if (width <= 0 || height <= 0) return
        val scale = minOf(1f, WIDTH_PX.toFloat() / width)
        val bitmap = runCatching {
            Bitmap.createBitmap((width * scale).toInt(), (height * scale).toInt(), Bitmap.Config.RGB_565)
        }.getOrNull() ?: return
        val canvas = Canvas(bitmap)
        canvas.drawColor(android.graphics.Color.WHITE)
        canvas.scale(scale, scale)
        // A WebView draws in page coordinates; move to what is on screen.
        canvas.translate(-webView.scrollX.toFloat(), -webView.scrollY.toFloat())
        webView.draw(canvas)
        val target = store.snapshotFile(id)
        io.launch {
            val written = runCatching {
                target.parentFile?.mkdirs()
                val temp = File(target.path + ".tmp")
                temp.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 72, it) }
                temp.renameTo(target)
            }.getOrDefault(false)
            bitmap.recycle()
            if (written) store.snapshotSaved(id)
        }
    }
}
