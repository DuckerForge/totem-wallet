package com.clearsign.core

/*
 * Paying by holding two phones together. What travels over NFC is a request, never a key or
 * a signature: the merchant's phone pretends to be an NFC tag holding a Solana Pay URI, the
 * payer's phone reads it and opens its ordinary receipt. Touching does not pay, it fills the
 * form; simulation, risk engine and fingerprint are unchanged. Pure bytes, tested without
 * hardware: a reader's conversation with a tag is a fixed sequence of APDUs.
 */

/** NDEF: the tiny message format an NFC tag carries. */
object Ndef {
    private const val TNF_WELL_KNOWN = 0x01
    private val TYPE_URI = byteArrayOf('U'.code.toByte())

    /**
     * A one-record NDEF message holding [uri]. The first payload byte abbreviates common prefixes
     * (`http://www.` and friends); `solana:` is not in that table, so it is 0x00 and the URI travels whole.
     */
    fun uriMessage(uri: String): ByteArray {
        val bytes = uri.toByteArray(Charsets.UTF_8)
        val payload = ByteArray(bytes.size + 1)
        payload[0] = 0x00
        bytes.copyInto(payload, 1)

        val short = payload.size < 256
        // MB + ME (only record) + TNF; SR set when the length fits one byte.
        val header = (0xC0 or (if (short) 0x10 else 0x00) or TNF_WELL_KNOWN).toByte()
        val out = ArrayList<Byte>(payload.size + 8)
        out.add(header)
        out.add(TYPE_URI.size.toByte())
        if (short) {
            out.add(payload.size.toByte())
        } else {
            out.add((payload.size ushr 24).toByte())
            out.add((payload.size ushr 16).toByte())
            out.add((payload.size ushr 8).toByte())
            out.add(payload.size.toByte())
        }
        TYPE_URI.forEach { out.add(it) }
        payload.forEach { out.add(it) }
        return out.toByteArray()
    }

    /** Read back a URI from an NDEF message. Null when it is not a single URI record. */
    fun uriOf(message: ByteArray): String? {
        if (message.size < 4) return null
        val header = message[0].toInt() and 0xFF
        if ((header and 0x07) != TNF_WELL_KNOWN) return null
        val short = (header and 0x10) != 0
        var i = 1
        val typeLen = message[i++].toInt() and 0xFF
        val payloadLen = if (short) {
            message[i++].toInt() and 0xFF
        } else {
            if (message.size < i + 4) return null
            var v = 0
            repeat(4) { v = (v shl 8) or (message[i++].toInt() and 0xFF) }
            v
        }
        // The IL flag means the record carries an id: one length byte here, the id bytes after the
        // type. Skipping only the length byte left the id where the payload was expected, and a tag
        // written with an id handed back an address with stray characters in front.
        val idLen = if ((header and 0x08) != 0) {
            if (message.size < i + 1) return null
            message[i++].toInt() and 0xFF
        } else {
            0
        }
        if (typeLen < 0 || payloadLen < 1 || message.size < i + typeLen + idLen + payloadLen) return null
        val type = String(message, i, typeLen, Charsets.UTF_8)
        if (type != "U") return null
        i += typeLen + idLen
        return String(message, i + 1, payloadLen - 1, Charsets.UTF_8)
    }
}

/**
 * The NFC Forum Type 4 tag a reader expects, emulated in software. A reader always does the
 * same four things: pick the NDEF application, read the capability container for the sizes,
 * pick the NDEF file, read it in chunks of its own choosing. Anything else gets a refusal, never an invented answer.
 */
class Type4Tag(message: ByteArray) {

    /** Two bytes of length, then the message: that is what the NDEF file holds. */
    private val file: ByteArray = ByteArray(message.size + 2).also {
        it[0] = (message.size ushr 8).toByte()
        it[1] = message.size.toByte()
        message.copyInto(it, 2)
    }

    private var selected: Selected = Selected.NONE

    private enum class Selected { NONE, APP, CC, NDEF }

    fun reset() { selected = Selected.NONE }

    /** One command in, one response out. The last two bytes are always the status word. */
    fun process(apdu: ByteArray): ByteArray {
        if (apdu.size < 4) return SW_WRONG_LENGTH
        val ins = apdu[1].toInt() and 0xFF
        val p1 = apdu[2].toInt() and 0xFF
        val p2 = apdu[3].toInt() and 0xFF

        if (ins == INS_SELECT) {
            val lc = if (apdu.size > 4) apdu[4].toInt() and 0xFF else 0
            if (apdu.size < 5 + lc) return SW_WRONG_LENGTH
            val body = apdu.copyOfRange(5, 5 + lc)
            return when {
                p1 == 0x04 && body.contentEquals(AID_NDEF) -> { selected = Selected.APP; SW_OK }
                p1 == 0x00 && p2 == 0x0C && body.contentEquals(FILE_CC) && selected != Selected.NONE -> { selected = Selected.CC; SW_OK }
                p1 == 0x00 && p2 == 0x0C && body.contentEquals(FILE_NDEF) && selected != Selected.NONE -> { selected = Selected.NDEF; SW_OK }
                else -> SW_NOT_FOUND
            }
        }

        if (ins == INS_READ_BINARY) {
            val source = when (selected) {
                Selected.CC -> CAPABILITY_CONTAINER
                Selected.NDEF -> file
                else -> return SW_NOT_FOUND
            }
            val offset = (p1 shl 8) or p2
            if (offset > source.size) return SW_WRONG_PARAMS
            val asked = if (apdu.size > 4) (apdu[4].toInt() and 0xFF).let { if (it == 0) 256 else it } else 0
            val length = minOf(asked, source.size - offset)
            return source.copyOfRange(offset, offset + length) + SW_OK
        }

        return SW_NOT_FOUND
    }

    companion object {
        private const val INS_SELECT = 0xA4
        private const val INS_READ_BINARY = 0xB0

        /** The NDEF application every Type 4 reader looks for first. */
        val AID_NDEF = byteArrayOf(0xD2.toByte(), 0x76, 0x00, 0x00, 0x85.toByte(), 0x01, 0x01)
        val FILE_CC = byteArrayOf(0xE1.toByte(), 0x03)
        val FILE_NDEF = byteArrayOf(0xE1.toByte(), 0x04)

        val SW_OK = byteArrayOf(0x90.toByte(), 0x00)
        val SW_NOT_FOUND = byteArrayOf(0x6A, 0x82.toByte())
        val SW_WRONG_PARAMS = byteArrayOf(0x6A, 0x86.toByte())
        val SW_WRONG_LENGTH = byteArrayOf(0x67, 0x00)

        /** The capability container: fifteen bytes with the version, how much may be asked at a time, and that the NDEF file is read-only. */
        val CAPABILITY_CONTAINER = byteArrayOf(
            0x00, 0x0F,                                     // this structure is 15 bytes
            0x20,                                           // mapping version 2.0
            0x00, 0xFF.toByte(),                            // max bytes we answer to a read
            0x00, 0xFF.toByte(),                            // max bytes we accept in a write
            0x04, 0x06,                                     // NDEF file control TLV, 6 bytes
            0xE1.toByte(), 0x04,                            // file id
            0x7F, 0xFF.toByte(),                            // max NDEF size
            0x00,                                           // read: allowed
            0xFF.toByte(),                                  // write: never
        )
    }
}

/** What a Solana Pay URI says. */
data class PayRequest(val recipient: String, val amount: Double?, val mint: String?, val message: String?, val label: String?)

/**
 * Reading `solana:<address>?amount=…&message=…`. The payer's phone trusts none of it: it fills
 * a form, and the resulting transaction is simulated and shown like any other before signing.
 */
object SolanaPay {
    /** The path of the web page that carries a request to phones without a wallet. */
    const val WEB_PAY_PATH = "/apex/p"

    fun parse(uri: String): PayRequest? {
        val t = uri.trim()
        val web = t.startsWith("http://", ignoreCase = true) || t.startsWith("https://", ignoreCase = true)
        if (!web && !t.startsWith("solana:", ignoreCase = true)) return null
        // The web form carries the same fields, with the address in `to` instead of
        // the scheme-specific part: one parser, so a tag, a QR and a tapped link all
        // arrive here the same way.
        val body = if (web) t.substringAfter("://").substringAfter('/', "") else t.substring(7)
        if (web && !("/" + body.substringBefore('?')).startsWith(WEB_PAY_PATH)) return null
        val recipient = if (web) "" else body.substringBefore('?').trim().also {
            if (it.isEmpty() || it.contains('/')) return null                // a transaction request, not a transfer
        }
        val query = body.substringAfter('?', "")
        val params = HashMap<String, String>()
        if (query.isNotEmpty()) {
            for (pair in query.split('&')) {
                val k = pair.substringBefore('=')
                val v = pair.substringAfter('=', "")
                if (k.isNotEmpty()) params[k.lowercase()] = decode(v)
            }
        }
        val amount = params["amount"]?.replace(',', '.')?.toDoubleOrNull()?.takeIf { it > 0 }
        val to = recipient.ifEmpty { params["to"].orEmpty().trim() }
        if (to.isEmpty() || to.contains('/')) return null
        return PayRequest(to, amount, params["spl-token"], params["message"], params["label"])
    }

    /** Percent-decoding, plus the `+` that some writers use for a space. */
    private fun decode(s: String): String {
        if (!s.contains('%') && !s.contains('+')) return s
        val out = StringBuilder(s.length)
        val bytes = ArrayList<Byte>()
        fun flush() {
            if (bytes.isNotEmpty()) { out.append(String(bytes.toByteArray(), Charsets.UTF_8)); bytes.clear() }
        }
        var i = 0
        while (i < s.length) {
            val c = s[i]
            when {
                c == '%' && i + 2 < s.length -> {
                    val v = s.substring(i + 1, i + 3).toIntOrNull(16)
                    if (v == null) { flush(); out.append(c); i++ } else { bytes.add(v.toByte()); i += 3 }
                }
                c == '+' -> { flush(); out.append(' '); i++ }
                else -> { flush(); out.append(c); i++ }
            }
        }
        flush()
        return out.toString()
    }
}
