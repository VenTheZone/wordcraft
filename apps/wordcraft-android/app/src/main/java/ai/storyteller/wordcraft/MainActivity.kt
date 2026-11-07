package ai.storyteller.wordcraft

import android.content.ClipData
import android.content.ClipboardManager
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.util.Locale

class MainActivity : ComponentActivity() {
    private val engine by lazy { (application as? WordCraftApp)?.engine ?: Engine() }
    private var commands by mutableStateOf<JSONObject?>(null)
    private var doc by mutableStateOf<DocumentView?>(null)
    /** The label of the ribbon command in flight, empty otherwise: the name in "isn't on the tablet yet" when it only asks for a dialog. */
    private var lastCommandLabel = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
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
        lifecycleScope.launch {
            engine.init(filesDir.path, Locale.getDefault().toLanguageTag(), intent?.getBooleanExtra("sample", false) == true)
            commands = engine.callAwait("commands")
        }
        setContent {
            MaterialTheme {
                val view by engine.view.collectAsState()
                Column(Modifier.fillMaxSize().statusBarsPadding()) {
                    // A7: the top bar (File menu, title) goes here.
                    Ribbon(engine, view, commands, doc, { lastCommandLabel = it })
                    AndroidView(
                        factory = { DocumentView(it, engine, lifecycleScope).also { v -> doc = v } },
                        update = { it.update(view) }, modifier = Modifier.weight(1f).fillMaxWidth(),
                    )
                }
            }
        }
    }

    /**
     * A dialog the engine asks the app to open. The ones A7 builds (save, save as, PDF, open, close) and
     * the ones that are not dialogs (scroll, focus) are left alone; the rest get a message.
     */
    private fun onUiRequest(r: JSONObject) {
        if (r.has("scrollTo") || r.has("focus") || r.has("close") || r.optString("open") in A7_OPEN) return
        Toast.makeText(this, "${lastCommandLabel.ifEmpty { "That command" }} isn't on the tablet yet", Toast.LENGTH_SHORT).show()
    }

    /** A copy or cut in the engine, on the system clipboard: rich when there is HTML, else plain. */
    private fun putOnClipboard(c: EngineEvent.Clipboard) {
        val clip = if (c.html.isNotEmpty()) ClipData.newHtmlText("WordCraft", c.text, c.html) else ClipData.newPlainText("WordCraft", c.text)
        try { getSystemService(ClipboardManager::class.java)?.setPrimaryClip(clip) } catch (e: Exception) { /* the clipboard is the system's; a refusal must not crash the editor */ }
    }

    private companion object {
        val A7_OPEN = setOf("save", "saveAs", "exportPdf", "openFile")
    }
}
