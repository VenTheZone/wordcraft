package ai.storyteller.wordcraft

import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.os.SystemClock
import android.util.LruCache
import android.view.GestureDetector
import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.KeyCharacterMap
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputMethodManager
import android.widget.Toast
import android.text.InputType
import android.widget.OverScroller
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.json.JSONObject

private object NoRestart

/**
 * The document: pages in a vertical stack, drawn from engine rasters, with the caret, selection
 * and input on top (A4, A5). Page geometry from the engine is in points; [zoom] is px per point.
 */
class DocumentView(context: Context, private val engine: Engine, internal val scope: CoroutineScope) : View(context) {
    private val density = resources.displayMetrics.density
    private val gap = 16 * density
    private var state: ViewState? = null
    /** Pixels per point. 0 until the first layout fits the widest page to the width. */
    var zoom = 0f
        private set
    private val scroller = OverScroller(context)
    private var scrollPxX = 0f
    private var scrollPxY = 0f
    /** Page rectangles in content pixels, from [layoutPages]. */
    private var pageRects: List<RectF> = emptyList()
    /**
     * Sized by [resizeCache] to hold the render window at the current zoom. Bitmap pixels are native
     * memory on API 26+, so the budget follows the window, not the Java heap: a cache smaller than the
     * window would evict pages it still needs and re-render them forever.
     */
    private val cache = object : LruCache<String, Bitmap>(MIN_CACHE_BYTES) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }
    /** Renders in flight, by cache key, so a page isn't requested twice. */
    private val pending = HashMap<String, Job>()
    /** Keys whose render returned nothing; not retried until the key (content or zoom) changes. */
    private val failed = HashSet<String>()
    private val white = Paint().apply { color = Color.WHITE }
    private val paperShadow = Paint().apply { color = Color.argb(40, 0, 0, 0) }
    private val bitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val selectionPaint = Paint().apply { color = Color.argb(70, 59, 91, 219) }
    private val caretPaint = Paint().apply { color = Color.rgb(0x1A, 0x1A, 0x1A) }
    private val preeditPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(0x1A, 0x1A, 0x1A); strokeWidth = 1.5f * density }

    /** The input method's uncommitted text ([DocInputConnection] sets it); drawn at the caret, never sent to the engine. */
    var preedit: String = ""
        set(value) { field = value; invalidate() }

    /** True while a pinch or zoom key is moving [zoom]: pages draw stretched and no renders are requested. */
    private var zooming = false
    private val settle = Runnable { zooming = false; invalidate() }
    private var caretOn = true
    private val blink = object : Runnable { override fun run() { caretOn = !caretOn; invalidate(); postDelayed(this, BLINK_MS) } }

    init {
        setBackgroundColor(Color.rgb(0xE9, 0xEB, 0xEE)); isFocusable = true; isFocusableInTouchMode = true
        // The default focus highlight is a dark overlay drawn over the whole view; the caret already shows focus.
        defaultFocusHighlightEnabled = false
    }

    fun update(view: ViewState?) {
        if (view == null || view === state) return
        val old = state
        state = view
        layoutPages()
        if ((old?.rev != view.rev || old.caret != view.caret) && !dragSelecting) scrollToCaret()
        restartBlink()
        syncIme()
    }

    private var conn: DocInputConnection? = null
    /** What the input method was last told: the text window, and the document it belongs to. */
    private var imeSent: ImeText? = null
    private var imeDoc = -1L

    override fun onCheckIsTextEditor() = true

    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection {
        outAttrs.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES or InputType.TYPE_TEXT_FLAG_AUTO_CORRECT
        outAttrs.imeOptions = EditorInfo.IME_FLAG_NO_FULLSCREEN or EditorInfo.IME_FLAG_NO_EXTRACT_UI or EditorInfo.IME_ACTION_NONE
        val st = engine.view.value
        val ime = st?.ime
        outAttrs.initialSelStart = ime?.let { minOf(it.cursor, it.anchor) } ?: 0
        outAttrs.initialSelEnd = ime?.let { maxOf(it.cursor, it.anchor) } ?: 0
        imeSent = ime; imeDoc = st?.docId ?: -1L
        return DocInputConnection(this, engine).also { it.ctx = ime; outAttrs.initialCapsMode = it.getCursorCapsMode(outAttrs.inputType); conn = it }
    }

    /**
     * Tell the input method what the engine's view now holds: restart it when the paragraph or
     * document changed, else report the selection. Waits while the connection has edits in flight
     * (its own text is ahead of the engine's), and reads the engine's latest view, not the
     * composition's, which can lag.
     */
    internal fun syncIme() {
        val st = engine.view.value ?: return
        val c = conn
        if (c != null && c.busy) return
        val ni = st.ime
        c?.ctx = ni
        val old = imeSent; val oldDoc = imeDoc
        if (ni == old && st.docId == oldDoc) return
        val restart = ni == null || old == null || ni.para != old.para || st.docId != oldDoc
        if (restart && preedit.isNotEmpty() && c != null && st.docId == oldDoc) {
            // The word being typed goes into the document before the keyboard starts over. Its job
            // runs syncIme again when it is done. Another document gets nothing: it is dropped below.
            c.finishComposingText()
            return
        }
        imeSent = ni; imeDoc = st.docId
        val imm = context.getSystemService(InputMethodManager::class.java) ?: return
        if (restart) {
            preedit = ""
            imm.restartInput(this)
        } else if (ni != null && old != null) {
            // While composing, the keyboard counts the pre-edit as text: the caret is after it, and it is the composing range.
            val s0 = minOf(ni.cursor, ni.anchor)
            if (preedit.isEmpty()) imm.updateSelection(this, s0, maxOf(ni.cursor, ni.anchor), -1, -1)
            else imm.updateSelection(this, s0 + preedit.length, s0 + preedit.length, s0, s0 + preedit.length)
        }
    }

    /** What [restartImeOnce] last restarted for. */
    private var restartMark: Any? = NoRestart

    /** [restartIme], but not again for the same [ctx]: the new keyboard session will ask the same thing. */
    internal fun restartImeOnce(ctx: ImeText?) {
        if (restartMark == ctx) return
        restartMark = ctx
        restartIme()
    }

    /** Ask the keyboard to start over, after the call it is in is done. */
    internal fun restartIme() { post { context.getSystemService(InputMethodManager::class.java)?.restartInput(this) } }

    /** Solid caret now; blinking resumes after [BLINK_MS] if the view is focused and the selection is collapsed. */
    private fun restartBlink() {
        removeCallbacks(blink)
        caretOn = true
        invalidate()
        if (isAttachedToWindow && hasFocus() && state?.selection.isNullOrEmpty()) postDelayed(blink, BLINK_MS)
    }

    override fun onFocusChanged(gainFocus: Boolean, direction: Int, previouslyFocusedRect: Rect?) {
        super.onFocusChanged(gainFocus, direction, previouslyFocusedRect)
        restartBlink()
    }
    override fun onAttachedToWindow() { super.onAttachedToWindow(); requestFocus(); restartBlink() }
    override fun onDetachedFromWindow() { removeCallbacks(blink); removeCallbacks(settle); zooming = false; super.onDetachedFromWindow() }

    private fun layoutPages() {
        val pages = state?.pages ?: return
        if (width == 0 || pages.isEmpty()) return
        if (zoom == 0f) zoom = ((width - 2 * gap) / (pages.maxOf { it.w })).coerceIn(MIN_ZOOM, MAX_ZOOM)
        var y = gap
        val contentW = maxOf(width.toFloat(), pages.maxOf { it.w } * zoom + 2 * gap)
        pageRects = pages.map { p -> val w = p.w * zoom; val h = p.h * zoom; RectF((contentW - w) / 2, y, (contentW + w) / 2, y + h).also { y += h + gap } }
        clampScroll()
        resizeCache(pages)
    }

    /** Room for the most pages the render window can hold (viewport straddle plus a neighbour each side), plus two spare. */
    private fun resizeCache(pages: List<PageInfo>) {
        val pageBytes = pages.maxOf { val s = renderScale(it); Math.ceil((it.w * s).toDouble()) * Math.ceil((it.h * s).toDouble()) * 4 }
        val shortest = pages.minOf { it.h * zoom }
        val windowPages = Math.ceil((height + gap) / (shortest + gap).toDouble()) + 1 + 2
        cache.resize(((windowPages + 2) * pageBytes).coerceIn(MIN_CACHE_BYTES.toDouble(), Int.MAX_VALUE.toDouble()).toInt())
    }

    // Only a width change refits the zoom; a height change (the soft keyboard) keeps the user's zoom.
    // A shorter view (the soft keyboard came up) can hide the caret behind it: scroll it back into sight.
    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        if (w != oldw) zoom = 0f
        layoutPages()
        if (w == oldw && h < oldh) scrollToCaret()
    }

    private fun contentHeight() = (pageRects.lastOrNull()?.bottom ?: 0f) + gap
    private fun contentWidth() = (pageRects.maxOfOrNull { it.right } ?: 0f) + gap
    private fun clampScroll() {
        scrollPxY = scrollPxY.coerceIn(0f, maxOf(0f, contentHeight() - height))
        scrollPxX = scrollPxX.coerceIn(0f, maxOf(0f, contentWidth() - width))
    }

    /** View pixel → (page, x pt, y pt), or null between pages. */
    fun toPage(x: Float, y: Float): Triple<Int, Float, Float>? {
        if (zoom <= 0f) return null
        val cx = x + scrollPxX; val cy = y + scrollPxY
        val i = pageRects.indexOfFirst { cy >= it.top - gap / 2 && cy <= it.bottom + gap / 2 }.takeIf { it >= 0 } ?: return null
        val r = pageRects[i]
        return Triple(i, ((cx - r.left) / zoom).coerceIn(0f, r.width() / zoom), ((cy - r.top) / zoom).coerceIn(0f, r.height() / zoom))
    }

    private fun placeAt(page: Int, x: Float, y: Float, extend: Boolean = false) {
        // The caret moves away from the word being composed: put the word in the document first (its
        // job is queued ahead of the caret move below), then let the keyboard start over.
        if (preedit.isNotEmpty()) {
            conn?.finishComposingText() ?: run { preedit = "" }
            context.getSystemService(InputMethodManager::class.java)?.restartInput(this)
        }
        engine.run("caret.set", JSONObject().put("page", page).put("x", x.toDouble()).put("y", y.toDouble()).put("extend", extend))
    }

    /** Scroll so the caret is inside the viewport (inset 48 dp), putting it a third of the way down when it isn't. */
    fun scrollToCaret() {
        val c = state?.caret ?: return
        val r = pageRects.getOrNull(c.page) ?: return
        val inset = 48 * density
        val left = r.left + c.x * zoom; val top = r.top + c.top * zoom; val bottom = top + c.height * zoom
        var moved = false
        if (top < scrollPxY + inset || bottom > scrollPxY + height - inset) { scrollPxY = top - height / 3f; moved = true }
        if (left < scrollPxX + inset || left > scrollPxX + width - inset) { scrollPxX = left - width / 3f; moved = true }
        if (!moved) return
        scroller.forceFinished(true)
        clampScroll()
        invalidate()
    }

    /** Set [zoom] (px per point, clamped), keeping the page point under view pixel ([fx], [fy]) in place. */
    private fun zoomTo(target: Float, fx: Float, fy: Float) {
        val z = target.coerceIn(MIN_ZOOM, MAX_ZOOM)
        if (zoom <= 0f || !z.isFinite() || z == zoom) return
        val anchor = toPage(fx, fy)
        val ratio = z / zoom
        val oldX = scrollPxX; val oldY = scrollPxY
        zoom = z
        zooming = true
        removeCallbacks(settle)
        layoutPages()
        val r = anchor?.let { pageRects.getOrNull(it.first) }
        if (anchor != null && r != null) {
            scrollPxX = r.left + anchor.second * zoom - fx; scrollPxY = r.top + anchor.third * zoom - fy
        } else { scrollPxX = (oldX + fx) * ratio - fx; scrollPxY = (oldY + fy) * ratio - fy }
        clampScroll()
        invalidate()
    }

    /** Renders at the final zoom start [SETTLE_MS] after the last zoom step. */
    private fun settleSoon() { removeCallbacks(settle); postDelayed(settle, SETTLE_MS) }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean = when (keyCode) {
        KeyEvent.KEYCODE_ZOOM_IN -> { zoomTo(zoom * 1.25f, width / 2f, height / 2f); settleSoon(); true }
        KeyEvent.KEYCODE_ZOOM_OUT -> { zoomTo(zoom / 1.25f, width / 2f, height / 2f); settleSoon(); true }
        else -> hardwareKey(keyCode, event) || super.onKeyDown(keyCode, event)
    }

    /** Put the word being typed into the document; the engine job it queues runs before any command sent after this returns. */
    fun commitComposition() { conn?.finishComposingText() }

    private fun cmd(id: String, params: JSONObject = JSONObject()): Boolean { engine.run(id, params); return true }

    /**
     * A key from a hardware keyboard (or one the soft keyboard sent as a key event), as the same
     * commands the desktop keyboard runs. True when it was taken. Characters the soft keyboard
     * commits as text never arrive here.
     */
    private fun hardwareKey(k: Int, e: KeyEvent): Boolean {
        // A key that edits or moves ends the word being composed; a bare modifier doesn't.
        if (preedit.isNotEmpty() && !KeyEvent.isModifierKey(k)) conn?.finishComposingText()
        val ctrl = e.isCtrlPressed || e.isMetaPressed; val shift = e.isShiftPressed
        val extend = JSONObject().put("extend", shift)
        when (k) {
            KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> if (!ctrl) return cmd(if (shift) "text.lineBreak" else "text.newParagraph")
            KeyEvent.KEYCODE_DEL -> return cmd(if (ctrl) "text.deleteWordBack" else "text.backspace")
            KeyEvent.KEYCODE_FORWARD_DEL -> return cmd(if (ctrl) "text.deleteWordForward" else "text.delete")
            KeyEvent.KEYCODE_TAB -> if (!ctrl) return cmd(if (shift) "text.backTab" else "text.tab")
            KeyEvent.KEYCODE_DPAD_LEFT -> return cmd(if (ctrl) "caret.wordLeft" else "caret.left", extend)
            KeyEvent.KEYCODE_DPAD_RIGHT -> return cmd(if (ctrl) "caret.wordRight" else "caret.right", extend)
            KeyEvent.KEYCODE_DPAD_UP -> if (!ctrl) return cmd("caret.up", extend)
            KeyEvent.KEYCODE_DPAD_DOWN -> if (!ctrl) return cmd("caret.down", extend)
            KeyEvent.KEYCODE_MOVE_HOME -> return cmd(if (ctrl) "caret.docStart" else "caret.home", extend)
            KeyEvent.KEYCODE_MOVE_END -> return cmd(if (ctrl) "caret.docEnd" else "caret.end", extend)
            KeyEvent.KEYCODE_PAGE_UP -> if (!ctrl) return cmd("caret.pageUp", extend)
            KeyEvent.KEYCODE_PAGE_DOWN -> if (!ctrl) return cmd("caret.pageDown", extend)
        }
        val function = k in KeyEvent.KEYCODE_F1..KeyEvent.KEYCODE_F12
        if (ctrl || function) {
            val name = keyName(k, e) ?: return false
            if (ctrl && k == KeyEvent.KEYCODE_V && !shift && !e.isAltPressed) return paste()
            val key = (if (ctrl) "Mod+" else "") + (if (e.isAltPressed) "Alt+" else "") + (if (shift) "Shift+" else "") + name
            engine.call("shortcut", JSONObject().put("key", key))
            return true
        }
        val u = e.unicodeChar
        if (u in 32..0x10FFFF && u !in 127..159) return cmd("text.insert", JSONObject().put("text", String(Character.toChars(u))))
        return false
    }

    /** The key's name as the command registry writes shortcuts (`B`, `7`, `F7`, `Left`, `]`), or null for a modifier or unknown key. */
    private fun keyName(k: Int, e: KeyEvent): String? = when (k) {
        in KeyEvent.KEYCODE_F1..KeyEvent.KEYCODE_F12 -> "F${k - KeyEvent.KEYCODE_F1 + 1}"
        KeyEvent.KEYCODE_SPACE -> "Space"
        KeyEvent.KEYCODE_INSERT -> "Insert"
        KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> "Enter"
        KeyEvent.KEYCODE_TAB -> "Tab"
        KeyEvent.KEYCODE_ESCAPE -> "Escape"
        KeyEvent.KEYCODE_DEL -> "Backspace"
        KeyEvent.KEYCODE_FORWARD_DEL -> "Delete"
        KeyEvent.KEYCODE_DPAD_LEFT -> "Left"
        KeyEvent.KEYCODE_DPAD_RIGHT -> "Right"
        KeyEvent.KEYCODE_DPAD_UP -> "Up"
        KeyEvent.KEYCODE_DPAD_DOWN -> "Down"
        KeyEvent.KEYCODE_PAGE_UP -> "PageUp"
        KeyEvent.KEYCODE_PAGE_DOWN -> "PageDown"
        KeyEvent.KEYCODE_MOVE_HOME -> "Home"
        KeyEvent.KEYCODE_MOVE_END -> "End"
        in KeyEvent.KEYCODE_A..KeyEvent.KEYCODE_Z -> ('A' + (k - KeyEvent.KEYCODE_A)).toString()
        in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9 -> ('0' + (k - KeyEvent.KEYCODE_0)).toString()
        KeyEvent.KEYCODE_COMMA -> ","
        KeyEvent.KEYCODE_PERIOD -> "."
        KeyEvent.KEYCODE_MINUS -> "-"
        KeyEvent.KEYCODE_EQUALS -> "="
        KeyEvent.KEYCODE_LEFT_BRACKET -> "["
        KeyEvent.KEYCODE_RIGHT_BRACKET -> "]"
        KeyEvent.KEYCODE_SEMICOLON -> ";"
        KeyEvent.KEYCODE_APOSTROPHE -> "'"
        KeyEvent.KEYCODE_SLASH -> "/"
        KeyEvent.KEYCODE_BACKSLASH -> "\\"
        KeyEvent.KEYCODE_GRAVE -> "`"
        // Any other key: its own character, unshifted (so Ctrl+Shift+, is `,` not `<`).
        else -> e.getUnicodeChar(0).takeIf { it in 33..0x10FFFF && it and KeyCharacterMap.COMBINING_ACCENT_MASK == 0 }?.let { String(Character.toChars(it)).uppercase() }
    }

    /**
     * Ctrl+V: the system clipboard's text, at most [MAX_PASTE] characters. An empty paste tells the
     * engine to use its own last copy. A clip item that is a content URI is not opened: that would be
     * file I/O on the UI thread.
     */
    internal fun paste(): Boolean {
        var text = try {
            val item = context.getSystemService(ClipboardManager::class.java)?.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)
            item?.text?.toString() ?: item?.takeIf { it.uri == null }?.coerceToText(context)?.toString().orEmpty()
        } catch (e: Exception) { "" }
        if (text.length > MAX_PASTE) {
            var cut = MAX_PASTE
            if (Character.isHighSurrogate(text[cut - 1])) cut -= 1
            text = text.substring(0, cut)
            Toast.makeText(context, "Pasted text was cut at 1 MB", Toast.LENGTH_SHORT).show()
        }
        engine.call("paste", JSONObject().put("text", text))
        return true
    }

    /**
     * Px per point to render [page] at: [zoom], but never so large that one page bitmap passes
     * [MAX_PAGE_PIXELS] (also keeps a large page under the bridge's 4096 px side cap). The bitmap is
     * drawn stretched to the zoomed page rectangle.
     * ponytail: past about 4 px/pt on a Letter page the text is upscaled and softer; tiles via the
     * render crate's `render_area` are the upgrade.
     */
    private fun renderScale(page: PageInfo): Float = minOf(zoom, Math.sqrt(MAX_PAGE_PIXELS / (page.w.toDouble() * page.h)).toFloat())

    private fun cacheKey(i: Int, page: PageInfo, scale: Float) = "$i:${page.key}:$scale"

    override fun onDraw(canvas: Canvas) {
        val pages = state?.pages ?: return
        if (scroller.computeScrollOffset()) { scrollPxX = scroller.currX.toFloat(); scrollPxY = scroller.currY.toFloat(); clampScroll(); postInvalidateOnAnimation() }
        // ponytail: whole-page bitmaps for the render window only (pages in view plus one neighbour
        // each side), with the cache sized to that window; tiles via the engine's `render_area` are
        // the upgrade for very large zooms or documents.
        val first = pageRects.indexOfFirst { it.bottom > scrollPxY }
        val last = pageRects.indexOfLast { it.top < scrollPxY + height }
        val window = if (first in 0..last) maxOf(0, first - 1)..minOf(pageRects.size - 1, last + 1) else IntRange.EMPTY
        val wanted = window.mapNotNull { i -> pages.getOrNull(i)?.let { cacheKey(i, it, renderScale(it)) } }.toSet()
        // Pages that left the window: cancel their queued renders so they don't hold up edits on the engine thread.
        pending.keys.filter { it !in wanted }.forEach { pending.remove(it)?.cancel() }
        newest.keys.retainAll { it in window }
        if (failed.size > 64) failed.retainAll(wanted)
        canvas.save()
        canvas.translate(-scrollPxX, -scrollPxY)
        for (i in window) {
            val r = pageRects.getOrNull(i) ?: continue
            val page = pages.getOrNull(i) ?: continue
            canvas.drawRect(r.left + 2 * density, r.top + 2 * density, r.right + 2 * density, r.bottom + 2 * density, paperShadow)
            val scale = renderScale(page)
            val key = cacheKey(i, page, scale)
            // While zooming, the cache has nothing at the moving scale; draw the stretched newest bitmap and render once it settles.
            val bmp = cache.get(key) ?: newestBitmap(i).also { if (!zooming) requestRender(i, key, scale) }
            if (bmp != null) canvas.drawBitmap(bmp, null, r, bitmapPaint) else canvas.drawRect(r, white)
        }
        drawOverlays(canvas)
        canvas.restore()
    }

    /** The page's last bitmap at any scale or content, shown stretched until the new one is ready. */
    private val newest = HashMap<Int, Bitmap>()
    private fun newestBitmap(i: Int): Bitmap? = newest[i]?.takeIf { !it.isRecycled }

    private fun requestRender(i: Int, key: String, scale: Float) {
        if (key in failed || pending[key]?.isActive == true) return
        pending[key] = scope.launch {
            val bmp = engine.render(i, scale)
            pending.remove(key)
            if (bmp == null) { failed.add(key); return@launch }
            cache.put(key, bmp); newest[i] = bmp; invalidate()
        }
    }

    /** Selection boxes, the caret and the pre-edit text, in content coordinates. Reads state only. */
    private fun drawOverlays(canvas: Canvas) {
        val st = state ?: return
        for (b in st.selection) {
            val r = pageRects.getOrNull(b.page) ?: continue
            val top = r.top + b.y * zoom; val bottom = top + b.h * zoom
            if (bottom < scrollPxY || top > scrollPxY + height) continue
            val left = r.left + b.x * zoom
            canvas.drawRect(left, top, left + b.w * zoom, bottom, selectionPaint)
        }
        val c = st.caret ?: return
        val r = pageRects.getOrNull(c.page) ?: return
        val x = r.left + c.x * zoom; val top = r.top + c.top * zoom; val h = c.height * zoom
        var caretX = x
        if (preedit.isNotEmpty()) {
            preeditPaint.textSize = h * 0.75f
            val base = top + h * 0.8f
            canvas.drawText(preedit, x, base, preeditPaint)
            val w = preeditPaint.measureText(preedit)
            canvas.drawLine(x, base + 2 * density, x + w, base + 2 * density, preeditPaint)
            caretX = x + w
        }
        if (st.selection.isEmpty() && (caretOn || !hasFocus())) canvas.drawRect(caretX, top, caretX + 2 * density, top + h, caretPaint)
    }

    /** True from a long press until the finger lifts: moves extend the selection instead of scrolling. */
    private var dragSelecting = false
    private var lastDragMs = 0L
    private var lastDrag: Triple<Int, Float, Float>? = null
    /** After a pinch the leftover finger must not scroll or tap; wait for a fresh ACTION_DOWN. */
    private var ignoreUntilDown = false

    /** Extend the selection to the finger; throttled to one call per [DRAG_MS] and to moves over 1 pt unless [force]. */
    private fun dragTo(e: MotionEvent, force: Boolean) {
        val now = SystemClock.uptimeMillis()
        if (!force && now - lastDragMs < DRAG_MS) return
        val p = toPage(e.x, e.y) ?: return
        val last = lastDrag
        if (last != null && last.first == p.first && Math.hypot((p.second - last.second).toDouble(), (p.third - last.third).toDouble()) <= 1.0) return
        lastDragMs = now; lastDrag = p
        placeAt(p.first, p.second, p.third, extend = true)
    }

    private val gestures = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent): Boolean { scroller.forceFinished(true); return true }
        override fun onSingleTapUp(e: MotionEvent): Boolean {
            val p = toPage(e.x, e.y) ?: return true
            placeAt(p.first, p.second, p.third)
            requestFocus()
            context.getSystemService(InputMethodManager::class.java)?.showSoftInput(this@DocumentView, 0)
            return true
        }
        override fun onDoubleTap(e: MotionEvent): Boolean {
            val p = toPage(e.x, e.y) ?: return true
            placeAt(p.first, p.second, p.third)
            engine.run("select.word")
            return true
        }
        override fun onLongPress(e: MotionEvent) {
            val p = toPage(e.x, e.y) ?: return
            placeAt(p.first, p.second, p.third)
            engine.run("select.word")
            dragSelecting = true; lastDrag = p; lastDragMs = SystemClock.uptimeMillis()
            performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        }
        override fun onScroll(e1: MotionEvent?, e2: MotionEvent, dx: Float, dy: Float): Boolean { scrollPxX += dx; scrollPxY += dy; clampScroll(); invalidate(); return true }
        override fun onFling(e1: MotionEvent?, e2: MotionEvent, vx: Float, vy: Float): Boolean {
            scroller.fling(scrollPxX.toInt(), scrollPxY.toInt(), -vx.toInt(), -vy.toInt(), 0, maxOf(0, (contentWidth() - width).toInt()), 0, maxOf(0, (contentHeight() - height).toInt()))
            postInvalidateOnAnimation(); return true
        }
    })

    private val pinch = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScaleBegin(detector: ScaleGestureDetector): Boolean { removeCallbacks(settle); return true }
        override fun onScale(detector: ScaleGestureDetector): Boolean { zoomTo(zoom * detector.scaleFactor, detector.focusX, detector.focusY); return true }
        override fun onScaleEnd(detector: ScaleGestureDetector) { settleSoon() }
    }).apply { isQuickScaleEnabled = false }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val wasPinching = pinch.isInProgress
        pinch.onTouchEvent(event)
        if (pinch.isInProgress) {
            if (!wasPinching) { // the pinch took over: drop any tap, long press or scroll in progress
                val cancel = MotionEvent.obtain(event).apply { action = MotionEvent.ACTION_CANCEL }
                gestures.onTouchEvent(cancel); cancel.recycle()
                dragSelecting = false; ignoreUntilDown = true
            }
            return true
        }
        if (event.actionMasked == MotionEvent.ACTION_DOWN) ignoreUntilDown = false
        if (ignoreUntilDown) return true
        if (dragSelecting) {
            when (event.actionMasked) {
                MotionEvent.ACTION_MOVE -> dragTo(event, force = false)
                MotionEvent.ACTION_UP -> { dragTo(event, force = true); dragSelecting = false; gestures.onTouchEvent(event) }
                MotionEvent.ACTION_CANCEL -> { dragSelecting = false; gestures.onTouchEvent(event) }
            }
            return true
        }
        return gestures.onTouchEvent(event) || super.onTouchEvent(event)
    }

    private companion object {
        const val MIN_CACHE_BYTES = 64 * 1024 * 1024
        const val MIN_ZOOM = 0.25f
        const val MAX_ZOOM = 5f
        /** 8 megapixels: one page bitmap is at most 32 MB. */
        const val MAX_PAGE_PIXELS = 8_000_000.0
        const val MAX_PASTE = 1 shl 20
        const val BLINK_MS = 530L
        const val SETTLE_MS = 150L
        const val DRAG_MS = 32L
    }
}
