package io.github.atc1441.oeplnfc

/**
 * A TLSR8258 firmware image, checked before a single byte of it goes over the
 * air.
 *
 * The tag performs exactly these three checks itself in `isFirmwareValid()`
 * (src/eeprom.c) once the whole image has landed in its update area, and only
 * copies it over its own firmware if they pass. Doing them here as well is not
 * duplication for its own sake: a wrong file would otherwise take minutes to
 * transfer before being rejected, and the one thing worse than that is a file
 * that passes a sloppier check here than the tag applies there.
 *
 *  * the Telink startup marker, "TLNK" as a little endian word at offset 8;
 *  * the length word at 0x18, which the tag compares against its update area;
 *  * a CRC32 over everything but the last four bytes, which is where that
 *    CRC is stored.
 *
 * The CRC is the usual reflected CRC32 but *without* the final complement -
 * `the_xcrc32()` returns the register as it stands - so it is the familiar
 * value inverted. [crc32] here gives the familiar one, hence the `inv()`.
 */
object FirmwareImage {

    /** 0x544C4E4B, i.e. the bytes 4B 4E 4C 54 at offset 8. */
    const val TLNK = 0x544C4E4B

    const val MARKER_OFFSET = 8
    const val SIZE_OFFSET = 0x18

    /**
     * The tag's update area, EEPROM_UPDATE_AREA_LEN in src/eeprom.h. An image
     * larger than this is refused by the tag after the transfer, so it is
     * refused here before it.
     */
    const val MAX_SIZE = 0x33000

    class Invalid(message: String) : Exception(message)

    /** What the image says about itself, once it has been accepted. */
    class Info(val size: Int, val crc: Int) {
        /** One line for the log, in the terms the tag's own printf uses. */
        override fun toString() =
            "%d bytes, TLNK marker ok, CRC %08X ok".format(size, crc)
    }

    /**
     * Check [fw] the way the tag will, and throw [Invalid] with the reason if
     * it would not survive that check.
     */
    fun inspect(fw: ByteArray): Info {
        if (fw.size < SIZE_OFFSET + 4)
            throw Invalid("that file is ${fw.size} bytes, far too short for a firmware image")

        val marker = fw.u32le(MARKER_OFFSET)
        if (marker != TLNK)
            throw Invalid(
                "no TLNK marker at offset 8 (found %08X) - this is not a Telink firmware image"
                    .format(marker)
            )

        val stated = fw.u32le(SIZE_OFFSET)
        if (stated < 4 || stated > MAX_SIZE)
            throw Invalid("the image header claims $stated bytes, which the tag cannot hold")
        // The tag CRCs `stated` bytes from the start of its update area, so a
        // file that disagrees with its own header would be checked over the
        // wrong range - and a padded file is the common way that happens.
        if (stated != fw.size)
            throw Invalid("the header says $stated bytes, the file is ${fw.size}")

        val want = fw.u32le(fw.size - 4)
        val calc = crc32(fw.copyOfRange(0, fw.size - 4)).inv()
        if (calc != want)
            throw Invalid("CRC is %08X, the image header says %08X".format(calc, want))

        return Info(fw.size, calc)
    }
}
