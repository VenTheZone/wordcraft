package ai.storyteller.wordcraft

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.FormatIndentDecrease
import androidx.compose.material.icons.automirrored.filled.FormatIndentIncrease
import androidx.compose.material.icons.automirrored.filled.FormatListBulleted
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ContentCut
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.FormatAlignCenter
import androidx.compose.material.icons.filled.FormatAlignJustify
import androidx.compose.material.icons.automirrored.filled.FormatAlignLeft
import androidx.compose.material.icons.automirrored.filled.FormatAlignRight
import androidx.compose.material.icons.filled.FormatBold
import androidx.compose.material.icons.filled.FormatClear
import androidx.compose.material.icons.filled.FormatColorText
import androidx.compose.material.icons.filled.FormatItalic
import androidx.compose.material.icons.filled.FormatListNumbered
import androidx.compose.material.icons.filled.FormatPaint
import androidx.compose.material.icons.filled.FormatUnderlined
import androidx.compose.material.icons.filled.Highlight
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.StrikethroughS
import androidx.compose.material.icons.filled.Subscript
import androidx.compose.material.icons.filled.Superscript
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.PopupProperties
import kotlinx.coroutines.launch
import org.json.JSONObject

/** One registered command, from the bridge's `commands` reply. */
internal data class Cmd(val id: String, val label: String, val enabled: Boolean, val needsParams: Boolean)

/** One ribbon button the engine's Word feature catalog lists. */
internal data class Entry(val tab: String, val group: String, val label: String, val id: String)

/** The catalog tabs the ribbon shows after the hand-made Home, in order. The rest are File (the menu), contextual or keyboard-only. */
internal val GENERATED_TABS = listOf("Insert", "Draw", "Design", "Layout", "References", "Mailings", "Review", "View")

/**
 * Tab, then group, then entries, for the tabs in [GENERATED_TABS] that have a button that runs with
 * no params. Required params are written `"key":` in a command's doc, optional ones `"key"?:`.
 */
internal fun usableTabs(entries: List<Entry>, cmds: Map<String, Cmd>): Map<String, Map<String, List<Entry>>> {
    val usable = entries.filter { cmds[it.id]?.needsParams == false }
    return GENERATED_TABS.mapNotNull { t -> usable.filter { it.tab == t }.takeIf { it.isNotEmpty() }?.let { t to it.groupBy { e -> e.group } } }.toMap()
}

/** A command's params doc names a required key as `"key":`; optional ones are `"key"?:`. */
internal fun needsParams(doc: String) = doc.contains("\":")

/** Commands whose label contains [query] (case-insensitive), at most [max]; none for a blank query. */
internal fun searchCommands(cmds: Collection<Cmd>, query: String, max: Int = 30): List<Cmd> {
    val q = query.trim()
    return if (q.isEmpty()) emptyList() else cmds.asSequence().filter { it.label.contains(q, ignoreCase = true) }.take(max).toList()
}

private fun parseCommands(reply: JSONObject?): Pair<Map<String, Cmd>, List<Entry>> {
    val cs = reply?.optJSONArray("commands")
    val cmds = (0 until (cs?.length() ?: 0)).mapNotNull { i ->
        cs?.optJSONObject(i)?.let { Cmd(it.optString("id"), it.optString("label"), it.optBoolean("enabled", true), needsParams(it.optString("params"))) }
    }.associateBy { it.id }
    val es = reply?.optJSONArray("catalog")
    val entries = (0 until (es?.length() ?: 0)).mapNotNull { i ->
        es?.optJSONObject(i)?.let { Entry(it.optString("tab"), it.optString("group"), it.optString("label"), it.optString("id")) }
    }
    return cmds to entries
}

private val FONTS = listOf("Source Sans 3", "Source Serif 4", "Inter", "JetBrains Mono", "Roboto", "Noto Serif", "Noto Sans")
private val SIZES = listOf(8, 9, 10, 11, 12, 14, 16, 18, 20, 24, 28, 36, 48, 72)
private val SWATCHES = listOf("000000" to "Black", "C92A2A" to "Red", "E8590C" to "Orange", "F08C00" to "Amber", "2F9E44" to "Green", "1971C2" to "Blue", "6741D9" to "Purple", "868E96" to "Gray")
private val STYLES = listOf("Normal" to "para.normal", "Heading 1" to "para.heading1", "Heading 2" to "para.heading2", "Heading 3" to "para.heading3")
private val STYLE_IDS = listOf("Normal", "Heading1", "Heading2", "Heading3")

/**
 * The ribbon: the hand-made Home tab, then the catalog's tabs as plain text buttons. [doc] takes the
 * word being typed before a command moves the caret; [onRun] is told the label of the command about
 * to run, for the "isn't on the tablet yet" message when it only asks for a dialog.
 */
@Composable
fun Ribbon(engine: Engine, view: ViewState?, commands: JSONObject?, doc: DocumentView?, onRun: (String) -> Unit, modifier: Modifier = Modifier) {
    var fresh by remember { mutableStateOf<JSONObject?>(null) }
    val (cmds, entries) = remember(fresh, commands) { parseCommands(fresh ?: commands) }
    val tabs = remember(cmds, entries) { usableTabs(entries, cmds) }
    var tab by rememberSaveable { mutableStateOf("Home") }
    // A saved tab that the loaded catalog doesn't have (commands still loading) shows Home.
    val shown = if (tab == "Home" || tab in tabs) tab else "Home"
    val refresh: suspend () -> Unit = { engine.callAwait("commands")?.let { fresh = it } }
    // ponytail: enabled flags are a snapshot from when a tab opens or a search starts; they go stale while it stays open.
    LaunchedEffect(shown) { if (shown != "Home") refresh() }
    val scope = rememberCoroutineScope()

    fun act(id: String, params: JSONObject = JSONObject()) {
        doc?.commitComposition()
        onRun(cmds[id]?.label ?: id)
        // The label is for this command's own dialog request only: the reply is handled before runAwait returns, then it is cleared.
        scope.launch { engine.runAwait(id, params); onRun("") }
    }

    Column(modifier.fillMaxWidth().height(112.dp).background(MaterialTheme.colorScheme.surfaceVariant)) {
        Row(Modifier.fillMaxWidth().height(40.dp).horizontalScroll(rememberScrollState())) {
            for (t in listOf("Home") + tabs.keys) {
                val on = t == shown
                Box(Modifier.fillMaxHeight().clickable { tab = t }.padding(horizontal = 16.dp).semantics { selected = on }, contentAlignment = Alignment.Center) {
                    Text(t, fontSize = 14.sp, color = if (on) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
                }
            }
        }
        HorizontalDivider()
        Row(Modifier.fillMaxWidth().weight(1f).horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically) {
            if (shown == "Home") {
                HistoryGroup(view, ::act)
                ClipboardGroup(doc, ::act)
                FontGroup(view, ::act)
                ParagraphGroup(::act)
                StylesGroup(view, ::act)
                EditingGroup(cmds.values, doc, refresh, ::act)
            } else {
                GeneratedTab(tabs[shown].orEmpty(), cmds, ::act)
            }
        }
    }
}

private typealias Act = (String, JSONObject) -> Unit

@Composable
internal fun RibbonButton(onClick: () -> Unit, modifier: Modifier = Modifier, icon: ImageVector? = null, text: String? = null, desc: String = text.orEmpty(), checked: Boolean = false, enabled: Boolean = true) {
    val cs = MaterialTheme.colorScheme
    val tint = if (enabled) cs.onSurface else cs.onSurface.copy(alpha = 0.38f)
    Box(
        modifier.defaultMinSize(40.dp, 40.dp).clip(RoundedCornerShape(6.dp)).background(if (checked) cs.primaryContainer else Color.Transparent)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick).semantics { selected = checked },
        contentAlignment = Alignment.Center,
    ) {
        if (icon != null) Icon(icon, desc, Modifier.size(24.dp), tint)
        if (text != null) Text(text, Modifier.padding(horizontal = 8.dp), color = tint, fontSize = 14.sp, maxLines = 1)
    }
}

/** A group: its buttons in a row, its name in small grey text underneath. */
@Composable
private fun Group(name: String, content: @Composable RowScope.() -> Unit) {
    Column(Modifier.padding(horizontal = 6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Row(verticalAlignment = Alignment.CenterVertically, content = content)
        Text(name, color = Color.Gray, fontSize = 10.sp, maxLines = 1)
    }
    Box(Modifier.width(1.dp).height(48.dp).background(MaterialTheme.colorScheme.outlineVariant))
}

@Composable
private fun GeneratedTab(groups: Map<String, List<Entry>>, cmds: Map<String, Cmd>, act: Act) {
    for ((name, es) in groups) Group(name) {
        for (e in es) RibbonButton({ act(e.id, JSONObject()) }, text = e.label, enabled = cmds[e.id]?.enabled != false)
    }
}

@Composable
private fun HistoryGroup(view: ViewState?, act: Act) = Group("Undo") {
    RibbonButton({ act("edit.undo", JSONObject()) }, icon = Icons.AutoMirrored.Filled.Undo, desc = "Undo", enabled = view?.canUndo == true)
    RibbonButton({ act("edit.redo", JSONObject()) }, icon = Icons.AutoMirrored.Filled.Redo, desc = "Redo", enabled = view?.canRedo == true)
}

@Composable
private fun ClipboardGroup(doc: DocumentView?, act: Act) = Group("Clipboard") {
    RibbonButton({ doc?.commitComposition(); doc?.paste() }, icon = Icons.Filled.ContentPaste, desc = "Paste")
    RibbonButton({ act("edit.cut", JSONObject()) }, icon = Icons.Filled.ContentCut, desc = "Cut")
    RibbonButton({ act("edit.copy", JSONObject()) }, icon = Icons.Filled.ContentCopy, desc = "Copy")
    RibbonButton({ act("edit.formatPainter", JSONObject()) }, icon = Icons.Filled.FormatPaint, desc = "Format Painter")
}

/** The state of a format key as the field shows it: blank when the selection mixes values. */
private fun JSONObject.shown(key: String): String = when {
    isNull(key) -> ""
    else -> when (val v = opt(key)) {
        is Number -> if (v.toDouble() % 1.0 == 0.0) v.toLong().toString() else v.toDouble().toString()
        else -> v?.toString().orEmpty()
    }
}

@Composable
private fun FontGroup(view: ViewState?, act: Act) {
    val f = view?.format ?: JSONObject()
    Group("Font") {
        Picker(f.shown("font"), 150.dp, "Font", FONTS) { act("format.font", JSONObject().put("name", it)) }
        Picker(f.shown("size"), 64.dp, "Font size", SIZES.map { it.toString() }) { act("format.size", JSONObject().put("size", it.toInt())) }
        RibbonButton({ act("format.bold", JSONObject()) }, icon = Icons.Filled.FormatBold, desc = "Bold", checked = f.optBoolean("bold"))
        RibbonButton({ act("format.italic", JSONObject()) }, icon = Icons.Filled.FormatItalic, desc = "Italic", checked = f.optBoolean("italic"))
        RibbonButton({ act("format.underline", JSONObject()) }, icon = Icons.Filled.FormatUnderlined, desc = "Underline", checked = f.optBoolean("underline"))
        RibbonButton({ act("format.strikethrough", JSONObject()) }, icon = Icons.Filled.StrikethroughS, desc = "Strikethrough", checked = f.optBoolean("strike"))
        RibbonButton({ act("format.subscript", JSONObject()) }, icon = Icons.Filled.Subscript, desc = "Subscript", checked = f.optBoolean("subscript"))
        RibbonButton({ act("format.superscript", JSONObject()) }, icon = Icons.Filled.Superscript, desc = "Superscript", checked = f.optBoolean("superscript"))
        RibbonButton({ act("format.highlight", JSONObject()) }, icon = Icons.Filled.Highlight, desc = "Highlight")
        Box {
            var open by remember { mutableStateOf(false) }
            RibbonButton({ open = true }, icon = Icons.Filled.FormatColorText, desc = "Font color")
            DropdownMenu(open, { open = false }) {
                Row(Modifier.padding(horizontal = 8.dp)) {
                    for ((hex, name) in SWATCHES) Box(
                        Modifier.size(40.dp).clickable(role = Role.Button) { open = false; act("format.color", JSONObject().put("color", hex)) }.semantics { contentDescription = name },
                        contentAlignment = Alignment.Center,
                    ) { Box(Modifier.size(28.dp).clip(CircleShape).background(Color(0xFF000000L or hex.toLong(16)))) }
                }
                DropdownMenuItem({ Text("Automatic") }, { open = false; act("format.color", JSONObject().put("color", "auto")) })
            }
        }
        RibbonButton({ act("format.clear", JSONObject()) }, icon = Icons.Filled.FormatClear, desc = "Clear formatting")
    }
}

@Composable
private fun ParagraphGroup(act: Act) = Group("Paragraph") {
    RibbonButton({ act("para.bullets", JSONObject()) }, icon = Icons.AutoMirrored.Filled.FormatListBulleted, desc = "Bullets")
    RibbonButton({ act("para.numbering", JSONObject()) }, icon = Icons.Filled.FormatListNumbered, desc = "Numbering")
    RibbonButton({ act("para.outdent", JSONObject()) }, icon = Icons.AutoMirrored.Filled.FormatIndentDecrease, desc = "Decrease indent")
    RibbonButton({ act("para.indent", JSONObject()) }, icon = Icons.AutoMirrored.Filled.FormatIndentIncrease, desc = "Increase indent")
    RibbonButton({ act("para.alignLeft", JSONObject()) }, icon = Icons.AutoMirrored.Filled.FormatAlignLeft, desc = "Align left")
    RibbonButton({ act("para.alignCenter", JSONObject()) }, icon = Icons.Filled.FormatAlignCenter, desc = "Center")
    RibbonButton({ act("para.alignRight", JSONObject()) }, icon = Icons.AutoMirrored.Filled.FormatAlignRight, desc = "Align right")
    RibbonButton({ act("para.justify", JSONObject()) }, icon = Icons.Filled.FormatAlignJustify, desc = "Justify")
    RibbonButton({ act("view.marks", JSONObject()) }, text = "¶", desc = "Show formatting marks")
}

@Composable
private fun StylesGroup(view: ViewState?, act: Act) = Group("Styles") {
    val current = view?.format?.optString("style")
    STYLES.forEachIndexed { i, (label, id) -> RibbonButton({ act(id, JSONObject()) }, text = label, checked = current == STYLE_IDS[i]) }
}

/** "Search commands" (Word's Tell me): every command by label; a tap runs it with no params. */
@Composable
private fun EditingGroup(cmds: Collection<Cmd>, doc: DocumentView?, refresh: suspend () -> Unit, act: Act) = Group("Editing") {
    var query by remember { mutableStateOf("") }
    // The list is closed by tapping away; typing or tapping the field opens it again. The query stays.
    var open by remember { mutableStateOf(true) }
    LaunchedEffect(query.isNotEmpty()) { if (query.isNotEmpty()) refresh() }
    val focus = LocalFocusManager.current
    val found = remember(cmds, query) { searchCommands(cmds, query) }
    Box {
        Row(
            Modifier.width(220.dp).height(40.dp).border(BorderStroke(1.dp, MaterialTheme.colorScheme.outline), RoundedCornerShape(6.dp)).padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Filled.Search, null, Modifier.size(20.dp))
            BasicTextField(
                query, { query = it; open = true }, Modifier.padding(start = 8.dp).fillMaxWidth().onFocusChanged { if (it.isFocused) open = true }, singleLine = true,
                textStyle = androidx.compose.ui.text.TextStyle(fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurface),
                decorationBox = { inner -> if (query.isEmpty()) Text("Search commands", color = Color.Gray, fontSize = 14.sp); inner() },
            )
        }
        DropdownMenu(found.isNotEmpty() && open, { open = false }, Modifier.heightIn(max = 360.dp), properties = PopupProperties(focusable = false)) {
            for (c in found) DropdownMenuItem({ Text(c.label, maxLines = 1, overflow = TextOverflow.Ellipsis) }, {
                query = ""; focus.clearFocus(); doc?.requestFocus()
                act(c.id, JSONObject())
            }, enabled = c.enabled)
        }
    }
}

@Composable
private fun Picker(value: String, width: Dp, desc: String, items: List<String>, onPick: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box(Modifier.padding(horizontal = 2.dp)) {
        Row(
            Modifier.width(width).height(40.dp).clip(RoundedCornerShape(6.dp)).border(BorderStroke(1.dp, MaterialTheme.colorScheme.outline), RoundedCornerShape(6.dp))
                .clickable(role = Role.DropdownList) { open = true }.padding(start = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(value, Modifier.weight(1f), fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Icon(Icons.Filled.ArrowDropDown, desc)
        }
        DropdownMenu(open, { open = false }) { for (item in items) DropdownMenuItem({ Text(item) }, { open = false; onPick(item) }) }
    }
}
