package io.github.atc1441.oeplnfc

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The wire format is checked here rather than on a tag, because the firmware
 * answers a wrong checksum with a retry rather than an error message - an
 * off-by-one would show up as an upload that never finishes and never says why.
 *
 * Each expectation is derived from the firmware source, named in the test.
 */
class ProtocolTest {

    /** checkCRC() in src/syncedproto.c: byte 0 is the sum of all that follow. */
    private fun firmwareCheckCrc(p: ByteArray, len: Int): Boolean {
        var total = 0
        for (c in 1 until len) total = (total + (p[c].toInt() and 0xFF)) and 0xFF
        return (p[0].toInt() and 0xFF) == total
    }

    /** validateBlockData() in src/syncedproto.c. */
    private fun firmwareValidateBlock(buf: ByteArray): Boolean {
        val size = (buf[0].toInt() and 0xFF) or ((buf[1].toInt() and 0xFF) shl 8)
        val checksum = (buf[2].toInt() and 0xFF) or ((buf[3].toInt() and 0xFF) shl 8)
        if (4 + size > buf.size) return false
        var t = 0
        for (c in 0 until size) t = (t + (buf[4 + c].toInt() and 0xFF)) and 0xFFFF
        return checksum == t
    }

    @Test
    fun crc32MatchesTheStandardCheckValue() {
        // The canonical CRC-32 check value, so the table and the final xor are
        // the same ones the web tool's computeCRC32 uses.
        assertEquals(0xCBF43926.toInt(), crc32("123456789".toByteArray()))
    }

    @Test
    fun blockPartPassesTheFirmwareChecksum() {
        val payload = ByteArray(5000) { (it * 31 and 0xFF).toByte() }
        val block = blockWithHeader(payload, 0, BLOCK_DATA_SIZE)
        val count = partsInBlock(BLOCK_DATA_SIZE)
        assertEquals(18, count)   // (4 + 4096) / 230, rounded up
        for (i in 0 until count) {
            val part = blockPart(0, i, block)
            assertEquals(3 + BLOCK_PART_DATA_SIZE, part.size)
            assertTrue("part $i fails checkCRC", firmwareCheckCrc(part, part.size))
            assertEquals(0, part[1].toInt())
            assertEquals(i, part[2].toInt())
        }
    }

    @Test
    fun lastPartIsZeroPaddedNotTruncated() {
        // processBlockPartBLE always checksums a fixed 233 bytes, so a short
        // final part has to be padded or every upload fails on its last part.
        val payload = ByteArray(100) { 0x5A }
        val block = blockWithHeader(payload, 0, payload.size)
        val parts = (0 until partsInBlock(payload.size)).map { blockPart(0, it, block) }
        assertEquals(1, parts.size)
        assertEquals(3 + BLOCK_PART_DATA_SIZE, parts[0].size)
        assertTrue(firmwareCheckCrc(parts[0], parts[0].size))
        for (i in (3 + 4 + 100) until parts[0].size) assertEquals(0, parts[0][i].toInt())
    }

    @Test
    fun reassembledBlockPassesValidateBlockData() {
        // Walk a whole transfer the way the firmware does and check the buffer
        // it ends up with, including a block that is not a multiple of 230.
        val payload = ByteArray(BLOCK_DATA_SIZE + 777) { ((it xor 0x3C) and 0xFF).toByte() }
        var sent = 0
        var blockId = 0
        while (sent < payload.size) {
            val to = minOf(sent + BLOCK_DATA_SIZE, payload.size)
            val block = blockWithHeader(payload, sent, to)
            val buffer = ByteArray(BLOCK_DATA_SIZE + 4)
            for (i in 0 until partsInBlock(to - sent)) {
                val part = blockPart(blockId, i, block)
                val start = i * BLOCK_PART_DATA_SIZE
                var n = BLOCK_PART_DATA_SIZE
                if (start + n > buffer.size) n = buffer.size - start
                part.copyInto(buffer, start, 3, 3 + n)
            }
            assertTrue("block $blockId fails validateBlockData", firmwareValidateBlock(buffer))
            assertArrayEquals(
                payload.copyOfRange(sent, to),
                buffer.copyOfRange(4, 4 + (to - sent))
            )
            sent = to
            blockId++
        }
        assertEquals(2, blockId)
    }

    @Test
    fun availDataInfoHasTheStructLayout() {
        // struct AvailDataInfo, packed little endian, 17 bytes on the target.
        val a = availDataInfo(0x1122334455667788L, 0x00010203, 0x21, 0x05, 0x0607)
        assertEquals(17, a.size)
        assertEquals(0xFF, a[0].toInt() and 0xFF)
        assertArrayEquals(
            byteArrayOf(0x88.toByte(), 0x77, 0x66, 0x55, 0x44, 0x33, 0x22, 0x11),
            a.copyOfRange(1, 9)
        )
        assertArrayEquals(byteArrayOf(0x03, 0x02, 0x01, 0x00), a.copyOfRange(9, 13))
        assertEquals(0x21, a[13].toInt())
        assertEquals(0x05, a[14].toInt())
        assertArrayEquals(byteArrayOf(0x07, 0x06), a.copyOfRange(15, 17))
    }

    @Test
    fun type2TlvMatchesWhatTheApSends() {
        // prepareNFCReq() in the AP's content_tagdata.inl, for the same URL but
        // written out with no prefix abbreviation, is:
        //     03 1F D1 01 1B 55 00 <26 url bytes> FE
        // Ours abbreviates https:// to prefix code 0x04, which is the same
        // record in the shorter legal encoding - so the container must have the
        // identical shape and a correspondingly shorter length.
        val msg = NdefCodec.uriMessage("https://atc1441.github.io/")
        assertArrayEquals(
            byteArrayOf(0xD1.toByte(), 0x01, 0x13, 'U'.code.toByte(), 0x04) +
                "atc1441.github.io/".toByteArray(),
            msg
        )
        val tlv = NdefCodec.toType2Tlv(msg)
        assertEquals(0x03, tlv[0].toInt())
        assertEquals(msg.size, tlv[1].toInt() and 0xFF)
        assertEquals(0xFE, tlv[tlv.size - 1].toInt() and 0xFF)
        assertArrayEquals(msg, tlv.copyOfRange(2, 2 + msg.size))
    }

    @Test
    fun type2TlvUsesTheThreeByteLengthWhenNeeded() {
        val msg = NdefCodec.textMessage("x".repeat(300))
        val tlv = NdefCodec.toType2Tlv(msg)
        assertEquals(0x03, tlv[0].toInt())
        assertEquals(0xFF, tlv[1].toInt() and 0xFF)
        assertEquals(msg.size, ((tlv[2].toInt() and 0xFF) shl 8) or (tlv[3].toInt() and 0xFF))
        assertEquals(0xFE, tlv[tlv.size - 1].toInt() and 0xFF)
    }

    @Test
    fun uriPrefixesAreAbbreviatedTheSameWayTheFirmwareExpands() {
        // The codes must line up with uri_prefix[] in src/nfc_ndef.c.
        val cases = mapOf(
            "http://www.a.de" to Pair(0x01, "a.de"),
            "https://www.b.de" to Pair(0x02, "b.de"),
            "http://c.de" to Pair(0x03, "c.de"),
            "https://d.de" to Pair(0x04, "d.de"),
            "tel:+49123" to Pair(0x00, "tel:+49123")
        )
        for ((url, want) in cases) {
            val m = NdefCodec.uriMessage(url)
            assertEquals("code for $url", want.first, m[4].toInt() and 0xFF)
            assertEquals("body for $url", want.second, String(m, 5, m.size - 5))
        }
    }

    @Test
    fun describeReadsBackWhatWeWrite() {
        assertEquals("URL: https://a.de/x", NdefCodec.describe(NdefCodec.uriMessage("https://a.de/x")))
        assertEquals("Text: hallo", NdefCodec.describe(NdefCodec.textMessage("hallo")))
        assertEquals("(empty)", NdefCodec.describe(ByteArray(0)))
    }

    @Test
    fun dataVersionIsStableAndContentDependent() {
        val a = ByteArray(1000) { 1 }
        val b = ByteArray(1000) { 1 }
        val c = ByteArray(1000) { if (it == 500) 2 else 1 }
        assertEquals(dataVersionOf(a), dataVersionOf(b))
        assertTrue(dataVersionOf(a) != dataVersionOf(c))
        assertTrue(dataVersionOf(a) in 0..0xFFFFFFFFL)
    }

    @Test
    fun tagInfoReadsTheSettingsStructOffsets() {
        // A synthetic settings_struct: see src/settings.h. The offsets here are
        // the ones the web config tool parses too.
        val raw = ByteArray(176)
        fun put16(at: Int, v: Int) {
            raw[at] = (v and 0xFF).toByte(); raw[at + 1] = (v shr 8).toByte()
        }
        put16(12, 0x006C)          // oepl_hw_type
        raw[19] = 1                // screen_w_h_inversed_ble
        put16(22, 200)             // screen_h field
        put16(24, 152)             // screen_w field
        raw[30] = 3                // colors
        raw[33] = 3                // gui rotation
        raw[34] = 1                // oepl enabled
        for (i in 0 until 8) raw[47 + i] = (0x10 + i).toByte()
        "TAG".toByteArray().copyInto(raw, 61)
        raw[101] = 3

        val info = TagInfo(raw)
        assertEquals(0x006C, info.hwType)
        // inversed_ble set, so the field order is swapped on the way out
        assertEquals(200, info.width)
        assertEquals(152, info.height)
        assertEquals(3, info.colors)
        assertEquals(3, info.guiRotation)
        assertTrue(info.oeplEnabled)
        assertEquals("17:16:15:14:13:12:11:10", info.mac)
        assertEquals("TAG", info.name)
    }
}
