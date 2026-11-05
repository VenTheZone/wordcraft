package ai.storyteller.wordcraft

/** The Rust engine (apps/wordcraft-android/src). Call only through [Engine], on its thread. */
object Native {
    init { System.loadLibrary("wordcraft_android") }
    @JvmStatic external fun call(method: String, params: String): String
    @JvmStatic external fun render(page: Int, scale: Float): ByteArray
    @JvmStatic external fun save(name: String): ByteArray
}
