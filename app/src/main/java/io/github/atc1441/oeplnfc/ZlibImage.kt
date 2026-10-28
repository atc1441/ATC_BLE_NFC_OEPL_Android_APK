package io.github.atc1441.oeplnfc

import java.io.ByteArrayOutputStream
import java.util.zip.Adler32
import java.util.zip.Deflater

/**
 * The compressed image format, `DATATYPE_IMG_ZLIB`, as `decompressOneTime()`
 * in the firmware's `src/drawing.c` reads it.
 *
 * Worth having over NFC more than anywhere else: a 168x384 one-plane image is
 * 8064 bytes raw and a few hundred compressed, and over this link every byte
 * is a frame's worth of time. It is also what the access point sends, so this
 * is the same path the tag already takes for an image over the radio.
 *
 * The payload stored in the image slot is:
 *
 *     u32 LE  size of everything the stream decompresses to
 *     zlib stream
 *
 * and the stream decompresses to a tiny header followed by the planes:
 *
 *     u8      header length + 1, so 6
 *     u16 LE  width
 *     u16 LE  height
 *     u8      number of planes, 1 or 2
 *     plane 1 (width*height/8 bytes), then plane 2 if there is one
 *
 * The firmware reads that first byte on its own and then the rest in one go,
 * which is why the length counts itself out by one.
 *
 * **The window size is the part that has to be right.** The tag decompresses
 * with uzlib into a fixed 4096 byte dictionary and refuses anything that asks
 * for more (`MAX_WINDOW_SIZE`), while [Deflater] writes a 32K window by
 * default - the stream would be rejected before a byte of it was used. Simply
 * patching the header byte would be worse: it would be accepted and then
 * decompress to rubbish, because the matches really do reach back further than
 * the dictionary the tag keeps. So the input is fed in 4096 byte pieces with a
 * `FULL_FLUSH` between them, which resets the compression state and leaves no
 * match pointing further back than the tag can follow.
 */
object ZlibImage {

    /** What the tag's dictionary holds, from MAX_WINDOW_SIZE in drawing.c. */
    const val WINDOW = 4096

    /** zlib header for CM=8, CINFO=4 (a 4096 byte window); 0x480D % 31 == 0. */
    private const val CMF = 0x48
    private const val FLG = 0x0D

    const val HEADER_LEN = 6

    /**
     * The decompressed form: the little header the firmware expects, then the
     * plane bytes exactly as [ImagePack.pack] produced them.
     */
    fun body(planes: ByteArray, w: Int, h: Int, planeCount: Int): ByteArray {
        require(planeCount == 1 || planeCount == 2) { "planeCount must be 1 or 2" }
        val out = ByteArray(HEADER_LEN + planes.size)
        out[0] = HEADER_LEN.toByte()
        out[1] = (w and 0xFF).toByte()
        out[2] = ((w shr 8) and 0xFF).toByte()
        out[3] = (h and 0xFF).toByte()
        out[4] = ((h shr 8) and 0xFF).toByte()
        out[5] = planeCount.toByte()
        planes.copyInto(out, HEADER_LEN)
        return out
    }

    /**
     * Deflate [data] into a zlib stream the tag can follow: a 4096 byte
     * window, declared in the header and respected by the matches.
     */
    fun deflate(data: ByteArray): ByteArray {
        val out = ByteArrayOutputStream(data.size / 4 + 64)
        out.write(CMF)
        out.write(FLG)

        val def = Deflater(Deflater.BEST_COMPRESSION, true)
        try {
            val buf = ByteArray(8192)
            var off = 0
            while (off < data.size) {
                val n = minOf(WINDOW, data.size - off)
                def.setInput(data, off, n)
                off += n
                if (off >= data.size) {
                    def.finish()
                    while (!def.finished()) {
                        val k = def.deflate(buf, 0, buf.size)
                        if (k <= 0) break
                        out.write(buf, 0, k)
                    }
                } else {
                    // FULL_FLUSH empties the input and resets the compression
                    // state, so the next piece starts a fresh window. Keep
                    // going while the output buffer came back full, which is
                    // how Deflater says there is more.
                    var k: Int
                    do {
                        k = def.deflate(buf, 0, buf.size, Deflater.FULL_FLUSH)
                        if (k > 0) out.write(buf, 0, k)
                    } while (k == buf.size)
                }
            }
        } finally {
            def.end()
        }

        val sum = Adler32()
        sum.update(data)
        val v = sum.value
        out.write(((v shr 24) and 0xFF).toInt())
        out.write(((v shr 16) and 0xFF).toInt())
        out.write(((v shr 8) and 0xFF).toInt())
        out.write((v and 0xFF).toInt())
        return out.toByteArray()
    }

    /**
     * The whole payload for an image slot: the decompressed length, then the
     * stream. [planes] is what the uncompressed path would have sent.
     */
    fun payload(planes: ByteArray, w: Int, h: Int, planeCount: Int): ByteArray {
        val raw = body(planes, w, h, planeCount)
        val z = deflate(raw)
        val out = ByteArray(4 + z.size)
        out[0] = (raw.size and 0xFF).toByte()
        out[1] = ((raw.size shr 8) and 0xFF).toByte()
        out[2] = ((raw.size shr 16) and 0xFF).toByte()
        out[3] = ((raw.size shr 24) and 0xFF).toByte()
        z.copyInto(out, 4)
        return out
    }
}
