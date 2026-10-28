package io.github.atc1441.oeplnfc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The check byte that rides with a staged command.
 *
 * It only works if both ends compute it the same way, and they are in
 * different languages in different repositories - so the vector below is
 * pinned. It was taken from the firmware's own t4t_cmd_digest() and confirmed
 * on hardware: the tag accepted a command carrying this byte and refused the
 * same command carrying any other, with SW 6A80.
 *
 * If this test fails, the two have drifted and every staged command over NFC
 * will be refused.
 */
class CommandCheckTest {

    private val vector = byteArrayOf(
        0x00, 0x1B, 0x7F, 0x00, 0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07
    )

    @Test
    fun digestMatchesTheFirmware() {
        assertEquals(0x4628, OeplNfc.cmdDigest(vector))
        assertEquals(54, OeplNfc.checkByte(vector))
    }

    @Test
    fun theCheckIsNeverZero() {
        // Zero is how a host says it sent no check at all, so a command whose
        // digest happens to land there must not be sent unprotected.
        for (n in 0..600) {
            val data = ByteArray(n) { (it * 7 + n).toByte() }
            val c = OeplNfc.checkByte(data)
            assertTrue("check byte $c out of range for length $n", c in 1..127)
        }
    }

    @Test
    fun aChangedByteChangesTheCheck() {
        // The failure this exists to catch: one byte of a command altered on
        // the way in. A plain sum would miss a swap; this is position
        // dependent, so it does not.
        val a = byteArrayOf(0x00, 0x64, 0x11, 0x22, 0x33)
        val b = byteArrayOf(0x00, 0x64, 0x22, 0x11, 0x33)
        assertNotEquals(OeplNfc.cmdDigest(a), OeplNfc.cmdDigest(b))
        val c = a.copyOf()
        c[1] = 0x1B
        assertNotEquals(OeplNfc.checkByte(a), OeplNfc.checkByte(c))
    }
}
