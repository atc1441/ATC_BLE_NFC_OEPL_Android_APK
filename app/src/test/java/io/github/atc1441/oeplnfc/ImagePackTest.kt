package io.github.atc1441.oeplnfc

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The packed byte stream has to match what the firmware's image path expects,
 * which is also what the existing web tool produces. A wrong bit order or a
 * reset bit counter does not fail loudly - it just draws garbage on a panel -
 * so it is pinned down here.
 */
class ImagePackTest {

    private val W = ImagePack.WHITE
    private val B = ImagePack.BLACK
    private val R = ImagePack.RED
    private val Y = ImagePack.YELLOW

    private fun opts(block: ImageOptions.() -> Unit = {}) = ImageOptions().apply {
        dither = Dither.NONE
        block()
    }

    @Test
    fun blackPlaneIsInvertedAndMsbFirst() {
        // One row of eight: black, white x7 -> after inversion bit 7 set only.
        val px = intArrayOf(B, W, W, W, W, W, W, W)
        val out = ImagePack.pack(px, 8, 1, false, false)
        assertEquals(1, out.size)
        assertEquals(0x80.toByte(), out[0])

        val px2 = intArrayOf(W, W, W, W, W, W, W, B)
        assertEquals(0x01.toByte(), ImagePack.pack(px2, 8, 1, false, false)[0])
    }

    @Test
    fun allWhiteIsZeroAndAllBlackIsFf() {
        assertEquals(0x00.toByte(), ImagePack.pack(IntArray(8) { W }, 8, 1, false, false)[0])
        assertEquals(0xFF.toByte(), ImagePack.pack(IntArray(8) { B }, 8, 1, false, false)[0])
    }

    @Test
    fun colourPlaneFollowsTheBlackPlaneWhole() {
        // white, black, red, yellow, then four white, in one byte per plane.
        val px = intArrayOf(W, B, R, Y, W, W, W, W)
        val out = ImagePack.pack(px, 8, 1, true, true)
        assertEquals(2, out.size)
        // black plane: white=0, black=1, red=0, yellow=1
        assertEquals(0b01010000.toByte(), out[0])
        // colour plane: white=0, black=0, red=1, yellow=1
        assertEquals(0b00110000.toByte(), out[1])
    }

    @Test
    fun withoutAColourPlaneTheOutputIsOnePlane() {
        val px = IntArray(16) { W }
        assertEquals(2, ImagePack.pack(px, 16, 1, false, false).size)
        assertEquals(4, ImagePack.pack(px, 16, 1, true, false).size)
    }

    @Test
    fun theBitCounterRunsAcrossRowBoundaries() {
        // This is the property that is easy to get wrong: a panel whose width
        // is not a multiple of eight packs rows continuously, it does not pad
        // each row to a byte. Three rows of 4 px = 12 bits = 2 bytes.
        val px = intArrayOf(
            B, W, W, W,
            W, B, W, W,
            W, W, B, W
        )
        val out = ImagePack.pack(px, 4, 3, false, false)
        assertEquals(2, out.size)
        // black pixels at stream indices 0, 5, 10, MSB first
        assertEquals(0b10000100.toByte(), out[0])
        // 0b0010 for the last four pixels, then 0b1111 of padding: only white
        // sets a bit and the plane is inverted afterwards, so bits that were
        // never written come out black. Inherited from the web tool, and it
        // only ever touches the last few bits of the whole stream.
        assertEquals(0b00101111.toByte(), out[1])
    }

    @Test
    fun aPartialFinalByteIsStillEmitted() {
        val px = IntArray(12) { B }
        val out = ImagePack.pack(px, 12, 1, false, false)
        assertEquals(2, out.size)
        assertEquals(0xFF.toByte(), out[0])
        // four black pixels plus four padding bits, which read black too
        assertEquals(0xFF.toByte(), out[1])
    }

    @Test
    fun packedSizeMatchesTheByteCountTheFirmwareExpects() {
        // 200x152 1bpp is 3800 bytes, 2bpp twice that - the sizes the 2" panel
        // actually takes.
        val px = IntArray(200 * 152) { W }
        assertEquals(3800, ImagePack.pack(px, 200, 152, false, false).size)
        assertEquals(7600, ImagePack.pack(px, 200, 152, true, false).size)
    }

    // --- quantisation -------------------------------------------------------

    @Test
    fun nearestColourMapsToThePanelPalette() {
        val px = intArrayOf(
            0xFF202020.toInt(),   // near black
            0xFFF0F0F0.toInt(),   // near white
            0xFFE01010.toInt(),   // near red
            0xFFE0E010.toInt()    // near yellow
        )
        ImagePack.quantisePixels(px, 4, 1, opts { red = true; yellow = true })
        assertArrayEquals(intArrayOf(B, W, R, Y), px)
    }

    @Test
    fun aColourIsNotUsedUnlessThePanelHasIt() {
        val redPx = intArrayOf(0xFFFF0000.toInt())
        ImagePack.quantisePixels(redPx, 1, 1, opts())
        assertTrue("pure red on a mono panel must become black or white",
            redPx[0] == B || redPx[0] == W)

        val yellowPx = intArrayOf(0xFFFFFF00.toInt())
        ImagePack.quantisePixels(yellowPx, 1, 1, opts { red = true })
        assertTrue("yellow on a BWR panel must not come out yellow", yellowPx[0] != Y)
    }

    @Test
    fun invertSwapsBlackAndWhite() {
        val px = intArrayOf(B, W)
        ImagePack.quantisePixels(px, 2, 1, opts { invert = true })
        assertArrayEquals(intArrayOf(W, B), px)
    }

    @Test
    fun thresholdMovesTheBlackWhiteBoundary() {
        val mid = 0xFF808080.toInt()
        val low = intArrayOf(mid)
        ImagePack.quantisePixels(low, 1, 1, opts { threshold = 10 })
        assertEquals("a low threshold keeps mid grey white", W, low[0])

        val high = intArrayOf(mid)
        ImagePack.quantisePixels(high, 1, 1, opts { threshold = 250 })
        assertEquals("a high threshold pushes mid grey to black", B, high[0])
    }

    @Test
    fun brightnessAndContrastClampInsteadOfWrapping() {
        val px = IntArray(256) { 0xFF000000.toInt() or (it shl 16) or (it shl 8) or it }
        // Extreme settings must not produce a value outside 0..255, which would
        // show up as wrapped-around noise rather than a flat area.
        ImagePack.quantisePixels(px, 256, 1, opts { brightness = 100; contrast = 100 })
        assertTrue(px.all { it == B || it == W })
    }

    @Test
    fun everyDitherModeProducesOnlyPaletteColours() {
        for (d in Dither.values()) {
            val px = IntArray(64) { 0xFF000000.toInt() or (it * 4 * 0x010101) }
            ImagePack.quantisePixels(px, 8, 8, opts { dither = d; red = true; yellow = true })
            assertTrue(
                "$d produced a colour outside the palette",
                px.all { it == B || it == W || it == R || it == Y }
            )
        }
    }

    @Test
    fun ditheringAFlatMidGreyGivesAMixNotOneColour() {
        // The point of dithering: a flat 50% grey must come out as a mix. A
        // mode that collapses it to one colour is broken.
        for (d in listOf(Dither.FLOYD, Dither.ATKINSON, Dither.SIERRA_LITE,
                         Dither.BAYER4, Dither.BAYER8)) {
            val px = IntArray(256) { 0xFF808080.toInt() }
            ImagePack.quantisePixels(px, 16, 16, opts { dither = d })
            val blacks = px.count { it == B }
            assertTrue("$d collapsed a flat grey to a single colour",
                blacks in 1..255)
        }
    }

    @Test
    fun orderedMatricesArePermutationsOfTheirRange() {
        val m4 = ImagePack.ditherMatrix(4)
        assertEquals(16, m4.size)
        assertEquals((0 until 16).toList(), m4.sorted())

        val m8 = ImagePack.ditherMatrix(8)
        assertEquals(64, m8.size)
        assertEquals((0 until 64).toList(), m8.sorted())
    }

    // --- source decoding ----------------------------------------------------

    @Test
    fun sampleSizeBringsTheLongEdgeUnderTheTarget() {
        // The case that matters: a 12 MP camera photo against a 2048 target.
        // At full size that bitmap is about 48 MB of ARGB_8888, which is what
        // runs a small heap out of memory.
        assertEquals(2, ImagePack.sampleSizeFor(4000, 3000, 2048))
        assertEquals(2, ImagePack.sampleSizeFor(3000, 4000, 2048))
        // Already small enough: do not subsample and lose detail for nothing.
        assertEquals(1, ImagePack.sampleSizeFor(2048, 1536, 2048))
        assertEquals(1, ImagePack.sampleSizeFor(400, 300, 2048))
        // Exactly one over the line still has to halve.
        assertEquals(2, ImagePack.sampleSizeFor(2049, 100, 2048))
        // Very large sources keep halving.
        assertEquals(4, ImagePack.sampleSizeFor(8192, 100, 2048))
        assertEquals(8, ImagePack.sampleSizeFor(16384, 100, 2048))
    }

    @Test
    fun sampleSizeIsAlwaysAUsablePowerOfTwo() {
        // BitmapFactory rounds anything else down to a power of two, so a value
        // it cannot use would silently change the decoded size.
        for (w in listOf(1, 99, 640, 1920, 4000, 6000, 12000)) {
            for (t in listOf(1, 64, 512, 2048)) {
                val s = ImagePack.sampleSizeFor(w, w * 3 / 4, t)
                assertTrue("$w/$t gave $s", s >= 1 && (s and (s - 1)) == 0)
                assertTrue("$w/$t still over target", w / s <= t)
            }
        }
        // A nonsense target must not loop forever.
        assertEquals(1, ImagePack.sampleSizeFor(4000, 3000, 0))
    }

    // --- transform ----------------------------------------------------------

    @Test
    fun fitScaleCoversOrContainsAsAsked() {
        // A 100x100 source onto a 200x100 panel.
        assertEquals(2f, ImagePack.fitScale(100, 100, 200, 100, Fit.COVER), 1e-5f)
        assertEquals(1f, ImagePack.fitScale(100, 100, 200, 100, Fit.CONTAIN), 1e-5f)
        // Degenerate input must not divide by zero.
        assertEquals(1f, ImagePack.fitScale(0, 0, 200, 100, Fit.COVER), 1e-5f)
    }

    @Test
    fun resetPutsTheTransformAndLevelsBackToTheWebToolDefaults() {
        val o = ImageOptions()
        o.rotationDeg = 37f; o.scale = 3f; o.offsetX = 10f; o.offsetY = -4f
        o.flipH = true; o.flipV = true
        o.brightness = 50; o.contrast = -20; o.invert = true; o.threshold = 200
        o.resetTransform()
        o.resetLevels()
        assertEquals(0f, o.rotationDeg, 0f)
        assertEquals(1f, o.scale, 0f)
        assertEquals(0f, o.offsetX, 0f)
        assertEquals(0f, o.offsetY, 0f)
        assertTrue(!o.flipH && !o.flipV && !o.invert)
        assertEquals(0, o.brightness)
        assertEquals(0, o.contrast)
        assertEquals(128, o.threshold)
    }

    @Test
    fun defaultsReproduceTheWebToolsPipeline() {
        // Defaults must be: cover fit, Floyd-Steinberg, no tone changes, no
        // colour planes - i.e. what getPixelData() in the web tool does.
        val o = ImageOptions()
        assertEquals(Fit.COVER, o.fit)
        assertEquals(Dither.FLOYD, o.dither)
        assertEquals(0, o.brightness)
        assertEquals(0, o.contrast)
        assertEquals(128, o.threshold)
        assertEquals(1f, o.scale, 0f)
        assertEquals(0f, o.rotationDeg, 0f)
        assertTrue(!o.red && !o.yellow && !o.invert)
    }
}
