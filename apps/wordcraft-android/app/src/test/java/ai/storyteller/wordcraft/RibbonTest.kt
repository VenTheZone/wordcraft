package ai.storyteller.wordcraft

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RibbonTest {
    private fun cmd(id: String, label: String = id, needsParams: Boolean = false) = Cmd(id, label, true, needsParams)

    @Test fun tabsKeepOrderAndDropUnusableEntries() {
        val cmds = listOf(cmd("a"), cmd("b"), cmd("c", needsParams = true), cmd("d")).associateBy { it.id }
        val entries = listOf(
            Entry("View", "Zoom", "A", "a"), Entry("Insert", "Pages", "B", "b"), Entry("Insert", "Links", "C", "c"),
            Entry("Layout", "Page", "Needs params", "c"), // only entry needs params: the tab goes
            Entry("File", "Info", "D", "d"), Entry("Home", "Font", "D", "d"), // not generated tabs
            Entry("Review", "Proofing", "Unknown", "zzz"), // not a registered command
            Entry("Insert", "Pages", "B2", "d"),
        )
        val tabs = usableTabs(entries, cmds)
        assertEquals(listOf("Insert", "View"), tabs.keys.toList())
        assertEquals(listOf("Pages"), tabs["Insert"]?.keys?.toList())
        assertEquals(listOf("B", "B2"), tabs["Insert"]?.get("Pages")?.map { it.label })
    }

    @Test fun searchIsCaseInsensitiveSubstringAndCapped() {
        val cmds = (1..40).map { cmd("h$it", "Heading $it") } + cmd("x", "Find and Replace")
        assertEquals(30, searchCommands(cmds, "head").size)
        assertEquals(listOf("x"), searchCommands(cmds, " REPLACE ").map { it.id })
        assertTrue(searchCommands(cmds, "").isEmpty())
        assertTrue(searchCommands(cmds, "   ").isEmpty())
        assertTrue(searchCommands(cmds, "nothing like this").isEmpty())
    }
}
