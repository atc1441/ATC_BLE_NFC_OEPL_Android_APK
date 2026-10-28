package io.github.atc1441.oeplnfc

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The firmware is inconsistent about endianness on purpose-free grounds - some
 * commands take a big endian u16, others little endian - and it answers a
 * wrongly encoded command with a generic error rather than a hint. So the
 * encoding of every parameterised command is pinned here against the layouts
 * in src/app_bleCmdHandle.c and src/debug_cmd.h.
 */
class CommandsTest {

    private fun spec(name: String): CommandSpec {
        val s = (Commands.tag + Commands.debug).firstOrNull { it.name == name }
        assertNotNull("no command called '$name'", s)
        return s!!
    }

    private fun encode(name: String, vararg values: String): ByteArray {
        val s = spec(name)
        assertEquals(
            "'$name' takes ${s.params.size} values, test passed ${values.size}",
            s.params.size, values.size
        )
        return Commands.encode(s.params, values.toList())
    }

    private fun hex(b: ByteArray) = b.joinToString("") { "%02X".format(it) }

    // --- the big endian ones ------------------------------------------------

    @Test
    fun setTypeIsBigEndian() {
        // settings_set_screen_data((payload[0]<<8)|payload[1])
        assertEquals("0026", hex(encode("Set device type", "38")))
        assertEquals("FFFF", hex(encode("Set device type", "65535")))
    }

    @Test
    fun advertisingPeriodIsBigEndian() {
        // newAdvInterval = (payload[0]<<8)|payload[1]
        assertEquals("0C80", hex(encode("OEPL advertising period", "3200")))
    }

    @Test
    fun connectionSpeedIsBigEndian() {
        assertEquals("0064", hex(encode("BLE connection speed", "100")))
    }

    @Test
    fun readSensorsIsBigEndian() {
        // debug_data_type = (payload[0]<<8)|payload[1]
        assertEquals("0000", hex(encode("Read sensors", "0")))
    }

    @Test
    fun resetGuardIsTheValueTheFirmwareChecks() {
        // if(len == 2 && payload[0] == 0x12 && payload[1] == 0x34)
        val s = spec("Reset settings")
        assertEquals("0x1234", s.params[0].default)
        assertEquals("1234", hex(Commands.encode(s.params, listOf(s.params[0].default))))
    }

    // --- the little endian ones ---------------------------------------------

    @Test
    fun setClockIsLittleEndianAndFiveBytesLong() {
        // payload[3]<<24 | payload[2]<<16 | payload[1]<<8 | payload[0],
        // and payload[4] is read unconditionally, so it must be there.
        val b = encode("Set clock", "0x11223344", "1")
        assertEquals("4433221101", hex(b))
        assertEquals(5, b.size)
    }

    @Test
    fun readNdefOffsetIsLittleEndian() {
        assertEquals("3412", hex(encode("Read NDEF", "0x1234")))
    }

    @Test
    fun debugChannelIsLittleEndianThroughout() {
        // [addr:u32][len:u8][width:u8]
        assertEquals("00008400" + "40" + "04",
            hex(encode("Memory read", "0x840000", "64", "4")))
        // [off:u16][len:u8][width:u8]
        assertEquals("8005" + "30" + "01",
            hex(encode("Register read", "0x0580", "48", "1")))
        // [addr:u32][count:u16][width:u8][value:u32]
        assertEquals("00008400" + "1000" + "02" + "EFBEADDE",
            hex(encode("Memory fill", "0x840000", "16", "2", "0xDEADBEEF")))
        // [dev][reglen][reg:u16][len]
        assertEquals("AE" + "02" + "F2FF" + "08",
            hex(encode("I2C read", "0xAE", "2", "0xFFF2", "8")))
    }

    // --- length-critical commands -------------------------------------------

    @Test
    fun customMacIsExactlyEightBytes() {
        // if(len == 8) ... else error
        val b = encode("Set custom MAC", "00:11:22:33:44:55:66:77")
        assertEquals(8, b.size)
        assertEquals("0011223344556677", hex(b))
    }

    @Test
    fun aMacOfTheWrongLengthIsRejectedBeforeItIsSent() {
        val s = spec("Set custom MAC")
        for (bad in listOf("001122334455", "001122334455667788")) {
            try {
                Commands.encode(s.params, listOf(bad))
                throw AssertionError("'$bad' should not encode as a MAC")
            } catch (e: IllegalArgumentException) {
                assertTrue(e.message!!.contains("8 bytes"))
            }
        }
    }

    @Test
    fun ledPatternIsExactlyTwelveBytes() {
        // if(len == 12) ... else error - and the pattern has an editor of its
        // own, so the bytes come from LedPattern rather than from a form.
        assertEquals(Editor.LED, spec("LED pattern").editor)
        assertEquals(12, LedPattern().encode().size)
        assertEquals(12, LedPattern.off().size)
        for ((name, p) in LedPattern.presets) {
            assertEquals("preset '$name' is not 12 bytes", 12, p.encode().size)
            assertEquals("preset '$name' does not run", 1, p.encode()[0].toInt() and 0x0F)
        }
    }

    // --- the LED pattern ----------------------------------------------------

    @Test
    fun theLedPatternLandsWhereLedCExpectsIt() {
        // setLedBuffer() in src/led.c:
        //   brightness = (cfg[0] >> 4) + 1, mode = cfg[0] & 0x0F
        //   c1 = cfg[1], loop1delay = cfg[2] >> 4, loopcnt1 = cfg[2] & 0x0F
        //   c2/c3 at 4 and 7, their loop bytes at 5 and 8
        //   grouprepeats = cfg[10] + 1
        val p = LedPattern()
        p.brightness = 16
        p.repeats = 7
        p.groups[0].let { it.r = 7; it.g = 0; it.b = 0; it.flashes = 3; it.spacing = 2 }
        p.groups[1].let { it.r = 0; it.g = 7; it.b = 0; it.flashes = 1; it.spacing = 4 }
        p.groups[2].let { it.r = 0; it.g = 0; it.b = 3; it.flashes = 15; it.spacing = 15 }
        val b = p.encode()

        assertEquals("brightness 16 is 15 in the high nibble", 15, (b[0].toInt() shr 4) and 0x0F)
        assertEquals("mode must be 1 or the engine clears instead", 1, b[0].toInt() and 0x0F)
        assertEquals(0xE0, b[1].toInt() and 0xFF)   // red 7 -> 111 000 00
        assertEquals(0x23, b[2].toInt() and 0xFF)   // spacing 2, count 3
        assertEquals(0x1C, b[4].toInt() and 0xFF)   // green 7 -> 000 111 00
        assertEquals(0x41, b[5].toInt() and 0xFF)
        assertEquals(0x03, b[7].toInt() and 0xFF)   // blue 3 -> 000 000 11
        assertEquals(0xFF, b[8].toInt() and 0xFF)
        assertEquals("7 passes is 6 in the byte", 6, b[10].toInt() and 0xFF)
        // The bytes the engine stores and never reads stay zero.
        for (i in listOf(3, 6, 9, 11)) assertEquals("byte $i", 0, b[i].toInt())
    }

    @Test
    fun endlessIsTheOneValueTheFirmwareDoesNotWrapOn() {
        // grouprepeats = cfg[10] + 1 in a uint8_t, and the loop only stops
        // when that sum differs from 255. So 254 runs forever and 255 wraps to
        // zero and stops after a single pass - which is not what anyone asking
        // for 256 repeats means.
        val p = LedPattern()
        p.repeats = LedPattern.ENDLESS
        assertEquals(254, p.encode()[10].toInt() and 0xFF)
        assertEquals(-1, p.durationMs())
        p.repeats = 1
        assertEquals(0, p.encode()[10].toInt() and 0xFF)
        assertTrue(p.durationMs() > 0)
    }

    @Test
    fun theColourByteIsPackedAsFlashledReadsIt() {
        // colorred = color >> 5 (3 bits), colorgreen = color >> 2 (3 bits),
        // colorblue = color & 3 (2 bits).
        assertEquals(0x00, LedPattern.colour(0, 0, 0))
        assertEquals(0xFF, LedPattern.colour(7, 7, 3))
        for (r in 0..7) for (g in 0..7) for (b in 0..3) {
            val v = LedPattern.colour(r, g, b)
            assertEquals(r, LedPattern.red(v))
            assertEquals(g, LedPattern.green(v))
            assertEquals(b, LedPattern.blue(v))
        }
        // Out of range is clamped rather than wrapped into another channel.
        assertEquals(LedPattern.colour(7, 7, 3), LedPattern.colour(9, 99, 7))
    }

    @Test
    fun aPatternDurationIsWhatTheTickEngineWouldTake() {
        // One tick is 100 ms; a group costs flashes x spacing ticks, and a
        // spacing of zero still costs one tick because the engine compares
        // after incrementing.
        val p = LedPattern.of(
            8, 2,
            LedPattern.Group(7, 0, 0, 2, 3),
            LedPattern.Group(0, 7, 0, 1, 0),
            LedPattern.Group(0, 0, 3, 1, 1)
        )
        // (2*3 + 1*1 + 1*1) = 8 ticks, twice, at 100 ms each
        assertEquals(1600, p.durationMs())
    }

    @Test
    fun theConfirmationFlashIsGreenAndEnds() {
        // The main screen's LED button sends this, so it must not be one of
        // the endless ones - walking away from a tag that keeps flashing is a
        // flat battery.
        val b = LedPattern.confirmation().encode()
        assertEquals(12, b.size)
        assertEquals(1, b[0].toInt() and 0x0F)
        assertEquals(0, LedPattern.red(b[1].toInt() and 0xFF))
        assertTrue(LedPattern.green(b[1].toInt() and 0xFF) > 0)
        assertTrue("it would never stop", (b[10].toInt() and 0xFF) < 254)
        assertTrue(LedPattern.confirmation().durationMs() in 1..3000)
    }

    @Test
    fun hwConfigBlobsAreTheSizeTheFirmwareDemands() {
        // settings_set_dynamic_screen_data() refuses anything but 93 bytes:
        // 2 + 42 + 26 + 7 + 8 + 8.
        val blob = "AB".repeat(93)
        for (name in listOf("Test HW config", "Save HW config")) {
            assertEquals(93, encode(name, blob).size)
        }
    }

    // --- hex parsing --------------------------------------------------------

    @Test
    fun hexParsingIsForgivingAboutSeparatorsButNotAboutLength() {
        assertArrayEquals(
            byteArrayOf(0xDE.toByte(), 0xAD.toByte(), 0xBE.toByte(), 0xEF.toByte()),
            Commands.hexBytes("DE AD:BE,EF")
        )
        assertEquals(0, Commands.hexBytes("").size)
        try {
            Commands.hexBytes("ABC")
            throw AssertionError("odd length should be rejected")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("even"))
        }
    }

    @Test
    fun valuesAcceptDecimalAndHex() {
        assertEquals("FF", hex(Commands.encode(listOf(Param("v", ParamType.U8)), listOf("255"))))
        assertEquals("FF", hex(Commands.encode(listOf(Param("v", ParamType.U8)), listOf("0xFF"))))
    }

    // --- catalogue sanity ---------------------------------------------------

    @Test
    fun everyDebugEntryRidesTheDebugCommand() {
        for (s in Commands.debug) {
            assertEquals("${s.name} must use BLE_CMD_DEBUG", Cmd.DEBUG, s.id)
            assertNotNull("${s.name} has no sub-command", s.sub)
        }
        for (s in Commands.tag) {
            assertTrue("${s.name} is a tag command and must not carry a sub", s.sub == null)
        }
    }

    @Test
    fun noTwoCommandsCollide() {
        val seen = HashMap<String, String>()
        for (s in Commands.tag + Commands.debug) {
            val key = "${s.id}/${s.sub}"
            val prev = seen.put(key, s.name)
            assertTrue("'${s.name}' and '$prev' both claim $key", prev == null)
        }
    }

    @Test
    fun everyDefaultValueEncodes() {
        // A default that cannot be parsed turns into a crash the first time
        // someone picks that command, so check them all up front. Required
        // fields are exempt: they have no default on purpose.
        for (s in Commands.tag + Commands.debug) {
            if (s.params.any { it.required }) continue
            try {
                Commands.encode(s.params, s.params.map { it.default })
            } catch (e: Exception) {
                throw AssertionError("'${s.name}' has a default that does not encode: ${e.message}")
            }
        }
    }

    @Test
    fun requiredFieldsRefuseToBeLeftEmpty() {
        // These are the commands that would otherwise send a zero length or a
        // placeholder payload - a wrong MAC or a malformed panel config.
        var checked = 0
        for (s in Commands.tag + Commands.debug) {
            if (s.params.none { it.required }) continue
            checked++
            try {
                Commands.encode(s.params, s.params.map { if (it.required) "" else it.default })
                throw AssertionError("'${s.name}' accepted an empty required field")
            } catch (e: IllegalArgumentException) {
                assertTrue(
                    "'${s.name}' gave an unhelpful message: ${e.message}",
                    e.message!!.contains("filled in") || e.message!!.contains("8 bytes")
                )
            }
        }
        assertTrue("no required fields found at all - the flag is not being set", checked >= 5)
    }

    @Test
    fun onlyFieldsWithoutASafeDefaultAreRequired() {
        // Marking something required that has a perfectly good default just
        // makes the screen annoying, so keep the two notions apart.
        for (s in Commands.tag + Commands.debug) {
            for (p in s.params) {
                if (p.required) assertEquals(
                    "'${s.name}' / '${p.label}' is required but also carries a default",
                    "", p.default
                )
            }
        }
    }

    @Test
    fun everyDropdownDefaultIsOneOfItsOptions() {
        for (s in Commands.tag + Commands.debug) {
            for (p in s.params) {
                val opts = p.options ?: continue
                if (p.default.isEmpty()) continue
                val d = p.default.trim().let {
                    if (it.startsWith("0x")) it.substring(2).toInt(16) else it.toInt()
                }
                assertTrue(
                    "'${s.name}' / '${p.label}': default $d is not in the option list",
                    opts.any { it.second == d }
                )
            }
        }
    }

    @Test
    fun decodersSurviveShortAndEmptyPayloads() {
        // The tag can answer with less than expected - a truncated frame, or a
        // different reply than the one the command usually gets. A decoder that
        // throws there takes the whole screen down.
        for (s in Commands.tag + Commands.debug) {
            val d = s.decode ?: continue
            for (n in listOf(0, 1, 2, 5)) {
                try {
                    d(ByteArray(n))
                } catch (e: Exception) {
                    throw AssertionError("'${s.name}' decoder threw on $n bytes: $e")
                }
            }
        }
    }

    @Test
    fun deviceTypeListCoversTheWholeEnum() {
        // DeviceType in src/settings.h runs 0..49, plus 0xFFFF for dynamic.
        val values = Commands.deviceTypes.map { it.second }
        assertEquals((0..49).toList() + 65535, values)
    }

    @Test
    fun everyCommandExplainsItself() {
        // The screen is generic, so the description is the only thing standing
        // between a command and a number nobody can aim.
        for (s in Commands.tag + Commands.debug) {
            assertTrue("'${s.name}' has barely any help", s.help.length >= 60)
            assertTrue("'${s.name}' has no category", s.category.isNotEmpty())
        }
    }

    @Test
    fun sliderRangesContainTheirDefaults() {
        // A default outside the range would open the screen with a slider that
        // means something other than the field says.
        var sliders = 0
        for (s in Commands.tag + Commands.debug) {
            for (p in s.params) {
                if (p.widget != Widget.SLIDER) continue
                sliders++
                val d = p.default.trim().let {
                    if (it.startsWith("0x")) it.substring(2).toInt(16) else it.toInt()
                }
                assertTrue(
                    "'${s.name}' / '${p.label}': default $d outside ${p.min}..${p.max}",
                    d in p.min..p.max
                )
            }
        }
        assertTrue("no sliders in the catalogue at all", sliders >= 10)
    }

    @Test
    fun switchesHaveExactlyTwoNamedValuesStartingWithOff() {
        var switches = 0
        for (s in Commands.tag + Commands.debug) {
            for (p in s.params) {
                if (p.widget != Widget.SWITCH) continue
                switches++
                val o = p.options
                assertNotNull("'${s.name}' / '${p.label}' is a switch with no options", o)
                assertEquals(
                    "'${s.name}' / '${p.label}' needs exactly two values", 2, o!!.size
                )
                assertEquals(
                    "'${s.name}' / '${p.label}': the first value must be the off one",
                    0, o[0].second
                )
            }
        }
        assertTrue("no switches in the catalogue at all", switches >= 5)
    }

    @Test
    fun theGpioListIsThePortMapTheChipDefines() {
        // GPIO_GROUPA is 0x000 and GPIO_PA0 is GROUPA | BIT(0), so PB3 is
        // 0x0108 - which is also the default the GPIO commands carry.
        assertEquals(40, Commands.gpioPins.size)
        assertEquals("PA0" to 0x0001, Commands.gpioPins.first())
        assertEquals("PE7" to 0x0480, Commands.gpioPins.last())
        assertTrue(Commands.gpioPins.contains("PB3" to 0x0108))
        assertEquals("PB3", Commands.pinName(0x0108))
        assertEquals("PA1", Commands.pinName(0x0002))
        // Anything the firmware would refuse has to read as itself rather than
        // as a made up pin name: group 5 does not exist and an empty mask is
        // rejected by every GPIO sub-command.
        assertEquals("0x0500", Commands.pinName(0x0500))
        assertEquals("0x0100", Commands.pinName(0x0100))
    }

    @Test
    fun hexFieldsWithAFixedLengthRefuseAnythingElse() {
        // settings_set_dynamic_screen_data() wants exactly 93 bytes and answers
        // a wrong length with a bare error, so the length is checked here.
        val s = spec("Test HW config")
        for (bad in listOf("AB".repeat(92), "AB".repeat(94))) {
            try {
                Commands.encode(s.params, listOf(bad))
                throw AssertionError("a wrong length should not encode")
            } catch (e: IllegalArgumentException) {
                assertTrue(e.message!!.contains("93 bytes"))
            }
        }
    }

    @Test
    fun theLoopingCommandsAreTheOnesTheFirmwareWindows() {
        assertEquals(Loop.LUT, spec("Read the panel LUT").loop)
        assertEquals(Loop.NDEF, spec("Read NDEF").loop)
        // Nothing else claims to loop, or the screen would ask repeatedly for
        // something that answers once.
        for (s in Commands.tag + Commands.debug) {
            if (s.name in listOf("Read the panel LUT", "Read NDEF")) continue
            assertEquals("'${s.name}' should not loop", Loop.NONE, s.loop)
        }
    }

    @Test
    fun everyDestructiveDebugCommandSaysSo() {
        // The ones that can cost you the tag. The note is not decoration: the
        // screen asks before arming anything that carries one.
        for (name in listOf(
            "Flash write", "Flash erase sector", "Flash unlock",
            "Memory write", "Register write", "Analog write", "GPIO set pin"
        )) {
            assertTrue("'$name' carries no warning", spec(name).danger.isNotEmpty())
        }
    }

    @Test
    fun commandsThatRebootAreMarkedAsSuch() {
        // These restart the tag, so the screen must not sit waiting for a reply.
        for (name in listOf(
            "Set device type", "Set custom MAC", "Reset settings", "Reboot",
            "Save HW config", "Deep sleep", "BLE off"
        )) {
            assertTrue("'$name' should be flagged as rebooting", spec(name).reboots)
        }
        assertTrue("'LED colour' answers with nothing", !spec("LED colour").replies)
    }
}
