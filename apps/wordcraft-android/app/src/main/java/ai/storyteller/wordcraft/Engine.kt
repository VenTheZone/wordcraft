package ai.storyteller.wordcraft

import android.graphics.Bitmap
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.Executors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/** Engine geometry is in page points (1/72 inch), page-local, origin top left. */
data class PageInfo(val w: Float, val h: Float, val key: String)
data class CaretInfo(val page: Int, val x: Float, val top: Float, val height: Float)
data class Box(val page: Int, val x: Float, val y: Float, val w: Float, val h: Float)
/** Text around the caret for the input method; offsets are UTF-16 indexes into [text]. */
data class ImeText(val text: String, val cursor: Int, val anchor: Int, val para: String)
data class ViewState(
    val docId: Long, val rev: Long, val dirty: Boolean, val title: String,
    val canUndo: Boolean, val canRedo: Boolean, val status: String,
    val pages: List<PageInfo>, val caret: CaretInfo?, val selection: List<Box>,
    val format: JSONObject, val ime: ImeText?,
)

sealed interface EngineEvent {
    data class Error(val message: String) : EngineEvent
    data class UiRequest(val request: JSONObject) : EngineEvent
    data class Clipboard(val text: String, val html: String) : EngineEvent
}

/**
 * The one thread that talks to the engine. Every call runs there in order, so the session is
 * never touched from two threads. Failures become [EngineEvent.Error], never exceptions.
 */
class Engine {
    // 16 MB stack: Rust layout, import and render recurse deeply, and a stack overflow is a SIGSEGV no catch stops.
    private val dispatcher = Executors.newSingleThreadExecutor { Thread(null, it, "wordcraft-engine", 16L shl 20) }.asCoroutineDispatcher()
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val _view = MutableStateFlow<ViewState?>(null)
    val view: StateFlow<ViewState?> = _view
    // A channel, not a shared flow: an event waits here until the (single) collector arrives, so an
    // error or a copy reported before the Activity collects, or while it is recreated, isn't lost.
    private val _events = Channel<EngineEvent>(Channel.BUFFERED)
    val events: Flow<EngineEvent> = _events.receiveAsFlow()

    private fun fail(message: String): JSONObject? { _events.trySend(EngineEvent.Error(message)); return null }

    /** On the engine thread: one call, its `ok` payload, or null after reporting the error. */
    internal fun callNow(method: String, params: JSONObject = JSONObject(), report: Boolean = true): JSONObject? = try {
        val reply = JSONObject(Native.call(method, params.toString()))
        if (reply.has("error")) fail(reply.optString("error"))
        else {
            val ok = reply.opt("ok")
            if (ok is JSONObject) absorb(ok, report)
            ok as? JSONObject ?: JSONObject().put("value", ok)
        }
    } catch (e: Throwable) { fail(e.message ?: "The engine failed") } // absorb included: nothing escapes into the caller's coroutine

    /** Take what an edit reply carries: the view, the app requests, the clipboard, the error (unless [report] is off: the caller shows it). */
    private fun absorb(ok: JSONObject, report: Boolean) {
        (ok.optJSONObject("view") ?: ok.takeIf { it.has("pages") })?.let { parseView(it)?.let { v -> _view.value = v } }
        ok.optJSONArray("uiRequests")?.let { a -> for (i in 0 until a.length()) a.optJSONObject(i)?.let { _events.trySend(EngineEvent.UiRequest(it)) } }
        ok.optJSONObject("clipboard")?.let { _events.trySend(EngineEvent.Clipboard(it.optString("text"), it.optString("html"))) }
        if (report && ok.has("error") && !ok.isNull("error")) _events.trySend(EngineEvent.Error(ok.optString("error")))
    }

    suspend fun init(filesDir: String, locale: String, sample: Boolean): JSONObject? = withContext(dispatcher) {
        callNow("init", JSONObject().put("filesDir", filesDir).put("locale", locale).put("sample", sample))
    }
    fun run(id: String, params: JSONObject = JSONObject(), ime: Boolean = false) { scope.launch { runNow(id, params, ime) } }
    suspend fun runAwait(id: String, params: JSONObject = JSONObject(), ime: Boolean = false): JSONObject? = withContext(dispatcher) { runNow(id, params, ime) }
    internal fun runNow(id: String, params: JSONObject = JSONObject(), ime: Boolean = false) = callNow("run", JSONObject().put("id", id).put("params", params).put("ime", ime))
    /**
     * Run [block] as one job on the engine thread. The job is queued as soon as this is called, and
     * nothing queued later runs in the middle of it. [block] calls [runNow] and [callNow].
     */
    internal suspend fun job(block: Engine.() -> Unit) = withContext(dispatcher) { block() }
    fun call(method: String, params: JSONObject = JSONObject(), report: Boolean = true) { scope.launch { callNow(method, params, report) } }
    suspend fun callAwait(method: String, params: JSONObject = JSONObject(), report: Boolean = true): JSONObject? = withContext(dispatcher) { callNow(method, params, report) }

    /**
     * Close the active document, discarding it, only if it is still at revision [rev]: true closed,
     * false the engine refused, null the document changed since [rev] was read. Check and close are
     * one engine job, so no edit lands between them.
     */
    suspend fun closeIfRev(rev: Long): Boolean? = withContext(dispatcher) {
        if (callNow("view")?.optLong("rev", -1L) != rev) return@withContext null
        val r = runNow("file.closeDocument", JSONObject().put("force", true))
        r != null && r.isNull("error")
    }

    /** `markSaved` only if the document is still at revision [rev] (what was written): an edit made while the file was being written stays dirty. */
    suspend fun markSavedIf(name: String, rev: Long): Boolean = withContext(dispatcher) {
        if (callNow("view")?.optLong("rev", -1L) != rev) return@withContext false
        callNow("markSaved", JSONObject().put("name", name)) != null
    }

    /** Page [page] at [scale] px per point, or null (bad page, out of memory, engine error). */
    suspend fun render(page: Int, scale: Float): Bitmap? = withContext(dispatcher) {
        val bytes = try { Native.render(page, scale) } catch (e: Throwable) { return@withContext null }
        if (bytes.size < 8) return@withContext null
        val head = ByteBuffer.wrap(bytes, 0, 8).order(ByteOrder.LITTLE_ENDIAN)
        val w = head.getInt(0); val h = head.getInt(4)
        if (w <= 0 || h <= 0 || bytes.size.toLong() != 8L + w.toLong() * h * 4) return@withContext null
        try {
            Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also { it.copyPixelsFromBuffer(ByteBuffer.wrap(bytes, 8, w * h * 4)) }
        } catch (e: Throwable) { null }
    }

    /** The bytes of a save and the document revision they were made at. */
    class Saved(val bytes: ByteArray, val rev: Long)

    /** The document as [name]'s format and its revision, read in one engine job; the app writes it, then calls [markSavedIf]. */
    suspend fun save(name: String): Result<Saved> = withContext(dispatcher) {
        val out = try { Native.save(name) } catch (e: Throwable) { return@withContext Result.failure(e) }
        when (out.firstOrNull()) {
            0.toByte() -> Result.success(Saved(out.copyOfRange(1, out.size), callNow("view")?.optLong("rev", -1L) ?: -1L))
            else -> Result.failure(IllegalStateException(if (out.size > 1) String(out, 1, out.size - 1) else "The document couldn't be saved"))
        }
    }

    /** A number the engine sent, or NaN when missing or not a number; callers drop what isn't finite. */
    private fun JSONObject.num(key: String) = optDouble(key, Double.NaN).toFloat()

    private fun parseView(o: JSONObject): ViewState? = try {
        val pages = o.optJSONArray("pages") ?: JSONArray()
        ViewState(
            docId = o.optLong("docId"), rev = o.optLong("rev"), dirty = o.optBoolean("dirty"), title = o.optString("title"),
            canUndo = o.optBoolean("canUndo"), canRedo = o.optBoolean("canRedo"), status = o.optString("status"),
            pages = (0 until pages.length()).mapNotNull { i ->
                pages.optJSONObject(i)?.let { PageInfo(it.num("w"), it.num("h"), it.optString("key")) }?.takeIf { it.w.isFinite() && it.h.isFinite() && it.w > 0f && it.h > 0f }
            },
            caret = o.optJSONObject("caret")?.let { CaretInfo(it.optInt("page"), it.num("x"), it.num("top"), it.num("height")) }
                ?.takeIf { it.x.isFinite() && it.top.isFinite() && it.height.isFinite() },
            selection = o.optJSONArray("selection")?.let { a ->
                (0 until a.length()).mapNotNull { i ->
                    a.optJSONObject(i)?.let { Box(it.optInt("page"), it.num("x"), it.num("y"), it.num("w"), it.num("h")) }
                        ?.takeIf { it.x.isFinite() && it.y.isFinite() && it.w.isFinite() && it.h.isFinite() }
                }
            } ?: emptyList(),
            format = o.optJSONObject("format") ?: JSONObject(),
            ime = o.optJSONObject("ime")?.let { ImeText(it.optString("text"), it.optInt("cursor"), it.optInt("anchor"), it.optString("para")) },
        )
    } catch (e: Exception) { null }
}
