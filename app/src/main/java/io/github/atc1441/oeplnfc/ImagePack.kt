package io.github.atc1441.oeplnfc

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint

/**
 * Turns a bitmap into the byte stream the firmware's image path expects, with
 * the transform and tone controls from [ImageOptions] applied first.
 *
 * The packed format is not invented here: it is byte for byte what the existing
 * web tool (ATC_BLE_OEPL_Image_Upload.html, getPixelData) produces, so with
 * default options an image sent from this app lands on the panel exactly as one
 * sent from there.
 *
 * Layout: one bit per pixel, row major with x running fastest, most
 * significant bit first, and - this is the easy part to get wrong - the bit
 * position is NOT reset at the end of a row. A panel whose width is not a
 * multiple of eight therefore has rows running into one another.
 *
 * The black plane is stored inverted (a set bit means black); when a second
 * colour is in play its plane follows, appended whole:
 *
 *     white   black=0 colour=0
 *     black   black=1 colour=0
 *     red     black=0 colour=1
 *     yellow  black=1 colour=1
 */
object ImagePack {


    // ARGB_8888 helpers, identical to android.graphics.Color but without the
    // framework dependency - that keeps quantisation and packing testable in a
    // plain JVM unit test, where android.jar methods are not available.
    const val WHITE = 0xFFFFFFFF.toInt()
    const val BLACK = 0xFF000000.toInt()
    const val RED = 0xFFFF0000.toInt()
    const val YELLOW = 0xFFFFFF00.toInt()

    private fun cr(p: Int) = (p shr 16) and 0xFF
    private fun cg(p: Int) = (p shr 8) and 0xFF
    private fun cb(p: Int) = p and 0xFF
    private fun rgb(r: Int, g: Int, b: Int) = BLACK or (r shl 16) or (g shl 8) or b

    /**
      * What a transfer will send: [data] with [dataType], and - when that is
      * compressed - [rawSize] as the uncompressed planes would have been, so
      * the saving can be shown rather than guessed at.
      */
     class Result(
         val data: ByteArray,
         val dataType: Int,
         val preview: Bitmap,
         val rawSize: Int = data.size
     ) {
         val compressed get() = dataType == DataType.IMG_ZLIB
     }

    /**
     * Smallest power of two that brings the long edge of a source image to
     * [target] or under, for BitmapFactory.Options.inSampleSize.
     *
     * BitmapFactory only honours powers of two, and it divides - so the test
     * has to be on the result of the division, not on the next step down. An
     * off-by-one here is invisible until a phone with a small heap meets a
     * camera photo.
     */
    fun sampleSizeFor(w: Int, h: Int, target: Int): Int {
        if (target < 1) return 1
        var s = 1
        while (maxOf(w, h) / s > target) s *= 2
        return s
    }

    /**
     * The scale at which the source exactly satisfies the fit mode. Exposed so
     * the preview view can report a meaningful zoom percentage.
     */
    fun fitScale(srcW: Int, srcH: Int, w: Int, h: Int, fit: Fit): Float {
        if (srcW <= 0 || srcH <= 0) return 1f
        val sx = w.toFloat() / srcW
        val sy = h.toFloat() / srcH
        return if (fit == Fit.COVER) maxOf(sx, sy) else minOf(sx, sy)
    }

    /**
     * Draw the source into a panel-sized bitmap under the current transform.
     * Rotation is about the panel centre, so turning the dial does not also
     * walk the image out of frame.
     */
    fun render(src: Bitmap, opt: ImageOptions, w: Int, h: Int): Bitmap {
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        canvas.drawColor(opt.background)

        val base = fitScale(src.width, src.height, w, h, opt.fit) * opt.scale
        val m = Matrix()
        m.postTranslate(-src.width / 2f, -src.height / 2f)
        m.postScale(
            if (opt.flipH) -base else base,
            if (opt.flipV) -base else base
        )
        m.postRotate(opt.rotationDeg)
        m.postTranslate(w / 2f + opt.offsetX, h / 2f + opt.offsetY)

        canvas.drawBitmap(src, m, Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG))
        return out
    }

    /**
     * What the panel will show, without yet working out what goes on the wire.
     *
     * This is the half that has to keep up with a finger: it is recomputed on
     * every drag, zoom and slider tick. Packing and especially compressing are
     * not - the bytes only matter when something is about to be sent - so they
     * live in [packQuantised] and can wait for the gesture to end.
     */
    class Quantised(
        val px: IntArray,
        val w: Int,
        val h: Int,
        val preview: Bitmap,
        val red: Boolean,
        val yellow: Boolean,
        val compress: Boolean
    ) {
        /** The size of the raw planes, without packing them to find out. */
        val rawSize: Int get() = ((w * h + 7) / 8) * (if (red || yellow) 2 else 1)

        /** Whether this still matches what the options now say. */
        fun matches(opt: ImageOptions, w: Int, h: Int) =
            this.w == w && this.h == h && red == opt.red &&
                yellow == opt.yellow && compress == opt.compress
    }

    /** Render and quantise, stopping before the wire format. */
    fun quantised(src: Bitmap, opt: ImageOptions, w: Int, h: Int): Quantised {
        val rendered = render(src, opt, w, h)
        val px = IntArray(w * h)
        rendered.getPixels(px, 0, w, 0, 0, w, h)
        rendered.recycle()

        quantisePixels(px, w, h, opt)

        val preview = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        preview.setPixels(px, 0, w, 0, 0, w, h)
        return Quantised(px, w, h, preview, opt.red, opt.yellow, opt.compress)
    }

    /** Turn an already quantised image into the bytes a transfer sends. */
    fun packQuantised(q: Quantised): Result {
        val colorPlane = q.red || q.yellow
        val planes = pack(q.px, q.w, q.h, q.red, q.yellow)
        if (q.compress) {
            return Result(
                ZlibImage.payload(planes, q.w, q.h, if (colorPlane) 2 else 1),
                DataType.IMG_ZLIB, q.preview, planes.size
            )
        }
        return Result(
            planes,
            if (colorPlane) DataType.IMG_RAW_2BPP else DataType.IMG_RAW_1BPP,
            q.preview
        )
    }

    /** Render, quantise and pack in one go. */
    fun process(src: Bitmap, opt: ImageOptions, w: Int, h: Int): Result {
        val rendered = render(src, opt, w, h)
        val px = IntArray(w * h)
        rendered.getPixels(px, 0, w, 0, 0, w, h)
        rendered.recycle()

        quantisePixels(px, w, h, opt)

        val preview = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        preview.setPixels(px, 0, w, 0, 0, w, h)

        val colorPlane = opt.red || opt.yellow
        val planes = pack(px, w, h, opt.red, opt.yellow)
        if (opt.compress) {
            return Result(
                ZlibImage.payload(planes, w, h, if (colorPlane) 2 else 1),
                DataType.IMG_ZLIB, preview, planes.size
            )
        }
        return Result(
            planes,
            if (colorPlane) DataType.IMG_RAW_2BPP else DataType.IMG_RAW_1BPP,
            preview
        )
    }

    /**
     * Tone controls then quantisation, in place. This is the half of
     * [process] that needs no Android framework, which is also what makes
     * it directly unit testable.
     */
    fun quantisePixels(px: IntArray, w: Int, h: Int, opt: ImageOptions) {
        applyLevels(px, opt)
        quantise(px, w, h, opt)
    }

    // --- tone controls ------------------------------------------------------

    private fun applyLevels(px: IntArray, opt: ImageOptions) {
        if (opt.brightness == 0 && opt.contrast == 0 && !opt.invert) return
        // Standard contrast factor around mid grey, then a linear brightness
        // offset, so the two controls stay independent of each other.
        val c = (opt.contrast + 100) / 100f
        val cf = c * c
        val b = opt.brightness * 255 / 100
        val lut = IntArray(256)
        for (i in 0 until 256) {
            var v = ((i - 128) * cf + 128 + b).toInt()
            if (opt.invert) v = 255 - v
            lut[i] = v.coerceIn(0, 255)
        }
        for (i in px.indices) {
            val p = px[i]
            px[i] = rgb(
                lut[cr(p)], lut[cg(p)], lut[cb(p)]
            )
        }
    }

    // --- quantisation -------------------------------------------------------

    /** (dx, dy, weight) kernels; the divisor is the sum of the weights. */
    private val kFloyd = intArrayOf(1, 0, 7, -1, 1, 3, 0, 1, 5, 1, 1, 1)
    private val kAtkinson = intArrayOf(
        1, 0, 1, 2, 0, 1, -1, 1, 1, 0, 1, 1, 1, 1, 1, 0, 2, 1
    )
    private val kSierraLite = intArrayOf(1, 0, 2, -1, 1, 1, 0, 1, 1)

    private val bayer4 = intArrayOf(
        0, 8, 2, 10,
        12, 4, 14, 6,
        3, 11, 1, 9,
        15, 7, 13, 5
    )
    // The standard 8x8 Bayer matrix. A unit test checks it is a permutation of
    // 0..63, which is the one property a typo here would break.
    private val bayer8 = intArrayOf(
        0, 32, 8, 40, 2, 34, 10, 42,
        48, 16, 56, 24, 50, 18, 58, 26,
        12, 44, 4, 36, 14, 46, 6, 38,
        60, 28, 52, 20, 62, 30, 54, 22,
        3, 35, 11, 43, 1, 33, 9, 41,
        51, 19, 59, 27, 49, 17, 57, 25,
        15, 47, 7, 39, 13, 45, 5, 37,
        63, 31, 55, 23, 61, 29, 53, 21
    )

    /** Exposed only so the unit test can validate the matrices. */
    fun ditherMatrix(side: Int) = if (side == 4) bayer4 else bayer8

    private fun quantise(px: IntArray, w: Int, h: Int, opt: ImageOptions) {
        // The threshold slider is expressed as a brightness bias so it is
        // meaningful in every mode, not just the plain threshold one.
        val bias = 128 - opt.threshold
        when (opt.dither) {
            Dither.NONE ->
                for (i in px.indices) px[i] = nearest(shift(px[i], bias), opt.red, opt.yellow)
            Dither.FLOYD -> diffuse(px, w, h, opt, kFloyd, 16, bias)
            Dither.ATKINSON -> diffuse(px, w, h, opt, kAtkinson, 8, bias)
            Dither.SIERRA_LITE -> diffuse(px, w, h, opt, kSierraLite, 4, bias)
            Dither.BAYER4 -> ordered(px, w, h, opt, bayer4, 4, 16, bias)
            Dither.BAYER8 -> ordered(px, w, h, opt, bayer8, 8, 64, bias)
        }
    }

    private fun shift(p: Int, bias: Int): Int {
        if (bias == 0) return p
        return rgb(
            (cr(p) + bias).coerceIn(0, 255),
            (cg(p) + bias).coerceIn(0, 255),
            (cb(p) + bias).coerceIn(0, 255)
        )
    }

    /**
     * Error diffusion. Atkinson deliberately throws away a quarter of the
     * error (its weights sum to 6 over a divisor of 8), which is what gives it
     * the open, high-contrast look - so the divisor is passed in rather than
     * derived from the weights.
     */
    private fun diffuse(
        px: IntArray, w: Int, h: Int, opt: ImageOptions,
        kernel: IntArray, divisor: Int, bias: Int
    ) {
        val er = FloatArray(w * h)
        val eg = FloatArray(w * h)
        val eb = FloatArray(w * h)

        for (y in 0 until h) {
            for (x in 0 until w) {
                val i = y * w + x
                val p = px[i]
                val oldR = (cr(p) + bias + er[i]).coerceIn(0f, 255f).toInt()
                val oldG = (cg(p) + bias + eg[i]).coerceIn(0f, 255f).toInt()
                val oldB = (cb(p) + bias + eb[i]).coerceIn(0f, 255f).toInt()
                val q = nearest(rgb(oldR, oldG, oldB), opt.red, opt.yellow)
                px[i] = q
                val dr = (oldR - cr(q)).toFloat() / divisor
                val dg = (oldG - cg(q)).toFloat() / divisor
                val db = (oldB - cb(q)).toFloat() / divisor
                var k = 0
                while (k < kernel.size) {
                    val nx = x + kernel[k]
                    val ny = y + kernel[k + 1]
                    val wt = kernel[k + 2]
                    k += 3
                    if (nx < 0 || nx >= w || ny < 0 || ny >= h) continue
                    val j = ny * w + nx
                    er[j] += dr * wt
                    eg[j] += dg * wt
                    eb[j] += db * wt
                }
            }
        }
    }

    /** Ordered dithering: a fixed per-pixel bias from the matrix, no state. */
    private fun ordered(
        px: IntArray, w: Int, h: Int, opt: ImageOptions,
        matrix: IntArray, side: Int, levels: Int, bias: Int
    ) {
        for (y in 0 until h) {
            for (x in 0 until w) {
                val i = y * w + x
                val t = matrix[(y % side) * side + (x % side)]
                // Spread the matrix over +/- 128 so it actually pushes pixels
                // across the decision boundary.
                val add = bias + ((t * 256 / levels) - 128)
                px[i] = nearest(shift(px[i], add), opt.red, opt.yellow)
            }
        }
    }

    private fun nearest(p: Int, red: Boolean, yellow: Boolean): Int {
        val r = cr(p)
        val g = cg(p)
        val b = cb(p)
        var best = WHITE
        var bestD = dist(r, g, b, 255, 255, 255)
        var d = dist(r, g, b, 0, 0, 0)
        if (d < bestD) { bestD = d; best = BLACK }
        if (red) {
            d = dist(r, g, b, 255, 0, 0)
            if (d < bestD) { bestD = d; best = RED }
        }
        if (yellow) {
            d = dist(r, g, b, 255, 255, 0)
            if (d < bestD) best = YELLOW
        }
        return best
    }

    private fun dist(r: Int, g: Int, b: Int, r2: Int, g2: Int, b2: Int): Int {
        val dr = r - r2
        val dg = g - g2
        val db = b - b2
        return dr * dr + dg * dg + db * db
    }

    // --- bit packing --------------------------------------------------------

    /**
     * Pack an already-quantised image. Kept separate and free of options so it
     * stays the one place the wire format is decided.
     */
    fun pack(px: IntArray, w: Int, h: Int, red: Boolean, yellow: Boolean): ByteArray {
        val colorPlane = red || yellow
        val planeBytes = (w * h + 7) / 8
        val black = ByteArray(planeBytes)
        val colour = ByteArray(planeBytes)

        var bit = 7
        var idx = 0
        var cur = 0
        var curColour = 0
        for (y in 0 until h) {
            for (x in 0 until w) {
                val p = px[y * w + x]
                val r = cr(p)
                val g = cg(p)
                val b = cb(p)

                val isBlack = r < 128 && g < 128 && b < 128
                val isYellow = yellow && !isBlack && r > 128 && g > 128 && b < 128
                val isRed = red && !isBlack && !isYellow && r > 128 && g < 128 && b < 128

                when {
                    isBlack -> {}
                    isRed -> {
                        cur = cur or (1 shl bit)
                        curColour = curColour or (1 shl bit)
                    }
                    isYellow -> curColour = curColour or (1 shl bit)
                    else -> cur = cur or (1 shl bit)   // white
                }

                bit--
                if (bit < 0) {
                    black[idx] = cur.inv().toByte()
                    colour[idx] = curColour.toByte()
                    idx++
                    cur = 0
                    curColour = 0
                    bit = 7
                }
            }
        }
        if (bit != 7 && idx < planeBytes) {
            black[idx] = cur.inv().toByte()
            colour[idx] = curColour.toByte()
            idx++
        }

        val out = if (colorPlane) ByteArray(idx * 2) else ByteArray(idx)
        black.copyInto(out, 0, 0, idx)
        if (colorPlane) colour.copyInto(out, idx, 0, idx)
        return out
    }
}
