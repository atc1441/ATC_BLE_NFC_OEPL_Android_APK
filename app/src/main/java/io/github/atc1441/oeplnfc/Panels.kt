package io.github.atc1441.oeplnfc

/** Which colour a panel can show besides black and white. */
enum class Second { NONE, RED, YELLOW, BOTH }

/**
 * What a screen type is, as far as an image needs to know.
 *
 * The tag reports its colour *count* and nothing else: screen_colors is 1, 2
 * or 3. Two is where that falls down - it means "there is a second colour",
 * not which one, and sending a red plane to a yellow panel paints the picture
 * in the wrong colour with no error anywhere. The type number does say, and
 * the tag reports that too, so the second colour is looked up here rather than
 * guessed from the count.
 *
 * [h] and [w] are in the firmware's own order (screen_h, screen_w), copied
 * from default_setting[] in settings.c; the names are the ones the web tool
 * shows, so a tag's type reads the same in both places. Geometry for an upload
 * still comes from the tag itself - this is for naming it and for the colours.
 */
class Panel(
    val type: Int,
    val name: String,
    val h: Int,
    val w: Int,
    val colors: Int,
    val second: Second
) {
    val red get() = second == Second.RED || second == Second.BOTH
    val yellow get() = second == Second.YELLOW || second == Second.BOTH

    /** "290 HS BWR SSD, 296x128, black/white/red" */
    val label: String get() = "$name, ${h}x$w, $colourWords"

    val colourWords: String
        get() = when (second) {
            Second.NONE -> "black/white"
            Second.RED -> "black/white/red"
            Second.YELLOW -> "black/white/yellow"
            Second.BOTH -> "black/white/red/yellow"
        }
}

object Panels {
    /** The type a tag reports when it runs a hand-written configuration. */
    const val DYNAMIC = 65535

    private fun p(type: Int, name: String, h: Int, w: Int, colors: Int, second: Second) =
        Panel(type, name, h, w, colors, second)

    val all: List<Panel> = listOf(
        p( 1, "350 HS BWY UC", 384, 184, 2, Second.YELLOW),
        p( 2, "350 HS BWY UC Inverted", 384, 184, 2, Second.YELLOW),
        p( 3, "350 HS BWY SSD", 384, 184, 2, Second.YELLOW),
        p( 4, "350 HS BW UC", 384, 184, 1, Second.NONE),
        p( 5, "200 HS BWY SSD", 200, 152, 2, Second.YELLOW),
        p( 6, "750 HS BWY UC", 480, 800, 2, Second.YELLOW),
        p( 7, "350 HS BWR UC", 384, 184, 2, Second.RED),
        p( 8, "350 HS BWR SSD", 384, 184, 2, Second.RED),
        p( 9, "266 HS BWR SSD", 296, 152, 2, Second.RED),
        p(10, "213 HS BW UC", 250, 128, 1, Second.NONE),
        p(11, "213 Gici BWR SSD", 256, 128, 2, Second.RED),
        p(12, "290 Gici BWR SSD", 296, 128, 2, Second.RED),
        p(13, "213 Gici BW ST", 250, 136, 1, Second.NONE),
        p(14, "970 TI BWR", 672, 960, 2, Second.RED),
        p(15, "1200 TI BWR", 768, 960, 2, Second.RED),
        p(16, "213 HS BWR SSD", 256, 128, 2, Second.RED),
        p(17, "350 HS BWRY JD", 384, 184, 3, Second.BOTH),
        p(18, "154 HS BWR H SSD", 200, 200, 2, Second.RED),
        p(19, "213 HS BWR UC", 250, 128, 2, Second.RED),
        p(20, "420 HS BWR SSD", 300, 400, 2, Second.RED),
        p(21, "420 HS BWR UC", 300, 400, 2, Second.RED),
        p(22, "420 Gici BWR SSD", 300, 400, 2, Second.RED),
        p(23, "1200 TI BWR V2", 768, 960, 2, Second.RED),
        p(24, "290 HS BWR SSD", 296, 128, 2, Second.RED),
        p(25, "213 HS BW SSD", 212, 104, 1, Second.NONE),
        p(26, "581 TI BW", 720, 256, 1, Second.NONE),
        p(27, "581 TI BWR", 720, 256, 2, Second.RED),
        p(28, "213 Gici BWR UC", 256, 128, 2, Second.RED),
        p(29, "213 Gici BW SSD", 256, 128, 1, Second.NONE),
        p(30, "213 Gici BW UC", 256, 128, 1, Second.NONE),
        p(31, "583 HS BWY UC", 480, 648, 2, Second.YELLOW),
        p(32, "350 HS BW SSD", 384, 184, 1, Second.NONE),
        p(33, "266 HS BWR SSD Offset", 296, 152, 2, Second.RED),
        p(34, "581 TI BWR UC", 256, 720, 2, Second.RED),
        p(35, "346 HS BWY UC", 480, 176, 2, Second.YELLOW),
        p(36, "290 WO BWRY JD", 296, 128, 3, Second.BOTH),
        p(37, "750 HS BWRY JD", 480, 800, 3, Second.BOTH),
        p(38, "200 HS BWRY JD", 200, 152, 3, Second.BOTH),
        p(39, "290 WO BWRY JD V2", 296, 128, 3, Second.BOTH),
        p(40, "260 HS BWRY JD", 296, 152, 3, Second.BOTH),
        p(41, "260 WO BWRY JD V2", 296, 152, 3, Second.BOTH),
        p(42, "420 HS BWY SSD", 300, 400, 2, Second.YELLOW),
        p(43, "290 HS BWR SSD V2", 384, 168, 2, Second.RED),
        p(44, "583 HS BWR UC", 480, 648, 2, Second.RED),
        p(45, "213 HS BWR SSD V2", 296, 144, 2, Second.RED),
        p(46, "290 HS BWR SSD V3", 296, 128, 2, Second.RED),
        p(47, "213 HS BWR UC V2", 296, 144, 2, Second.RED),
        p(48, "350 HS BWR SSD V2", 384, 184, 2, Second.RED),
        p(49, "420 HS BWR UC V2", 300, 400, 2, Second.RED),
    )

    private val index: Map<Int, Panel> = all.associateBy { it.type }

    fun byType(type: Int): Panel? = index[type]

    /**
     * The second colour a tag has, from its type where that is known and from
     * the colour count where it is not.
     *
     * A hand-written (dynamic) configuration carries no type, and so no name
     * to read the colours off. Red is the fallback for a two-colour panel
     * because every two-colour type in the table above except the few BWY ones
     * is red - a guess, but the likely one, and the user can still override it.
     */
    fun secondFor(type: Int, colors: Int): Second {
        byType(type)?.let { return it.second }
        return when {
            colors >= 3 -> Second.BOTH
            colors == 2 -> Second.RED
            else -> Second.NONE
        }
    }

    fun describe(type: Int, colors: Int): String {
        byType(type)?.let { return it.label }
        val c = secondFor(type, colors)
        val words = Panel(type, "", 0, 0, colors, c).colourWords
        return if (type == DYNAMIC) "hand-written configuration, $words"
        else "type $type (not in the table), $words"
    }
}
