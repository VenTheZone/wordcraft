package ai.storyteller.wordcraft

import android.content.ClipData
import android.content.ClipboardManager
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import java.util.Locale

class MainActivity : ComponentActivity() {
    private val engine by lazy { (application as? WordCraftApp)?.engine ?: Engine() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Collect before init: events wait in the engine's channel, but start the collector first anyway.
        lifecycleScope.launch(start = CoroutineStart.UNDISPATCHED) {
            engine.events.collect {
                when (it) {
                    is EngineEvent.Error -> Toast.makeText(this@MainActivity, it.message, Toast.LENGTH_SHORT).show()
                    is EngineEvent.Clipboard -> putOnClipboard(it)
                    is EngineEvent.UiRequest -> Unit
                }
            }
        }
        lifecycleScope.launch {
            engine.init(filesDir.path, Locale.getDefault().toLanguageTag(), intent?.getBooleanExtra("sample", false) == true)
        }
        setContent {
            MaterialTheme {
                val view by engine.view.collectAsState()
                Column(Modifier.fillMaxSize()) {
                    AndroidView(factory = { DocumentView(it, engine, lifecycleScope) }, update = { it.update(view) }, modifier = Modifier.fillMaxSize())
                }
            }
        }
    }

    /** A copy or cut in the engine, on the system clipboard: rich when there is HTML, else plain. */
    private fun putOnClipboard(c: EngineEvent.Clipboard) {
        val clip = if (c.html.isNotEmpty()) ClipData.newHtmlText("WordCraft", c.text, c.html) else ClipData.newPlainText("WordCraft", c.text)
        try { getSystemService(ClipboardManager::class.java)?.setPrimaryClip(clip) } catch (e: Exception) { /* the clipboard is the system's; a refusal must not crash the editor */ }
    }
}
