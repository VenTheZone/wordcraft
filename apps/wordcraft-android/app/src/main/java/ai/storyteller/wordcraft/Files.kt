package ai.storyteller.wordcraft

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import android.provider.OpenableColumns
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException

/** Largest file the app copies in to open: bigger is refused with a message, not an out-of-memory crash. */
const val MAX_OPEN_BYTES = 200L shl 20
const val DOCX_MIME = "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
/** What Open offers, and what the manifest's VIEW filter accepts. */
val OPEN_MIMES = arrayOf(
    DOCX_MIME, "application/msword", "application/vnd.oasis.opendocument.text", "application/rtf", "text/rtf",
    "text/plain", "text/markdown", "text/html", "application/octet-stream",
)
/** Extensions the engine saves everything in (`KEEPS_EVERYTHING` in the bridge): Save writes in place only for these. */
val KEEPS_EVERYTHING = setOf("docx", "docm", "dotx", "dotm", "odt", "ott", "fodt", "rtf", "json", "xml")

fun extensionOf(name: String) = name.substringAfterLast('.', "").lowercase()

/** [name] as a file name that is safe inside our own cache folder: no path parts, no control characters, at most 120 characters, the extension kept. */
fun cacheName(name: String): String {
    val clean = name.filter { it != '/' && it != '\\' && it >= ' ' && it != '\u007f' }.trim()
    if (clean.isEmpty() || clean.all { it == '.' }) return "document" // "..", or nothing, would name the folder itself or its parent
    if (clean.length <= 120) return clean
    val ext = clean.substringAfterLast('.', "").takeIf { it.length in 1..20 }
    val tail = ext?.let { ".$it" } ?: ""
    val stem = clean.dropLast(tail.length)
    var keep = 120 - tail.length
    if (keep > 0 && Character.isHighSurrogate(stem[keep - 1])) keep-- // never cut a surrogate pair in half
    return stem.take(keep) + tail
}

/** "<title>.docx" for the Save As and Share names: a title that already ends in an extension gives it up. */
fun docxName(title: String): String {
    val ext = title.substringAfterLast('.', "")
    val stem = if (ext.length in 1..5 && ext.all(Char::isLetterOrDigit)) title.dropLast(ext.length + 1) else title
    return cacheName(stem.ifBlank { "Document" }) + ".docx"
}

/** The name the provider shows for [uri], or its last path part, or "document". Never throws. */
fun displayName(ctx: Context, uri: Uri): String {
    val shown = try {
        ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    } catch (e: Exception) { null }
    return (shown ?: uri.lastPathSegment?.substringAfterLast('/'))?.takeIf { it.isNotBlank() } ?: "document"
}

/**
 * Copy [uri]'s bytes to `cacheDir/open/<name>` (that folder emptied first) for the engine to read
 * itself, so a big file is never held on the Java heap. Fails past [maxBytes] or on any I/O error.
 */
fun copyToCache(ctx: Context, uri: Uri, name: String, maxBytes: Long = MAX_OPEN_BYTES): Result<File> {
    val dir = File(ctx.cacheDir, "open")
    return try {
        dir.deleteRecursively()
        if (!dir.mkdirs() && !dir.isDirectory) throw IOException("The app's cache folder isn't writable")
        val file = File(dir, cacheName(name))
        val input = ctx.contentResolver.openInputStream(uri) ?: throw IOException("The file can't be read")
        var total = 0L
        input.use { i ->
            file.outputStream().use { o ->
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val n = i.read(buf)
                    if (n < 0) break
                    total += n
                    if (total > maxBytes) throw IOException("The file is larger than ${maxBytes shr 20} MB")
                    o.write(buf, 0, n)
                }
            }
        }
        Result.success(file)
    } catch (e: Exception) {
        dir.deleteRecursively()
        Result.failure(e)
    }
}

/**
 * Replace what [uri] holds with [bytes]. Mode "wt" truncates; a provider that refuses it is
 * reported rather than retried with "w", which some providers don't truncate (a corrupt file).
 */
fun writeUri(ctx: Context, uri: Uri, bytes: ByteArray): Result<Unit> = try {
    val out = ctx.contentResolver.openOutputStream(uri, "wt") ?: throw IOException("The location can't be written")
    out.use { it.write(bytes) }
    Result.success(Unit)
} catch (e: Exception) {
    Result.failure(IOException(e.message ?: "The location can't be written", e))
}

/** Write [bytes] as `cacheDir/share/<name>` (that folder emptied first) for the share sheet. */
fun writeShareFile(ctx: Context, name: String, bytes: ByteArray): Result<File> = try {
    val dir = File(ctx.cacheDir, "share")
    dir.deleteRecursively()
    if (!dir.mkdirs() && !dir.isDirectory) throw IOException("The app's cache folder isn't writable")
    Result.success(File(dir, cacheName(name)).also { it.writeBytes(bytes) })
} catch (e: Exception) { Result.failure(e) }

data class RecentFile(val uri: String, val name: String)

/** Where [Recent] keeps its list: preferences in the app, memory in the test. */
interface RecentStore {
    fun load(): List<RecentFile>
    fun save(list: List<RecentFile>)
}

/** The preferences file `recent`, one JSON array of `{uri, name}`. */
class PrefsRecentStore(private val prefs: SharedPreferences) : RecentStore {
    override fun load(): List<RecentFile> = try {
        val a = JSONArray(prefs.getString("recent", null) ?: "[]")
        (0 until a.length()).mapNotNull { i -> a.optJSONObject(i)?.let { RecentFile(it.optString("uri"), it.optString("name")) }?.takeIf { it.uri.isNotEmpty() } }
    } catch (e: Exception) { emptyList() }

    override fun save(list: List<RecentFile>) {
        prefs.edit().putString("recent", JSONArray(list.map { JSONObject().put("uri", it.uri).put("name", it.name) }).toString()).apply()
    }
}

/** The last [max] files opened or saved: newest first, each file once. */
class Recent(private val store: RecentStore, private val max: Int = 10) {
    fun list() = store.load()
    fun add(uri: String, name: String) = store.save((listOf(RecentFile(uri, name)) + list().filter { it.uri != uri }).take(max))
    fun remove(uri: String) = store.save(list().filter { it.uri != uri })
}
