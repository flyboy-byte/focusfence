package app.focusfence.dns

/**
 * Just enough IPv4/IPv6 + UDP handling for a DNS-only tunnel: parse an incoming
 * UDP datagram out of a raw IP packet, and build the reply packet for it.
 * No fragmentation or extension-header support — DNS packets on the tunnel
 * never need either.
 */
object Packets {

    class UdpDatagram(
        val ipVersion: Int,
        val srcAddr: ByteArray,
        val dstAddr: ByteArray,
        val srcPort: Int,
        val dstPort: Int,
        /** Backing buffer holding the full IP packet. */
        val packet: ByteArray,
        /** Offset of the UDP payload within [packet]. */
        val payloadOffset: Int,
        val payloadLength: Int,
    ) {
        fun copyPayload(): ByteArray = packet.copyOfRange(payloadOffset, payloadOffset + payloadLength)
    }

    fun parseUdp(packet: ByteArray, length: Int): UdpDatagram? {
        if (length < 1) return null
        return when ((packet[0].toInt() and 0xf0) ushr 4) {
            4 -> parseUdp4(packet, length)
            6 -> parseUdp6(packet, length)
            else -> null
        }
    }

    private fun parseUdp4(p: ByteArray, length: Int): UdpDatagram? {
        if (length < 20) return null
        val ihl = (p[0].toInt() and 0x0f) * 4
        if (ihl < 20 || length < ihl + 8) return null
        if ((p[9].toInt() and 0xff) != 17) return null
        // Reject fragments (offset != 0 or MF set).
        if ((readU16(p, 6) and 0x3fff) != 0) return null
        val udpLen = readU16(p, ihl + 4)
        if (udpLen < 8 || ihl + udpLen > length) return null
        return UdpDatagram(
            ipVersion = 4,
            srcAddr = p.copyOfRange(12, 16),
            dstAddr = p.copyOfRange(16, 20),
            srcPort = readU16(p, ihl),
            dstPort = readU16(p, ihl + 2),
            packet = p,
            payloadOffset = ihl + 8,
            payloadLength = udpLen - 8,
        )
    }

    private fun parseUdp6(p: ByteArray, length: Int): UdpDatagram? {
        if (length < 48) return null
        // Only a bare UDP next-header is supported; extension headers are dropped.
        if ((p[6].toInt() and 0xff) != 17) return null
        val payloadLen = readU16(p, 4)
        if (payloadLen < 8 || 40 + payloadLen > length) return null
        return UdpDatagram(
            ipVersion = 6,
            srcAddr = p.copyOfRange(8, 24),
            dstAddr = p.copyOfRange(24, 40),
            srcPort = readU16(p, 40),
            dstPort = readU16(p, 42),
            packet = p,
            payloadOffset = 48,
            payloadLength = payloadLen - 8,
        )
    }

    /** Builds a full IP packet carrying [payload] back to the sender of [request]. */
    fun buildUdpReply(request: UdpDatagram, payload: ByteArray, payloadLength: Int = payload.size): ByteArray =
        if (request.ipVersion == 4) buildReply4(request, payload, payloadLength)
        else buildReply6(request, payload, payloadLength)

    private fun buildReply4(req: UdpDatagram, payload: ByteArray, payloadLen: Int): ByteArray {
        val totalLen = 20 + 8 + payloadLen
        val p = ByteArray(totalLen)
        p[0] = 0x45
        writeU16(p, 2, totalLen)
        p[6] = 0x40 // DF
        p[8] = 64   // TTL
        p[9] = 17   // UDP
        System.arraycopy(req.dstAddr, 0, p, 12, 4)
        System.arraycopy(req.srcAddr, 0, p, 16, 4)
        writeU16(p, 10, finishChecksum(sum(p, 0, 20)))
        writeU16(p, 20, req.dstPort)
        writeU16(p, 22, req.srcPort)
        writeU16(p, 24, 8 + payloadLen)
        System.arraycopy(payload, 0, p, 28, payloadLen)
        // UDP checksum is optional over IPv4; compute it anyway for strict stacks.
        var s = sum(p, 12, 8) // pseudo-header addresses
        s += 17L + (8 + payloadLen)
        s += sum(p, 20, 8 + payloadLen)
        var ck = finishChecksum(s)
        if (ck == 0) ck = 0xffff
        writeU16(p, 26, ck)
        return p
    }

    private fun buildReply6(req: UdpDatagram, payload: ByteArray, payloadLen: Int): ByteArray {
        val udpLen = 8 + payloadLen
        val p = ByteArray(40 + udpLen)
        p[0] = 0x60
        writeU16(p, 4, udpLen)
        p[6] = 17  // UDP
        p[7] = 64  // hop limit
        System.arraycopy(req.dstAddr, 0, p, 8, 16)
        System.arraycopy(req.srcAddr, 0, p, 24, 16)
        writeU16(p, 40, req.dstPort)
        writeU16(p, 42, req.srcPort)
        writeU16(p, 44, udpLen)
        System.arraycopy(payload, 0, p, 48, payloadLen)
        // UDP checksum is mandatory over IPv6.
        var s = sum(p, 8, 32) // src + dst
        s += udpLen.toLong() + 17L
        s += sum(p, 40, udpLen)
        var ck = finishChecksum(s)
        if (ck == 0) ck = 0xffff
        writeU16(p, 46, ck)
        return p
    }

    private fun readU16(b: ByteArray, i: Int): Int =
        ((b[i].toInt() and 0xff) shl 8) or (b[i + 1].toInt() and 0xff)

    private fun writeU16(b: ByteArray, i: Int, v: Int) {
        b[i] = ((v ushr 8) and 0xff).toByte()
        b[i + 1] = (v and 0xff).toByte()
    }

    private fun sum(b: ByteArray, off: Int, len: Int): Long {
        var s = 0L
        var i = off
        val end = off + len
        while (i + 1 < end) {
            s += (((b[i].toInt() and 0xff) shl 8) or (b[i + 1].toInt() and 0xff)).toLong()
            i += 2
        }
        if (i < end) s += ((b[i].toInt() and 0xff) shl 8).toLong()
        return s
    }

    private fun finishChecksum(s0: Long): Int {
        var s = s0
        while (s > 0xffff) s = (s and 0xffff) + (s ushr 16)
        return s.inv().toInt() and 0xffff
    }
}
