package app.focusfence.dns

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class DnsTest {

    /** Builds a DNS query for [name] with ID 0x1234, type A, class IN. */
    private fun query(name: String): ByteArray {
        val out = ArrayList<Byte>()
        out.addAll(listOf(0x12, 0x34, 0x01, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00).map { it.toByte() })
        for (label in name.split('.')) {
            out.add(label.length.toByte())
            out.addAll(label.toByteArray(Charsets.US_ASCII).toList())
        }
        out.add(0)
        out.addAll(listOf(0x00, 0x01, 0x00, 0x01).map { it.toByte() }) // A, IN
        return out.toByteArray()
    }

    @Test
    fun parsesQuestionName() {
        val q = query("WWW.Reddit.com")
        val parsed = Dns.parseQuestion(q, 0, q.size)
        assertNotNull(parsed)
        assertEquals("www.reddit.com", parsed!!.qname)
        assertEquals(q.size, parsed.questionEnd)
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
    fun nxDomainResponseKeepsIdAndQuestion() {
        val q = query("reddit.com")
        val parsed = Dns.parseQuestion(q, 0, q.size)!!
        val resp = Dns.buildNxDomain(q, 0, parsed.questionEnd)

        assertEquals(q.size, resp.size)
        assertEquals(q[0], resp[0]) // ID preserved
        assertEquals(q[1], resp[1])
        assertEquals(0x81.toByte(), resp[2]) // QR set
        assertEquals(0x83.toByte(), resp[3]) // RCODE=3
        assertEquals(0x00.toByte(), resp[7]) // ANCOUNT=0
        // Question section copied verbatim.
        for (i in 12 until q.size) assertEquals(q[i], resp[i])
    }

    @Test
    fun blockedReplyPacketRoundTrips() {
        // Wrap a query in an IPv4/UDP packet, build the NXDOMAIN reply, and re-parse it.
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

        val reply = Packets.buildUdpReply(udp, Dns.buildNxDomain(udp.packet, udp.payloadOffset, parsed.questionEnd))
        val replyUdp = Packets.parseUdp(reply, reply.size)
        assertNotNull(replyUdp)
        // Addresses and ports are swapped in the reply.
        assertEquals(49153, replyUdp!!.dstPort)
        assertEquals(53, replyUdp.srcPort)
        assertEquals(dst.toList(), replyUdp.srcAddr.toList())
        assertEquals(src.toList(), replyUdp.dstAddr.toList())
    }
}
