package ai.storyteller.wordcraft

import org.junit.Assert.assertEquals
import org.junit.Test

class CodePointsTest {
    @Test fun before() {
        assertEquals(3, codePointsBefore("Hello", 5, 3))
        assertEquals(1, codePointsBefore("a😀", 3, 2))
        assertEquals(1, codePointsBefore("a😀", 3, 1)) // half a pair grows to the pair
        assertEquals(0, codePointsBefore("", 5, 9))
        assertEquals(0, codePointsBefore("Hello", 2, Int.MIN_VALUE)) // a negative count removes nothing
        assertEquals(0, codePointsBefore("Hello", 5, 0)) // caret at the end, nothing asked: no read past the text
        assertEquals(0, codePointsBefore("a😀", 3, 0))
        assertEquals(0, codePointsBefore("Hello", 0, 3))
    }

    @Test fun after() {
        assertEquals(3, codePointsAfter("Hello", 0, 3))
        assertEquals(1, codePointsAfter("a😀b", 1, 1)) // half a pair grows to the pair
        assertEquals(2, codePointsAfter("a😀b", 1, 3))
        assertEquals(0, codePointsAfter("", 5, 9))
        assertEquals(5, codePointsAfter("Hello", 0, Int.MAX_VALUE))
        assertEquals(0, codePointsAfter("Hello", 0, 0))
        assertEquals(0, codePointsAfter("Hello", 5, 3)) // at the end
        assertEquals(0, codePointsAfter("Hello", 5, 0))
    }

    @Test fun snap() {
        assertEquals(1, snapDown("a😀b", 2)) // inside the pair: back to its start
        assertEquals(3, snapUp("a😀b", 2)) // inside the pair: on to its end
        assertEquals(1, snapDown("a😀b", 1))
        assertEquals(0, snapDown("", 4))
        assertEquals(0, snapUp("", 4))
    }
}
