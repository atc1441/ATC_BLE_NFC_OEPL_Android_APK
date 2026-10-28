package io.github.atc1441.oeplnfc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * How a long command is cut up for the tag's staged INS 0xC0 delivery.
 *
 * The rule the firmware depends on: every staged piece is full, the final
 * piece is the one that dispatches, and it is never empty - an empty dispatch
 * would be answered with SW 6700 and the staged command would be dropped.
 */
class StagingTest {

    private fun check(size: Int, limit: Int) {
        val (staged, dispatchAt) = OeplNfc.stageSlices(size, limit)
        for ((i, off) in staged.withIndex()) {
            assertEquals("piece $i is not where it should be", i * limit, off)
        }
        assertEquals("dispatch does not follow the staged pieces",
            staged.size * limit, dispatchAt)
        val tail = size - dispatchAt
        assertTrue("dispatch piece is empty", tail > 0)
        assertTrue("dispatch piece is too long for one APDU", tail <= limit)
    }

    @Test
    fun shortCommandIsOneApdu() {
        val (staged, dispatchAt) = OeplNfc.stageSlices(7, 20)
        assertTrue(staged.isEmpty())
        assertEquals(0, dispatchAt)
    }

    @Test
    fun exactlyOneApdu() {
        val (staged, dispatchAt) = OeplNfc.stageSlices(20, 20)
        assertTrue(staged.isEmpty())
        assertEquals(0, dispatchAt)
    }

    @Test
    fun oneByteOver() {
        val (staged, dispatchAt) = OeplNfc.stageSlices(21, 20)
        assertEquals(listOf(0), staged)
        assertEquals(20, dispatchAt)
    }

    @Test
    fun anOeplBlockPart() {
        // 233 byte block part plus the two command id bytes, at MLc 20.
        val (staged, dispatchAt) = OeplNfc.stageSlices(235, 20)
        assertEquals(11, staged.size)
        assertEquals(220, dispatchAt)
        check(235, 20)
    }

    @Test
    fun exactMultipleStillEndsWithAFullPiece() {
        // 240 = 12 x 20: the last twenty bytes dispatch, they are not staged
        // and followed by an empty dispatch.
        val (staged, dispatchAt) = OeplNfc.stageSlices(240, 20)
        assertEquals(11, staged.size)
        assertEquals(220, dispatchAt)
        check(240, 20)
    }

    @Test
    fun theSizeActuallyUsed() {
        // A block part at the piece size the client falls back to when the
        // capability container could not be read.
        val (staged, dispatchAt) = OeplNfc.stageSlices(235, OeplNfc.STAGE_PIECE)
        assertEquals(13, staged.size)
        assertEquals(13 * OeplNfc.STAGE_PIECE, dispatchAt)
        check(235, OeplNfc.STAGE_PIECE)
    }

    @Test
    fun everyPieceFitsOneFrame() {
        // The invariant that matters, and the one a fixed 150 byte piece
        // broke: a staged APDU is [CLA INS P1 P2 Lc][data], it travels as
        // [PCB][APDU][CRC16], and the tag's NFC chip has a 32 byte FIFO. A
        // piece that does not fit has to be chained into two frames, and
        // measured against this tag every chained command failed - at 22
        // bytes and at 140 - while everything inside one frame went through.
        //
        // So the default piece size has to fit, whatever else changes around
        // it.
        val frame = 1 + 5 + OeplNfc.STAGE_PIECE + 2
        assertTrue("a staged piece of ${OeplNfc.STAGE_PIECE} bytes makes a " +
            "$frame byte frame, over the 32 the chip holds", frame <= 32)
    }

    @Test
    fun everySizeUpToTheStagingBuffer() {
        for (limit in intArrayOf(5, 14, 18, 20, 29, 60, 150)) {
            for (size in 1..OeplNfc.STAGE_MAX) check(size, limit)
        }
    }
}
