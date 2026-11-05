package ai.storyteller.wordcraft

/** Holds the [Engine] so it outlives activity recreation, as the Rust session does. */
class WordCraftApp : android.app.Application() { val engine by lazy { Engine() } }
