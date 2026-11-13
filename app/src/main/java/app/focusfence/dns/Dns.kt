package app.focusfence.dns

import java.util.Locale

/**
 * Minimal DNS wire-format helpers: enough to read the question of a query
 * and synthesize blocked answers.
 */
object Dns {

    const val TYPE_A = 1
    const val TYPE_AAAA = 28

    /** The parsed question of a DNS query. [questionEnd] is the offset (relative to the
     *  start of the DNS message) of the first byte after the question section. */
    data class Question(val qname: String, val qtype: Int, val questionEnd: Int)

    /**
     * Parses the header + first question of a DNS message. Returns null for anything
     * that is not a plain single-question query (responses, zero/multi-question
     * messages, compressed or malformed names).
     */
    fun parseQuestion(msg: ByteArray, offset: Int, length: Int): Question? {
        if (length < 13) return null
        // QR bit set means this is a response, not a query.
        if (msg[offset + 2].toInt() and 0x80 != 0) return null
        val qdCount = readU16(msg, offset + 4)
        if (qdCount != 1) return null

        val end = offset + length
        var i = offset + 12
        val sb = StringBuilder()
        while (true) {
            if (i >= end) return null
            val labelLen = msg[i].toInt() and 0xff
            if (labelLen == 0) {
                i++
                break
            }
            // Compression pointers are invalid at the start of a question.
            if (labelLen >= 0xc0) return null
            if (i + 1 + labelLen > end) return null
            if (sb.isNotEmpty()) sb.append('.')
            for (j in 1..labelLen) sb.append((msg[i + j].toInt() and 0xff).toChar())
            if (sb.length > 253) return null
            i += 1 + labelLen
        }
        // QTYPE + QCLASS must follow.
        if (i + 4 > end) return null
        return Question(
            sb.toString().lowercase(Locale.ROOT),
            readU16(msg, i),
            i + 4 - offset,
        )
    }

    /**
     * Builds the response for a blocked query. A and AAAA questions get a real
     * answer (0.0.0.0 / ::) carrying [ttlSeconds], so clients CACHE the block
     * instead of re-asking endlessly — plain NXDOMAIN without an SOA is
     * uncacheable and was waking the VPN thousands of times a day. Other query
     * types get an empty NOERROR (NODATA) response.
     */
    fun buildBlockedResponse(
        msg: ByteArray,
        offset: Int,
        question: Question,
        ttlSeconds: Int,
    ): ByteArray {
        val rdataLength = when (question.qtype) {
            TYPE_A -> 4
            TYPE_AAAA -> 16
            else -> -1
        }
        if (rdataLength < 0) {
            // NODATA: NOERROR with no answer records.
            val resp = ByteArray(question.questionEnd)
            System.arraycopy(msg, offset, resp, 0, question.questionEnd)
            resp[2] = 0x81.toByte() // QR=1, RD=1
            resp[3] = 0x80.toByte() // RA=1, RCODE=0
            for (j in 6..11) resp[j] = 0
            return resp
        }

        val resp = ByteArray(question.questionEnd + 12 + rdataLength)
        System.arraycopy(msg, offset, resp, 0, question.questionEnd)
        resp[2] = 0x81.toByte() // QR=1, RD=1
        resp[3] = 0x80.toByte() // RA=1, RCODE=0
        for (j in 6..11) resp[j] = 0
        resp[7] = 1 // ANCOUNT = 1

        var i = question.questionEnd
        resp[i++] = 0xc0.toByte() // name: pointer to the question name at offset 12
        resp[i++] = 0x0c
        resp[i++] = 0
        resp[i++] = question.qtype.toByte()
        resp[i++] = 0
        resp[i++] = 1 // class IN
        resp[i++] = ((ttlSeconds ushr 24) and 0xff).toByte()
        resp[i++] = ((ttlSeconds ushr 16) and 0xff).toByte()
        resp[i++] = ((ttlSeconds ushr 8) and 0xff).toByte()
        resp[i++] = (ttlSeconds and 0xff).toByte()
        resp[i++] = 0
        resp[i] = rdataLength.toByte()
        // RDATA is all zeros: 0.0.0.0 / :: — already zero-initialized.
        return resp
    }

    private fun readU16(b: ByteArray, i: Int): Int =
        ((b[i].toInt() and 0xff) shl 8) or (b[i + 1].toInt() and 0xff)
}
