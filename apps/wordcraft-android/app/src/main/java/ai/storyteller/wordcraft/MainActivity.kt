package ai.storyteller.wordcraft

import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.FileProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.FileNotFoundException
import java.util.Locale
import kotlin.coroutines.resume

class MainActivity : ComponentActivity() {
    private val app get() = application as WordCraftApp
    private val engine by lazy { app.engine }
    private val recent by lazy { Recent(PrefsRecentStore(getSharedPreferences("recent", MODE_PRIVATE))) }
    private var commands by mutableStateOf<JSONObject?>(null)
    private var doc by mutableStateOf<DocumentView?>(null)
    /** The label of the ribbon command in flight, empty otherwise: the name in "isn't on the tablet yet" when it only asks for a dialog. */
    private var lastCommandLabel = ""
    private var current: Pair<Long, Uri>?
        get() = app.current
        set(v) { app.current = v }
    /** The one open/save/close flow at a time; a menu tap or request that arrives while one runs (a dialog is up) is dropped. */
    private var flow: Job? = null
    private var pendingView: Uri?
        get() = app.pendingView
        set(v) { app.pendingView = v }
    private var picked: CompletableDeferred<Uri?>? = null
    private val ready = CompletableDeferred<Unit>()
    private var tickBusy = false
    private var tickError = ""

    private val openPicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { picked?.complete(it) }
    // A generic type: a provider keeps the extension the user types (with the docx type it adds ".docx": "a.txt" becomes "a.txt.docx").
    private val saveAsPicker = registerForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { picked?.complete(it) }
    private val pdfPicker = registerForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { picked?.complete(it) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // The app is always light: light bar styles keep the status and nav icons dark when the system is in dark mode.
        val light = SystemBarStyle.light(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT)
        enableEdgeToEdge(statusBarStyle = light, navigationBarStyle = light)
        // Collect before init: events wait in the engine's channel, but start the collector first anyway.
        lifecycleScope.launch(start = CoroutineStart.UNDISPATCHED) {
            engine.events.collect {
                when (it) {
                    is EngineEvent.Error -> Toast.makeText(this@MainActivity, it.message, Toast.LENGTH_SHORT).show()
                    is EngineEvent.Clipboard -> putOnClipboard(it)
                    is EngineEvent.UiRequest -> onUiRequest(it.request)
                }
            }
        }
        launchFlow {
            val fresh = engine.init(filesDir.path, Locale.getDefault().toLanguageTag(), intent?.getBooleanExtra("sample", false) == true)?.optBoolean("fresh", true) ?: true
            commands = engine.callAwait("commands")
            ready.complete(Unit)
            // The engine finds earlier copies on a worker (busy from the start until the scan lands), so wait, then ask.
            // Only for a session this call made: a recreated activity finds the old session, whose documents are open.
            if (fresh) {
                settleRecovery()
                engine.callAwait("recovery.list")?.optJSONArray("value")?.let { recover(it) }
            }
            if (savedInstanceState == null) viewedUri(intent)?.let { openFrom(it) }
        }
        // The recovery copy of the open document is written on this tick.
        lifecycleScope.launch {
            ready.await()
            repeatOnLifecycle(Lifecycle.State.STARTED) { while (true) { tick(); delay(2000) } }
        }
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() { launchFlow { if (guard() != null) finish() } }
        })
        setContent {
            MaterialTheme {
                val view by engine.view.collectAsState()
                // Edge to edge on every API level (Android 15 forces it): the root pads for the bars, cutout and keyboard, so the page area ends above the keyboard.
                Column(Modifier.fillMaxSize().safeDrawingPadding()) {
                    TopBar(view)
                    Ribbon(engine, view, commands, doc, { lastCommandLabel = it })
                    AndroidView(
                        factory = { DocumentView(it, engine, lifecycleScope).also { v -> doc = v } },
                        update = { it.update(view) }, modifier = Modifier.weight(1f).fillMaxWidth(),
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        val uri = viewedUri(intent) ?: return
        if (flow?.isActive == true) pendingView = uri else launchFlow { ready.await(); openFrom(uri) }
    }

    /** One last recovery copy as the app leaves the screen. */
    override fun onStop() {
        super.onStop()
        if (ready.isCompleted) {
            doc?.commitComposition() // the word still being typed goes into the copy
            engine.call("recovery.tick", report = false)
        }
    }

    private fun viewedUri(i: Intent?): Uri? = i?.takeIf { it.action == Intent.ACTION_VIEW }?.data

    @Composable
    private fun TopBar(view: ViewState?) {
        var file by remember { mutableStateOf(false) }
        var recentList by remember { mutableStateOf(false) }
        var more by remember { mutableStateOf(false) }
        @Composable fun Item(text: String, closes: Boolean = true, run: () -> Unit) =
            DropdownMenuItem(text = { Text(text) }, onClick = { if (closes) { file = false; more = false }; run() })
        Row(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Box {
                TextButton(onClick = { recentList = false; file = true }) { Text("File") }
                DropdownMenu(expanded = file, onDismissRequest = { file = false }) {
                    if (recentList) {
                        Item("Back", closes = false) { recentList = false }
                        recent.list().forEach { r -> Item(r.name) { openRecent(r) } }
                    } else {
                        Item("New") { launchFlow { newDocument() } }
                        Item("Open…") { launchFlow { openPicked() } }
                        Item("Recent ▸", closes = false) { recentList = true }
                        Item("Save") { launchFlow { save() } }
                        Item("Save As…") { launchFlow { saveAs() } }
                        Item("Export PDF…") { launchFlow { exportPdf() } }
                        Item("Share…") { launchFlow { share() } }
                        Item("Close") { launchFlow { newDocument() } }
                    }
                }
            }
            Text(
                (view?.title ?: "") + if (view?.dirty == true) " •" else "", Modifier.weight(1f).padding(horizontal = 8.dp),
                maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleMedium,
            )
            Box {
                IconButton(onClick = { more = true }) { Icon(Icons.Filled.MoreVert, "More") }
                DropdownMenu(expanded = more, onDismissRequest = { more = false }) {
                    Item("Export PDF…") { launchFlow { exportPdf() } }
                    Item("Share…") { launchFlow { share() } }
                }
            }
        }
    }

    // ---- dialogs ----------------------------------------------------------------------------------

    /** The button picked, or [CANCEL] when the dialog was dismissed (back, outside tap, activity gone). */
    private suspend fun ask(title: String, text: String, yes: String, no: String? = null, cancel: String? = null): Int = suspendCancellableCoroutine { c ->
        val b = AlertDialog.Builder(this).setTitle(title).setMessage(text).setOnCancelListener { if (c.isActive) c.resume(CANCEL) }
            .setPositiveButton(yes) { _, _ -> if (c.isActive) c.resume(YES) }
        no?.let { b.setNegativeButton(it) { _, _ -> if (c.isActive) c.resume(NO) } }
        cancel?.let { b.setNeutralButton(it) { _, _ -> if (c.isActive) c.resume(CANCEL) } }
        val d = b.create()
        c.invokeOnCancellation { d.dismiss() }
        d.show()
    }

    private suspend fun message(title: String, text: String) { ask(title, text, "OK") }

    // ---- flows ------------------------------------------------------------------------------------

    private fun launchFlow(block: suspend () -> Unit) {
        if (flow?.isActive == true) return
        flow = lifecycleScope.launch {
            block()
            while (true) { val u = pendingView ?: break; pendingView = null; openFrom(u) }
        }
    }

    /** The name [uri] shows, read off the main thread (a provider can be slow). */
    private suspend fun nameOf(uri: Uri) = withContext(Dispatchers.IO) { displayName(this@MainActivity, uri) }

    private suspend fun pick(launch: () -> Unit): Uri? {
        val d = CompletableDeferred<Uri?>()
        picked = d
        try { launch() } catch (e: Exception) { message("Can't pick a file", e.message ?: "The file picker isn't available"); return null }
        return d.await()
    }

    private fun ok(r: JSONObject?) = r != null && r.isNull("error")

    /** The document as [name]'s format, with the word still being typed in the keyboard committed first (queued ahead of the save). */
    private suspend fun bytesOf(name: String): Result<Engine.Saved> {
        doc?.commitComposition()
        return engine.save(name)
    }

    /**
     * The revision the user approved replacing, or null when they cancelled: the document is saved,
     * or they said Don't Save. The word still being typed in the keyboard is committed first, so it
     * counts as a change. Whoever replaces the document next passes the revision to [closeGuarded].
     */
    private suspend fun guard(): Long? {
        doc?.commitComposition()
        val v = engine.callAwait("view")
        if (v?.optBoolean("dirty", true) == false) return v.optLong("rev", -1L)
        val title = engine.view.value?.title.orEmpty().ifEmpty { "this document" }
        return when (ask("Save changes?", "\"$title\" has changes that aren't saved.", "Save", "Don't Save", "Cancel")) {
            YES -> if (save() && engine.view.value?.dirty != true) engine.view.value?.rev else null
            NO -> engine.callAwait("view")?.optLong("rev", -1L)
            else -> null
        }
    }

    /**
     * Close the active document after the guard approved revision [approved]. Typing can go on while
     * a picker or a slow provider is busy; if the revision moved, ask again rather than discard it.
     */
    private suspend fun closeGuarded(approved: Long): Boolean {
        var rev = approved
        while (true) {
            doc?.commitComposition()
            when (engine.closeIfRev(rev)) {
                true -> return true
                false -> return false
                null -> rev = guard() ?: return false
            }
        }
    }

    private suspend fun newDocument() { guard()?.let { closeGuarded(it) } }

    private suspend fun openPicked() {
        val rev = guard() ?: return
        pick { openPicker.launch(OPEN_MIMES) }?.let { openUri(it, null, rev) }
    }

    private fun openRecent(r: RecentFile) = launchFlow { guard()?.let { openUri(Uri.parse(r.uri), r, it) } }

    /** A file another app sent us: the same as Open, after the same guard. */
    private suspend fun openFrom(uri: Uri) { guard()?.let { openUri(uri, null, it) } }

    /**
     * Copy the file in, then swap it for the document on screen ([approved] is the revision the guard
     * allowed replacing). A failure before the swap leaves the current document alone; [fromRecent]
     * is dropped from the list when its permission or file is gone.
     */
    private suspend fun openUri(uri: Uri, fromRecent: RecentFile?, approved: Long) {
        persist(uri)
        val name = nameOf(uri)
        val file = withContext(Dispatchers.IO) { copyToCache(this@MainActivity, uri, name) }.getOrElse {
            val gone = fromRecent != null && (it is SecurityException || it is FileNotFoundException)
            if (fromRecent != null && gone) forget(fromRecent)
            message("Can't open \"$name\"", (it.message ?: "The file can't be read") + if (gone) "\n\nIt was removed from the recent files." else "")
            return
        }
        if (!closeGuarded(approved)) return
        if (!ok(engine.runAwait("file.open", JSONObject().put("path", file.path)))) return // the engine has said why
        engine.view.value?.let { current = it.docId to uri }
        addRecent(uri, name)
    }

    /** Keep read and write access to [uri] across restarts, so Recent can open it again. */
    private fun persist(uri: Uri) {
        val read = Intent.FLAG_GRANT_READ_URI_PERMISSION
        try { contentResolver.takePersistableUriPermission(uri, read or Intent.FLAG_GRANT_WRITE_URI_PERMISSION) } catch (e: Exception) {
            // A file picked read-only has no write grant to keep: keep the read, so Recent still opens it after a restart.
            try { contentResolver.takePersistableUriPermission(uri, read) } catch (e2: Exception) { /* some providers refuse; the file still opens now */ }
        }
    }

    private fun addRecent(uri: Uri, name: String) = recent.add(uri.toString(), name).forEach { release(it) }

    private fun forget(r: RecentFile) { recent.remove(r.uri); release(r) }

    /** Give back the permission to a file that left the recent list. */
    private fun release(r: RecentFile) {
        try { contentResolver.releasePersistableUriPermission(Uri.parse(r.uri), Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION) } catch (e: Exception) { /* not a grant we held */ }
    }

    /** Write the document to the file it came from or went to last; Save As when it has none or its format would lose something. */
    private suspend fun save(): Boolean {
        val v = engine.view.value ?: return false
        val uri = current?.takeIf { it.first == v.docId }?.second ?: return saveAs()
        val name = nameOf(uri)
        if (extensionOf(name) !in KEEPS_EVERYTHING) return saveAs()
        return write(uri, name, inPlace = true)
    }

    private suspend fun saveAs(): Boolean {
        val v = engine.view.value ?: return false
        val uri = pick { saveAsPicker.launch(docxName(v.title)) } ?: return false
        return write(uri, nameOf(uri), inPlace = false)
    }

    /**
     * Bytes in the format [name] asks for, onto [uri]. Only a write that worked, with no edit since the
     * bytes were made, marks the document saved. A format that loses something is a copy: the document
     * stays dirty, keeps its file, and the copy isn't listed as recent. A file saved over in place
     * ([inPlace]) that the app can only read (opened from another app) goes to Save As instead.
     */
    private suspend fun write(uri: Uri, name: String, inPlace: Boolean): Boolean {
        val format = if (extensionOf(name).isEmpty()) "$name.docx" else name
        val saved = bytesOf(format).getOrElse { return failed("save", name, it) }
        withContext(Dispatchers.IO) { writeUri(this@MainActivity, uri, saved.bytes, Engine.Saved.START) }.onFailure {
            if (inPlace && it is CantOverwrite) {
                message("Can't save over that file", "Choose where to save a copy.")
                return saveAs()
            }
            return failed("save", name, it)
        }
        if (extensionOf(format) !in KEEPS_EVERYTHING) {
            Toast.makeText(this, "Saved a copy", Toast.LENGTH_SHORT).show()
            return true
        }
        val marked = engine.markSavedIf(format, saved.rev)
        engine.view.value?.let { current = it.docId to uri }
        persist(uri) // a Save As target is in Recent: keep it readable after the process dies
        addRecent(uri, name)
        Toast.makeText(this, if (marked) "Saved" else "Saved, but the document changed meanwhile", Toast.LENGTH_SHORT).show()
        return true
    }

    private suspend fun failed(what: String, name: String, e: Throwable): Boolean {
        message("Couldn't $what \"$name\"", (e.message ?: "Unknown error") + "\n\nThe document is still open${if (what == "save") " and still has unsaved changes" else ""}.")
        return false
    }

    private suspend fun exportPdf() {
        val title = engine.view.value?.title.orEmpty()
        val name = docxName(title).removeSuffix(".docx") + ".pdf"
        val uri = pick { pdfPicker.launch(name) } ?: return
        val shown = nameOf(uri)
        val saved = bytesOf(name).getOrElse { failed("export", shown, it); return }
        withContext(Dispatchers.IO) { writeUri(this@MainActivity, uri, saved.bytes, Engine.Saved.START) }.onFailure { failed("export", shown, it); return }
        Toast.makeText(this, "Exported", Toast.LENGTH_SHORT).show()
    }

    private suspend fun share() {
        val name = docxName(engine.view.value?.title.orEmpty())
        val saved = bytesOf(name).getOrElse { failed("share", name, it); return }
        val file = withContext(Dispatchers.IO) { writeShareFile(this@MainActivity, name, saved.bytes, Engine.Saved.START) }.getOrElse { failed("share", name, it); return }
        try {
            val uri = FileProvider.getUriForFile(this, "$packageName.files", file)
            val send = Intent(Intent.ACTION_SEND).setType(DOCX_MIME).putExtra(Intent.EXTRA_STREAM, uri)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION).apply { clipData = ClipData.newRawUri(name, uri) }
            startActivity(Intent.createChooser(send, null))
        } catch (e: Exception) { failed("share", name, e) }
    }

    /**
     * One recovery tick, true while the worker is busy. A tick can open a restored copy but its reply
     * carries no view, so when a job has just finished the view is fetched to put the copy on screen.
     */
    private suspend fun tick(): Boolean {
        val r = engine.callAwait("recovery.tick", report = false)
        // A failing recovery (disk full) repeats on every tick: show each different message once.
        val error = r?.takeIf { !it.isNull("error") }?.optString("error").orEmpty()
        if (error.isNotEmpty() && error != tickError) Toast.makeText(this, error, Toast.LENGTH_SHORT).show()
        tickError = error
        val busy = r?.optBoolean("busy") == true
        if (tickBusy && !busy) engine.callAwait("view")
        tickBusy = busy
        return busy
    }

    /** Tick until the recovery worker is idle (at most 5 s): its scan, or a restored copy's load, has then landed. */
    private suspend fun settleRecovery() {
        tickBusy = true
        for (n in 0 until 50) { if (!tick()) break; delay(100) }
    }

    /** Offer each copy an earlier run left. Restore swaps it in (after the guard); Discard deletes it; dismissing keeps it for the next launch. */
    private suspend fun recover(list: JSONArray) {
        for (i in 0 until list.length()) {
            val r = list.optJSONObject(i) ?: continue
            val id = r.optString("id")
            if (id.isEmpty()) continue
            val what = ask("Recover unsaved work?", "${r.optString("name")}\nSaved ${r.optString("savedAt")}\n\nDiscard deletes the recovered copy for good.", "Restore", "Discard", "Later")
            when (what) {
                YES -> {
                    val rev = guard() ?: return
                    if (!closeGuarded(rev)) return
                    if (!ok(engine.runAwait("file.recover", JSONObject().put("restore", id)))) return
                    // The copy loads on a worker and opens on a later tick; wait so the next copy doesn't race it.
                    settleRecovery()
                }
                NO -> engine.runAwait("file.recover", JSONObject().put("discard", id))
                else -> return
            }
        }
    }

    /**
     * A dialog the engine asks the app to open. New, save, save as, PDF, open and close are flows here;
     * scroll and focus are not dialogs; the rest get a message.
     */
    private fun onUiRequest(r: JSONObject) {
        when {
            r.has("scrollTo") || r.has("focus") -> {}
            r.has("close") -> launchFlow { newDocument() }
            r.optString("open") == "new" -> launchFlow { newDocument() }
            r.optString("open") == "save" -> launchFlow { save() }
            r.optString("open") == "saveAs" -> launchFlow { saveAs() }
            r.optString("open") == "exportPdf" -> launchFlow { exportPdf() }
            r.optString("open") == "openFile" -> launchFlow { openPicked() }
            else -> Toast.makeText(this, "${lastCommandLabel.ifEmpty { "That command" }} isn't on the tablet yet", Toast.LENGTH_SHORT).show()
        }
    }

    /** A copy or cut in the engine, on the system clipboard: rich when there is HTML, else plain. */
    private fun putOnClipboard(c: EngineEvent.Clipboard) {
        val clip = if (c.html.isNotEmpty()) ClipData.newHtmlText("WordCraft", c.text, c.html) else ClipData.newPlainText("WordCraft", c.text)
        try { getSystemService(ClipboardManager::class.java)?.setPrimaryClip(clip) } catch (e: Exception) { /* the clipboard is the system's; a refusal must not crash the editor */ }
    }

    private companion object {
        const val CANCEL = 0
        const val YES = 1
        const val NO = 2
    }
}
