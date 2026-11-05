package ai.storyteller.wordcraft

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
import kotlinx.coroutines.launch
import java.util.Locale

class MainActivity : ComponentActivity() {
    private val engine by lazy { (application as? WordCraftApp)?.engine ?: Engine() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        lifecycleScope.launch {
            engine.init(filesDir.path, Locale.getDefault().toLanguageTag(), intent?.getBooleanExtra("sample", false) == true)
        }
        lifecycleScope.launch { engine.events.collect { if (it is EngineEvent.Error) Toast.makeText(this@MainActivity, it.message, Toast.LENGTH_SHORT).show() } }
        setContent {
            MaterialTheme {
                val view by engine.view.collectAsState()
                Column(Modifier.fillMaxSize()) {
                    AndroidView(factory = { DocumentView(it, engine, lifecycleScope) }, update = { it.update(view) }, modifier = Modifier.fillMaxSize())
                }
            }
        }
    }
}
