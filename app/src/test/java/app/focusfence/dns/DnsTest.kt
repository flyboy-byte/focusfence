package app.focusfence.dns

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class DnsTest {

    /** Builds a DNS query for [name] with ID 0x1234, the given type, class IN. */
    private fun query(name: String, qtype: Int = Dns.TYPE_A): ByteArray {
        val out = ArrayList<Byte>()
        out.addAll(listOf(0x12, 0x34, 0x01, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00).map { it.toByte() })
        for (label in name.split('.')) {
            out.add(label.length.toByte())
            out.addAll(label.toByteArray(Charsets.US_ASCII).toList())
        }
        out.add(0)
        out.add(((qtype shr 8) and 0xff).toByte())
        out.add((qtype and 0xff).toByte())
        out.addAll(listOf(0x00, 0x01).map { it.toByte() }) // IN
        return out.toByteArray()
    }

    @Test
    fun parsesQuestionName() {
        val q = query("WWW.Reddit.com")
        val parsed = Dns.parseQuestion(q, 0, q.size)
        assertNotNull(parsed)
        assertEquals("www.reddit.com", parsed!!.qname)
        assertEquals(Dns.TYPE_A, parsed.qtype)
        assertEquals(q.size, parsed.questionEnd)
    }

    @Test
    fun blockedAResponseIsCacheableZeroAnswer() {
        val q = query("ads.example.com")
        val parsed = Dns.parseQuestion(q, 0, q.size)!!
        val resp = Dns.buildBlockedResponse(q, 0, parsed, 3600)

        assertEquals(q.size + 16, resp.size)
        assertEquals(q[0], resp[0]) // ID preserved
        assertEquals(0x81.toByte(), resp[2]) // QR set
        assertEquals(0x80.toByte(), resp[3]) // NOERROR
        assertEquals(1, resp[7].toInt()) // ANCOUNT = 1

        val a = parsed.questionEnd
        assertEquals(0xc0.toByte(), resp[a]) // name pointer
        assertEquals(0x0c.toByte(), resp[a + 1])
        assertEquals(Dns.TYPE_A, ((resp[a + 2].toInt() and 0xff) shl 8) or (resp[a + 3].toInt() and 0xff))
        // TTL 3600 = 0x00000E10
        assertEquals(0x0e.toByte(), resp[a + 8])
        assertEquals(0x10.toByte(), resp[a + 9])
        assertEquals(4, resp[a + 11].toInt()) // RDLENGTH
        for (j in 0 until 4) assertEquals(0.toByte(), resp[a + 12 + j]) // 0.0.0.0
    }

    @Test
    fun blockedAaaaResponseCarriesSixteenZeroBytes() {
        val q = query("ads.example.com", Dns.TYPE_AAAA)
        val parsed = Dns.parseQuestion(q, 0, q.size)!!
        assertEquals(Dns.TYPE_AAAA, parsed.qtype)
        val resp = Dns.buildBlockedResponse(q, 0, parsed, 60)
        assertEquals(q.size + 28, resp.size)
        assertEquals(1, resp[7].toInt())
        val a = parsed.questionEnd
        assertEquals(16, resp[a + 11].toInt()) // RDLENGTH
        for (j in 0 until 16) assertEquals(0.toByte(), resp[a + 12 + j]) // ::
    }

    @Test
    fun blockedOtherTypeGetsEmptyNoError() {
        val q = query("ads.example.com", 16) // TXT
        val parsed = Dns.parseQuestion(q, 0, q.size)!!
        val resp = Dns.buildBlockedResponse(q, 0, parsed, 3600)
        assertEquals(q.size, resp.size)
        assertEquals(0x80.toByte(), resp[3]) // NOERROR
        assertEquals(0, resp[7].toInt()) // no answers
    }

    @Test
    fun rejectsResponsesAndTruncatedMessages() {
        val q = query("example.com")
        q[2] = 0x80.toByte() // QR=1 -> response
        assertNull(Dns.parseQuestion(q, 0, q.size))

        val short = query("example.com")
        assertNull(Dns.parseQuestion(short, 0, 14))
    }

    @Test
    fun blockedReplyPacketRoundTrips() {
        // Wrap a query in an IPv4/UDP packet, build the block reply, and re-parse it.
        val dnsQuery = query("facebook.com")
        val src = byteArrayOf(10, 111, 222.toByte(), 1)
        val dst = byteArrayOf(10, 111, 222.toByte(), 3)
        val ipPacket = ByteArray(28 + dnsQuery.size)
        ipPacket[0] = 0x45
        ipPacket[2] = ((ipPacket.size shr 8) and 0xff).toByte()
        ipPacket[3] = (ipPacket.size and 0xff).toByte()
        ipPacket[8] = 64
        ipPacket[9] = 17
        System.arraycopy(src, 0, ipPacket, 12, 4)
        System.arraycopy(dst, 0, ipPacket, 16, 4)
        ipPacket[20] = 0xc0.toByte(); ipPacket[21] = 0x01 // src port 49153
        ipPacket[23] = 53 // dst port
        val udpLen = 8 + dnsQuery.size
        ipPacket[24] = ((udpLen shr 8) and 0xff).toByte()
        ipPacket[25] = (udpLen and 0xff).toByte()
        System.arraycopy(dnsQuery, 0, ipPacket, 28, dnsQuery.size)

        val udp = Packets.parseUdp(ipPacket, ipPacket.size)
        assertNotNull(udp)
        assertEquals(53, udp!!.dstPort)

        val parsed = Dns.parseQuestion(udp.packet, udp.payloadOffset, udp.payloadLength)!!
        assertEquals("facebook.com", parsed.qname)

        val reply = Packets.buildUdpReply(udp, Dns.buildBlockedResponse(udp.packet, udp.payloadOffset, parsed, 60))
        val replyUdp = Packets.parseUdp(reply, reply.size)
        assertNotNull(replyUdp)
        // Addresses and ports are swapped in the reply.
        assertEquals(49153, replyUdp!!.dstPort)
        assertEquals(53, replyUdp.srcPort)
        assertEquals(dst.toList(), replyUdp.srcAddr.toList())
        assertEquals(src.toList(), replyUdp.dstAddr.toList())
    }
}
