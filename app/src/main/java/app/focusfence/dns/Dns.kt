package app.focusfence.dns

import java.util.Locale

/**
 * Minimal DNS wire-format helpers: enough to read the question of a query
 * and synthesize an NXDOMAIN answer for blocked names.
 */
object Dns {

    /** The parsed question of a DNS query. [questionEnd] is the offset (relative to the
     *  start of the DNS message) of the first byte after the question section. */
    data class Question(val qname: String, val questionEnd: Int)

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
        return Question(sb.toString().lowercase(Locale.ROOT), i + 4 - offset)
    }

    /**
     * Builds an NXDOMAIN response for the query in [msg]: same ID and question,
     * QR/RA set, RCODE=3, no answer records.
     */
    fun buildNxDomain(msg: ByteArray, offset: Int, questionEnd: Int): ByteArray {
        val resp = ByteArray(questionEnd)
        System.arraycopy(msg, offset, resp, 0, questionEnd)
        resp[2] = 0x81.toByte() // QR=1, opcode=query, RD=1
        resp[3] = 0x83.toByte() // RA=1, RCODE=3 (NXDOMAIN)
        // ANCOUNT / NSCOUNT / ARCOUNT = 0 (drops any EDNS OPT the client sent).
        for (j in 6..11) resp[j] = 0
        return resp
    }

    private fun readU16(b: ByteArray, i: Int): Int =
        ((b[i].toInt() and 0xff) shl 8) or (b[i + 1].toInt() and 0xff)
}
