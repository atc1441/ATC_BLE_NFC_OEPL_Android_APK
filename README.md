# ATC_BLE_OEPL NFC — Android app

[![Build APK](https://github.com/atc1441/ATC_BLE_NFC_OEPL_Android_APK/actions/workflows/release.yml/badge.svg)](https://github.com/atc1441/ATC_BLE_NFC_OEPL_Android_APK/actions/workflows/release.yml)

**[⬇ Download the latest APK](https://github.com/atc1441/ATC_BLE_NFC_OEPL_Android_APK/releases/latest)** — every push to `main` publishes a new signed release.

Android 5.0 (API 21) or newer, and a phone with NFC. See [BUILDING.md](BUILDING.md)
to build it yourself.

---

# ATC_BLE_OEPL NFC app

An Android app that speaks the NFC protocol implemented by
`ATC_BLE_OEPL_FW/src/nfc.c`, `nfc_t4t.c` and `nfc_ndef.c`. It reads a tag,
configures its NDEF content persistently, uploads images, updates the tag's
firmware, and runs the ordinary firmware commands — all over NFC, no BLE
pairing and no access point involved.

## The screen

The top block does not scroll. What the tag is doing - armed, working, how far
through an upload, and the last three log lines - is what you are reading while
a phone is held against a tag, and it used to be at the bottom of a page you
cannot reach with the phone in the field. Everything else is in sections that
collapse, with the two you reach for constantly open.

The line under the status says whether **this app** has the NFC reader or
Android does, which is worth saying out loud: when it does not, taps go to the
platform instead and the app looks broken for no visible reason. Tapping that
line asks for it back.

### An armed action stays armed

You arm an action and then hold the phone against the tag, so the question is
what a tap that did not work should leave behind. It used to disarm whatever
happened, which is wrong in the one case that matters: an upload is seconds of
continuous contact with a phone held on by hand, it slides and lifts, and the
next tap would then read the tag instead of finishing the upload - so you were
back at the button, again, with the phone already on the tag.

Now a failed tap costs an attempt and nothing else, and the budget counts the
right thing: **only the taps that got nowhere**. Any tap that moves the
transfer further puts the count back to zero, because a firmware image is
138 kB over a link that manages a kilobyte a second - a minute and a half of
contact, which no hand provides in one go. How many taps it has taken is not a
problem; a tap that achieved nothing twice over is. The status says the
percentage after a tap that made progress and "attempt n of m" after one that
did not, there is a **Cancel** button in the header for as long as anything is
armed, and the progress bar is not reset between taps because a resumed
transfer does not start at zero.

Only the reboot is a single shot, because a reboot that answers and then drops
the link has worked, and sending another one is not what anybody meant.

The retry only pays off if the tag keeps what it already has, and it did not:
`processAvailDataInfoBLE()` erased the slot and set `blockId` back to 0 for any
image it had not finished, so eight attempts were eight attempts at block 0.
The firmware now resumes an image the same way it has always resumed a
firmware update - **`src/app_bleCmdHandle.c`**, and the bench proves it
(`hwtest/resume_image.py`). Whole blocks that arrived stay in the slot; the one
block that was in flight is asked for again, because the buffer holding a
partial block is shared with other things on the tag and trusting it across a
gap would save one block and risk the image.

So the app does not decide where to pick up - the tag does, in its answer to
the `AVAILDATA`, and `uploadData` says which block that was. A tag running
firmware older than this still answers block 0, and the log then shows that
honestly rather than the app claiming progress it did not make.

The upload button also opens the picker when there is no image yet, instead of
saying there is no image. And picking a picture is only ever the first half of
sending one, so the upload arms itself as soon as one is in - whichever button
opened the picker.

The other half of this was in the firmware, and it was the larger half: a tap
*forces an OEPL check-in*, which is what a tap is for, but the force landed on
the first frame of the exchange and the radio scan that followed did not
service NFC at all. So an upload was cut off a second or two after it started,
every time, and a firmware update never got through. A connected BLE link had
always held that switch off; an NFC exchange in progress now does the same
(`nfc_busy()`, `src/zigbee_ble_switch.c`). The check-in still happens, just
after the exchange rather than through it.

## Build

Needs the Android SDK (platform 34) and a JDK 17.

```
echo sdk.dir=/path/to/Android/Sdk > local.properties
./gradlew :app:assembleDebug        # app/build/outputs/apk/debug/app-debug.apk
./gradlew :app:testDebugUnitTest    # wire-format tests, no device needed
```

## How it works

The tag is a **software** NFC-Forum Type-4 tag. Its NFC IC - a Fudan
FM11NT082C on the hardware this was developed against - runs ISO14443-3,
anti-collision and RATS in hardware and hands whole ISO-DEP frames to the MCU
through a **32 byte FIFO**; the firmware runs the block layer, the Type-4 file
system and the APDU layer on the MCU itself.

That FIFO is the limit that matters, and it is smaller than it looks: a frame
has to fit it in one piece. Measured against a reader, 28 bytes in is reliable,
32 - exactly a full FIFO - works about two times in three, and 36 fails. The
capability container therefore advertises **MLe 29 and MLc 20**, and the app
reads those rather than assuming anything (`readCapabilities`). Long reads and
writes are split accordingly, and the firmware chains long *responses* over
ISO-DEP by itself.

Bulk transfer does not fit in a single command, and that is handled rather
than refused. A single OEPL block part is 233 bytes, so `command()` splits a
long command across several APDUs using the firmware's staged delivery:
`P1 = 1` appends to the tag's staging buffer, `P1 = 0` dispatches what is
staged plus the last piece, and `P2` bit 0 discards anything an interrupted
attempt left behind. The ceiling is then the staging buffer - 240 bytes, an
OEPL block part plus its command id - and not the frame size.

The piece size on that channel is **150 bytes, deliberately not MLc**. MLc is
what a generic Type-4 host must honour for the NDEF file, and it is small
because it follows the chip's FIFO; `INS 0xC0` is this firmware's own channel
and reassembles chained I-blocks up to 300 bytes. Chaining is the reader's job
either way - Android's stack does it without being asked - so a larger piece
costs the same frames and one round trip instead of eight. On the bench that is
the difference between 800 and 1344 bytes a second. NDEF reads and writes are
untouched and keep honouring MLe and MLc exactly.

(The ATS the chip ships with claimed a 256 byte frame against its 32 byte
FIFO, which is why long APDUs used to lose their tail. The firmware now
corrects the chip's ATS on startup; see the firmware's `hwtest/README.md`.)

Everything goes through the Type-4 application `D2760000850101`:

| | |
|---|---|
| `SELECT` / `READ BINARY` / `UPDATE BINARY` on file `E104` | the NDEF file, the normal way any phone reads and writes a tag |
| `READ BINARY` on file `E103` | the capability container, which is where the app gets the frame sizes and the NDEF size limit from instead of guessing |
| **`INS 0xC0`** | a direct channel into the firmware's command dispatcher |

`INS 0xC0` is the interesting one. Its APDU is

```
00 C0 00 00 Lc <cmd-hi> <cmd-lo> <payload...>
```

and the firmware hands `cmd` and `payload` to `zb_ble_hci_cmd_handler()` — the
*same* dispatcher the BLE characteristic feeds. The reply the firmware would
have notified over BLE comes back as the response body, in the same
`[cmd-hi][cmd-lo][data...]` layout, followed by the status word. So every
`BLE_CMD_*` works over NFC unchanged, and nothing had to be duplicated to make
that true.

### Compressed upload

An image goes as `DATATYPE_IMG_ZLIB` by default - the same format the access
point sends, read by `decompressOneTime()` in the firmware's `drawing.c`. Over
NFC this is the difference between an upload and a wait: a 168x384 QR is 8064
bytes raw and **270 compressed**, which is 0.4 s against 50.

The part that has to be right is the window size. The tag decompresses with
uzlib into a fixed 4096 byte dictionary and refuses any stream that declares a
bigger one, while `java.util.zip.Deflater` writes a 32K window by default.
Patching the header byte would be worse than useless - the stream would be
accepted and then decompress to rubbish, because the matches really do reach
back further than the tag keeps. So `ZlibImage` feeds the deflater in 4096 byte
pieces with a `FULL_FLUSH` between them, which resets the compression state and
leaves no match pointing further back than the tag can follow.

Checked against the tag rather than assumed: `ZlibImageTest` writes the exact
bytes this code produces to a file, and `hwtest/zlib_upload.py` sends them to a
real tag, which reports back

```
Lets download a compressed image
FS: Opened compressed file with dictionary size 4096
Compressed: 266 Decompress size: 00001F86 Display Size: 00003F00
Infos Header Len: 5 Width: 168 Height: 384 BPP: 1
```

- the window it wanted, and the geometry the app wrote.

### The panel size is settled on the tap

The panel size is a text field, and a wrong one is easy to end up with: a
different tag was read last, or none was. Packing to it gives an image that is
cropped or letterboxed or simply the wrong shape, and the first you know of it
is a tag showing it.

So the upload does not send what the preview was built from. On the tap it asks
the tag for its own geometry (`GET_INFOS`), and if that disagrees it packs the
picture again against the answer before sending a byte - and updates the fields
and the preview to match, so the next one is right from the start. A second
colour plane for a panel that has only one colour is dropped the same way; the
other direction is left alone, because a black and white picture on a red
capable panel is a choice, not a mistake.

### Image upload

Over BLE the OEPL block transfer is notification driven. Over NFC each APDU
already carries exactly one reply, so the same state machine collapses into a
request/response loop (`uploadData` in `OeplProtocol.kt`):

```
AVAILDATA(struct AvailDataInfo)  ->  REQ(struct blockRequest)   tag picks the block
BLK_DATA(struct blockPart)       ->  ACK_BLKPRT                 next part
        ...                      ->  REQ                        next block
        ...                      ->  ACK                        done
```

The tag decides which block it wants, so retry and resume keep working the way
they do over the radio - and `requestedParts` in that request is honoured, not
just `blockId`, bounded by the part count the app worked out itself because the
spare bits of that six byte bitmap are set too. Each block part goes out as several staged APDUs, and
the rate is set by how fast frames turn around. The tag now spends about
2.3 ms per RF frame, so what is left is the reader's own turnaround: a USB
reader on the bench manages 1.3 kB/s, which is about 6 s for a 168×384
one-colour-plane image and under two minutes for a firmware image. A phone,
whose controller answers a frame in a millisecond or two rather than eight,
should do better. Hold it still — this is a continuous exchange, not a
one-shot write.

A firmware update is the same loop with `DataType.FW_UPDATE`, and **Pick .bin
/ Flash firmware** drives it. The file is checked here first, with exactly the
three tests the tag applies in `isFirmwareValid()` - the TLNK marker at offset
8, the length word at 0x18, and a CRC32 over everything but the trailing four
bytes that hold it (`FirmwareImage`). A wrong file would otherwise take minutes
to transfer before being rejected, and the unit tests run that check against
this repository's own `ATC_BLE_OEPL.bin` so it cannot drift from the tag's.
Measured on the bench reader: a 136 kB image in 121 s. The tag answers
`ACK_FW_UPDATED`, copies the update area over its own firmware and reboots into
it. It checks the image's TLNK marker, length and CRC32 before it touches
anything, so an interrupted transfer leaves the running firmware alone.

The packed byte format is byte for byte what the existing web tool
(`ATC_BLE_OEPL_Image_Upload.html`, `getPixelData`) produces, so an image sent
from here lands on the panel exactly as one sent from there. The easy part to
get wrong: the bit position is **not** reset at the end of a row, so on a panel
whose width is not a multiple of eight the rows run into one another.

### Persistent NDEF

Both routes in the app end up in the same flash store (`src/nfc_ndef.c`), and
so does the OEPL AP:

* **plain NDEF write** — `UPDATE BINARY`, the three-step NLEN/body/NLEN
  procedure. The firmware treats the closing NLEN write as the commit point, so
  a write from *any* generic NFC app is already persistent.
* **`BLE_CMD_SET_NDEF`** (the checkbox) — carries a Type-2 TLV container, which
  is the form the AP sends as `DATATYPE_NFC_RAW_CONTENT`. Same code path on the
  tag as a push from the AP, which is the point of offering it.

## Commands

The main screen carries the things you reach for constantly. Everything else
lives behind **All commands**, which is generated from `Commands.kt` rather
than hand-built: pick a command, fill in the fields it declares, hold the phone
against the tag.

It covers all 27 commands the firmware's dispatcher accepts and all 37
sub-commands of the debug channel, plus a raw hex box for anything newer than
the app. Each entry carries its own parameter list, so the screen knows that
`SET_TYPE`, the advertising period, the connection speed and the sensor read
take a **big endian** u16 while the clock takes a **little endian** u32 and the
whole debug channel is little endian. That inconsistency is real and in the
firmware; encoding it per parameter is what stops it becoming a silent bug.

### Generic, but not twelve numbered boxes

A parameter says what it *is*, and that is what gets built:

| what the firmware wants | what you get |
|---|---|
| a quantity with a range | a slider, with the value and its unit under it - the advertising period reads "3200 ms   every 3.2 s", the connection interval "100 units   = 125.0 ms" |
| an enumeration | a named dropdown: the 50 panel presets, the clock's two designs, the pull resistors, the GPIO functions |
| a flag or a 0xA5 guard | a checkbox, so the state is readable without opening anything |
| a GPIO pin | the port map by name, `PA0` to `PE7` - `pin = group << 8 | bitmask`, so PB3 is 0x0108 and nobody should have to know that |
| an ADC pin | only the ten the SAR ADC can reach, from `ADC_cstm_GPIO_tab` |
| a unix time | a field with a **Now** button and the date spelled out underneath, in local time, because the firmware has no timezone |
| a fixed-length blob | a byte counter that says "91 of 93 bytes" instead of letting the tag answer with a bare error |

Under the form is the exact frame that will be sent - `cmd 0x0015   12 bytes
31 E0 23 00 ...`. Half of these commands are one length or one endianness away
from a generic error reply, and seeing the bytes is how you tell which.

Every entry also carries its own description: what it does, the units and
ranges, what comes back, and the thing that will catch you out - that the
advertising period is not written to flash, that `BLE_CMD_LED` has no timer
behind it, that a clock mode of 0 is treated as 1. Commands that can cost you
the tag carry a warning of their own and ask before arming, as do the ones that
restart it.

Two commands answer in windows and are driven as sequences rather than single
shots: the panel LUT (`READ_LUT` then `READ_LUT_NEXT` until the tag says done,
230 bytes a time) and `GET_NDEF`, which this screen walks to the end rather
than leaving the offset arithmetic to whoever is holding the phone.

Fields with no safe default - a MAC, a panel configuration, the bytes of a
write - are marked required and refuse to be sent empty rather than quietly
shipping a placeholder.

### The LED pattern

`BLE_CMD_SET_LED` is twelve bytes with two values packed into most of them, and
it is the clearest case for not rendering a command as its bytes. It gets a
form of its own: three groups of flashes, each with a **colour picker** (red
and green have eight steps and blue four, because that is exactly what
`flashled()`'s time slicing can produce), a flash count and a spacing slider
that reads in milliseconds; brightness, which is really the length of one flash
at about 0.7 ms a step; and a repeat count. Underneath it says how long one run
takes and shows the twelve bytes anyway. Six presets are there to start from,
and arming is one tap on a preset.

Two details come straight out of `src/led.c` and are pinned by tests. The
engine ticks every 100 ms and a group's spacing is counted in those ticks, so
"spacing 3" is 300 ms between flashes and a spacing of 0 still costs one tick.
And `grouprepeats = ledcfg[10] + 1` in a `uint8_t` while the loop only ends
when that sum is *not* 255 - so 254 in that byte runs forever and 255 wraps to
zero and stops after a single pass. "Until cleared" sends 254; nothing in the
app can send 255.

## Files

| | |
|---|---|
| `OeplNfc.kt` | APDU transport: application select, CC, NDEF file, `INS 0xC0` |
| `OeplProtocol.kt` | command ids, `AvailDataInfo` / `blockData` / `blockPart`, the upload loop, `TagInfo` |
| `NdefCodec.kt` | URI and Text records, the Type-2 TLV container, and a decoder for the log |
| `ImagePack.kt` | bitmap → OEPL plane format, with Floyd-Steinberg matching the web tool |
| `MainActivity.kt` | the main screen |
| `Commands.kt` | the command catalogue: ids, parameters, endianness, reply decoders |
| `CommandActivity.kt` | the generated form that drives all of them |

## Tests

```
./gradlew :app:testDebugUnitTest        # 90 tests, no device needed
./gradlew :app:connectedDebugAndroidTest  # 11 tests, needs a device or emulator
```

The unit tests pin the wire formats: the block transfer against the firmware's
own `checkCRC` and `validateBlockData`, the image packing (including the bit
counter that deliberately runs across row boundaries), the encoding of every
parameterised command, how a long command is cut up for staged delivery -
where the rule that matters is that the final piece is the one that dispatches
and is never empty - the firmware image check, against the real
`ATC_BLE_OEPL.bin` next door as well as against single flipped bytes, and the
compressed container, round tripped through a standard inflater and pinned to
the 4096 byte window the tag can hold.

They also pin the parameter model itself, because it is now the thing that
decides what the screen offers: every slider's default inside its range, every
switch exactly two values with the off one first, the GPIO list against the
chip's own `GPIO_GROUPA | BIT(n)` encoding, every command carrying a
description, and the LED pattern byte for byte against `setLedBuffer()` -
including the repeat count's wraparound, which is the one value in the whole
command that does the opposite of what it says.

The instrumented tests cover what a unit test cannot see. Gesture routing is
one: the preview sits in a `ScrollView` that will otherwise claim any drag, and
`adb shell input` cannot even express a pinch. The other is the command screen,
which builds over sixty forms at runtime - every one of them gets selected once,
because a single bad default only shows up when that one command is picked - and
four of them check that the right *kind* of control came up: that the LED
pattern is sliders rather than boxes, that a range is a slider that says what it
means, that a 0xA5 guard is a checkbox, and that a pin parameter is forty named
pins.

## Caveats

The protocol is exercised against real hardware, with an Elatec TWN4 reader
standing in for the phone: application select, the capability container, NDEF
read and write with read-back, the `INS 0xC0` command channel including a 176
byte chained response, every command the dispatcher accepts - including the
ones that reboot the tag, change its MAC or wipe its configuration - an image
upload verified byte for byte against the tag's own flash, and a firmware
update. See `ATC_BLE_OEPL_FW/hwtest/`, in particular `test_cmds_all.py`,
`test_cmds_risky_nfc.py`, `verify_image_nfc.py` and `test_fw_nfc.py`.

What has *not* been through a phone is this app's own NFC stack - the Android
side is still only as good as its unit tests.

If a tap does nothing, the tag's UART log is the place to look. `NFC session
start` followed by no frames means the exchange is failing below the APDU
layer, which is the firmware's problem rather than this app's. Two things that
look like app bugs and are not: a reader or phone left sitting on the tag
interrupts it several times a second and crowds out everything else, and the
tag advertises over BLE only about every thirteen seconds, so a short scan can
miss it entirely.

The app arms an action and then waits for a tap, instead of offering a
connect-now-act-later model. That is not a shortcut: a tag is only reachable
while it is in the field, so there is no honest way to present it otherwise.
