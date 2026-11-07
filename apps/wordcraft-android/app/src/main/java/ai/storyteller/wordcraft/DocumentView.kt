package ai.storyteller.wordcraft

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
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.OverScroller
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.json.JSONObject

/**
 * The document: pages in a vertical stack, drawn from engine rasters, with the caret, selection
 * and input on top (A4, A5). Page geometry from the engine is in points; [zoom] is px per point.
 */
class DocumentView(context: Context, private val engine: Engine, private val scope: CoroutineScope) : View(context) {
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

    /** The input method's uncommitted text (A5 sets it); drawn at the caret, never sent to the engine. */
    var preedit: String = ""
        set(value) { field = value; invalidate() }

    /** True while a pinch or zoom key is moving [zoom]: pages draw stretched and no renders are requested. */
    private var zooming = false
    private val settle = Runnable { zooming = false; invalidate() }
    private var caretOn = true
    private val blink = object : Runnable { override fun run() { caretOn = !caretOn; invalidate(); postDelayed(this, BLINK_MS) } }

    init { setBackgroundColor(Color.rgb(0xE9, 0xEB, 0xEE)); isFocusable = true; isFocusableInTouchMode = true }

    fun update(view: ViewState?) {
        if (view == null || view === state) return
        val old = state
        state = view
        layoutPages()
        if ((old?.rev != view.rev || old.caret != view.caret) && !dragSelecting) scrollToCaret()
        restartBlink()
    }

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
    override fun onDetachedFromWindow() { removeCallbacks(blink); removeCallbacks(settle); super.onDetachedFromWindow() }

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
        val pageBytes = pages.maxOf { Math.ceil((it.w * zoom).toDouble()) * Math.ceil((it.h * zoom).toDouble()) * 4 }
        val shortest = pages.minOf { it.h * zoom }
        val windowPages = Math.ceil((height + gap) / (shortest + gap).toDouble()) + 1 + 2
        cache.resize(((windowPages + 2) * pageBytes).coerceIn(MIN_CACHE_BYTES.toDouble(), Int.MAX_VALUE.toDouble()).toInt())
    }

    // Only a width change refits the zoom; a height change (the soft keyboard) keeps the user's zoom.
    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) { if (w != oldw) zoom = 0f; layoutPages() }

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
        else -> super.onKeyDown(keyCode, event)
    }

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
        val wanted = window.mapNotNull { i -> pages.getOrNull(i)?.let { cacheKey(i, it, zoom) } }.toSet()
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
            val key = cacheKey(i, page, zoom)
            // While zooming, the cache has nothing at the moving scale; draw the stretched newest bitmap and render once it settles.
            val bmp = cache.get(key) ?: newestBitmap(i).also { if (!zooming) requestRender(i, key) }
            if (bmp != null) canvas.drawBitmap(bmp, null, r, bitmapPaint) else canvas.drawRect(r, white)
        }
        drawOverlays(canvas)
        canvas.restore()
    }

    /** The page's last bitmap at any scale or content, shown stretched until the new one is ready. */
    private val newest = HashMap<Int, Bitmap>()
    private fun newestBitmap(i: Int): Bitmap? = newest[i]?.takeIf { !it.isRecycled }

    private fun requestRender(i: Int, key: String) {
        if (key in failed || pending[key]?.isActive == true) return
        val scale = zoom
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
        const val BLINK_MS = 530L
        const val SETTLE_MS = 150L
        const val DRAG_MS = 32L
    }
}
