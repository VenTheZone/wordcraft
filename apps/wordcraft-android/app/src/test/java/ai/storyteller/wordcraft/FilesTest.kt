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
        r.add("u4", "d") // over the cap: the oldest goes
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
        val pairs = cacheName("😀".repeat(100) + ".odt") // 200 UTF-16 units
        assertTrue(pairs.length <= 120 && pairs.endsWith(".odt"))
        assertFalse(Character.isHighSurrogate(pairs[pairs.length - 5]))
        assertEquals(120, cacheName("y".repeat(300)).length) // no extension: just cut
    }

    @Test fun docxNameReplacesAnExtension() {
        assertEquals("Document1.docx", docxName("Document1"))
        assertEquals("report.docx", docxName("report.md"))
        assertEquals("Mr. Smith.docx", docxName("Mr. Smith"))
        assertEquals("Document.docx", docxName(""))
        assertEquals("ab.docx", docxName("a/b"))
    }
}
