package io.github.atc1441.oeplnfc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * The firmware image check, pinned against the tag's own.
 *
 * The important case is the real thing: `ATC_BLE_OEPL.bin` is built by the
 * Telink toolchain and carries the marker, length and CRC that the tag's
 * `isFirmwareValid()` looks for, so if this check disagrees with that file it
 * disagrees with the tag. Everything else here is a single byte of that same
 * file changed, because a check that only passes valid images is half a check.
 */
class FirmwareImageTest {

    /** The repository's own build output, two directories up from the app. */
    private fun realImage(): ByteArray? {
        for (p in listOf(
            "../ATC_BLE_OEPL_FW/ATC_BLE_OEPL.bin",
            "../../ATC_BLE_OEPL_FW/ATC_BLE_OEPL.bin"
        )) {
            val f = java.io.File(p)
            if (f.isFile) return f.readBytes()
        }
        return null
    }

    /**
     * A synthetic image with the same three properties, for the cases that
     * need a specific shape rather than a specific file.
     */
    private fun image(size: Int = 512): ByteArray {
        val fw = ByteArray(size)
        for (i in fw.indices) fw[i] = (i * 7).toByte()
        fw.putLe(FirmwareImage.MARKER_OFFSET, FirmwareImage.TLNK)
        fw.putLe(FirmwareImage.SIZE_OFFSET, size)
        fw.putLe(size - 4, crc32(fw.copyOfRange(0, size - 4)).inv())
        return fw
    }

    private fun ByteArray.putLe(at: Int, v: Int) {
        for (k in 0 until 4) this[at + k] = (v shr (8 * k)).toByte()
    }

    private fun refused(fw: ByteArray): String {
        try {
            FirmwareImage.inspect(fw)
        } catch (e: FirmwareImage.Invalid) {
            return e.message ?: ""
        }
        fail("that image should have been refused")
        return ""
    }

    @Test
    fun theFirmwareInThisRepositoryIsAccepted() {
        val fw = realImage()
        if (fw == null) {
            // Not a failure: the unit tests have to run without the firmware
            // tree next door. The synthetic cases still cover the rules.
            println("ATC_BLE_OEPL.bin not found next door, skipping")
            return
        }
        val info = FirmwareImage.inspect(fw)
        assertEquals(fw.size, info.size)
        assertTrue(fw.size in 1000..FirmwareImage.MAX_SIZE)
    }

    @Test
    fun aSyntheticImageIsAccepted() {
        assertEquals(512, FirmwareImage.inspect(image()).size)
    }

    @Test
    fun theMarkerHasToBeThere() {
        val fw = image()
        fw[FirmwareImage.MARKER_OFFSET] = 0
        // The CRC covers the marker, so fix it up: this must fail on the
        // marker, not incidentally on the checksum.
        fw.putLe(fw.size - 4, crc32(fw.copyOfRange(0, fw.size - 4)).inv())
        assertTrue(refused(fw).contains("TLNK"))
    }

    @Test
    fun theLengthHasToMatchTheFile() {
        val fw = image()
        fw.putLe(FirmwareImage.SIZE_OFFSET, 500)
        fw.putLe(fw.size - 4, crc32(fw.copyOfRange(0, fw.size - 4)).inv())
        assertTrue(refused(fw).contains("500"))
    }

    @Test
    fun aLengthLargerThanTheUpdateAreaIsRefused() {
        val fw = image()
        fw.putLe(FirmwareImage.SIZE_OFFSET, FirmwareImage.MAX_SIZE + 1)
        fw.putLe(fw.size - 4, crc32(fw.copyOfRange(0, fw.size - 4)).inv())
        assertTrue(refused(fw).contains("cannot hold"))
    }

    @Test
    fun oneFlippedByteIsCaught() {
        val fw = image()
        fw[100] = (fw[100].toInt() xor 1).toByte()
        assertTrue(refused(fw).contains("CRC"))
    }

    @Test
    fun aShortFileIsRefusedRatherThanIndexedPast() {
        assertTrue(refused(ByteArray(16)).contains("too short"))
        assertTrue(refused(ByteArray(0)).contains("too short"))
    }

    /**
     * The tag's CRC is the register as `the_xcrc32()` leaves it - reflected
     * CRC32 with no final complement - which is the familiar value inverted.
     * Getting this backwards would reject every real image, so it is pinned
     * against a known vector rather than against our own helper.
     */
    @Test
    fun theCrcConventionMatchesTheFirmware() {
        // CRC32("123456789") is 0xCBF43926 in the usual convention.
        assertEquals(0xCBF43926.toInt(), crc32("123456789".toByteArray()))
        val fw = image()
        val stored = fw.u32le(fw.size - 4)
        assertEquals(crc32(fw.copyOfRange(0, fw.size - 4)).inv(), stored)
    }
}
