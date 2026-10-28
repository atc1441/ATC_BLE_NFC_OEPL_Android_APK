package io.github.atc1441.oeplnfc

/**
 * Every command the firmware's dispatcher accepts, described well enough that
 * one generic screen can drive all of them - and described in the terms the
 * command is actually about, not in the bytes it happens to travel as.
 *
 * That is the whole point of the extra description here. A command like the LED
 * pattern is twelve bytes with two values packed into most of them; typing
 * "0x31 01 0x23 ..." into twelve boxes is not using the command, it is
 * transcribing it. So a parameter says what range it has, what unit it is in
 * and how to show it, and the screen turns that into a slider, a switch, a pin
 * list or a colour picker.
 *
 * The layouts come from src/app_bleCmdHandle.c, src/debug_cmd.h and src/led.c.
 * Watch the endianness: it is genuinely mixed in the firmware - SET_TYPE,
 * BLE_PERIOD, SET_SPEED and READ_DEBUG take a big endian u16, SET_UNIXTIME a
 * little endian u32, GET_NDEF a little endian u16, and the whole debug channel
 * is little endian. That is why each parameter says which it is instead of the
 * app assuming one.
 */

enum class ParamType { U8, U16BE, U16LE, U32LE, HEX, MAC8, ASCII }

/** How one parameter is presented. [Param.widget] resolves it. */
enum class Widget { TEXT, CHOICE, SLIDER, SWITCH, COLOR, NOW }

/** Commands whose shape deserves a form of its own rather than a byte list. */
enum class Editor { GENERIC, LED }

/**
 * Commands that only make sense as a sequence: the firmware answers them in
 * windows and expects to be asked again until it says it is done.
 */
enum class Loop { NONE, LUT, NDEF }

class Param(
    val label: String,
    val type: ParamType,
    val default: String = "",
    val hint: String = "",
    /** When set the field becomes a dropdown of (label, value). */
    val options: List<Pair<String, Int>>? = null,
    /**
     * No sensible default exists and an empty value is an error. Marked rather
     * than given a placeholder: a stand-in MAC or panel configuration that
     * someone sends by accident is worse than an empty field that refuses.
     */
    val required: Boolean = false,
    /** Inclusive slider range, when the value is a quantity and not a choice. */
    val min: Int = 0,
    val max: Int = 0,
    /** Shown after the value, e.g. "ms". */
    val unit: String = "",
    /** HEX only: the exact number of bytes the firmware insists on. */
    val bytes: Int = 0,
    /** Overrides the resolved widget where the type cannot imply it. */
    val ui: Widget? = null,
    /** Turns a raw value into something worth reading, under the slider. */
    val explain: ((Int) -> String)? = null
) {
    val widget: Widget
        get() = ui ?: when {
            options != null -> Widget.CHOICE
            max > min -> Widget.SLIDER
            else -> Widget.TEXT
        }

    /** The slider label: the value, its unit, and whatever [explain] adds. */
    fun show(v: Int): String {
        val head = if (unit.isEmpty()) "$v" else "$v $unit"
        val tail = explain?.invoke(v) ?: ""
        return if (tail.isEmpty()) head else "$head   $tail"
    }
}

class CommandSpec(
    val id: Int,
    val name: String,
    val help: String,
    val params: List<Param> = emptyList(),
    /** Some commands answer with nothing at all; do not wait for a reply. */
    val replies: Boolean = true,
    /** The tag restarts, so the session dies and no reply can come back. */
    val reboots: Boolean = false,
    /** Debug sub-command, prefixed to the payload; id is then BLE_CMD_DEBUG. */
    val sub: Int? = null,
    val decode: ((ByteArray) -> String)? = null,
    /** Which part of the tag this touches, for the picker. */
    val category: String = "Tag",
    /** A form of its own, for commands a byte list does not describe. */
    val editor: Editor = Editor.GENERIC,
    /** Keep asking until the tag says it is done. */
    val loop: Loop = Loop.NONE,
    /** Shown apart from the help, because it can cost you the tag. */
    val danger: String = ""
) {
    val fullName: String
        get() = if (sub != null) "%s  (0x%02X)".format(name, sub) else "%s  (%d)".format(name, id)

    /** What the picker lists: the category up front, so it reads as a group. */
    val pickerName: String get() = "$category  ·  $fullName"
}

object Commands {

    // --- encoding ---------------------------------------------------------

    private fun num(s: String): Long {
        val t = s.trim()
        if (t.isEmpty()) throw IllegalArgumentException("empty value")
        return if (t.startsWith("0x") || t.startsWith("0X")) t.substring(2).toLong(16)
        else t.toLong()
    }

    fun hexBytes(s: String): ByteArray {
        val clean = s.filter { !it.isWhitespace() && it != ':' && it != ',' }
        if (clean.length % 2 != 0)
            throw IllegalArgumentException("hex needs an even number of digits")
        return ByteArray(clean.length / 2) {
            clean.substring(it * 2, it * 2 + 2).toInt(16).toByte()
        }
    }

    /** Build the payload for one command from the strings the user typed. */
    fun encode(params: List<Param>, values: List<String>): ByteArray {
        val out = ArrayList<Byte>()
        for ((i, p) in params.withIndex()) {
            val raw = values.getOrElse(i) { p.default }
            if (p.required && raw.isBlank())
                throw IllegalArgumentException("'${p.label}' has to be filled in")
            when (p.type) {
                ParamType.U8 -> out.add(num(raw).toByte())
                ParamType.U16BE -> {
                    val v = num(raw)
                    out.add((v shr 8).toByte()); out.add(v.toByte())
                }
                ParamType.U16LE -> {
                    val v = num(raw)
                    out.add(v.toByte()); out.add((v shr 8).toByte())
                }
                ParamType.U32LE -> {
                    val v = num(raw)
                    for (k in 0 until 4) out.add((v shr (8 * k)).toByte())
                }
                ParamType.HEX -> {
                    val b = hexBytes(raw)
                    // Caught here rather than by the tag, which answers a
                    // wrong length with a bare error and no hint which field.
                    if (p.bytes > 0 && b.size != p.bytes)
                        throw IllegalArgumentException(
                            "'${p.label}' must be exactly ${p.bytes} bytes, this is ${b.size}"
                        )
                    b.forEach { out.add(it) }
                }
                ParamType.ASCII -> {
                    // Sent as typed. The one user of this is the PIN, where
                    // what the tag stores has to be exactly what was keyed in
                    // on the other side - so no trimming and no case folding.
                    for (c in raw) {
                        if (c.code > 0xFF)
                            throw IllegalArgumentException(
                                "'${p.label}' must be plain ASCII")
                        out.add(c.code.toByte())
                    }
                    if (p.bytes > 0 && raw.length > p.bytes)
                        throw IllegalArgumentException(
                            "'${p.label}' is ${raw.length} characters, at most ${p.bytes}")
                }
                ParamType.MAC8 -> {
                    val b = hexBytes(raw)
                    if (b.size != 8)
                        throw IllegalArgumentException("MAC must be exactly 8 bytes")
                    // Shown most significant first, as the tag prints it, and
                    // sent in that same order - set_custom_mac() copies it raw.
                    b.forEach { out.add(it) }
                }
            }
        }
        return out.toByteArray()
    }

    // --- shared option lists ----------------------------------------------

    /**
     * BLE_CMD_LED goes to set_led_color(), which is a switch over three cases
     * and a default that turns everything off - so these four are the whole
     * command, and 4..255 are all "off" rather than further colours.
     */
    val solidColours = listOf(
        "off" to 0, "red" to 1, "green" to 2, "blue" to 3
    )

    private val onOff = listOf("off" to 0, "on" to 1)

    /** The 0xA5 guard the destructive debug sub-commands require. */
    private val confirm = listOf("no" to 0, "yes, send 0xA5" to 0xA5)

    private val widths = listOf("8 bit" to 1, "16 bit" to 2, "32 bit" to 4)

    /**
     * pin = group << 8 | bitmask, as GPIO_PinTypeDef is defined in
     * platform/chip_8258/gpio.h: GPIO_GROUPA is 0x000 and GPIO_PA0 is
     * GROUPA | BIT(0). Typing 0x0108 for PB3 is a transcription step with
     * nothing to gain, so the whole port map is a list.
     *
     * Which of them this build is already using is a question for "GPIO pin
     * roles" - it differs per tag, so nothing is labelled here that would be
     * a guess on the next one.
     */
    val gpioPins: List<Pair<String, Int>> = buildList {
        for (g in 0 until 5) for (bit in 0 until 8)
            add("P%c%d".format('A' + g, bit) to ((g shl 8) or (1 shl bit)))
    }

    /** The ten pins the SAR ADC can reach, from ADC_cstm_GPIO_tab in battery.c. */
    private val adcPins: List<Pair<String, Int>> =
        listOf("leave the configured pin" to 0) +
            gpioPins.filter { (name, _) ->
                name in listOf(
                    "PB0", "PB1", "PB2", "PB3", "PB4", "PB5", "PB6", "PB7", "PC4", "PC5"
                )
            }

    /** DeviceType from src/settings.h, in enum order. */
    val deviceTypes: List<Pair<String, Int>> = listOf(
        "0 BARE_SOC" to 0,
        "1 HS BWY 350 UC" to 1,
        "2 HS BWY 350 UC inverted" to 2,
        "3 HS BWY 350 SSD" to 3,
        "4 HS BW 350 UC" to 4,
        "5 HS BWY 200 SSD" to 5,
        "6 HS BWY 750 UC" to 6,
        "7 HS BWR 350 UC" to 7,
        "8 HS BWR 350 SSD" to 8,
        "9 HS BWR 266 SSD" to 9,
        "10 HS BW 213 UC" to 10,
        "11 GICI BWR 213 SSD" to 11,
        "12 GICI BWR 290 SSD" to 12,
        "13 GICI BW 213 ST" to 13,
        "14 TI BWR 970" to 14,
        "15 TI BWR 1200" to 15,
        "16 HS BWR 213 SSD" to 16,
        "17 HS BWRY 350 JD" to 17,
        "18 HS BWR 154 H SSD" to 18,
        "19 HS BWR 213 UC" to 19,
        "20 HS BWR 420 SSD" to 20,
        "21 HS BWR 420 UC" to 21,
        "22 GICI BWR 420 SSD" to 22,
        "23 TI BWR 1200 V2" to 23,
        "24 HS BWR 290 SSD" to 24,
        "25 HS BW 213 SSD" to 25,
        "26 TI BW 581" to 26,
        "27 TI BWR 581" to 27,
        "28 GICI BWR 213 UC" to 28,
        "29 GICI BW 213 SSD" to 29,
        "30 GICI BW 213 UC" to 30,
        "31 HS BWY 583 UC" to 31,
        "32 HS BW 350 SSD" to 32,
        "33 HS BWR 266 SSD offset" to 33,
        "34 TI BWR 581 UC" to 34,
        "35 HS BWY 346 UC" to 35,
        "36 WO BWRY 290 JD" to 36,
        "37 HS BWRY 750 JD" to 37,
        "38 HS BWRY 200 JD" to 38,
        "39 WO BWRY 290 JD V2" to 39,
        "40 HS BWRY 260 JD" to 40,
        "41 WO BWRY 260 JD V2" to 41,
        "42 HS BWY 420 SSD" to 42,
        "43 HS BWR 290 SSD V2" to 43,
        "44 HS BWR 583 UC" to 44,
        "45 HS BWR 213 SSD V2" to 45,
        "46 HS BWR 290 SSD V3" to 46,
        "47 HS BWR 213 UC V2" to 47,
        "48 HS BWR 350 SSD V2" to 48,
        "49 HS BWR 420 UC V2" to 49,
        "65535 dynamic (from HW config)" to 65535
    )

    // --- helpers used by the decoders -------------------------------------

    fun hexDump(base: Int, b: ByteArray): String {
        val sb = StringBuilder()
        var o = 0
        while (o < b.size) {
            sb.append("%06X  ".format(base + o))
            for (i in 0 until 16) {
                sb.append(if (o + i < b.size) "%02X ".format(b[o + i]) else "   ")
                if (i == 7) sb.append(' ')
            }
            sb.append('|')
            for (i in 0 until 16) {
                if (o + i >= b.size) break
                val c = b[o + i].toInt() and 0xFF
                sb.append(if (c in 32..126) c.toChar() else '.')
            }
            sb.append("|\n")
            o += 16
        }
        return if (sb.isEmpty()) "(no data)" else sb.toString()
    }

    private fun u16le(b: ByteArray, i: Int) =
        (b[i].toInt() and 0xFF) or ((b[i + 1].toInt() and 0xFF) shl 8)

    private fun u16be(b: ByteArray, i: Int) =
        ((b[i].toInt() and 0xFF) shl 8) or (b[i + 1].toInt() and 0xFF)

    private fun u32le(b: ByteArray, i: Int): Long =
        ((b[i].toInt() and 0xFF).toLong()) or
            ((b[i + 1].toInt() and 0xFF).toLong() shl 8) or
            ((b[i + 2].toInt() and 0xFF).toLong() shl 16) or
            ((b[i + 3].toInt() and 0xFF).toLong() shl 24)

    private fun i8(b: ByteArray, i: Int) = b[i].toInt().let { if (it > 127) it - 256 else it }

    /** "PB3" for 0x0108, and something honest for anything else. */
    fun pinName(v: Int): String {
        val group = v shr 8
        val mask = v and 0xFF
        if (group > 4 || mask == 0) return "0x%04X".format(v)
        val single = (0 until 8).firstOrNull { mask == (1 shl it) }
            ?: return "P%c mask 0x%02X".format('A' + group, mask)
        return "P%c%d".format('A' + group, single)
    }

    private fun wakeupName(v: Int) = when (v) {
        0 -> "timed"
        2 -> "GPIO"
        3 -> "NFC field"
        0xFC -> "first boot"
        0xFD -> "network scan"
        0xFE -> "watchdog reset"
        else -> "0x%02X".format(v)
    }

    private fun capabilityNames(v: Int): String {
        val bits = listOf(
            0x01 to "LED", 0x02 to "compression", 0x04 to "custom LUTs",
            0x08 to "alt LUT size", 0x10 to "external power",
            0x20 to "wake button", 0x40 to "NFC", 0x80 to "NFC wake",
            0x100 to "BLE"
        ).filter { v and it.first != 0 }.map { it.second }
        return if (bits.isEmpty()) "none" else bits.joinToString(", ")
    }

    private fun duration(ms: Long): String {
        val s = ms / 1000
        return "%d:%02d:%02d".format(s / 3600, (s / 60) % 60, s % 60)
    }

    // --- reply decoders ---------------------------------------------------

    private fun decodeInfos(b: ByteArray): String {
        if (b.size < TagInfo.MIN_SIZE) return "settings reply too short (${b.size})\n" + hexDump(0, b)
        val t = TagInfo(b)
        return buildString {
            appendLine("MAC             ${t.mac}")
            appendLine("BLE name        ${t.name}")
            appendLine("oepl hw type    0x%04X".format(t.hwType))
            appendLine("screen type     ${t.screenType}")
            appendLine("controller      ${t.screenFunctions}")
            appendLine("panel           ${t.width} x ${t.height}, ${t.colors} colours")
            appendLine("gui rotation    ${t.guiRotation}")
            appendLine("settings ver    ${t.version}")
            appendLine("oepl enabled    ${t.oeplEnabled}")
            append(hexDump(0, b))
        }
    }

    /** [has_pin][locked][nfc_cmds_enabled][idle_relock_s] */
    private fun decodeProtection(b: ByteArray): String {
        if (b.size < 4) return hexDump(0, b)
        return "PIN            ${if (b[0].toInt() != 0) "set" else "not set"}\n" +
            "right now      ${if (b[1].toInt() != 0) "LOCKED" else "open"}\n" +
            "NFC commands   ${if (b[2].toInt() != 0) "allowed" else "DISABLED (persistent)"}\n" +
            "relocks after  ${b[3].toInt() and 0xFF} s of silence"
    }

    private fun decodeDebugStatus(b: ByteArray): String {
        if (b.size < 6) return hexDump(0, b)
        return "battery        ${u16be(b, 0)} mV\n" +
            "adc raw        ${u16be(b, 2)}\n" +
            "adc temp       ${i8(b, 4)} C\n" +
            "panel temp     ${i8(b, 5)} C"
    }

    /**
     * The 93-byte dynamic configuration: 2 bytes of screen type, then
     * default_settings_struct (42), then the screen, led, nfc and flash pinout
     * structs (26 / 7 / 8 / 8). Offsets verified against the target build.
     */
    private fun decodeDynamic(b: ByteArray): String {
        if (b.size < 93) return "dynamic config is ${b.size} bytes, expected 93\n" + hexDump(0, b)
        return buildString {
            appendLine("screen type        ${u16le(b, 0)}")
            appendLine("oepl hw type       0x%04X".format(u16le(b, 2)))
            appendLine("controller family  ${u16le(b, 4)}")
            appendLine("w/h inversed ble   ${b[6].toInt() and 0xFF}")
            appendLine("w/h inversed       ${u16le(b, 7)}")
            appendLine("screen_h           ${u16le(b, 9)}")
            appendLine("screen_w           ${u16le(b, 11)}")
            appendLine("h offset           ${u16le(b, 13)}")
            appendLine("w offset           ${u16le(b, 15)}")
            appendLine("colours            ${u16le(b, 17)}")
            appendLine("black invert       ${u16le(b, 19)}")
            appendLine("second invert      ${u16le(b, 21)}")
            appendLine("ADC pin            ${pinName(u16le(b, 39))}")
            appendLine("UART pin           ${pinName(u16le(b, 41))}")
            appendLine("gui rotation       ${b[43].toInt() and 0xFF}")
            appendLine()
            appendLine("screen pinout  @44, led @70, nfc @77, flash @85")
            appendLine("Copy the whole dump into 'Test HW config' to try a change")
            appendLine("without saving it.")
            append(hexDump(0, b))
        }
    }

    private fun decodeNdef(b: ByteArray): String {
        if (b.size < 5) return hexDump(0, b)
        val total = u16le(b, 1)
        val off = u16le(b, 3)
        val msg = b.copyOfRange(5, b.size)
        val src = when (b[0].toInt() and 0xFF) {
            0x00 -> "built-in default"
            0xA0 -> "stored TLV (as the AP sends)"
            0xA1 -> "stored URL"
            0xA2 -> "stored NDEF message"
            else -> "0x%02X".format(b[0])
        }
        return "source $src, %d bytes total, window at %d\n%s\n\n%s".format(
            total, off, NdefCodec.describe(msg), hexDump(off, msg)
        )
    }

    private fun decodeLut(b: ByteArray) = hexDump(0, b)

    // --- the tag command set ----------------------------------------------

    val tag: List<CommandSpec> = listOf(
        CommandSpec(
            Cmd.GET_INFOS, "Read settings",
            "The whole settings_struct exactly as the tag holds it in RAM - MAC, " +
                "BLE name, panel geometry and colours, rotation, whether OEPL is on. " +
                "This is the one command worth sending first: everything else on " +
                "this screen is easier to aim once you know what the tag thinks it is.",
            decode = ::decodeInfos, category = "Info"
        ),
        CommandSpec(
            Cmd.READ_DEBUG, "Read sensors",
            "Battery voltage in mV, the raw ADC reading, the temperature the ADC " +
                "derives and the one the panel controller reports. Type 0 is the " +
                "only one the firmware defines; anything else answers nothing at all.",
            listOf(
                Param(
                    "info type", ParamType.U16BE, "0",
                    options = listOf("0 battery and temperature" to 0)
                )
            ),
            decode = ::decodeDebugStatus, category = "Info"
        ),

        // --- LED -----------------------------------------------------------
        CommandSpec(
            Cmd.LED, "LED colour",
            "Drives the three LED pins and leaves them that way. There is no " +
                "timer behind it: the LED stays lit until something else changes " +
                "it, so send 'off' when you are done, or use the LED pattern, " +
                "which ends by itself.\n\n" +
                "set_led_color() is a switch over three cases, so red, green and " +
                "blue are the whole command - every other value lands in the " +
                "default and turns the LED off.",
            listOf(Param("colour", ParamType.U8, "1", options = solidColours)),
            replies = false, category = "LED"
        ),
        CommandSpec(
            Cmd.SET_LED, "LED pattern",
            "Three groups of flashes, each with its own colour, a count and a " +
                "spacing, and the whole sequence repeated as often as you like. " +
                "The tag runs it by itself from the main loop, so it keeps " +
                "flashing after the phone is gone.\n\n" +
                "The colour is mixed by time slicing in flashled(): red and green " +
                "have eight steps, blue has four. Brightness is really the length " +
                "of one flash - the mixing loop runs that many times, about 0.7 ms " +
                "each - so a bright flash is also a longer one.",
            editor = Editor.LED, category = "LED"
        ),

        // --- clock ---------------------------------------------------------
        CommandSpec(
            Cmd.SET_UNIXTIME, "Set clock",
            "Sets the tag's clock and puts the clock screen on the panel. The " +
                "stamp is local time, not UTC - the firmware has no timezone - and " +
                "the clock mode byte is read unconditionally, so all five bytes " +
                "have to be there.\n\n" +
                "The panel then redraws once a minute, with a full refresh at the " +
                "top of the hour. Sending an image switches the clock back off.",
            listOf(
                Param(
                    "local unix time", ParamType.U32LE, "0", hint = "seconds",
                    ui = Widget.NOW
                ),
                Param(
                    "clock mode", ParamType.U8, "1",
                    options = listOf(
                        "1 info screen with date, time and battery" to 1,
                        "2 big seven segment clock" to 2
                    ),
                    hint = "0 is treated as 1"
                )
            ),
            category = "Clock"
        ),
        CommandSpec(
            Cmd.DISABLE_UNIXTIME, "Clock off",
            "Stops the clock screen. The panel keeps whatever is on it until " +
                "something redraws - this stops the minute-by-minute refresh, it " +
                "does not clear the glass.",
            category = "Clock"
        ),

        // --- OEPL and BLE --------------------------------------------------
        CommandSpec(
            Cmd.OEPL_ENABLE, "OEPL on",
            "Re-enables the OEPL radio check-in, so the tag goes looking for an " +
                "access point again. Takes effect on the next wake.",
            category = "OEPL"
        ),
        CommandSpec(
            Cmd.OEPL_DISABLE, "OEPL off",
            "Stops the OEPL radio check-in. The tag stops scanning for an access " +
                "point, which is most of its idle power - worth doing on a tag you " +
                "only ever drive over NFC or BLE.",
            category = "OEPL"
        ),
        CommandSpec(
            Cmd.OEPL_BLE_PERIOD, "OEPL advertising period",
            "How long the tag sleeps between advertising bursts. Longer is less " +
                "power and a longer wait before a scanner finds it.\n\n" +
                "Big endian, and the firmware silently substitutes 3200 ms for " +
                "anything outside 100..10000 rather than refusing. It is also not " +
                "written to flash here, so a reboot brings the old value back.",
            listOf(
                Param(
                    "interval", ParamType.U16BE, "3200", unit = "ms",
                    min = 100, max = 10000,
                    explain = { v -> "every %.1f s".format(v / 1000.0) }
                )
            ),
            category = "BLE"
        ),
        CommandSpec(
            Cmd.SET_SPEED, "BLE connection speed",
            "Asks the phone for a connection interval, in units of 1.25 ms. " +
                "Shorter means more round trips a second, which is what an upload " +
                "lives on, and more power.\n\n" +
                "A no-op over NFC: ble_set_connection_speed() returns straight away " +
                "when there is no link, so this is here for the BLE path.",
            listOf(
                Param(
                    "interval", ParamType.U16BE, "100", unit = "units",
                    min = 6, max = 3200,
                    explain = { v -> "= %.1f ms".format(v * 1.25) }
                )
            ),
            category = "BLE"
        ),
        CommandSpec(
            Cmd.BLE_FAST, "BLE fast",
            "Shorthand for a connection interval of 8 units, 10 ms - what the " +
                "firmware itself asks for during a block transfer.",
            category = "BLE"
        ),
        CommandSpec(
            Cmd.BLE_SLOW, "BLE slow",
            "Shorthand for a connection interval of 100 units, 125 ms - the idle " +
                "setting the firmware returns to after a transfer.",
            category = "BLE"
        ),
        CommandSpec(
            Cmd.BLE_DISABLE, "BLE off",
            "Stops BLE advertising for this boot. Nothing is written to flash, so " +
                "a reboot brings it back - and NFC keeps working, which is how you " +
                "get back in.",
            reboots = true, category = "BLE"
        ),
        CommandSpec(
            Cmd.DEEPSLEEP, "Deep sleep",
            "Turns OEPL off, stops BLE and draws a sleep screen. The tag then only " +
                "wakes on an NFC field, which makes this the right way to put one " +
                "in a drawer - and NFC is also the only way to wake it again.",
            reboots = true, category = "Power", danger = "Only NFC can wake it after this."
        ),
        CommandSpec(
            Cmd.REBOOT, "Reboot",
            "Restarts the tag. Settings already written to flash survive; anything " +
                "only set in RAM - the advertising period, a clock mode - does not.",
            reboots = true, category = "Power"
        ),

        // --- panel configuration -------------------------------------------
        CommandSpec(
            Cmd.SET_TYPE, "Set device type",
            "Picks one of the built-in panel presets, writes it to flash and " +
                "reboots into it. Big endian. 65535 means 'use the dynamic " +
                "configuration' instead of a preset.\n\n" +
                "The wrong preset leaves a panel that does not draw, or draws " +
                "shifted or inverted - recoverable, because this command still " +
                "works over NFC on a tag whose panel shows nothing.",
            listOf(Param("type", ParamType.U16BE, "0", options = deviceTypes)),
            reboots = true, category = "Panel"
        ),
        CommandSpec(
            Cmd.SET_DYNAMIC_TYPE_READ, "Read HW config",
            "The 93-byte dynamic panel configuration: geometry, offsets, colours, " +
                "inversion, rotation and every pin. Copy the hex out of the reply, " +
                "change what you want and send it back with 'Test HW config'.",
            decode = ::decodeDynamic, category = "Panel"
        ),
        CommandSpec(
            Cmd.SET_DYNAMIC_TYPE_TEST, "Test HW config",
            "Applies a configuration and refreshes the panel without saving it. " +
                "The panel says 'Testing' when the blob was accepted and 'Wrong " +
                "CFG' when it was not. Nothing is written to flash, so a reboot " +
                "undoes it - try everything here first.",
            listOf(
                Param(
                    "93 bytes", ParamType.HEX, "", hint = "paste from Read HW config",
                    required = true, bytes = 93
                )
            ),
            category = "Panel"
        ),
        CommandSpec(
            Cmd.SET_DYNAMIC_TYPE_SAVE, "Save HW config",
            "Applies a configuration, writes it to flash and reboots into it. " +
                "Same 93 bytes as the test above, which is the one to get right " +
                "first.",
            listOf(
                Param(
                    "93 bytes", ParamType.HEX, "", hint = "paste from Read HW config",
                    required = true, bytes = 93
                )
            ),
            reboots = true, category = "Panel",
            danger = "Written to flash. Test it first."
        ),
        CommandSpec(
            Cmd.READ_LUT, "Read the panel LUT",
            "Reads the panel's waveform table out of the controller and hands it " +
                "back 230 bytes at a time. This screen walks the whole table for " +
                "you and dumps it; the panel is left asleep afterwards.",
            loop = Loop.LUT, category = "Panel"
        ),
        CommandSpec(
            Cmd.READ_LUT_NEXT, "LUT: one more chunk",
            "The single step behind the command above: 230 bytes from where the " +
                "last one stopped, until the tag answers 'done' instead.",
            decode = ::decodeLut, category = "Panel"
        ),

        // --- identity ------------------------------------------------------
        CommandSpec(
            Cmd.SET_CUSTOM_MAC, "Set custom MAC",
            "Eight bytes, in the order the tag prints its MAC. Saved to flash, " +
                "then the tag reboots with the new address - so a scanner will see " +
                "it as a different tag afterwards.",
            listOf(Param("MAC", ParamType.MAC8, "", hint = "8 bytes hex", required = true)),
            reboots = true, category = "Identity"
        ),
        CommandSpec(
            Cmd.RESET_TO_DEFAULT, "Reset settings",
            "Restores the defaults for the current screen type and reboots. The " +
                "0x1234 guard is checked byte for byte, which is the only thing " +
                "between a stray command and your configuration.",
            listOf(Param("confirm", ParamType.U16BE, "0x1234")),
            reboots = true, category = "Identity",
            danger = "Throws away the stored configuration."
        ),

        // --- NDEF ----------------------------------------------------------
        CommandSpec(
            Cmd.SET_NDEF, "Set NDEF",
            "Replaces what a phone reads on a plain tap, and keeps it across " +
                "reboots. The first byte says what follows:\n" +
                "  0xA0  a Type-2 TLV container, the form the OEPL AP sends\n" +
                "  0xA1  a plain URL as text, which the tag wraps itself\n" +
                "  0xA2  a bare NDEF message\n\n" +
                "Up to 512 bytes are stored; longer contents go as an OEPL block " +
                "transfer instead. The main screen has fields for a URL or a text, " +
                "which is easier than hex.",
            listOf(
                Param(
                    "source", ParamType.U8, "0xA1",
                    options = listOf("0xA0 TLV" to 0xA0, "0xA1 URL" to 0xA1, "0xA2 message" to 0xA2)
                ),
                Param("content", ParamType.HEX, "", hint = "hex bytes", required = true)
            ),
            category = "NDEF"
        ),
        CommandSpec(
            Cmd.GET_NDEF, "Read NDEF",
            "What the tag currently serves, where it came from and how long it is. " +
                "The reply is windowed at 200 bytes, so this screen walks it from " +
                "the offset you give until it has the whole thing.",
            listOf(
                Param(
                    "start at offset", ParamType.U16LE, "0", unit = "bytes",
                    min = 0, max = 512
                )
            ),
            decode = ::decodeNdef, loop = Loop.NDEF, category = "NDEF"
        ),
        CommandSpec(
            Cmd.CLEAR_NDEF, "Clear NDEF",
            "Drops the stored content and goes back to the firmware's built-in " +
                "default, which is the configuration URL with this tag's MAC in it.",
            category = "NDEF"
        ),

        // --- protection ----------------------------------------------------
        CommandSpec(
            Cmd.GET_PROTECTION, "Protection state",
            "Whether a PIN is set, whether the tag is locked right now, whether " +
                "NFC commands are allowed, and how many seconds of silence it " +
                "takes to lock again. This is the one command a locked tag still " +
                "answers, so that an app can tell a locked tag from a broken one.",
            decode = ::decodeProtection, category = "Protection"
        ),
        CommandSpec(
            Cmd.NFC_CMDS_DISABLE, "Disable NFC commands",
            "Switches the NFC command channel off for good - it is stored with " +
                "the NDEF configuration and survives a reboot.\n\n" +
                "What still works: the NDEF a phone taps for, anticollision, and " +
                "the tap as a wakeup. What stops: telling the tag anything " +
                "through the field, including turning this back on. Only BLE or " +
                "the AP can do that.",
            category = "Protection",
            danger = "NFC cannot switch itself back on. You will need BLE or the AP."
        ),
        CommandSpec(
            Cmd.NFC_CMDS_ENABLE, "Enable NFC commands",
            "Allows commands over NFC again. Persistent, like the disable.",
            category = "Protection"
        ),
        // There were two more here, 'Set or clear the PIN' and
        // 'Unlock'. They are not offered any more, because this app
        // talks NFC and the tag refuses both of them over NFC outright:
        // the PIN guards BLE and the access point, and a passer-by who
        // could set one by holding a phone to the tag would be setting
        // the lock on somebody else's BLE. Use the web tool over BLE.
        //
        // What protects a tag from a passing phone is the switch above:
        // 'Disable NFC commands', which is persistent and can only be
        // undone over BLE or by the AP.
    )

    // --- the debug channel ------------------------------------------------

    private fun dbgReply(b: ByteArray): String {
        if (b.size < 2) return "truncated debug reply"
        val status = b[1].toInt() and 0xFF
        val names = mapOf(
            0 to "ok", 1 to "request too short", 2 to "unknown sub-command",
            3 to "argument out of range", 4 to "address or length not aligned",
            5 to "operation failed", 6 to "missing the confirmation byte",
            7 to "hardware not fitted"
        )
        val head = "sub 0x%02X  status %d (%s)".format(b[0], status, names[status] ?: "?")
        return if (b.size <= 2) head else head + "\n" + hexDump(0, b.copyOfRange(2, b.size))
    }

    /**
     * The data of a successful debug reply, or null when it did not succeed or
     * is shorter than the decoder needs. Every decoder starts here and falls
     * back to [dbgReply], because a short or failed reply has to read as what
     * it is rather than take the screen down.
     */
    private fun dbgData(b: ByteArray, min: Int): ByteArray? {
        if (b.size < 2) return null
        if ((b[1].toInt() and 0xFF) != 0) return null
        val d = b.copyOfRange(2, b.size)
        return if (d.size < min) null else d
    }

    private fun decodeSysinfo(b: ByteArray): String {
        val d = dbgData(b, 27) ?: return dbgReply(b)
        val caps = u16le(d, 6)
        return buildString {
            appendLine("firmware        v${u16le(d, 0)}")
            appendLine("oepl hw type    0x%04X".format(u16le(d, 2)))
            appendLine("screen type     ${u16le(d, 4)}")
            appendLine("capabilities    0x%04X  (%s)".format(caps, capabilityNames(caps)))
            appendLine("system ticks    ${u32le(d, 8)}")
            appendLine("uptime          ${duration(u32le(d, 12))}")
            appendLine("woke from       ${wakeupName(d[16].toInt() and 0xFF)}")
            appendLine("panel state     ${epdStateName(d[17].toInt() and 0xFF)}")
            appendLine("nfc session     ${if (d[18].toInt() != 0) "active" else "idle"}")
            appendLine("ndef source     0x%02X".format(d[19]))
            appendLine("oepl enabled    ${d[20].toInt() != 0}")
            appendLine("ble enabled     ${d[21].toInt() != 0}")
            appendLine("binary size     ${u32le(d, 22)} bytes")
            val n = (d[26].toInt() and 0xFF).coerceAtMost(d.size - 27)
            if (n > 0) appendLine("built           ${String(d, 27, n, Charsets.US_ASCII)}")
        }
    }

    private fun epdStateName(v: Int) = when (v) {
        0 -> "0 idle"
        1 -> "1 streaming data to the panel"
        2 -> "2 refreshing"
        else -> "$v"
    }

    private fun decodeSensors(b: ByteArray): String {
        val d = dbgData(b, 15) ?: return dbgReply(b)
        return buildString {
            appendLine("battery         ${u16le(d, 0)} mV")
            appendLine("adc raw         ${u16le(d, 2)}")
            appendLine("adc temp        ${i8(d, 4)} C")
            appendLine("panel temp      ${i8(d, 5)} C")
            appendLine("battery level   ${d[6].toInt() and 0xFF} %")
            appendLine("clock           ${u32le(d, 7)} (local unix)")
            appendLine("uptime          ${duration(u32le(d, 11))}")
            appendLine("woke from       ${wakeupName(d[15].toInt() and 0xFF)}")
        }
    }

    private fun decodeRamInfo(b: ByteArray): String {
        val d = dbgData(b, 44) ?: return dbgReply(b)
        val names = listOf(
            "start_data", "end_data", "start_bss", "end_bss",
            "start_custom_data", "end_custom_data",
            "start_custom_bss", "end_custom_bss", "ram_end",
            "code_size", "ramcode_size"
        )
        return buildString {
            for ((i, n) in names.withIndex())
                appendLine("%-18s 0x%06X".format(n, u32le(d, i * 4)))
            // The number that decides whether the next feature fits: retention
            // RAM ends at 0x848000 on this part.
            val endBss = u32le(d, 3 * 4)
            appendLine()
            appendLine("retention limit    0x848000")
            appendLine("free below it      ${0x848000L - endBss} bytes")
        }
    }

    private fun decodeFlashInfo(b: ByteArray): String {
        val d = dbgData(b, 22) ?: return dbgReply(b)
        return buildString {
            appendLine("JEDEC id        0x%06X".format(u32le(d, 0)))
            appendLine("unique id       " + (0 until 16).joinToString("") { "%02X".format(d[4 + it]) })
            appendLine("status          0x%04X".format(u16le(d, 20)))
        }
    }

    private fun decodeFlashMap(b: ByteArray): String {
        val d = dbgData(b, 40) ?: return dbgReply(b)
        val names = listOf(
            "firmware start", "write protected up to", "image / OTA area",
            "image / OTA length", "settings sector", "NDEF sector",
            "BLE NV", "factory MAC", "factory calibration", "bytes per image slot"
        )
        return buildString {
            for ((i, n) in names.withIndex()) {
                if ((i + 1) * 4 > d.size) break
                appendLine("%-22s 0x%06X".format(n, u32le(d, i * 4)))
            }
        }
    }

    /** The six registers of one port, at 0x580 + group * 8. */
    private fun gpioRegs(d: ByteArray, at: Int): String {
        val n = listOf("in", "ie", "oen", "out", "pol", "ds")
        return (0 until 6).joinToString("  ") { "%s 0x%02X".format(n[it], d[at + it]) }
    }

    private fun decodeGpio(b: ByteArray): String {
        val d = dbgData(b, 7) ?: return dbgReply(b)
        return "level ${d[0].toInt() and 0xFF}\nport   ${gpioRegs(d, 1)}"
    }

    private fun decodeGpioSnapshot(b: ByteArray): String {
        val d = dbgData(b, 30) ?: return dbgReply(b)
        return (0 until 5).joinToString("\n") {
            "P%c  %s".format('A' + it, gpioRegs(d, it * 6))
        }
    }

    private fun decodeGpioPinout(b: ByteArray): String {
        val d = dbgData(b, 5) ?: return dbgReply(b)
        val roles = mapOf(
            0x01 to "ADC", 0x02 to "UART",
            0x10 to "EPD RESET", 0x11 to "EPD DC", 0x12 to "EPD BUSY",
            0x13 to "EPD BUSYs", 0x14 to "EPD CS", 0x15 to "EPD CSs",
            0x16 to "EPD CLK", 0x17 to "EPD MOSI", 0x18 to "EPD ENABLE",
            0x19 to "EPD ENABLE1", 0x1A to "EPD FLASH CS",
            0x20 to "LED R", 0x21 to "LED G", 0x22 to "LED B",
            0x30 to "NFC SDA", 0x31 to "NFC SCL", 0x32 to "NFC CS",
            0x33 to "NFC IRQ",
            0x40 to "FLASH CS", 0x41 to "FLASH CLK", 0x42 to "FLASH MISO",
            0x43 to "FLASH MOSI"
        )
        return buildString {
            appendLine("pins this build uses:")
            for (g in 0 until 5) {
                val mask = d[g].toInt() and 0xFF
                if (mask == 0) continue
                appendLine("  P%c  %s".format(
                    'A' + g,
                    (0 until 8).filter { mask and (1 shl it) != 0 }.joinToString(" ") { "$it" }
                ))
            }
            appendLine()
            var o = 5
            while (o + 2 < d.size) {
                val role = d[o].toInt() and 0xFF
                val pin = u16le(d, o + 1)
                appendLine("  %-14s %s".format(roles[role] ?: "role 0x%02X".format(role), pinName(pin)))
                o += 3
            }
        }
    }

    private fun decodeI2cScan(b: ByteArray): String {
        val d = dbgData(b, 16) ?: return dbgReply(b)
        val found = (1 until 0x78).filter { (d[it shr 3].toInt() shr (it and 7)) and 1 != 0 }
        if (found.isEmpty()) return "no device answered on the bus"
        val known = mapOf(
            0x57 to "Fudan FM11NT082C (7 bit 0x57, write 0xAE)",
            0x53 to "ST ST25DV user memory",
            0x2D to "ST ST25DV system",
            0x55 to "NXP NTAG I2C plus"
        )
        return found.joinToString("\n") { a ->
            "0x%02X  (write 0x%02X)  %s".format(a, a shl 1, known[a] ?: "")
        }
    }

    private fun decodeAdc(b: ByteArray): String {
        val d = dbgData(b, 5) ?: return dbgReply(b)
        return "reading        ${u16le(d, 0)} mV\nraw            ${u16le(d, 2)}\n" +
            "derived temp   ${i8(d, 4)} C"
    }

    private fun decodeEpdState(b: ByteArray): String {
        val d = dbgData(b, 10) ?: return dbgReply(b)
        fun busy(v: Int) = when (v) {
            0xFF -> "no pin configured"
            0 -> "0 (low)"
            else -> "1 (high)"
        }
        return buildString {
            appendLine("update state    ${epdStateName(d[0].toInt() and 0xFF)}")
            appendLine("BUSY            ${busy(d[1].toInt() and 0xFF)}")
            appendLine("BUSYs           ${busy(d[2].toInt() and 0xFF)}")
            appendLine("panel temp      ${i8(d, 3)} C")
            appendLine("geometry        ${u16le(d, 4)} x ${u16le(d, 6)}")
            appendLine("colours         ${d[8].toInt() and 0xFF}")
            appendLine("controller      ${d[9].toInt() and 0xFF}")
            appendLine()
            appendLine("A BUSY that stays high with state 2 is a refresh the panel")
            appendLine("never finished; the firmware gives it 35 s and then sleeps it.")
        }
    }

    private fun decodeMemSearch(b: ByteArray): String {
        val d = dbgData(b, 1) ?: return dbgReply(b)
        val hits = d[0].toInt() and 0xFF
        if (hits == 0) return "no match"
        val n = minOf(hits, (d.size - 1) / 4)
        return "$hits hit(s)%s\n".format(if (hits >= 24) " (capped at 24)" else "") +
            (0 until n).joinToString("\n") { "  0x%06X".format(u32le(d, 1 + it * 4)) }
    }

    private fun decodeMemDiff(b: ByteArray): String {
        val d = dbgData(b, 1) ?: return dbgReply(b)
        val same = (d[0].toInt() and 0xFF) != 0
        val body = d.copyOfRange(1, d.size)
        return if (!same)
            "first look at this window - these are the bytes, ask again to see " +
                "what changed\n" + hexDump(0, body)
        else {
            val changed = body.indices.filter { body[it].toInt() != 0 }
            if (changed.isEmpty()) "nothing changed"
            else "${changed.size} byte(s) changed, as XOR masks:\n" + hexDump(0, body)
        }
    }

    private fun decodeSettingsRaw(b: ByteArray): String {
        val d = dbgData(b, 2) ?: return dbgReply(b)
        return "struct is ${u16le(d, 0)} bytes\n" + hexDump(0, d.copyOfRange(2, d.size))
    }

    /**
     * DBG_NFC_INFO: which NFC chip the firmware found, where, and how it is
     * configured. Worth decoding rather than dumping hex, because these are
     * the values that decide what the NFC path can do at all - a chip in NT
     * mode answers a reader out of its own memory and offers no command
     * channel, so there is nothing for the APDU path to talk to.
     */
    private fun decodeNfcInfo(b: ByteArray): String {
        if (b.size < 2) return "truncated debug reply"
        if ((b[1].toInt() and 0xFF) != 0) return dbgReply(b)
        val d = b.copyOfRange(2, b.size)
        if (d.size < 18) return "short NFC info (${d.size} bytes)"

        fun u(i: Int) = d[i].toInt() and 0xFF

        val kinds = mapOf(
            0 to "none", 1 to "Fudan FM11NT082C / FM11NC08", 2 to "ST ST25DV",
            3 to "NXP NTAG I2C plus", 4 to "unknown NFC IC"
        )
        val caps = u(1)
        val out = ArrayList<String>()
        out += "chip      %s".format(kinds[u(0)] ?: "kind ${u(0)}")
        out += "address   0x%02X%s, %d register address byte(s)".format(
            u(2), if (u(3) != 0) ", user 0x%02X".format(u(3)) else "", u(4))
        out += "caps      0x%02X%s%s%s".format(
            caps,
            if (caps and 1 != 0) " frame-FIFO" else "",
            if (caps and 2 != 0) " type-2-memory" else "",
            if (caps and 4 != 0) " user-device-select" else "")
        val uidLen = u(5).coerceIn(0, 7)
        out += "uid       %s".format(
            (0 until uidLen).joinToString(" ") { "%02X".format(d[6 + it]) })
        out += "ndef area %d bytes".format(u(13) or (u(14) shl 8))
        out += "field %d, session %d, %s".format(
            u(15), u(16), if (u(17) != 0) "NC channel mode" else "NT tag mode")

        val names = listOf(
            "USER_CFG0", "USER_CFG1", "USER_CFG2", "STATUS", "FIFO_WORDCNT",
            "NFC_STATUS", "NFC_CFG", "NFC_RATS", "MAIN_IRQ_MASK"
        )
        val states = mapOf(
            0 to "IDLE", 1 to "READY1", 2 to "READY2", 3 to "IDLE in inventory",
            4 to "HALT", 7 to "QUIET", 8 to "channel L3 / RATS forwarded",
            9 to "RATS back", 10 to "PPS back", 12 to "not authenticated",
            13 to "authenticated"
        )
        for ((i, n) in names.withIndex()) {
            if (18 + i >= d.size) break
            val v = u(18 + i)
            val note = when (n) {
                "USER_CFG0" -> "  %s, IRQ active %s, harvesting %s".format(
                    if (v and 1 != 0) "NC channel mode" else "NT tag mode",
                    if (v and 0x10 != 0) "low" else "high",
                    if (v and 0xC0 != 0) "on" else "off")
                "USER_CFG1" -> "  ISO14443-%d".format(if (v and 0x0C != 0) 3 else 4)
                "NFC_STATUS" -> "  %s".format(
                    states[v shr 4] ?: "state 0x%X".format(v shr 4))
                "FIFO_WORDCNT" -> "  %d byte(s) in the FIFO".format(v and 0x3F)
                "MAIN_IRQ_MASK" -> if (v != 0)
                    "  masked - the firmware clears this on every session" else "  frames raise IRQ"
                else -> ""
            }
            out += "%-13s 0x%02X%s".format(n, v, note)
        }
        if (u(17) == 0) {
            out += ""
            out += "NT mode: the chip serves its own memory to a reader and has"
            out += "no frame channel, so write the NDEF into the chip rather"
            out += "than expecting the APDU path to work."
        }
        return out.joinToString("\n")
    }

    private fun dbg(
        sub: Int, name: String, help: String, params: List<Param> = emptyList(),
        decode: ((ByteArray) -> String)? = ::dbgReply, category: String = "Debug",
        danger: String = ""
    ) = CommandSpec(
        Cmd.DEBUG, name, help, params, sub = sub, decode = decode,
        category = category, danger = danger
    )

    /** A read length, bounded by what one debug reply carries. */
    private fun lenParam(default: Int, label: String = "length") =
        Param(label, ParamType.U8, "$default", unit = "bytes", min = 1, max = 176)

    val debug: List<CommandSpec> = listOf(
        dbg(
            0x7F, "Echo (link test)",
            "Sends bytes and expects exactly them back. The cheapest proof that " +
                "the whole path works - reader, APDU staging, the dispatcher and " +
                "the reply - without changing anything on the tag.",
            listOf(Param("data", ParamType.HEX, "00112233445566778899AABBCCDDEEFF")),
            category = "Link"
        ),

        // --- system --------------------------------------------------------
        dbg(
            0x60, "Sysinfo",
            "Firmware version, hardware type, capabilities, uptime, why the tag " +
                "last woke, what the panel and the NFC session are doing, and the " +
                "build stamp - so there is no doubt which binary is answering.",
            decode = ::decodeSysinfo, category = "Info"
        ),
        dbg(
            0x51, "Sensors",
            "Battery in mV and as a percentage, the raw ADC value, both " +
                "temperatures, the tag's clock, its uptime and the reason for the " +
                "last wake.",
            decode = ::decodeSensors, category = "Info"
        ),
        dbg(
            0x62, "RAM map",
            "The linker's marks, and the number that actually matters: how much " +
                "retained RAM is left below the 0x848000 ceiling. That budget is " +
                "what decides whether the next feature fits.",
            decode = ::decodeRamInfo, category = "Info"
        ),
        dbg(
            0x61, "Settings raw",
            "A window into settings_struct as it is in RAM, or into the type " +
                "record it was built from. For looking at a field the decoders " +
                "above do not show.",
            listOf(
                Param(
                    "which", ParamType.U8, "0",
                    options = listOf("0 live settings" to 0, "1 type record" to 1)
                ),
                Param("offset", ParamType.U16LE, "0", unit = "bytes", min = 0, max = 255),
                lenParam(160)
            ),
            decode = ::decodeSettingsRaw, category = "Info"
        ),

        // --- NFC -----------------------------------------------------------
        CommandSpec(
            Cmd.DEBUG, "NFC chip info",
            "Which NFC IC was detected, on which address, its UID and its live " +
                "configuration registers. The chip is found at runtime, so this is " +
                "the only thing that says what the NFC path can actually do.\n\n" +
                "Worth reading when taps are unreliable: MAIN_IRQ_MASK reverts to " +
                "the chip's EEPROM default on any reset, and a masked chip never " +
                "tells the firmware a frame arrived.",
            sub = 0x44, decode = ::decodeNfcInfo, category = "NFC"
        ),
        CommandSpec(
            Cmd.DEBUG, "NFC recover",
            "Clocks a jammed I2C bus free and runs chip detection again, including " +
                "a full bus scan. For when the chip has stopped answering or has " +
                "turned up on an address nothing expects.",
            listOf(Param("confirm", ParamType.U8, "0xA5", options = confirm, ui = Widget.SWITCH)),
            sub = 0x45, decode = ::dbgReply, category = "NFC"
        ),
        dbg(
            0x40, "I2C scan",
            "Tries every one of the 128 seven-bit addresses and reports which " +
                "answered, with a name where it is a chip this firmware knows.",
            decode = ::decodeI2cScan, category = "I2C"
        ),
        dbg(
            0x41, "I2C read",
            "A register read on the software I2C bus. The register address may be " +
                "two bytes, one, or none at all - the Fudan chip's registers are at " +
                "0xFFE0..0xFFFC with two address bytes and a write address of 0xAE.",
            listOf(
                Param("device (8 bit write addr)", ParamType.U8, "0xAE", hint = "e.g. 0xAE"),
                Param(
                    "register bytes", ParamType.U8, "2",
                    options = listOf("2" to 2, "1" to 1, "none" to 0)
                ),
                Param("register", ParamType.U16LE, "0xFFF2"),
                lenParam(8)
            ),
            category = "I2C"
        ),
        dbg(
            0x42, "I2C write",
            "A register write on the same bus. Same address-length rules as the " +
                "read - and no guard, so a wrong device address writes to whatever " +
                "does answer.",
            listOf(
                Param("device", ParamType.U8, "0xAE"),
                Param(
                    "register bytes", ParamType.U8, "2",
                    options = listOf("2" to 2, "1" to 1, "none" to 0)
                ),
                Param("register", ParamType.U16LE, "0xFFF2"),
                Param("data", ParamType.HEX, "", required = true)
            ),
            category = "I2C"
        ),
        dbg(
            0x43, "I2C raw write",
            "A bare byte stream with no register phase, for chips that want a " +
                "command sequence rather than an address.",
            listOf(Param("device", ParamType.U8, "0xAE"), Param("data", ParamType.HEX, "03B5A0")),
            category = "I2C"
        ),

        // --- panel ---------------------------------------------------------
        dbg(
            0x71, "Panel state",
            "What the EPD state machine is doing, both BUSY lines, the panel's own " +
                "temperature and the geometry it is driving. The first thing to " +
                "read when a refresh does not finish.",
            decode = ::decodeEpdState, category = "Panel"
        ),
        dbg(
            0x72, "Panel power",
            "Brings the panel's rail and pins up or down. Powering it down while " +
                "a refresh is running leaves the image half drawn - harmless, but " +
                "it needs a full refresh to recover.",
            listOf(Param("power", ParamType.U8, "1", options = onOff, ui = Widget.SWITCH)),
            category = "Panel"
        ),
        dbg(
            0x70, "Panel raw command",
            "Writes a command byte and its data straight to the panel controller, " +
                "then clocks bytes back. A command with no data is legal.\n\n" +
                "Guarded by 0xA5 because a stray command can leave a panel in a " +
                "state only a reset clears.",
            listOf(
                Param("confirm", ParamType.U8, "0xA5", options = confirm, ui = Widget.SWITCH),
                Param("read back", ParamType.U8, "1", unit = "bytes", min = 0, max = 176),
                Param("command", ParamType.U8, "0x71"),
                Param("data", ParamType.HEX, "")
            ),
            category = "Panel", danger = "Can leave the panel needing a reset."
        ),

        // --- GPIO ----------------------------------------------------------
        dbg(
            0x35, "GPIO pin roles",
            "Every pin this build has a role for, by name - which is the list to " +
                "check before poking a pin, because the roles differ per tag and " +
                "one of them is the flash chip select.",
            decode = ::decodeGpioPinout, category = "GPIO"
        ),
        dbg(
            0x33, "GPIO snapshot",
            "The six registers of all five ports at once: input, input enable, " +
                "output enable, output value, polarity and drive strength.",
            decode = ::decodeGpioSnapshot, category = "GPIO"
        ),
        dbg(
            0x30, "GPIO read pin",
            "The level of one pin right now, plus the six registers of its port: " +
                "input, input enable, output enable, output value, polarity and " +
                "drive strength - which together say whether the level you are " +
                "reading is one the tag is driving or one it is only seeing.",
            listOf(Param("pin", ParamType.U16LE, "0x0108", options = gpioPins)),
            decode = ::decodeGpio, category = "GPIO"
        ),
        dbg(
            0x31, "GPIO set pin",
            "Drives one pin and reads it back. Nothing stops you from driving a " +
                "pin that belongs to the flash or the panel's power - check the " +
                "roles first.",
            listOf(
                Param("pin", ParamType.U16LE, "0x0108", options = gpioPins),
                Param("level", ParamType.U8, "1", options = onOff, ui = Widget.SWITCH)
            ),
            decode = ::decodeGpio, category = "GPIO",
            danger = "No guard. Driving the flash CS or the panel rail hangs the tag."
        ),
        dbg(
            0x32, "GPIO configure",
            "Function, output enable, input enable and pull resistor for one pin. " +
                "The function numbers are the chip's: GPIO, MSPI, SWIRE, UART, I2C, " +
                "SPI and ADC.",
            listOf(
                Param("pin", ParamType.U16LE, "0x0108", options = gpioPins),
                Param(
                    "function", ParamType.U8, "0",
                    options = listOf(
                        "0 GPIO" to 0, "1 MSPI" to 1, "2 SWIRE" to 2, "3 UART" to 3,
                        "4 I2C" to 4, "5 SPI" to 5, "11 ADC" to 11
                    )
                ),
                Param("output enable", ParamType.U8, "1", options = onOff, ui = Widget.SWITCH),
                Param("input enable", ParamType.U8, "0", options = onOff, ui = Widget.SWITCH),
                Param(
                    "pull", ParamType.U8, "0",
                    options = listOf(
                        "float" to 0, "up 1M" to 1, "down 100K" to 2, "up 10K" to 3
                    )
                )
            ),
            decode = ::decodeGpio, category = "GPIO",
            danger = "Reconfiguring SWIRE can cost you the debug connection."
        ),
        dbg(
            0x34, "GPIO pulse",
            "Drives a pin, waits, and puts it back where it was. Capped at 200 ms, " +
                "because this does block the main loop and a debug pulse is not " +
                "worth starving BLE or the panel for longer.",
            listOf(
                Param("pin", ParamType.U16LE, "0x0108", options = gpioPins),
                Param("level", ParamType.U8, "1", options = onOff, ui = Widget.SWITCH),
                Param(
                    "duration", ParamType.U16LE, "50", unit = "ms", min = 1, max = 200
                )
            ),
            category = "GPIO"
        ),

        // --- ADC -----------------------------------------------------------
        dbg(
            0x50, "ADC read",
            "One measurement in mV, its raw value and the temperature derived from " +
                "it. The pin is used for this reading only and put back afterwards; " +
                "only the ten pins the SAR ADC can reach are accepted.",
            listOf(Param("pin", ParamType.U16LE, "0", options = adcPins)),
            decode = ::decodeAdc, category = "Info"
        ),

        // --- memory and registers -------------------------------------------
        dbg(
            0x01, "Memory read",
            "Absolute addresses. SRAM starts at 0x840000 and the register file at " +
                "0x800000; the retained part of RAM ends at 0x848000.",
            listOf(
                Param("address", ParamType.U32LE, "0x840000"),
                lenParam(64),
                Param("width", ParamType.U8, "1", options = widths)
            ),
            category = "Memory"
        ),
        dbg(
            0x02, "Memory write",
            "Address and width have to line up with the data length, or the tag " +
                "answers 'not aligned' rather than writing something half right.",
            listOf(
                Param("address", ParamType.U32LE, "0x840000"),
                Param("width", ParamType.U8, "1", options = widths),
                Param("data", ParamType.HEX, "", required = true)
            ),
            category = "Memory", danger = "Writes live RAM. Nothing checks what it hits."
        ),
        dbg(
            0x03, "Memory fill",
            "Writes one value repeatedly at the given width - for clearing a " +
                "buffer or filling one with a recognisable pattern.",
            listOf(
                Param("address", ParamType.U32LE, "0x840000"),
                Param("count", ParamType.U16LE, "16", min = 1, max = 4096),
                Param("width", ParamType.U8, "1", options = widths),
                Param("value", ParamType.U32LE, "0")
            ),
            category = "Memory", danger = "Writes live RAM."
        ),
        dbg(
            0x04, "Memory search",
            "Scans up to 64 KiB and reports the first 24 matches. Fast enough not " +
                "to matter - the whole SRAM is 32 KiB - and bounded so a bad length " +
                "cannot turn into a four-gigabyte walk.",
            listOf(
                Param("address", ParamType.U32LE, "0x840000"),
                Param("length", ParamType.U32LE, "0x8000", unit = "bytes"),
                Param("pattern length", ParamType.U8, "3", min = 1, max = 16),
                Param("pattern", ParamType.HEX, "C0FFEE")
            ),
            decode = ::decodeMemSearch, category = "Memory"
        ),
        dbg(
            0x05, "Memory watch",
            "The first call takes a baseline of the window; every call after it " +
                "reports which bytes changed, as XOR masks. For watching a variable " +
                "move while the tag works.",
            listOf(
                Param("address", ParamType.U32LE, "0x840000"),
                lenParam(32)
            ),
            decode = ::decodeMemDiff, category = "Memory"
        ),
        dbg(
            0x08, "Register read",
            "Offsets from 0x800000. The GPIO ports live at 0x580 + group * 8.",
            listOf(
                Param("offset", ParamType.U16LE, "0x0580"),
                lenParam(48),
                Param("width", ParamType.U8, "1", options = widths)
            ),
            category = "Memory"
        ),
        dbg(
            0x09, "Register write",
            "Offsets from 0x800000. A wrong register here can stop the clock or " +
                "the radio until the next reboot.",
            listOf(
                Param("offset", ParamType.U16LE, "0x0580"),
                Param("width", ParamType.U8, "1", options = widths),
                Param("data", ParamType.HEX, "", required = true)
            ),
            category = "Memory", danger = "Writes chip registers directly."
        ),
        dbg(
            0x10, "Analog read",
            "The separate analog register file, which is where the power " +
                "management and the retention settings live.",
            listOf(
                Param("address", ParamType.U8, "0", min = 0, max = 255),
                lenParam(64)
            ),
            category = "Memory"
        ),
        dbg(
            0x11, "Analog write",
            "The same file, written. This is where a wrong byte can stop the tag " +
                "waking up again.",
            listOf(
                Param("address", ParamType.U8, "0", min = 0, max = 255),
                Param("data", ParamType.HEX, "", required = true)
            ),
            category = "Memory", danger = "Can stop the tag from waking up."
        ),

        // --- flash ---------------------------------------------------------
        dbg(
            0x23, "Flash ID",
            "JEDEC id, the unique id and the status register of the internal flash.",
            decode = ::decodeFlashInfo, category = "Flash"
        ),
        dbg(
            0x24, "Flash map",
            "Where the firmware, the image slots, the settings, the NDEF store, " +
                "the BLE NV area and the factory data sit - which is everything you " +
                "need to know not to write over the wrong one.",
            decode = ::decodeFlashMap, category = "Flash"
        ),
        dbg(
            0x20, "Flash read",
            "Raw internal flash by absolute address, with no idea what it is " +
                "looking at - read 'Flash map' first to know which region an " +
                "address belongs to. Harmless on its own; it is the write that " +
                "needs care.",
            listOf(
                Param("address", ParamType.U32LE, "0x79000"),
                lenParam(128)
            ),
            category = "Flash"
        ),
        dbg(
            0x21, "Flash write",
            "Needs the 0xA5 confirmation. Flash can only clear bits, so writing " +
                "over data that is not erased gives you the AND of the two - erase " +
                "the sector first if you mean to set any bit.",
            listOf(
                Param("address", ParamType.U32LE, "0x78000"),
                Param("confirm", ParamType.U8, "0xA5", options = confirm, ui = Widget.SWITCH),
                Param("data", ParamType.HEX, "", required = true)
            ),
            category = "Flash", danger = "A wrong address can brick the tag."
        ),
        dbg(
            0x22, "Flash erase sector",
            "Erases the 4 KiB sector containing the address. Needs the 0xA5 " +
                "confirmation.",
            listOf(
                Param("address", ParamType.U32LE, "0x78000"),
                Param("confirm", ParamType.U8, "0xA5", options = confirm, ui = Widget.SWITCH)
            ),
            category = "Flash", danger = "Erases 4 KiB. A wrong address can brick the tag."
        ),
        dbg(
            0x25, "Flash unlock",
            "Lifts the write protection over the firmware area. There is no reason " +
                "to do this unless you are writing a firmware image by hand, and a " +
                "wrong address afterwards leaves a tag only a programmer can revive.",
            listOf(Param("confirm", ParamType.U8, "0xA5", options = confirm, ui = Widget.SWITCH)),
            category = "Flash", danger = "Removes the only thing protecting the firmware."
        ),
        dbg(
            0x26, "Flash re-lock",
            "Puts the firmware write protection back. Worth sending after any " +
                "unlock, rather than leaving the tag unprotected.",
            category = "Flash"
        )
    )

    /** Reply ids, so an unexpected answer is readable rather than a number. */
    val replyNames = mapOf(
        Cmd.GET_INFOS to "GET_INFOS",
        Cmd.ACK_CMD to "ACK",
        Cmd.ERR_BLKPRT to "ERR_BLKPRT",
        Cmd.ACK_BLKPRT to "ACK_BLKPRT",
        Cmd.REQ to "BLOCK_REQUEST",
        Cmd.ACK to "ACK",
        Cmd.ACK_IS_SHOWN to "ALREADY_SHOWN",
        Cmd.ACK_FW_UPDATED to "FW_UPDATED",
        Cmd.ACK_LUT_START to "LUT_START",
        Cmd.ACK_LUT_DATA to "LUT_DATA",
        Cmd.ACK_LUT_DONE to "LUT_DONE",
        Cmd.ACK_DYNAMIC_READ to "HW_CONFIG",
        Cmd.ACK_DYNAMIC_OK to "HW_CONFIG_OK",
        Cmd.ACK_DYNAMIC_ERR to "HW_CONFIG_ERR",
        Cmd.ERR_FILE_TOO_BIG to "FILE_TOO_BIG",
        Cmd.ACK_DEBUG_INFO to "SENSORS",
        Cmd.ACK_NDEF_DATA to "NDEF_DATA",
        Cmd.ACK_DEBUG to "DEBUG",
        Cmd.ACK_PROTECTION to "PROTECTION",
        Cmd.ERR to "ERROR"
    )
}

/**
 * The LED pattern, as src/led.c runs it.
 *
 * Twelve bytes with two values packed into most of them, which is why this is a
 * class and not twelve input boxes. The engine is a tick at a time from the
 * main loop: every 100 ms it looks at the group it is on, counts down that
 * group's spacing, and flashes when the count runs out. Three groups make one
 * pass, and the pass repeats.
 *
 *   byte 0   brightness-1 in the high nibble, mode in the low one (1 = run)
 *   byte 1   group 1 colour, packed rrrgggbb
 *   byte 2   group 1 spacing in the high nibble, flash count in the low one
 *   byte 3   stored by the firmware and never read by the engine
 *   bytes 4-6, 7-9  the same for groups 2 and 3
 *   byte 10  passes - 1; 254 means run until something clears it
 *   byte 11  spare
 */
class LedPattern {

    class Group(
        var r: Int = 0,
        var g: Int = 0,
        var b: Int = 0,
        /** Flashes in this group, 1..15. */
        var flashes: Int = 1,
        /** Ticks of 100 ms between them, 0..15. */
        var spacing: Int = 2
    ) {
        fun copy() = Group(r, g, b, flashes, spacing)
    }

    /** 1..16. Also the length of one flash, about 0.7 ms per step. */
    var brightness = 8

    /** 1..254 passes, or [ENDLESS] to keep going. */
    var repeats = 3

    val groups = arrayOf(
        Group(7, 0, 0, 3, 2),
        Group(0, 7, 0, 3, 2),
        Group(0, 0, 3, 3, 2)
    )

    fun encode(): ByteArray {
        val b = ByteArray(12)
        b[0] = ((((brightness.coerceIn(1, 16) - 1) and 0x0F) shl 4) or MODE_RUN).toByte()
        for (i in 0 until 3) {
            val g = groups[i]
            b[1 + i * 3] = colour(g.r, g.g, g.b).toByte()
            b[2 + i * 3] = (((g.spacing.coerceIn(0, 15)) shl 4) or
                (g.flashes.coerceIn(0, 15))).toByte()
            b[3 + i * 3] = 0
        }
        // grouprepeats is ledcfg[10] + 1 in a uint8_t, and the engine only
        // stops when that sum is not 255 - so 254 here runs forever and 255
        // wraps to zero and stops after a single pass. 254 is the endless one.
        b[10] = (if (repeats >= ENDLESS) 254 else (repeats.coerceIn(1, 254) - 1)).toByte()
        b[11] = 0
        return b
    }

    /** How long one run takes, in ms, or -1 when it never ends. */
    fun durationMs(): Int {
        if (repeats >= ENDLESS) return -1
        var ticks = 0
        for (g in groups) {
            // A count of zero still flashes once: the engine increments before
            // it compares. Spacing zero fires on every tick.
            ticks += maxOf(g.flashes, 1) * maxOf(g.spacing, 1)
        }
        return ticks * TICK_MS * repeats.coerceIn(1, 254)
    }

    fun copyFrom(other: LedPattern) {
        brightness = other.brightness
        repeats = other.repeats
        for (i in 0 until 3) {
            groups[i].r = other.groups[i].r
            groups[i].g = other.groups[i].g
            groups[i].b = other.groups[i].b
            groups[i].flashes = other.groups[i].flashes
            groups[i].spacing = other.groups[i].spacing
        }
    }

    companion object {
        const val MODE_RUN = 1
        const val TICK_MS = 100

        /** [repeats] value that means "until something else clears the LED". */
        const val ENDLESS = 255

        /** 12 zero bytes: mode 0, which is what clearLED() reacts to. */
        fun off(): ByteArray = ByteArray(12)

        /**
         * A short green double flash - what the main screen's LED button
         * sends, and the thing to reach for when all you want is "that one,
         * yes, the tag I am touching".
         */
        fun confirmation() = of(
            10, 2,
            Group(0, 7, 0, 2, 2), Group(0, 0, 0, 1, 1), Group(0, 0, 0, 1, 1)
        )

        /**
         * flashled() slices the flash into seven sub-steps and compares the
         * component against each, so red and green have eight levels and blue,
         * which is compared twice, has four.
         */
        fun colour(r: Int, g: Int, b: Int) =
            ((r.coerceIn(0, 7) and 7) shl 5) or
                ((g.coerceIn(0, 7) and 7) shl 2) or
                (b.coerceIn(0, 3) and 3)

        fun red(v: Int) = (v shr 5) and 7
        fun green(v: Int) = (v shr 2) and 7
        fun blue(v: Int) = v and 3

        /** The packed colour as something a swatch can be painted with. */
        fun argb(r: Int, g: Int, b: Int): Int {
            val rr = r.coerceIn(0, 7) * 255 / 7
            val gg = g.coerceIn(0, 7) * 255 / 7
            val bb = b.coerceIn(0, 3) * 255 / 3
            return (0xFF shl 24) or (rr shl 16) or (gg shl 8) or bb
        }

        fun of(
            brightness: Int, repeats: Int, vararg groups: Group
        ) = LedPattern().also { p ->
            p.brightness = brightness
            p.repeats = repeats
            for (i in 0 until 3) p.groups[i].let { g ->
                val src = groups.getOrNull(i) ?: Group(0, 0, 0, 1, 1)
                g.r = src.r; g.g = src.g; g.b = src.b
                g.flashes = src.flashes; g.spacing = src.spacing
            }
        }

        /**
         * Starting points, because the interesting part of this command is the
         * shape of the sequence and nobody finds that from twelve sliders.
         */
        val presets: List<Pair<String, LedPattern>> = listOf(
            "Red alert" to of(
                16, 10,
                Group(7, 0, 0, 2, 1), Group(0, 0, 0, 1, 2), Group(0, 0, 0, 1, 1)
            ),
            "Green ok" to confirmation(),
            "Police" to of(
                16, 12,
                Group(7, 0, 0, 3, 1), Group(0, 0, 3, 3, 1), Group(0, 0, 0, 1, 1)
            ),
            "Rainbow" to of(
                12, 5,
                Group(7, 0, 0, 1, 3), Group(0, 7, 0, 1, 3), Group(0, 0, 3, 1, 3)
            ),
            "Slow pulse" to of(
                16, LedPattern.ENDLESS,
                Group(7, 7, 3, 1, 10), Group(0, 0, 0, 1, 10), Group(0, 0, 0, 1, 10)
            ),
            "Find me" to of(
                16, LedPattern.ENDLESS,
                Group(7, 7, 0, 4, 1), Group(0, 0, 0, 1, 8), Group(0, 0, 0, 1, 1)
            )
        )
    }
}
