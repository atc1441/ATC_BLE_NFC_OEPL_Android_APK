package io.github.atc1441.oeplnfc

/**
 * The firmware's command set and block transfer, as defined by
 * src/app_bleCmdHandle.h / src/proto.h. The numbers and the wire layout are
 * the same whether a command arrives over BLE or over NFC, because the
 * firmware dispatches both through zb_ble_hci_cmd_handler().
 */
object Cmd {
    const val LED = 1
    const val BLE_FAST = 2
    const val BLE_SLOW = 3
    const val SET_TYPE = 4
    const val GET_INFOS = 5
    const val OEPL_ENABLE = 6
    const val OEPL_DISABLE = 7
    const val OEPL_BLE_PERIOD = 8
    const val SET_CUSTOM_MAC = 9
    const val RESET_TO_DEFAULT = 10
    const val SET_UNIXTIME = 11
    const val DISABLE_UNIXTIME = 12
    const val READ_LUT = 13
    const val READ_LUT_NEXT = 14
    const val SET_DYNAMIC_TYPE_TEST = 15
    const val SET_DYNAMIC_TYPE_SAVE = 16
    const val SET_DYNAMIC_TYPE_READ = 17
    const val BLE_DISABLE = 18
    const val SET_SPEED = 19
    const val DEEPSLEEP = 20
    const val SET_LED = 21
    const val READ_DEBUG = 22
    const val REBOOT = 23
    const val SET_NDEF = 24
    const val GET_NDEF = 25
    const val CLEAR_NDEF = 26
    const val DEBUG = 27
    const val NFC_CMDS_DISABLE = 28
    const val NFC_CMDS_ENABLE = 29
    const val SET_PIN = 30
    const val UNLOCK = 31
    const val GET_PROTECTION = 32

    const val AVAILDATA = 100
    const val BLK_DATA = 101

    const val ACK_CMD = 99
    const val ERR_BLKPRT = 196
    const val ACK_BLKPRT = 197
    const val REQ = 198
    const val ACK = 199
    const val ACK_IS_SHOWN = 200
    const val ACK_FW_UPDATED = 201
    const val ACK_DYNAMIC_READ = 205
    const val ERR_FILE_TOO_BIG = 208
    const val ACK_LUT_START = 202
    const val ACK_LUT_DATA = 203
    const val ACK_LUT_DONE = 204
    const val ACK_DYNAMIC_OK = 206
    const val ACK_DYNAMIC_ERR = 207
    const val ACK_DEBUG_INFO = 209
    const val ACK_NDEF_DATA = 210
    const val ACK_DEBUG = 211
    const val ACK_PROTECTION = 212
    const val ERR = 0xFFFF
}

/**
 * Why a refusal happened, as the one byte the tag appends to ERR.
 *
 * A bare ERR says only "no": it is the same answer as an unknown command and
 * as a parameter the tag did not like. So a client that met a locked tag could
 * report nothing better than "everything was refused", when the useful
 * sentence is "this tag has a PIN". Firmware without this sends no payload,
 * and then there is simply nothing to read.
 */
object ErrReason {
    const val LOCKED = 0x01
}

/** OEPL data types, from oepl-definitions.h. */
object DataType {
    const val FW_UPDATE = 0x03
    const val IMG_RAW_1BPP = 0x20
    const val IMG_RAW_2BPP = 0x21
    const val IMG_ZLIB = 0x30

    /** Raw NDEF memory content, a Type-2 TLV container. */
    const val NFC_RAW_CONTENT = 0xA0

    /** A plain URL string the tag wraps into a URI record itself. */
    const val NFC_URL_DIRECT = 0xA1
}

/** How the firmware should interpret a BLE_CMD_SET_NDEF payload. */
object NdefSource {
    const val NONE = 0x00
    const val RAW = 0xA0   // Type-2 TLV container, same as the AP sends
    const val URL = 0xA1   // plain URL string
    const val MSG = 0xA2   // bare NDEF message
}

const val BLOCK_DATA_SIZE = 4096
const val BLOCK_PART_DATA_SIZE = 230
const val BLOCK_REQ_PARTS_BYTES = 6

/**
 * struct AvailDataInfo, little endian, 17 bytes. The checksum field is not
 * verified by the firmware on this path, so it carries the same 0xFF the web
 * tool sends.
 */
fun availDataInfo(
    dataVer: Long,
    dataSize: Int,
    dataType: Int,
    dataTypeArgument: Int = 0,
    nextCheckIn: Int = 0
): ByteArray {
    val b = ByteArray(17)
    b[0] = 0xFF.toByte()
    for (i in 0 until 8) b[1 + i] = ((dataVer shr (8 * i)) and 0xFF).toByte()
    for (i in 0 until 4) b[9 + i] = ((dataSize shr (8 * i)) and 0xFF).toByte()
    b[13] = dataType.toByte()
    b[14] = dataTypeArgument.toByte()
    b[15] = (nextCheckIn and 0xFF).toByte()
    b[16] = ((nextCheckIn shr 8) and 0xFF).toByte()
    return b
}

/**
 * struct blockData: a 16 bit length and a 16 bit sum of the payload, in front
 * of one block's worth of data. validateBlockData() in the firmware checks
 * both before the block is accepted.
 */
fun blockWithHeader(data: ByteArray, from: Int, to: Int): ByteArray {
    val n = to - from
    val out = ByteArray(4 + n)
    var sum = 0
    for (i in 0 until n) {
        val v = data[from + i].toInt() and 0xFF
        out[4 + i] = data[from + i]
        sum += v
    }
    out[0] = (n and 0xFF).toByte()
    out[1] = ((n shr 8) and 0xFF).toByte()
    out[2] = (sum and 0xFF).toByte()
    out[3] = ((sum shr 8) and 0xFF).toByte()
    return out
}

/**
 * struct blockPart: a one byte sum of everything after it, the block id, the
 * part index, and always exactly BLOCK_PART_DATA_SIZE payload bytes - the
 * firmware checksums a fixed length, so a short final part must be zero
 * padded rather than truncated.
 */
fun blockPart(blockId: Int, partIndex: Int, block: ByteArray): ByteArray {
    val out = ByteArray(3 + BLOCK_PART_DATA_SIZE)
    out[1] = blockId.toByte()
    out[2] = partIndex.toByte()
    val start = partIndex * BLOCK_PART_DATA_SIZE
    for (i in 0 until BLOCK_PART_DATA_SIZE) {
        val j = start + i
        out[3 + i] = if (j < block.size) block[j] else 0
    }
    var sum = 0
    for (i in 1 until out.size) sum += out[i].toInt() and 0xFF
    out[0] = (sum and 0xFF).toByte()
    return out
}

fun partsInBlock(blockPayloadSize: Int): Int {
    val total = 4 + blockPayloadSize   // sizeof(struct blockData) + data
    return (total + BLOCK_PART_DATA_SIZE - 1) / BLOCK_PART_DATA_SIZE
}

/**
 * The data version the tag uses to recognise content it already has. Computed
 * exactly as the web tool's computeCRC32() does, so the same image uploaded by
 * either tool is recognised as the same image: a CRC32 over the whole payload
 * with its blockData-style header in front.
 */
fun dataVersionOf(payload: ByteArray): Long {
    val framed = blockWithHeader(payload, 0, payload.size)
    return crc32(framed).toLong() and 0xFFFFFFFFL
}

private val crc32Table = IntArray(256) {
    var c = it
    repeat(8) { c = if (c and 1 != 0) (c ushr 1) xor 0xEDB88320.toInt() else c ushr 1 }
    c
}

fun crc32(data: ByteArray): Int {
    var crc = -1
    for (b in data) crc = (crc ushr 8) xor crc32Table[(crc xor b.toInt()) and 0xFF]
    return crc.inv()
}

/**
 * Drive one OEPL block transfer over NFC.
 *
 * Over BLE this protocol is notification driven; over NFC every APDU already
 * carries exactly one reply back, so the same state machine collapses into a
 * straight request/response loop. The tag decides which block it wants next,
 * which keeps retries and resumption working the way they do over the radio.
 */
fun uploadData(
    nfc: OeplNfc,
    payload: ByteArray,
    dataType: Int,
    dataTypeArgument: Int = 0,
    onProgress: (sent: Int, total: Int) -> Unit = { _, _ -> },
    onLog: (String) -> Unit = {}
) {
    val version = dataVersionOf(payload)
    onLog("AVAILDATA: %d bytes, type 0x%02X, ver %08X".format(payload.size, dataType, version))

    var reply = nfc.commandExpecting(
        Cmd.AVAILDATA,
        availDataInfo(version, payload.size, dataType, dataTypeArgument)
    )

    var blockId = -1
    var parts: List<ByteArray> = emptyList()

    /**
     * Which parts of the current block the tag asked for, and where we are in
     * that list. Normally all of them, but the tag has a say: `requestedParts`
     * in the block request is a bitmap, and after a block that failed
     * validation - or a transfer picked up again after the phone slipped - it
     * may only want some of them back.
     */
    var wanted: List<Int> = emptyList()
    var at = 0
    var retries = 0
    var firstBlock = true

    while (true) {
        when (reply.cmd) {
            Cmd.REQ -> {
                if (reply.payload.size < 17)
                    throw OeplException("truncated block request (${reply.payload.size} bytes)")
                // struct blockRequest: checksum, ver[8], blockId, type, parts[6]
                blockId = reply.payload[9].toInt() and 0xFF
                val from = blockId * BLOCK_DATA_SIZE
                if (from >= payload.size)
                    throw OeplException("tag asked for block $blockId, past the end of the data")
                val to = minOf(from + BLOCK_DATA_SIZE, payload.size)
                val block = blockWithHeader(payload, from, to)
                val count = partsInBlock(to - from)
                parts = (0 until count).map { blockPart(blockId, it, block) }
                // Bounded by the part count we worked out ourselves: the spare
                // bits of that six byte bitmap are set too, and sending a part
                // past the end of the block is a frame the tag can only reject.
                wanted = (0 until count).filter {
                    (reply.payload[11 + it / 8].toInt() shr (it % 8)) and 1 != 0
                }
                if (wanted.isEmpty()) wanted = (0 until count).toList()
                at = 0
                retries = 0
                if (firstBlock) {
                    firstBlock = false
                    if (blockId > 0) onLog(
                        "the tag still has blocks 0..${blockId - 1}, carrying on from $blockId"
                    )
                }
                onLog(
                    "block $blockId: ${wanted.size}" +
                        (if (wanted.size != count) " of $count" else "") +
                        " parts, ${to - from} bytes"
                )
                onProgress(from, payload.size)
                reply = nfc.commandExpecting(Cmd.BLK_DATA, parts[wanted[0]])
            }

            Cmd.ACK_BLKPRT -> {
                at++
                retries = 0
                if (at >= wanted.size) {
                    // Every part the tag asked for is in. What comes next is
                    // the tag's to say - another block, or the transfer done -
                    // and over NFC every answer belongs to a command, so it
                    // has to be asked for. Announcing the same content again
                    // is how: the tag answers with the next block it wants, or
                    // says it already has this content.
                    //
                    // Treating it as an error instead is what left the last
                    // block of an image hanging: a part the tag never recorded
                    // leaves the block incomplete, it keeps acknowledging
                    // parts, and the client runs out of them.
                    onLog("block $blockId delivered, asking what is next")
                    reply = nfc.commandExpecting(
                        Cmd.AVAILDATA,
                        availDataInfo(version, payload.size, dataType,
                                      dataTypeArgument)
                    )
                    continue
                }
                onProgress(
                    blockId * BLOCK_DATA_SIZE + at * BLOCK_PART_DATA_SIZE,
                    payload.size
                )
                reply = nfc.commandExpecting(Cmd.BLK_DATA, parts[wanted[at]])
            }

            Cmd.ERR_BLKPRT -> {
                // The part did not survive the air. Send it again; a frame lost
                // to a wobbling phone is normal, a wall of them is not.
                if (++retries > 8)
                    throw OeplException("part ${wanted[at]} of block $blockId keeps failing")
                onLog("part ${wanted[at]} rejected, retry $retries")
                reply = nfc.commandExpecting(Cmd.BLK_DATA, parts[wanted[at]])
            }

            Cmd.ACK -> {
                onProgress(payload.size, payload.size)
                onLog("transfer complete")
                return
            }

            Cmd.ACK_IS_SHOWN -> {
                onLog("tag already has this content, nothing to do")
                return
            }

            Cmd.ACK_FW_UPDATED -> {
                onLog("firmware written, the tag will reboot into it")
                return
            }

            Cmd.ERR_FILE_TOO_BIG ->
                throw OeplException("the tag has no room for ${payload.size} bytes")

            Cmd.ERR ->
                throw OeplException("the tag rejected the transfer")

            else -> {
                // An answer that is none of the above says nothing about
                // whether the part arrived - it is the answer itself that came
                // back mangled. That happens: over NFC the tag builds every
                // frame into its chip's FIFO a cell at a time, and ERR_BLKPRT,
                // which is 00 C4, has been seen arriving as C4 C4.
                //
                // So the part goes again, exactly as for a refusal. A
                // duplicate is answered ERR_BLKPRT and sent once more, which
                // costs one exchange; throwing the transfer away instead -
                // which is what this did - cost every part already sent, and
                // on a 15000 byte image that meant it never finished.
                if (parts.isNotEmpty() && at < wanted.size) {
                    if (++retries > 8)
                        throw OeplException(
                            "part ${wanted[at]} of block $blockId: eight " +
                                "unreadable answers in a row, last " +
                                "0x%04X".format(reply.cmd)
                        )
                    onLog("unreadable answer 0x%04X, part %d again (retry %d)"
                        .format(reply.cmd, wanted[at], retries))
                    reply = nfc.commandExpecting(Cmd.BLK_DATA, parts[wanted[at]])
                } else {
                    throw OeplException(
                        "unexpected reply 0x%04X during transfer".format(reply.cmd))
                }
            }
        }
    }
}

/**
 * The tag's live settings, as BLE_CMD_GET_INFOS reports them: the whole
 * settings_struct from src/settings.h, packed, little endian. Offsets are
 * taken from that struct and match what the web config tool parses.
 */
class TagInfo(val raw: ByteArray) {
    val version get() = raw.u32le(4)
    val hwType get() = raw.u16le(12)
    val screenType get() = raw.u16le(15)
    val screenFunctions get() = raw.u16le(17)
    private val whInversedBle get() = raw[19].toInt() and 0xFF

    // The firmware stores width and height in the order the controller family
    // wants them, and screen_w_h_inversed_ble says which way round that is.
    val width get() = if (whInversedBle == 1) raw.u16le(22) else raw.u16le(24)
    val height get() = if (whInversedBle == 1) raw.u16le(24) else raw.u16le(22)

    val colors get() = raw[30].toInt() and 0xFF
    val guiRotation get() = raw[33].toInt() and 0xFF
    val oeplEnabled get() = raw[34].toInt() != 0

    /**
     * Which second colour this panel has - red, yellow, both or none.
     *
     * [colors] alone cannot answer that: it counts them. The screen type
     * names them, so that is what gets asked first, and the count is only the
     * fallback for a tag running a hand-written configuration. Without this a
     * two-colour panel got a red plane whether or not red is what it shows.
     */
    val second get() = Panels.secondFor(screenType, colors)
    val panel get() = Panels.byType(screenType)

    /** "290 HS BWR SSD, 296x128, black/white/red", for the info pane. */
    val panelLabel get() = Panels.describe(screenType, colors)
    val mac: String
        get() = (0 until 8).reversed().joinToString(":") { "%02X".format(raw[47 + it]) }

    val name: String
        get() {
            val n = (raw[101].toInt() and 0xFF).coerceIn(0, 40)
            return String(raw, 61, n, Charsets.US_ASCII)
        }

    companion object {
        const val MIN_SIZE = 102

        fun parse(reply: OeplNfc.Reply): TagInfo {
            if (reply.payload.size < MIN_SIZE)
                throw OeplException(
                    "settings reply is ${reply.payload.size} bytes, expected at least $MIN_SIZE"
                )
            return TagInfo(reply.payload)
        }
    }
}
