package ai.storyteller.wordcraft

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FilesTest {
    private class Mem : RecentStore {
        var list = emptyList<RecentFile>()
        override fun load() = list
        override fun save(list: List<RecentFile>) { this.list = list }
    }

    @Test fun recentIsNewestFirstWithoutDuplicatesAndCapped() {
        val r = Recent(Mem(), max = 3)
        r.add("u1", "a"); r.add("u2", "b"); r.add("u3", "c")
        assertEquals(listOf("u3", "u2", "u1"), r.list().map { it.uri })
        r.add("u1", "a2") // again: moves to the front, once
        assertEquals(listOf("u1", "u3", "u2"), r.list().map { it.uri })
        assertEquals("a2", r.list().first().name)
        assertEquals(emptyList<RecentFile>(), r.add("u1", "a3")) // a repeat pushes nothing off
        assertEquals(listOf("u2"), r.add("u4", "d").map { it.uri }) // over the cap: the oldest goes, and is handed back
        assertEquals(listOf("u4", "u1", "u3"), r.list().map { it.uri })
        r.remove("u1")
        assertEquals(listOf("u4", "u3"), r.list().map { it.uri })
        r.remove("none")
        assertEquals(2, r.list().size)
    }

    @Test fun cacheNameHasNoPathAndKeepsTheExtension() {
        assertEquals("ab.docx", cacheName("a/b.docx"))
        assertEquals("ab.docx", cacheName("a\\b.docx"))
        assertEquals("a b.docx", cacheName("a\u0000 b.docx"))
        assertEquals("document", cacheName(".."))
        assertEquals("document", cacheName("/"))
        assertEquals("document", cacheName(""))
        val long = cacheName("x".repeat(300) + ".docx")
        assertEquals(120, long.length)
        assertTrue(long.endsWith(".docx"))
        // 100 emoji are 200 UTF-16 units; with ".docx" the cut lands at unit 115, a pair's first half, which must go too.
        val pairs = cacheName("😀".repeat(100) + ".docx")
        assertEquals(119, pairs.length)
        assertTrue(pairs.endsWith(".docx"))
        assertFalse(Character.isHighSurrogate(pairs[pairs.length - 6]))
        assertEquals(120, cacheName("y".repeat(300)).length) // no extension: just cut
    }

    @Test fun copyStreamStopsAtTheCapAndLeavesNoFile() {
        val dir = java.nio.file.Files.createTempDirectory("copy").toFile()
        try {
            val ok = java.io.File(dir, "ok.bin")
            assertTrue(copyStream(ByteArray(1000).inputStream(), ok, 1000).isSuccess)
            assertEquals(1000L, ok.length())
            val big = java.io.File(dir, "big.bin")
            val r = copyStream(ByteArray(1001).inputStream(), big, 1000)
            assertTrue(r.isFailure)
            assertTrue(r.exceptionOrNull()?.message.orEmpty().contains("larger"))
            assertFalse(big.exists())
        } finally { dir.deleteRecursively() }
    }

    @Test fun docxNameReplacesAnExtension() {
        assertEquals("Document1.docx", docxName("Document1"))
        assertEquals("report.docx", docxName("report.md"))
        assertEquals("Mr. Smith.docx", docxName("Mr. Smith"))
        assertEquals("Document.docx", docxName(""))
        assertEquals("ab.docx", docxName("a/b"))
    }
}
