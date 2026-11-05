package ai.storyteller.wordcraft

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.LruCache
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.widget.OverScroller
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

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
    private val cache = object : LruCache<String, Bitmap>(maxOf(32, (Runtime.getRuntime().maxMemory() / 4 / 1024 / 1024).toInt()).coerceAtMost(160) * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }
    /** Renders in flight, by cache key, so a page isn't requested twice. */
    private val pending = HashMap<String, Job>()
    private val white = Paint().apply { color = Color.WHITE }
    private val paperShadow = Paint().apply { color = Color.argb(40, 0, 0, 0) }
    private val bitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG)

    init { setBackgroundColor(Color.rgb(0xE9, 0xEB, 0xEE)); isFocusable = true; isFocusableInTouchMode = true }

    fun update(view: ViewState?) {
        if (view == null || view === state) return
        state = view
        layoutPages()
        invalidate()
    }

    private fun layoutPages() {
        val pages = state?.pages ?: return
        if (width == 0 || pages.isEmpty()) return
        if (zoom == 0f) zoom = ((width - 2 * gap) / (pages.maxOf { it.w })).coerceIn(0.2f, 4f)
        var y = gap
        val contentW = maxOf(width.toFloat(), pages.maxOf { it.w } * zoom + 2 * gap)
        pageRects = pages.map { p -> val w = p.w * zoom; val h = p.h * zoom; RectF((contentW - w) / 2, y, (contentW + w) / 2, y + h).also { y += h + gap } }
        clampScroll()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) { zoom = 0f; layoutPages() }

    private fun contentHeight() = (pageRects.lastOrNull()?.bottom ?: 0f) + gap
    private fun contentWidth() = (pageRects.maxOfOrNull { it.right } ?: 0f) + gap
    private fun clampScroll() {
        scrollPxY = scrollPxY.coerceIn(0f, maxOf(0f, contentHeight() - height))
        scrollPxX = scrollPxX.coerceIn(0f, maxOf(0f, contentWidth() - width))
    }

    private fun cacheKey(i: Int, page: PageInfo, scale: Float) = "$i:${page.key}:$scale"

    override fun onDraw(canvas: Canvas) {
        val pages = state?.pages ?: return
        if (scroller.computeScrollOffset()) { scrollPxX = scroller.currX.toFloat(); scrollPxY = scroller.currY.toFloat(); clampScroll(); postInvalidateOnAnimation() }
        // ponytail: whole-page bitmaps, so `newest` is pruned to three screens around the viewport;
        // tiles via the engine's `render_area` are the upgrade for very large zooms or documents.
        newest.keys.retainAll { i -> pageRects.getOrNull(i)?.let { it.bottom >= scrollPxY - 3 * height && it.top <= scrollPxY + 4 * height } == true }
        canvas.save()
        canvas.translate(-scrollPxX, -scrollPxY)
        for ((i, r) in pageRects.withIndex()) {
            if (r.bottom < scrollPxY - height || r.top > scrollPxY + 2 * height) continue  // one screen of margin
            val page = pages.getOrNull(i) ?: continue
            canvas.drawRect(r.left + 2 * density, r.top + 2 * density, r.right + 2 * density, r.bottom + 2 * density, paperShadow)
            val bmp = cache.get(cacheKey(i, page, zoom)) ?: newestBitmap(i).also { requestRender(i, page) }
            if (bmp != null) canvas.drawBitmap(bmp, null, r, bitmapPaint) else canvas.drawRect(r, white)
        }
        drawOverlays(canvas)
        canvas.restore()
    }

    /** The page's last bitmap at any scale or content, shown stretched until the new one is ready. */
    private val newest = HashMap<Int, Bitmap>()
    private fun newestBitmap(i: Int): Bitmap? = newest[i]?.takeIf { !it.isRecycled }

    private fun requestRender(i: Int, page: PageInfo) {
        val scale = zoom
        val key = cacheKey(i, page, scale)
        if (pending[key]?.isActive == true) return
        pending[key] = scope.launch {
            val bmp = engine.render(i, scale)
            pending.remove(key)
            if (bmp != null) { cache.put(key, bmp); newest[i] = bmp; invalidate() }
        }
    }

    /** A4 draws the selection and the caret here. */
    private fun drawOverlays(canvas: Canvas) {}

    private val gestures = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent): Boolean { scroller.forceFinished(true); return true }
        override fun onScroll(e1: MotionEvent?, e2: MotionEvent, dx: Float, dy: Float): Boolean { scrollPxX += dx; scrollPxY += dy; clampScroll(); invalidate(); return true }
        override fun onFling(e1: MotionEvent?, e2: MotionEvent, vx: Float, vy: Float): Boolean {
            scroller.fling(scrollPxX.toInt(), scrollPxY.toInt(), -vx.toInt(), -vy.toInt(), 0, maxOf(0, (contentWidth() - width).toInt()), 0, maxOf(0, (contentHeight() - height).toInt()))
            postInvalidateOnAnimation(); return true
        }
    })

    override fun onTouchEvent(event: MotionEvent): Boolean = gestures.onTouchEvent(event) || super.onTouchEvent(event)
}
