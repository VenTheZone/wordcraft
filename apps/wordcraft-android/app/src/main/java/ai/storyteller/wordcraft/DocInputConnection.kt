package ai.storyteller.wordcraft

import android.os.Build
import android.text.TextUtils
import android.view.KeyEvent
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.SurroundingText
import kotlinx.coroutines.launch
import org.json.JSONObject

/** How many code points the [units] UTF-16 units before [end] in [text] are. */
internal fun codePointsBefore(text: String, end: Int, units: Int): Int {
    val e = end.coerceIn(0, text.length)
    var s = (e - units.coerceAtLeast(0)).coerceIn(0, e)
    if (s > 0 && Character.isLowSurrogate(text[s]) && Character.isHighSurrogate(text[s - 1])) s -= 1
    return text.codePointCount(s, e)
}

/** How many code points the [units] UTF-16 units from [start] in [text] are. */
internal fun codePointsAfter(text: String, start: Int, units: Int): Int {
    val s = start.coerceIn(0, text.length)
    var e = (s.toLong() + units.coerceAtLeast(0)).coerceAtMost(text.length.toLong()).toInt()
    if (e > s && e < text.length && Character.isLowSurrogate(text[e]) && Character.isHighSurrogate(text[e - 1])) e += 1
    return text.codePointCount(s, e)
}

/**
 * The soft keyboard's side of the document: text arrives as commands on the engine, the way the
 * desktop keyboard's keys do. The engine owns the text; this connection only keeps [ctx], the
 * paragraph around the caret, so the input method's questions are answered on the UI thread without
 * waiting for the engine. [ctx] is updated at once after an edit and replaced by the engine's own
 * once every edit this connection sent has been applied.
 */
class DocInputConnection(private val view: DocumentView, private val engine: Engine) : BaseInputConnection(view, false) {
    var ctx: ImeText? = null

    /** Edits sent to the engine and not yet applied; while any, the engine's view is older than [ctx]. */
    private var pending = 0
    val busy get() = pending > 0

    private fun selStart(c: ImeText) = minOf(c.cursor, c.anchor).coerceIn(0, c.text.length)
    private fun selEnd(c: ImeText) = maxOf(c.cursor, c.anchor).coerceIn(0, c.text.length)

    /** Run [block] (engine calls, in order) and tell the view when the last one is done. */
    private fun send(block: suspend () -> Unit) {
        pending++
        view.scope.launch {
            try { block() } finally { pending--; if (pending == 0) view.syncIme() }
        }
    }

    private suspend fun type(text: String) {
        val parts = text.split('\n')
        parts.forEachIndexed { i, part ->
            if (i > 0) engine.runAwait("text.newParagraph")
            if (part.isNotEmpty()) engine.runAwait("text.insert", JSONObject().put("text", part), ime = true)
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

    override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean {
        view.preedit = ""
        commit(text?.toString().orEmpty())
        return true
    }

    private fun commit(t: String) {
        if (t.isEmpty()) return
        typeLocal(t)
        send { type(t) }
    }

    override fun setComposingText(text: CharSequence?, newCursorPosition: Int): Boolean {
        view.preedit = text?.toString().orEmpty()
        return true
    }

    override fun finishComposingText(): Boolean {
        val t = view.preedit
        view.preedit = ""
        commit(t)
        return true
    }

    override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
        val c = ctx
        if (c == null) return deleteBlind(beforeLength, afterLength)
        return deleteCodePoints(c, codePointsBefore(c.text, selStart(c), beforeLength), codePointsAfter(c.text, selEnd(c), afterLength), beforeLength > 0)
    }

    override fun deleteSurroundingTextInCodePoints(beforeLength: Int, afterLength: Int): Boolean {
        val c = ctx ?: return deleteBlind(beforeLength, afterLength)
        return deleteCodePoints(c, beforeLength.coerceAtLeast(0), afterLength.coerceAtLeast(0), beforeLength > 0)
    }

    /** No text window (the selection spans paragraphs): the keys' own commands, a bounded number of times. */
    private fun deleteBlind(before: Int, after: Int): Boolean {
        val b = before.coerceIn(0, MAX_BLIND); val a = after.coerceIn(0, MAX_BLIND)
        send { repeat(b) { engine.runAwait("text.backspace") }; repeat(a) { engine.runAwait("text.delete") } }
        return true
    }

    private fun deleteCodePoints(c: ImeText, wantBefore: Int, wantAfter: Int, askedBefore: Boolean): Boolean {
        val s = selStart(c); val e = selEnd(c)
        val before = wantBefore.coerceAtMost(c.text.codePointCount(0, s))
        val after = wantAfter.coerceAtMost(c.text.codePointCount(e, c.text.length))
        if (before == 0 && after == 0) {
            // At the paragraph's start the engine has nothing to cut here; Backspace joins it to the one before.
            if (askedBefore && s == e) send { engine.runAwait("text.backspace") }
            return true
        }
        val from = c.text.offsetByCodePoints(s, -before); val to = c.text.offsetByCodePoints(e, after)
        val text = c.text.removeRange(e, to).removeRange(from, s)
        val moved = s - from // a position at or after the selection start shifts back by what was cut before it
        fun at(p: Int) = (if (p >= s) p - moved else p).coerceIn(0, text.length)
        ctx = c.copy(text = text, cursor = at(c.cursor), anchor = at(c.anchor))
        send { engine.callAwait("ime.deleteSurrounding", JSONObject().put("before", before).put("after", after)) }
        return true
    }

    override fun getTextBeforeCursor(n: Int, flags: Int): CharSequence? {
        val c = ctx ?: return null
        val end = selStart(c)
        var start = (end - n.coerceAtLeast(0)).coerceAtLeast(0)
        if (start in 1 until c.text.length && Character.isLowSurrogate(c.text[start]) && Character.isHighSurrogate(c.text[start - 1])) start += 1
        return c.text.substring(start, end)
    }

    override fun getTextAfterCursor(n: Int, flags: Int): CharSequence? {
        val c = ctx ?: return null
        val start = selEnd(c)
        var end = (start.toLong() + n.coerceAtLeast(0)).coerceAtMost(c.text.length.toLong()).toInt()
        if (end > start && end < c.text.length && Character.isLowSurrogate(c.text[end]) && Character.isHighSurrogate(c.text[end - 1])) end -= 1
        return c.text.substring(start, end)
    }

    override fun getSelectedText(flags: Int): CharSequence? {
        val c = ctx ?: return null
        return if (selStart(c) == selEnd(c)) null else c.text.substring(selStart(c), selEnd(c))
    }

    override fun getSurroundingText(beforeLength: Int, afterLength: Int, flags: Int): SurroundingText? {
        val c = ctx ?: return null
        if (Build.VERSION.SDK_INT < 31) return null
        val s = selStart(c); val e = selEnd(c)
        val from = (s - beforeLength.coerceAtLeast(0)).coerceAtLeast(0)
        val to = (e.toLong() + afterLength.coerceAtLeast(0)).coerceAtMost(c.text.length.toLong()).toInt()
        return SurroundingText(c.text.substring(from, to), s - from, e - from, from)
    }

    override fun getCursorCapsMode(reqModes: Int): Int {
        val c = ctx ?: return 0
        return TextUtils.getCapsMode(c.text, selStart(c), reqModes)
    }

    override fun sendKeyEvent(event: KeyEvent): Boolean { view.dispatchKeyEvent(event); return true }

    override fun performEditorAction(editorAction: Int): Boolean { send { engine.runAwait("text.newParagraph") }; return true }

    private companion object { const val MAX_BLIND = 64 }
}
