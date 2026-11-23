package app.focusfence.server

import java.net.InetAddress

/**
 * Which connecting peers are our own reverse proxy, so their `X-Forwarded-For` can be believed.
 * `FF_TRUSTED_PROXIES` is a comma list of IPv4/IPv6 addresses and IPv4 CIDR ranges
 * (`172.16.0.0/12,10.0.0.0/8`); unset means loopback only, which is right for nginx or Caddy on the same host.
 * Trusting a range means trusting everything inside it, so keep it to the network the proxy runs on
 * (for Docker Compose, the compose network), never `0.0.0.0/0`.
 */
object TrustedProxies {

    val LOOPBACK: (String) -> Boolean = { it == "127.0.0.1" || it == "::1" || it == "0:0:0:0:0:0:0:1" || it == "localhost" }

    fun parse(spec: String?): (String) -> Boolean {
        val items = spec.orEmpty().split(',').map { it.trim() }.filter { it.isNotEmpty() }
        if (items.isEmpty()) return LOOPBACK
        val ranges = items.mapNotNull { item ->
            runCatching {
                val (addr, bits) = item.substringBefore('/') to item.substringAfter('/', "").takeIf { it.isNotEmpty() }?.toInt()
                val raw = InetAddress.getByName(addr).address
                Range(raw, bits ?: (raw.size * 8))
            }.getOrNull()
        }
        return { peer -> LOOPBACK(peer) || ranges.any { it.contains(peer) } }
    }

    private class Range(val net: ByteArray, val bits: Int) {
        fun contains(peer: String): Boolean {
            val raw = runCatching { InetAddress.getByName(peer).address }.getOrNull() ?: return false
            if (raw.size != net.size || bits !in 0..net.size * 8) return false
            var left = bits
            for (i in net.indices) {
                if (left <= 0) break
                val mask = if (left >= 8) 0xFF else (0xFF shl (8 - left)) and 0xFF
                if ((raw[i].toInt() and mask) != (net[i].toInt() and mask)) return false
                left -= 8
            }
            return true
        }
    }
}
