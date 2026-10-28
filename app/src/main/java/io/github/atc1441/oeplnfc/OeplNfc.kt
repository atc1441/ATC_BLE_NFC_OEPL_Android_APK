package io.github.atc1441.oeplnfc

import android.nfc.tech.IsoDep

/**
 * The ATC_BLE_OEPL tag's NFC interface, as implemented by src/nfc_t4t.c in the
 * firmware.
 *
 * The tag is a software NFC-Forum Type-4 tag: the IC does the RF layer and
 * hands whole ISO-DEP frames to the MCU, which runs the block protocol, the
 * Type-4 file system and the APDU layer itself. Its FIFO holds 32 bytes and
 * that is what its ATS advertises as the frame size, so a long APDU travels as
 * several chained frames - Android's ISO-DEP stack does that fragmentation for
 * us, so here we just send and receive whole APDUs.
 *
 * A frame size is not a command size, though. MLc in the capability container
 * is what a generic Type-4 host must honour for the NDEF file, and it is small
 * because it follows that FIFO; INS 0xC0 is this firmware's own channel and
 * reassembles chained frames up to 300 bytes. [command] therefore splits a
 * long command into APDUs of the size the tag's MLc allows and hands them over
 * with the firmware's staged delivery, which is what lets an image or a
 * firmware image be uploaded over NFC at all. Every one of those APDUs fits a
 * single frame on purpose - see [STAGE_PIECE].
 *
 * Two things live behind the Type-4 application:
 *   * the standard NDEF file (0xE104), read and written the normal way, which
 *     is also what any other phone sees on a plain tap;
 *   * INS 0xC0, a direct channel into the firmware's command dispatcher - the
 *     very same one the BLE characteristic feeds. Every BLE command therefore
 *     works over NFC unchanged, including the OEPL image upload.
 */
class OeplNfc(private val iso: IsoDep) {

    class Apdu(val sw: Int, val data: ByteArray) {
        val ok get() = sw == SW_OK
    }

    /**
     * A reply captured from the firmware's command dispatcher: the two-byte
     * command id it answered with, and whatever followed it. Identical in
     * layout to a BLE notification, because it is the same code path.
     */
    class Reply(val cmd: Int, val payload: ByteArray) {
        override fun toString() =
            "cmd=0x%04X %s".format(cmd, payload.toHex())
    }

    companion object {
        const val SW_OK = 0x9000

        // The tag refusing a staged piece that does not follow the one
        // before it, because an earlier piece never arrived.
        private const val SW_OUT_OF_ORDER = 0x6A86

        // The tag saying a command did not match the check byte sent with it.
        private const val SW_BAD_COMMAND = 0x6A80

        /**
         * The fold the firmware runs over a command's bytes, and the seven bit
         * value derived from it that rides in the upper bits of P2 on the
         * dispatch.
         *
         * Nothing between here and the tag's flash carried a check before.
         * Each piece of a staged command is acknowledged 9000 whether or not
         * its bytes survived, and about one exchange in ten over that chip's
         * bus does not - so a byte that changed on the way was simply obeyed.
         * A ninety three byte configuration command came back "unknown
         * command" on the bench, which is what happens when the two bytes
         * naming the command are the ones that changed; had they changed into
         * another command's number, the tag would have carried that one out.
         *
         * Must agree byte for byte with t4t_cmd_digest() in src/nfc_t4t.c.
         */
        fun cmdDigest(data: ByteArray): Int {
            var h = 0x1F35
            for (k in data.indices) {
                h = ((h shl 3) xor (h ushr 13) xor (data[k].toInt() and 0xFF) xor k) and 0xFFFF
            }
            return h
        }

        /** Never zero: zero is how a host says it sent no check at all. */
        fun checkByte(data: ByteArray): Int = cmdDigest(data) % 127 + 1

        private val AID = byteArrayOf(
            0xD2.toByte(), 0x76, 0x00, 0x00, 0x85.toByte(), 0x01, 0x01
        )
        private const val FILE_CC = 0xE103
        private const val FILE_NDEF = 0xE104

        private const val INS_SELECT = 0xA4
        private const val INS_READ = 0xB0
        private const val INS_UPDATE = 0xD6
        private const val INS_COMMAND = 0xC0

        // Staged delivery on INS 0xC0, as src/nfc_t4t.c implements it:
        //   P1 = 0x01  append this APDU's data to the staging buffer
        //   P1 = 0x00  dispatch what is staged plus this APDU's data
        //   P2 bit 0   discard anything staged first, for a clean start
        private const val P1_STAGE = 0x01
        private const val P1_DISPATCH = 0x00
        private const val P2_RESET = 0x01

        // P1 bit 1 on the dispatch asks the tag not to chain a long reply at
        // the block layer but to answer with one numbered piece and status
        // 61xx, the rest fetched with INS 0xC0, P2 = piece number.
        //
        // Chaining does not survive on this tag's NFC chip. A frame has to be
        // built into the chip's FIFO a cell at a time - two bus accesses per
        // byte, because a write fills every free cell with its first byte so
        // exactly one cell must be free - and the chip gives its contactless
        // side priority over that bus, so roughly one access in four hundred
        // silently does nothing while still being acknowledged. A 178 byte
        // reply needs some 434 of them, which caps it near a third of
        // attempts however carefully the frame is assembled; and it cannot be
        // checked afterwards either, because no register returns FIFO data
        // without popping it, so a check consumes what it would confirm.
        //
        // The same reply in numbered single frames, each of a length 61xx
        // states and each individually repeatable, goes through: measured on
        // the bench, 0 of 16 chained against 19 of 20 complete acceptance runs
        // this way.
        private const val P1_CHUNKED = 0x02

        // P1 bit 2 asks the tag to put a two byte check on the end of its
        // answer, computed the same way as the one the command carries in.
        // Without it a reply is taken on trust: a 176 byte settings read came
        // back with two fields holding a value neither of them can take, and
        // nothing noticed - the pieces are checked for length, and a byte
        // that changes on the way keeps the length and looks like data.
        private const val P1_DIGEST = 0x04

        // The largest piece a reply can arrive in, which is set by the air
        // frame and is not an agreement with the firmware. A frame carries a
        // PCB, the data and a status word inside an FSC of 32 that includes
        // the two check bytes the chip appends, so 27 is the ceiling.
        //
        // This used to be an agreement - a constant here that had to equal
        // T4T_CHUNK in src/nfc_t4t.c exactly, or every long reply was thrown
        // out for being the wrong length, in two clients and the firmware.
        // How big a piece is, is the tag's to choose, and it says so in the
        // first one: its status word promises more, so it is a full piece by
        // definition. Read it there and the firmware's frame size can be
        // tuned without shipping an app to match it.
        private const val REPLY_PIECE_MAX = 27

        // A piece that comes back the wrong length is one the chip mangled on
        // the way out, so it is asked for again rather than accepted.
        private const val PIECE_TRIES = 5

        // How often one APDU of a staged command is sent before giving up on
        // the command. See caseThreeRetrying.
        // Six, not three. Each one costs a frame or two against a link that
        // loses about one exchange in ten on the slow panel, and a staging
        // piece given up on costs the whole command - eleven frames - so the
        // cheap thing to do is ask again.
        private const val APDU_TRIES = 6

        // How often a whole command is staged again after the tag refuses the
        // body it assembled. See command(): a refusal means the command did
        // not run, so repeating it is safe, and eleven frames is a fifth of a
        // second against an abandoned transfer.
        // Six. This is also the budget for re-opening a link the stack
        // declared lost (see recover()), and on the reader that struggles
        // that happens often enough that four was the difference between a
        // command getting through and the tap ending.
        private const val COMMAND_TRIES = 6

        // How long to wait for one exchange. Set by the panel refresh at the
        // end of an image upload - see open().
        private const val SLOW_COMMAND_MS = 30000

        // Fallbacks if the capability container cannot be read. They match what
        // the firmware's CC advertises, which is derived from the NFC chip's
        // 32 byte FIFO: MLe 29, MLc 20. One frame has to fit that FIFO, and a
        // received frame carries [PCB][CLA INS P1 P2 Lc][data][CRC16].
        private const val DEFAULT_READ_CHUNK = 29 - 2
        private const val DEFAULT_WRITE_CHUNK = 20 - 6

        // The firmware's staging buffer: an OEPL block part (233 bytes) plus
        // its two command id bytes, rounded up.
        const val STAGE_MAX = 240

        // How much of a long command goes in one APDU: the tag's own MLc,
        // read from its capability container, and never more.
        //
        // This used to be 150 on the reasoning that Android chains a long
        // APDU into frames by itself, so a bigger piece costs no more frames
        // and saves round trips. The frames are not the question. Measured
        // against this tag, every command that crossed the one-frame boundary
        // failed - at 22 bytes and at 140, with either block number in the
        // acknowledgement - while everything inside one frame went through:
        // the reader sends its first chained block, is acknowledged, and
        // sends no more. A piece that fits one frame is therefore not a
        // cautious choice here but the only one that works, and MLc is the
        // tag saying what that is. [STAGE_PIECE] remains as the fallback for
        // a tag whose capability container could not be read.
        const val STAGE_PIECE = DEFAULT_WRITE_CHUNK + 6 - 2

        /**
         * How a command body of [size] bytes is cut up for a tag that accepts
         * [limit] bytes per APDU: the offsets of the pieces to stage, and
         * then the offset of the piece that dispatches.
         *
         * The last piece is never empty and never skipped - it is the one
         * carrying `P1 = 0`, which is what makes the tag act on the command -
         * so a body that divides exactly still ends with a full final piece
         * rather than a zero length dispatch.
         */
        fun stageSlices(size: Int, limit: Int): Pair<List<Int>, Int> {
            require(limit > 0) { "limit must be positive" }
            val staged = ArrayList<Int>()
            var off = 0
            while (size - off > limit) {
                staged.add(off)
                off += limit
            }
            return Pair(staged, off)
        }

    }

    private var readChunk = DEFAULT_READ_CHUNK
    private var selectedFile = 0
    // Whether a link is being made again right now, so that a command run as
    // part of that cannot ask for another recovery.
    private var recovering = false

    /**
     * Largest command payload MLc would allow in a single APDU, i.e. MLc less
     * the two command id bytes. Informational: [command] splits a long command
     * itself, in pieces of this size, and what it cannot exceed is the tag's
     * staging buffer.
     */
    var maxCommandPayload = DEFAULT_WRITE_CHUNK + 6 - 2
        private set

    /** Longest NDEF file the tag will accept, from its capability container. */
    var maxNdefSize = 0
        private set

    fun open() {
        // Long enough for the longest thing the tag does with a command still
        // in hand.
        //
        // Five seconds was not. A flash write stalls the tag for tens of
        // milliseconds, which five seconds covers easily - but the last part
        // of an image writes the header and then refreshes the panel, and on
        // a 4.2" that takes about eighteen seconds. The tag asks for the extra
        // time properly, with S(WTX), and the phone's controller grants it
        // without the app seeing any of it; what the app does control is how
        // long it is prepared to wait, and at five seconds it would have given
        // up on the one exchange that finishes the transfer.
        //
        // The firmware asks for 59 times its announced frame waiting time,
        // which is about 73 seconds, so this is the shorter of the two and
        // therefore the one that decides.
        iso.timeout = SLOW_COMMAND_MS
        if (!iso.isConnected) {
            // The same translation as transceive(): a tag that was there when
            // the callback started can be gone by the time the connection is
            // made, and that is the end of the tap rather than a fault to
            // report and retry around.
            try {
                iso.connect()
            } catch (e: android.nfc.TagLostException) {
                throw OeplTagLost("the tag left before the link was open")
            } catch (e: java.io.IOException) {
                throw OeplTagLost("the tag could not be opened: ${e.message}")
            }
        }
    }

    fun close() {
        try {
            iso.close()
        } catch (_: Exception) {
        }
    }

    // --- raw APDU -----------------------------------------------------------

    fun transceive(apdu: ByteArray): Apdu {
        // Checked before the exchange as well as caught after it: the stack
        // notices the carrier going away by itself, and asking first costs
        // nothing against a transceive that would block.
        if (!iso.isConnected) throw OeplTagLost("the tag is no longer in the field")
        val r = try {
            iso.transceive(apdu)
        } catch (e: android.nfc.TagLostException) {
            throw OeplTagLost("the tag left the field")
        } catch (e: java.io.IOException) {
            // A dead link reports itself as an IO error about as often as it
            // reports itself properly, so the connection is the thing to ask.
            if (!iso.isConnected) throw OeplTagLost("the tag left the field")
            throw e
        }
        if (r.size < 2) throw OeplException("short response (${r.size} bytes)")
        val sw = ((r[r.size - 2].toInt() and 0xFF) shl 8) or (r[r.size - 1].toInt() and 0xFF)
        return Apdu(sw, r.copyOfRange(0, r.size - 2))
    }

    private fun caseThree(ins: Int, p1: Int, p2: Int, data: ByteArray): Apdu {
        if (data.size > 255) throw OeplException("APDU data too long (${data.size})")
        val a = ByteArray(5 + data.size)
        a[0] = 0x00
        a[1] = ins.toByte()
        a[2] = p1.toByte()
        a[3] = p2.toByte()
        a[4] = data.size.toByte()
        data.copyInto(a, 5)
        return transceive(a)
    }

    // --- Type-4 application -------------------------------------------------

    /**
     * Open the link again and select the application, after the stack said the
     * tag had gone.
     *
     * "The tag left the field" is mostly not true. Android reports a tag as
     * lost when an exchange does not come back in time, and on this hardware a
     * late answer is ordinary: the NFC chip in the 400x300 panel fills every
     * free cell of its FIFO with the first byte of a write, so every answer is
     * assembled a cell at a time and about one in ten has to be assembled
     * twice, which puts it tens of milliseconds out. The phone is still being
     * held against the tag the whole time.
     *
     * Treating that as the end of the tap is what made sustained traffic
     * impossible while a plain read worked. A read is a handful of exchanges
     * and usually survives; an image upload is sixty-odd parts, and at one
     * spurious tag-lost in ten exchanges it essentially never finished - which
     * is exactly how it looked from the outside, "only ever sparse data, the
     * communication breaks off straight away".
     *
     * So the link is simply made again. Whatever was staged on the tag is gone
     * with the session, so the caller has to start its command over - which
     * is what [command] already does for a refused command, and the reason
     * this is used from there rather than from inside one exchange.
     *
     * Returns false when the tag really has gone, which ends the tap as
     * before.
     */
    fun recover(): Boolean {
        if (recovering) return false
        recovering = true
        return try {
            try {
                iso.close()
            } catch (_: Exception) {
            }
            iso.timeout = SLOW_COMMAND_MS
            iso.connect()
            selectedFile = 0
            selectApplication()
            // Nothing to unlock. A new NFC session is not a shut tag any
            // more: the PIN does not guard this way in.
            true
        } catch (_: Exception) {
            false
        } finally {
            // Cleared last, so nothing inside the recovery can start another
            // one: command() asks recover() for help, and recover() runs
            // commands.
            recovering = false
        }
    }

    /** Select the NDEF application. Must succeed before anything else works. */
    fun selectApplication() {
        val r = caseThree(INS_SELECT, 0x04, 0x00, AID)
        if (!r.ok) throw OeplException("SELECT application failed, SW=%04X".format(r.sw))
        selectedFile = 0
    }

    private fun selectFile(fid: Int) {
        if (selectedFile == fid) return
        val r = caseThree(
            INS_SELECT, 0x00, 0x0C,
            byteArrayOf((fid shr 8).toByte(), (fid and 0xFF).toByte())
        )
        if (!r.ok) throw OeplException("SELECT file %04X failed, SW=%04X".format(fid, r.sw))
        selectedFile = fid
    }

    private fun readBinary(offset: Int, len: Int): ByteArray {
        val r = transceive(
            byteArrayOf(
                0x00, INS_READ.toByte(),
                (offset shr 8).toByte(), (offset and 0xFF).toByte(),
                len.toByte()
            )
        )
        if (!r.ok) throw OeplException("READ BINARY failed, SW=%04X".format(r.sw))
        return r.data
    }

    /**
     * Read the capability container and adopt the frame sizes and the NDEF
     * file limit it advertises, instead of guessing them.
     */
    fun readCapabilities() {
        selectFile(FILE_CC)
        val head = readBinary(0, 15)
        if (head.size < 15) throw OeplException("short capability container")
        val mle = head.u16(3)
        val mlc = head.u16(5)
        if (mle > 2) readChunk = (mle - 2).coerceAtMost(255)
        if (mlc > 2) maxCommandPayload = (mlc - 2).coerceAtMost(255)
        maxNdefSize = head.u16(11)
    }

    /** The NDEF message currently served by the tag, without the NLEN field. */
    fun readNdef(): ByteArray {
        selectFile(FILE_NDEF)
        val nlen = readBinary(0, 2)
        if (nlen.size < 2) throw OeplException("short NLEN")
        val total = nlen.u16(0)
        if (total == 0) return ByteArray(0)
        val out = ByteArray(total)
        var done = 0
        while (done < total) {
            val n = minOf(readChunk, total - done)
            val part = readBinary(2 + done, n)
            if (part.isEmpty()) throw OeplException("NDEF read stalled at $done/$total")
            part.copyInto(out, done)
            done += part.size
        }
        return out
    }

    /**
     * The NDEF message, read until two reads agree.
     *
     * A single read can come back wrong and look right. About one exchange in
     * ten over this tag's NFC chip does not survive whole, and a frame that
     * loses a byte has it replaced rather than dropped - a message came back
     * as 64 64 64 64 where 6E 6D 6F 64 was stored, with the right length and a
     * valid record header, so nothing in the message says it is wrong.
     * Neither the NDEF file nor the staged command that wrote it carries a
     * checksum, so the only thing that can tell a good read from a bad one is
     * another read.
     *
     * Used where the answer is acted on - confirming a configuration that was
     * just written - rather than on every tap.
     */
    fun readNdefAgreed(tries: Int = 4): ByteArray {
        var last: ByteArray? = null
        repeat(tries) {
            val got = readNdef()
            if (last != null && got.contentEquals(last!!)) return got
            last = got
        }
        throw OeplException("two reads of the NDEF never agreed")
    }

    // There is no NDEF write here on purpose.
    //
    // UPDATE BINARY on the NDEF file comes back 6985: the firmware keeps that
    // file read-only to a reader and takes a new message only through
    // BLE_CMD_SET_NDEF, so that the staging, the deferred flash write and the
    // source byte in the stored header all live in one place rather than two
    // paths into the same flash sector, one of them open to any phone held
    // against the tag. MainActivity.writeNdefConfig sends that command, over
    // this same channel. A writeNdef() here would be a method that cannot
    // work, and its three-step NLEN procedure described a tag this is not.

    // --- direct command channel ---------------------------------------------

    /**
     * Run one firmware command. [cmd] is a BLE_CMD_* id; the reply is whatever
     * the firmware notified in response, or null if it stayed silent (several
     * commands acknowledge by doing nothing but succeeding).
     */
    fun command(cmd: Int, payload: ByteArray = ByteArray(0)): Reply? {
        // A refusal is not a failure, it is a command that did not run.
        //
        // The tag checks every staged command against a check byte computed
        // over the same bytes, because a byte can change between its NFC
        // chip's FIFO and its firmware - two bus accesses per byte, and the
        // chip gives its contactless side priority over that bus. When the
        // check fails the tag refuses, which is the right thing to do with a
        // command it cannot trust; what was wrong was this side treating it as
        // the end of the transfer. An upload then stopped at the same place
        // every time, which is exactly what it looked like from outside.
        //
        // Staging the command again costs eleven frames, about a fifth of a
        // second, and cannot run anything twice: a refused command never ran.
        var refused: Exception? = null
        for (attempt in 0 until COMMAND_TRIES) {
            try {
                val r = commandOnce(cmd, payload)
                // No question about locks here any more. The PIN guards
                // BLE only, and these commands go over NFC, which a PIN
                // never shuts - see cmd_lock.h in the firmware. What
                // used to be here was an unlock-and-retry on a refusal,
                // and it belonged to a design where NFC had a PIN of its
                // own; keeping it would mean the app asking the tag
                // about a lock the tag no longer applies to this way in.
                return r
            } catch (e: OeplTagLost) {
                // A lost tag is retried like a refusal, because most of the
                // time it is not lost - see recover(). The session is gone
                // either way, so the command is staged again from its first
                // piece, which is what this loop already does.
                //
                // Bounded by the same budget: if the link cannot be made
                // again, or the last attempt is used up, the tap ends here as
                // it always did.
                refused = e
                if (attempt == COMMAND_TRIES - 1 || !recover()) throw e
            } catch (e: OeplRefused) {
                refused = e
            }
        }
        throw refused ?: OeplException("command 0x%04X: refused".format(cmd))
    }

    private fun commandOnce(cmd: Int, payload: ByteArray = ByteArray(0)): Reply? {
        val body = ByteArray(2 + payload.size)
        body[0] = (cmd shr 8).toByte()
        body[1] = (cmd and 0xFF).toByte()
        payload.copyInto(body, 2)

        // The tag cannot hold more than its staging buffer, and an OEPL block
        // part is the largest thing anyone sends, so this is a real ceiling
        // rather than a conservative guess.
        if (body.size > STAGE_MAX) {
            throw OeplException(
                "command 0x%04X carries %d bytes, more than the %d the tag can "
                .format(cmd, body.size, STAGE_MAX) +
                "stage over NFC. Use BLE for anything larger."
            )
        }

        // One APDU if it fits; otherwise hand it over in MLc-sized pieces and
        // let the last one dispatch the lot. The first piece also clears
        // whatever an interrupted attempt may have left staged.
        // Each piece is sent until it is acknowledged, and so is the
        // dispatch. Every exchange over this tag's NFC chip is built into a
        // 32 byte FIFO a cell at a time, two bus accesses per byte, and the
        // chip gives its contactless side priority over that bus - so an
        // exchange now and then does not complete, or comes back with its
        // answer mangled. A block part takes a dozen of them, and without
        // repeating the ones that fail a long command rarely arrives whole.
        val limit = if (maxCommandPayload > 0) maxCommandPayload else STAGE_PIECE
        val (staged, dispatchAt) = stageSlices(body.size, limit)
        staged.forEachIndexed { i, off ->
            val piece = body.copyOfRange(off, minOf(off + limit, body.size))
            // The piece number rides in the rest of P2, counted from one, so
            // a piece whose acknowledgement went missing can be sent again
            // without being appended twice. About one exchange in ten over
            // this tag's chip does not complete and a block part takes
            // fourteen of them, so a command that cannot tolerate a repeated
            // piece cannot be delivered at all - and an unnumbered repeat
            // corrupts it silently, which is worse than losing it.
            val p2 = ((i + 1) shl 1) or (if (i == 0) P2_RESET else 0x00)
            // A status word other than 9000 here is almost never a refusal.
            // The tag either took the piece or it did not; what comes back
            // wrong is the answer, and SW 0000 is what a mangled one looks
            // like. So the piece goes again - which its number makes free of
            // consequence, because the tag appends nothing for a piece it
            // already has. Treating it as fatal threw away the eleven pieces
            // already delivered, and an image upload never finished.
            var ack: Apdu? = null
            for (attempt in 0 until APDU_TRIES) {
                val got = try {
                    caseThree(INS_COMMAND, P1_STAGE, p2, piece)
                } catch (e: OeplTagLost) {
                    // The link went, not the piece. Make it again and send
                    // this same piece once more.
                    //
                    // Throwing here abandoned the whole command, and on the
                    // reader that struggles that was the end of every block
                    // part: thirteen pieces and a dispatch have to land in a
                    // row, a session drop in the middle took the twelve
                    // already delivered with it, and the next attempt started
                    // at piece one and met the same wall. The tag now keeps
                    // what it has staged across a session, and the pieces are
                    // numbered - sending this one again is either appended or
                    // recognised as the repeat it is. So the part carries on
                    // where it stopped, eighteen bytes at a time.
                    if (!recover()) throw e
                    null
                } catch (e: Exception) {
                    null
                }
                if (got != null && got.ok) {
                    ack = got
                    break
                }
                if (got != null && got.sw == SW_OUT_OF_ORDER) {
                    // A piece before this one went missing, so the tag threw
                    // the lot away rather than leave a hole. Sending this one
                    // again cannot help - it is out of order every time - so
                    // the command has to start over from the first piece.
                    throw OeplException(
                        "command 0x%04X: piece %d arrived out of order"
                            .format(cmd, i + 1)
                    )
                }
            }
            if (ack == null) throw OeplException(
                "command 0x%04X: staging piece %d was never acknowledged"
                    .format(cmd, i + 1)
            )
        }
        // And the dispatch the same way: a status word that is neither 9000
        // nor 61xx is a mangled answer rather than an answer. Repeating it can
        // run the command twice, which every command long enough to be staged
        // survives: a duplicate OEPL block part is refused with ERR_BLKPRT and
        // sent again, and an AvailDataInfo, an NDEF message or a panel type
        // gives the same result the second time.
        var data: ByteArray? = null
        var last: Exception? = null
        for (attempt in 0 until APDU_TRIES) {
            try {
                val r = caseThree(INS_COMMAND,
                                  P1_DISPATCH or P1_CHUNKED or P1_DIGEST,
                                  checkByte(body) shl 1,
                                  body.copyOfRange(dispatchAt, body.size))
                if (r.sw == SW_BAD_COMMAND) {
                    // The command did not match its check byte, so the tag
                    // refused it rather than obey something it was not given,
                    // and threw the staged pieces away with it. Another
                    // dispatch cannot help - the staged bytes are gone - so
                    // this goes up to command(), which stages the whole thing
                    // again from the first piece.
                    throw OeplRefused(
                        "command 0x%04X arrived corrupted and was refused"
                            .format(cmd))
                }
                val whole = collectReply(cmd, r)
                if (whole.size >= 2) {
                    val body2 = whole.copyOfRange(0, whole.size - 2)
                    val h = cmdDigest(body2)
                    val want = byteArrayOf((h shr 8).toByte(), h.toByte())
                    if (!whole.copyOfRange(whole.size - 2, whole.size)
                            .contentEquals(want))
                        throw OeplException(
                            "command 0x%04X: the answer did not match its own check"
                                .format(cmd))
                    data = body2
                } else {
                    data = whole
                }
                break
            } catch (e: OeplTagLost) {
                // Same reasoning as the staging loop, and it matters more
                // here: everything is already staged on the tag, and letting
                // this go up to command() would stage all of it again from
                // the first piece with P2_RESET - throwing away the very work
                // that just survived the link going down.
                last = e
                if (!recover()) throw e
            } catch (e: Exception) {
                last = e
            }
        }
        if (data == null) throw last
            ?: OeplException("command 0x%04X: no answer".format(cmd))
        if (data.size < 2) return null
        val rc = ((data[0].toInt() and 0xFF) shl 8) or (data[1].toInt() and 0xFF)
        return Reply(rc, data.copyOfRange(2, data.size))
    }

    /**
     * Put a reply together from the pieces the tag hands out.
     *
     * A short reply arrives whole with 9000 and this does nothing. A long one
     * arrives as a first piece with 61xx, where xx is how much is still to
     * come, and the rest are fetched by number. Each piece is measured against
     * the length 61xx implies and asked for again if it does not match, which
     * is what makes the transfer whole rather than merely likely: a piece that
     * leaves the chip with a byte doubled is caught here instead of silently
     * displacing the rest of the reply.
     */
    private fun collectReply(cmd: Int, first: Apdu): ByteArray {
        if (first.ok) return first.data
        if ((first.sw and 0xFF00) != 0x6100) {
            throw OeplException("command 0x%04X rejected, SW=%04X".format(cmd, first.sw))
        }
        // The first piece's length is the piece size. A corrupted first piece
        // could name the wrong one and the rest would be assembled against it,
        // which the reply digest catches: it covers the whole reply.
        val pieceSize = first.data.size
        if (pieceSize < 1 || pieceSize > REPLY_PIECE_MAX) {
            throw OeplException(
                "command 0x%04X: first piece is %d bytes, which no frame can carry"
                    .format(cmd, pieceSize)
            )
        }

        val out = java.io.ByteArrayOutputStream()
        out.write(first.data)
        var remain = first.sw and 0xFF
        var index = 1
        while (remain > 0 && index < 40) {
            val want = if (remain > pieceSize) pieceSize else remain
            var piece: Apdu? = null
            for (attempt in 0 until PIECE_TRIES) {
                val got = try {
                    transceive(byteArrayOf(0x00, INS_COMMAND.toByte(), 0x00,
                                           index.toByte(), 0x00))
                } catch (_: Exception) {
                    continue
                }
                val sane = got.ok || (got.sw and 0xFF00) == 0x6100
                if (sane && got.data.size == want) {
                    piece = got
                    break
                }
            }
            val p = piece ?: throw OeplException(
                "command 0x%04X: piece %d never arrived whole".format(cmd, index)
            )
            out.write(p.data)
            remain = if ((p.sw and 0xFF00) == 0x6100) p.sw and 0xFF else 0
            index++
        }
        return out.toByteArray()
    }

    /** Like [command] but insists on a reply. */
    fun commandExpecting(cmd: Int, payload: ByteArray = ByteArray(0)): Reply =
        command(cmd, payload) ?: throw OeplException("no reply to command 0x%04X".format(cmd))

    // There was an unlockIfNeeded() here, and a PIN field in the app
    // feeding it. Both are gone.
    //
    // The PIN guards BLE, not NFC. A tag that
    // should not take commands from a passing phone has its NFC command
    // channel switched off instead - persistent, and only switchable
    // back on over BLE, which is a far stronger thing than
    // a PIN and much simpler to be sure of.
    //
    // Two transports having to agree about one lock is what never
    // worked: the state had to be asked for over a link that loses an
    // exchange in ten, and a lost answer read as "not locked". The
    // firmware refuses SET_PIN and UNLOCK over NFC outright now, so
    // there is nothing left here to do even if this wanted to.
}

class OeplException(message: String) : Exception(message)

/**
 * The tag refused a staged command because the body it assembled did not match
 * the check byte sent with it.
 *
 * Its own class because it means something very specific: the command did not
 * run. Every byte of it is still on this side, nothing on the tag changed, and
 * sending it again is therefore free of consequence - which is not true of a
 * command whose answer merely went missing. Told apart, it costs eleven frames
 * to recover from; lumped in with everything else, it cost an upload.
 */
class OeplRefused(message: String) : Exception(message)

/**
 * The tag has left the field.
 *
 * Its own class because every retry in this file is wrong once it is thrown:
 * retrying is for an exchange that failed on a tag that is still there, and
 * against one that has gone it only burns time. Burning time is the whole
 * problem on a phone with marginal coupling - the reader callback runs on the
 * NFC dispatch thread, and while it is retrying, the next tap cannot be
 * delivered. A phone with a weak field spent about ten seconds refusing to
 * notice a tag being held against it again, which is exactly when the person
 * is trying hardest.
 */
class OeplTagLost(message: String) : Exception(message)

internal fun ByteArray.u16(i: Int) =
    ((this[i].toInt() and 0xFF) shl 8) or (this[i + 1].toInt() and 0xFF)

internal fun ByteArray.u16le(i: Int) =
    ((this[i + 1].toInt() and 0xFF) shl 8) or (this[i].toInt() and 0xFF)

internal fun ByteArray.u32le(i: Int) =
    (this[i].toInt() and 0xFF) or ((this[i + 1].toInt() and 0xFF) shl 8) or
        ((this[i + 2].toInt() and 0xFF) shl 16) or ((this[i + 3].toInt() and 0xFF) shl 24)

internal fun ByteArray.toHex(): String =
    joinToString(" ") { "%02X".format(it) }
