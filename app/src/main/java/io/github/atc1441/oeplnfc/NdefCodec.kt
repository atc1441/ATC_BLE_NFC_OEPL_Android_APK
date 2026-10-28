package io.github.atc1441.oeplnfc

/**
 * The little bit of NDEF this app needs. Not android.nfc.NdefMessage, because
 * the tag is driven as a plain Type-4 APDU target here and the same bytes also
 * have to go out through BLE_CMD_SET_NDEF, where no Android class helps.
 */
object NdefCodec {

    /** The NFC Forum URI prefix abbreviations the firmware also knows. */
    private val prefixes = listOf(
        0x01 to "http://www.",
        0x02 to "https://www.",
        0x03 to "http://",
        0x04 to "https://"
    )

    /** A single URI record, abbreviating the scheme when one is recognised. */
    fun uriMessage(url: String): ByteArray {
        var code = 0x00
        var rest = url
        for ((c, p) in prefixes) {
            if (url.length > p.length && url.startsWith(p)) {
                code = c
                rest = url.substring(p.length)
                break
            }
        }
        val body = rest.toByteArray(Charsets.UTF_8)
        val out = ByteArray(5 + body.size)
        out[0] = 0xD1.toByte()          // MB|ME|SR|TNF=1 (well known)
        out[1] = 0x01                   // type length
        out[2] = (1 + body.size).toByte()
        out[3] = 'U'.code.toByte()
        out[4] = code.toByte()
        body.copyInto(out, 5)
        return out
    }

    /** A single well-known Text record, language "en". */
    fun textMessage(text: String): ByteArray {
        val body = text.toByteArray(Charsets.UTF_8)
        val out = ByteArray(7 + body.size)
        out[0] = 0xD1.toByte()
        out[1] = 0x01
        out[2] = (3 + body.size).toByte()
        out[3] = 'T'.code.toByte()
        out[4] = 0x02                   // UTF-8, two character language code
        out[5] = 'e'.code.toByte()
        out[6] = 'n'.code.toByte()
        body.copyInto(out, 7)
        return out
    }

    /**
     * Wrap a message in the Type-2 TLV container, which is the form the OEPL
     * AP sends for DATATYPE_NFC_RAW_CONTENT and what the firmware unwraps
     * again. Produced here so the app can exercise exactly that path.
     */
    fun toType2Tlv(message: ByteArray): ByteArray {
        return if (message.size < 0xFF) {
            val out = ByteArray(3 + message.size)
            out[0] = 0x03
            out[1] = message.size.toByte()
            message.copyInto(out, 2)
            out[out.size - 1] = 0xFE.toByte()
            out
        } else {
            val out = ByteArray(5 + message.size)
            out[0] = 0x03
            out[1] = 0xFF.toByte()
            out[2] = (message.size shr 8).toByte()
            out[3] = (message.size and 0xFF).toByte()
            message.copyInto(out, 4)
            out[out.size - 1] = 0xFE.toByte()
            out
        }
    }

    /**
     * Best-effort description of a message for the log. Only the two record
     * types this app writes are decoded; anything else is reported by its
     * type, which is enough to tell "this is not what I wrote" apart from
     * "this is empty".
     */
    fun describe(message: ByteArray): String {
        if (message.isEmpty()) return "(empty)"
        if (message.size < 4) return "(${message.size} bytes, too short to be a record)"
        val header = message[0].toInt() and 0xFF
        val sr = header and 0x10 != 0
        val typeLen = message[1].toInt() and 0xFF
        if (!sr) return "(long record, ${message.size} bytes)"
        val payloadLen = message[2].toInt() and 0xFF
        val typeAt = 3
        if (typeAt + typeLen > message.size) return "(malformed record)"
        val type = String(message, typeAt, typeLen, Charsets.US_ASCII)
        val payloadAt = typeAt + typeLen
        val end = minOf(payloadAt + payloadLen, message.size)
        if (payloadAt >= end) return "$type record (empty payload)"
        return when (type) {
            "U" -> {
                val code = message[payloadAt].toInt() and 0xFF
                val prefix = prefixes.firstOrNull { it.first == code }?.second ?: ""
                "URL: " + prefix + String(
                    message, payloadAt + 1, end - payloadAt - 1, Charsets.UTF_8
                )
            }
            "T" -> {
                val status = message[payloadAt].toInt() and 0xFF
                val langLen = status and 0x3F
                val textAt = payloadAt + 1 + langLen
                if (textAt >= end) "Text: (empty)"
                else "Text: " + String(message, textAt, end - textAt, Charsets.UTF_8)
            }
            else -> "$type record, ${payloadLen} byte payload"
        }
    }
}
