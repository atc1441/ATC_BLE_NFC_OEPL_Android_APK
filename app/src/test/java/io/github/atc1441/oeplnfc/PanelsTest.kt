package io.github.atc1441.oeplnfc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The panel table is transcribed from two places at once - the geometry and
 * colour count from default_setting[] in settings.c, the names from the web
 * tool's type list - so the tests here are mostly about the two agreeing.
 */
class PanelsTest {

    @Test
    fun colourCountMatchesTheName() {
        for (p in Panels.all) {
            val expected = when (p.second) {
                Second.NONE -> 1
                Second.RED, Second.YELLOW -> 2
                Second.BOTH -> 3
            }
            assertEquals(
                "type ${p.type} (${p.name}) is named for ${p.second} but counts ${p.colors}",
                expected, p.colors
            )
        }
    }

    @Test
    fun everyTypeHasANameAndASize() {
        for (p in Panels.all) {
            assertTrue("type ${p.type} has no name", p.name.isNotBlank())
            assertTrue("type ${p.type} is ${p.h}x${p.w}", p.h > 0 && p.w > 0)
        }
    }

    @Test
    fun typesAreUniqueAndContiguous() {
        val types = Panels.all.map { it.type }
        assertEquals(types.size, types.toSet().size)
        assertEquals(1, types.min())
        assertEquals(types.size, types.max())
    }

    @Test
    fun theTagsOwnPanelsAreRight() {
        // The three the bench has, by name as the web tool lists them.
        assertEquals(Second.RED, Panels.byType(24)!!.second)        // 290 HS BWR SSD
        assertEquals(Second.YELLOW, Panels.byType(5)!!.second)      // 200 HS BWY SSD
        assertEquals(Second.BOTH, Panels.byType(40)!!.second)       // 260 HS BWRY JD
        assertEquals(Second.NONE, Panels.byType(4)!!.second)        // 350 HS BW UC
    }

    @Test
    fun anUnknownTypeFallsBackToTheCount() {
        assertNull(Panels.byType(Panels.DYNAMIC))
        assertEquals(Second.NONE, Panels.secondFor(Panels.DYNAMIC, 1))
        assertEquals(Second.RED, Panels.secondFor(Panels.DYNAMIC, 2))
        assertEquals(Second.BOTH, Panels.secondFor(Panels.DYNAMIC, 3))
    }

    @Test
    fun aKnownTypeBeatsTheCount() {
        // A yellow panel still reports "2 colours"; the count must not be
        // allowed to turn that into red.
        assertEquals(Second.YELLOW, Panels.secondFor(5, 2))
    }

    @Test
    fun describeSaysSomethingUseful() {
        assertTrue(Panels.describe(24, 2).contains("red"))
        assertTrue(Panels.describe(5, 2).contains("yellow"))
        assertTrue(Panels.describe(Panels.DYNAMIC, 2).contains("hand-written"))
        assertNotNull(Panels.describe(1234, 1))
    }

    @Test
    fun redAndYellowFlagsFollowTheSecondColour() {
        val bwry = Panels.byType(40)!!
        assertTrue(bwry.red && bwry.yellow)
        val bwr = Panels.byType(24)!!
        assertTrue(bwr.red && !bwr.yellow)
        val bw = Panels.byType(4)!!
        assertTrue(!bw.red && !bw.yellow)
    }
}
