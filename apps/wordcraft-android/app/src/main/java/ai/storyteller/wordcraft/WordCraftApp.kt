package ai.storyteller.wordcraft

import android.net.Uri

/**
 * Holds the [Engine] and the file the document belongs to, so both outlive activity recreation (a
 * locale or font-size change, Back and relaunch) as the Rust session does. A document that came
 * back without its file would turn Save into Save As.
 */
class WordCraftApp : android.app.Application() {
    val engine by lazy { Engine() }
    /** The file the document with this id was opened from or saved to; any other document has no file yet. */
    var current: Pair<Long, Uri>? = null
    /** A file another app sent while an open/save flow ran: opened when it ends. */
    var pendingView: Uri? = null
}
