package io.github.atc1441.oeplnfc

/** How a pixel that is not one of the panel's colours gets turned into one. */
enum class Dither(val label: String) {
    NONE("None (threshold)"),
    FLOYD("Floyd-Steinberg"),
    ATKINSON("Atkinson"),
    SIERRA_LITE("Sierra Lite"),
    BAYER4("Ordered 4x4"),
    BAYER8("Ordered 8x8");

    override fun toString() = label
}

/** Whether the image fills the panel and gets cropped, or fits inside it. */
enum class Fit(val label: String) {
    COVER("Fill (crop)"),
    CONTAIN("Fit (letterbox)");

    override fun toString() = label
}

/**
 * Everything the user can change about an image before it is sent.
 *
 * [scale] and [offsetX]/[offsetY] are relative to the fit the [fit] mode
 * produces, so 1.0 / 0 / 0 is always "as the fit mode intended" no matter what
 * the panel or the source size is - which is what makes the Reset button
 * meaningful and the defaults reproduce the web tool exactly.
 */
class ImageOptions {
    var rotationDeg = 0f          // free, 0..360
    var scale = 1f                // multiplies the fit scale
    var offsetX = 0f              // in panel pixels
    var offsetY = 0f
    var flipH = false
    var flipV = false
    var fit = Fit.COVER
    var background = ImagePack.WHITE

    var brightness = 0            // -100..100
    var contrast = 0              // -100..100
    var invert = false

    // Used by Dither.NONE directly, and folded into the other modes as a
    // brightness bias so the slider does something useful in every mode.
    var threshold = 128           // 0..255

    var dither = Dither.FLOYD
    var red = false
    var yellow = false

    /**
     * Send the image compressed (DATATYPE_IMG_ZLIB) rather than as raw planes.
     * On by default: it is the same picture either way and it is several times
     * less to carry, which over NFC is several times less time holding a phone
     * against a tag. Turn it off to send what the uncompressed path sends, for
     * a tag whose firmware predates the compressed format.
     */
    var compress = true

    fun resetTransform() {
        rotationDeg = 0f
        scale = 1f
        offsetX = 0f
        offsetY = 0f
        flipH = false
        flipV = false
    }

    fun resetLevels() {
        brightness = 0
        contrast = 0
        invert = false
        threshold = 128
    }
}
