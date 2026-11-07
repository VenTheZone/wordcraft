package ai.storyteller.wordcraft

import android.os.Build
import android.text.TextUtils
import android.view.KeyEvent
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.SurroundingText
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import org.json.JSONObject

/** How many code points the [units] UTF-16 units before [end] in [text] are. */
internal fun codePointsBefore(text: String, end: Int, units: Int): Int {
    val e = end.coerceIn(0, text.length)
    var s = (e - units.coerceAtLeast(0)).coerceIn(0, e)
    if (s in 1 until e && Character.isLowSurrogate(text[s]) && Character.isHighSurrogate(text[s - 1])) s -= 1
    return text.codePointCount(s, e)
}

/** How many code points the [units] UTF-16 units from [start] in [text] are. */
internal fun codePointsAfter(text: String, start: Int, units: Int): Int {
    val s = start.coerceIn(0, text.length)
    var e = (s.toLong() + units.coerceAtLeast(0)).coerceAtMost(text.length.toLong()).toInt()
    if (e > s && e < text.length && Character.isLowSurrogate(text[e]) && Character.isHighSurrogate(text[e - 1])) e += 1
    return text.codePointCount(s, e)
}

/** [i] moved back to the start of the surrogate pair it is inside, if any. */
internal fun snapDown(text: String, i: Int): Int {
    val x = i.coerceIn(0, text.length)
    return if (x in 1 until text.length && Character.isLowSurrogate(text[x]) && Character.isHighSurrogate(text[x - 1])) x - 1 else x
}

/** [i] moved forward to the end of the surrogate pair it is inside, if any. */
internal fun snapUp(text: String, i: Int): Int {
    val x = i.coerceIn(0, text.length)
    return if (x in 1 until text.length && Character.isLowSurrogate(text[x]) && Character.isHighSurrogate(text[x - 1])) x + 1 else x
}

/**
 * The soft keyboard's side of the document: text arrives as commands on the engine, the way the
 * desktop keyboard's keys do. The engine owns the text; this connection only keeps [ctx], the
 * paragraph around the caret, so the input method's questions are answered on the UI thread without
 * waiting for the engine. [ctx] is updated at once after an edit and replaced by the engine's own
 * once every edit this connection sent has been applied.
 *
 * The composing text lives in [DocumentView.preedit], not in the engine. To the keyboard it is part
 * of the text, right before the caret, so every answer here goes through [effective].
 *
 * Every override catches everything and answers with a safe default: a failure in here must never
 * take the app down.
 */
class DocInputConnection(private val view: DocumentView, private val engine: Engine) : BaseInputConnection(view, false) {
    var ctx: ImeText? = null

    /** Edits sent to the engine and not yet applied; while any, the engine's view is older than [ctx]. */
    private var pending = 0
    val busy get() = pending > 0

    private fun selStart(c: ImeText) = minOf(c.cursor, c.anchor).coerceIn(0, c.text.length)
    private fun selEnd(c: ImeText) = maxOf(c.cursor, c.anchor).coerceIn(0, c.text.length)

    private inline fun <T> safe(default: T, block: () -> T): T = try { block() } catch (e: Throwable) { default }

    /** [ctx] as the keyboard sees it: the composing text in place of the selection, caret after it. */
    private fun effective(): ImeText? {
        val c = ctx ?: return null
        val p = view.preedit
        if (p.isEmpty()) return c
        val s = selStart(c)
        val at = s + p.length
        return ImeText(c.text.substring(0, s) + p + c.text.substring(selEnd(c)), at, at, c.para)
    }

    /**
     * Run [block] as one job on the engine thread and tell the view when it is done. The job is queued
     * before this returns, so whatever the caller sends next (a caret move after a tap) runs after it.
     */
    private fun send(block: Engine.() -> Unit) {
        view.scope.launch(start = CoroutineStart.UNDISPATCHED) {
            pending++
            try { engine.job(block) } finally { pending--; if (pending == 0) view.syncIme() }
        }
    }

    private fun Engine.type(text: String) {
        text.split('\n').forEachIndexed { i, part ->
            if (i > 0) runNow("text.newParagraph")
            if (part.isNotEmpty()) runNow("text.insert", JSONObject().put("text", part), ime = true)
        }
    }

    private fun typeLocal(text: String) {
        val c = ctx ?: return
        val s = selStart(c); val e = selEnd(c)
        val nl = text.lastIndexOf('\n')
        ctx = if (nl < 0) {
            val p = s + text.length
            c.copy(text = c.text.substring(0, s) + text + c.text.substring(e), cursor = p, anchor = p)
        } else {
            // A new paragraph: the text after the caret moves into it. The engine's view corrects the rest.
            val tail = text.substring(nl + 1)
            c.copy(text = tail + c.text.substring(e), cursor = tail.length, anchor = tail.length)
        }
    }

    private fun commit(t: String) {
        if (t.isEmpty()) return
        typeLocal(t)
        send { type(t) }
    }

    /** Put the composing text into the document. */
    private fun flush() {
        val t = view.preedit
        view.preedit = ""
        commit(t)
    }

    override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean = safe(false) {
        view.preedit = ""
        commit(text?.toString().orEmpty())
        true
    }

    override fun setComposingText(text: CharSequence?, newCursorPosition: Int): Boolean = safe(false) {
        view.preedit = text?.toString().orEmpty()
        true
    }

    override fun finishComposingText(): Boolean = safe(false) { flush(); true }

    /**
     * Make [start]..[end] the composing text. Only a region that ends at the caret can be: its text
     * leaves the document and becomes the pre-edit, which commit puts back. Anything else is refused
     * and the keyboard starts over.
     */
    override fun setComposingRegion(start: Int, end: Int): Boolean = safe(false) {
        flush()
        val c = ctx
        if (c == null) { view.restartIme(); return@safe false }
        val s = snapDown(c.text, minOf(start, end)); val e = snapUp(c.text, maxOf(start, end))
        if (selStart(c) != selEnd(c) || e != selEnd(c)) { view.restartIme(); return@safe false }
        if (s == e) return@safe true
        val region = c.text.substring(s, e)
        deleteCodePoints(c, c.text.codePointCount(s, e), 0, true)
        view.preedit = region
        true
    }

    /** A collapsed caret move inside the text window, as caret keys; anything else is refused. */
    override fun setSelection(start: Int, end: Int): Boolean = safe(false) {
        flush()
        val c = ctx ?: return@safe false
        if (start != end || c.cursor != c.anchor || start !in 0..c.text.length) return@safe false
        val target = snapDown(c.text, start)
        if (target == c.cursor) return@safe true
        val forward = target > c.cursor
        val steps = (if (forward) c.text.codePointCount(c.cursor, target) else c.text.codePointCount(target, c.cursor)).coerceAtMost(MAX_BLIND)
        val at = c.text.offsetByCodePoints(c.cursor, if (forward) steps else -steps)
        ctx = c.copy(cursor = at, anchor = at)
        val move = JSONObject().put("extend", false)
        send { repeat(steps) { runNow(if (forward) "caret.right" else "caret.left", move) } }
        true
    }

    override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean = safe(false) {
        flush()
        val c = ctx
        if (c == null) deleteBlind(beforeLength, afterLength)
        else deleteCodePoints(c, codePointsBefore(c.text, selStart(c), beforeLength), codePointsAfter(c.text, selEnd(c), afterLength), beforeLength > 0)
    }

    override fun deleteSurroundingTextInCodePoints(beforeLength: Int, afterLength: Int): Boolean = safe(false) {
        flush()
        val c = ctx
        if (c == null) deleteBlind(beforeLength, afterLength)
        else deleteCodePoints(c, beforeLength.coerceAtLeast(0), afterLength.coerceAtLeast(0), beforeLength > 0)
    }

    /** No text window (the selection spans paragraphs): the keys' own commands, a bounded number of times. */
    private fun deleteBlind(before: Int, after: Int): Boolean {
        val b = before.coerceIn(0, MAX_BLIND); val a = after.coerceIn(0, MAX_BLIND)
        send { repeat(b) { runNow("text.backspace") }; repeat(a) { runNow("text.delete") } }
        return true
    }

    private fun deleteCodePoints(c: ImeText, wantBefore: Int, wantAfter: Int, askedBefore: Boolean): Boolean {
        val s = selStart(c); val e = selEnd(c)
        val before = wantBefore.coerceAtMost(c.text.codePointCount(0, s))
        val after = wantAfter.coerceAtMost(c.text.codePointCount(e, c.text.length))
        if (before == 0 && after == 0) {
            // At the paragraph's start the engine has nothing to cut here; Backspace joins it to the one before.
            if (askedBefore && s == e) send { runNow("text.backspace") }
            return true
        }
        val from = c.text.offsetByCodePoints(s, -before); val to = c.text.offsetByCodePoints(e, after)
        val text = c.text.removeRange(e, to).removeRange(from, s)
        val moved = s - from // a position at or after the selection start shifts back by what was cut before it
        fun at(p: Int) = (if (p >= s) p - moved else p).coerceIn(0, text.length)
        ctx = c.copy(text = text, cursor = at(c.cursor), anchor = at(c.anchor))
        send { callNow("ime.deleteSurrounding", JSONObject().put("before", before).put("after", after)) }
        return true
    }

    override fun getTextBeforeCursor(n: Int, flags: Int): CharSequence? = safe(null) {
        val c = effective() ?: return@safe null
        val end = selStart(c)
        var start = (end - n.coerceAtLeast(0)).coerceAtLeast(0)
        if (start in 1 until end && Character.isLowSurrogate(c.text[start]) && Character.isHighSurrogate(c.text[start - 1])) start += 1
        c.text.substring(start, end)
    }

    override fun getTextAfterCursor(n: Int, flags: Int): CharSequence? = safe(null) {
        val c = effective() ?: return@safe null
        val start = selEnd(c)
        var end = (start.toLong() + n.coerceAtLeast(0)).coerceAtMost(c.text.length.toLong()).toInt()
        if (end > start && end < c.text.length && Character.isLowSurrogate(c.text[end]) && Character.isHighSurrogate(c.text[end - 1])) end -= 1
        c.text.substring(start, end)
    }

    override fun getSelectedText(flags: Int): CharSequence? = safe(null) {
        val c = effective() ?: return@safe null
        if (selStart(c) == selEnd(c)) null else c.text.substring(selStart(c), selEnd(c))
    }

    override fun getSurroundingText(beforeLength: Int, afterLength: Int, flags: Int): SurroundingText? = safe(null) {
        val c = effective() ?: return@safe null
        if (Build.VERSION.SDK_INT < 31) return@safe null
        val s = selStart(c); val e = selEnd(c)
        val from = snapDown(c.text, (s - beforeLength.coerceAtLeast(0)).coerceAtLeast(0))
        val to = snapUp(c.text, (e.toLong() + afterLength.coerceAtLeast(0)).coerceAtMost(c.text.length.toLong()).toInt())
        SurroundingText(c.text.substring(from, to), s - from, e - from, from)
    }

    override fun getCursorCapsMode(reqModes: Int): Int = safe(0) {
        val c = effective() ?: return@safe 0
        TextUtils.getCapsMode(c.text, selStart(c), reqModes)
    }

    override fun sendKeyEvent(event: KeyEvent): Boolean = safe(false) { view.dispatchKeyEvent(event); true }

    override fun performEditorAction(editorAction: Int): Boolean = safe(false) { flush(); send { runNow("text.newParagraph") }; true }

    private companion object { const val MAX_BLIND = 64 }
}
