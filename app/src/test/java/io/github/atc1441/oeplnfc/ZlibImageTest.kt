package io.github.atc1441.oeplnfc

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.zip.Inflater

/**
 * The compressed container, checked the way the tag reads it.
 *
 * There is no feedback from a wrong one: the tag refuses the stream or
 * decompresses it into rubbish and draws that, minutes after the upload
 * started. So the shape of the thing is pinned here.
 */
class ZlibImageTest {

    /** A plane with the kind of structure a dithered image has. */
    private fun plane(n: Int, seed: Int = 1): ByteArray {
        val b = ByteArray(n)
        var x = seed
        for (i in b.indices) {
            x = x * 1103515245 + 12345
            // Long runs with occasional noise, which is what an image of a QR
            // code or a mostly white panel actually looks like.
            b[i] = when {
                (i / 97) % 3 == 0 -> 0x00
                (i / 97) % 3 == 1 -> 0xFF.toByte()
                else -> ((x ushr 16) and 0xFF).toByte()
            }
        }
        return b
    }

    private fun inflate(stream: ByteArray, expected: Int): ByteArray {
        val inf = Inflater()
        inf.setInput(stream)
        val out = ByteArray(expected)
        var done = 0
        while (done < expected && !inf.finished()) {
            val n = inf.inflate(out, done, expected - done)
            if (n == 0 && (inf.needsInput() || inf.needsDictionary())) break
            done += n
        }
        inf.end()
        assertEquals("inflated short", expected, done)
        return out
    }

    @Test
    fun theHeaderIsWhatTheFirmwareReads() {
        val planes = plane(8064)
        val body = ZlibImage.body(planes, 168, 384, 1)
        // The firmware reads one byte, then that many less one.
        assertEquals(6, body[0].toInt())
        assertEquals(168, (body[1].toInt() and 0xFF) or ((body[2].toInt() and 0xFF) shl 8))
        assertEquals(384, (body[3].toInt() and 0xFF) or ((body[4].toInt() and 0xFF) shl 8))
        assertEquals(1, body[5].toInt())
        assertEquals(ZlibImage.HEADER_LEN + planes.size, body.size)
        assertArrayEquals(planes, body.copyOfRange(6, body.size))
    }

    @Test
    fun theWindowIsTheOneTheTagCanHold() {
        val z = ZlibImage.deflate(plane(20000))
        val cmf = z[0].toInt() and 0xFF
        val flg = z[1].toInt() and 0xFF
        assertEquals("deflate method", 8, cmf and 0x0F)
        // CINFO 4 means a 2^(4+8) = 4096 byte window, which is exactly the
        // dictionary the tag allocates. Anything larger is refused outright.
        assertEquals("window exponent", 4, (cmf shr 4) and 0x0F)
        assertEquals(4096, 0x100 shl ((cmf shr 4) and 0x0F))
        assertEquals("FCHECK", 0, (cmf * 256 + flg) % 31)
        assertEquals("no preset dictionary", 0, flg and 0x20)
    }

    @Test
    fun itRoundTripsThroughAStandardInflater() {
        for (n in intArrayOf(1, 100, 4095, 4096, 4097, 8064, 16128, 20001)) {
            val raw = plane(n, seed = n)
            val z = ZlibImage.deflate(raw)
            assertArrayEquals("size $n", raw, inflate(z, raw.size))
        }
    }

    @Test
    fun thePayloadStatesItsDecompressedLength() {
        val planes = plane(8064)
        val payload = ZlibImage.payload(planes, 168, 384, 1)
        val stated = (payload[0].toInt() and 0xFF) or
            ((payload[1].toInt() and 0xFF) shl 8) or
            ((payload[2].toInt() and 0xFF) shl 16) or
            ((payload[3].toInt() and 0xFF) shl 24)
        assertEquals(ZlibImage.HEADER_LEN + planes.size, stated)
        // And that is exactly what comes back out of the stream after it.
        val body = inflate(payload.copyOfRange(4, payload.size), stated)
        assertEquals(6, body[0].toInt())
        assertArrayEquals(planes, body.copyOfRange(6, body.size))
    }

    @Test
    fun twoPlanesAreCarried() {
        val planes = plane(16128)
        val payload = ZlibImage.payload(planes, 168, 384, 2)
        val stated = ZlibImage.HEADER_LEN + planes.size
        val body = inflate(payload.copyOfRange(4, payload.size), stated)
        assertEquals(2, body[5].toInt())
        assertArrayEquals(planes, body.copyOfRange(6, body.size))
    }

    @Test
    fun itIsActuallySmaller() {
        // The point of the exercise. A panel-sized plane with image-like
        // structure has to come out a fraction of its size, or the extra code
        // buys nothing.
        val planes = plane(8064)
        val payload = ZlibImage.payload(planes, 168, 384, 1)
        assertTrue("compressed to ${payload.size} of ${planes.size}",
            payload.size < planes.size / 2)
    }

    @Test
    fun aBlankPanelIsTiny() {
        val payload = ZlibImage.payload(ByteArray(8064), 168, 384, 1)
        assertTrue("blank panel came to ${payload.size} bytes", payload.size < 200)
    }

    /**
     * Writes the exact bytes this code produces to a file, so the hardware
     * bench can send them to a real tag and see whether its uzlib accepts
     * them. A round trip through [Inflater] proves the stream is valid zlib;
     * only the tag proves it is zlib the tag can read, and that is the thing
     * worth being sure about before a user waits out an upload.
     *
     * hwtest/zlib_upload.py reads it.
     */
    @Test
    fun writeASampleForTheHardwareBench() {
        // If the bench has left a real packed image next door, compress that:
        // a QR on the panel can be decoded from a photograph, where a
        // synthetic pattern can only be looked at.
        val fromBench = listOf(
            "../ATC_BLE_OEPL_FW/hwtest/run/qr_planes.bin",
            "../../ATC_BLE_OEPL_FW/hwtest/run/qr_planes.bin"
        ).map { java.io.File(it) }.firstOrNull { it.isFile }
        val planes = fromBench?.readBytes() ?: plane(8064)
        println("planes from " + (fromBench?.path ?: "the synthetic pattern"))
        val payload = ZlibImage.payload(planes, 168, 384, 1)
        val dir = java.io.File("build/sample")
        dir.mkdirs()
        java.io.File(dir, "zlib_payload.bin").writeBytes(payload)
        java.io.File(dir, "zlib_planes.bin").writeBytes(planes)
        println("sample: %d bytes compressed from %d".format(payload.size, planes.size))
    }

    @Test
    fun aPlaneCountOutsideTheFormatIsRefused() {
        for (n in intArrayOf(0, 3, 255)) {
            try {
                ZlibImage.body(ByteArray(16), 8, 2, n)
                throw AssertionError("planeCount $n should have been refused")
            } catch (_: IllegalArgumentException) {
            }
        }
    }
}
