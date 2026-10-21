package app.gatekeeper.insights

import java.net.InetAddress

/** The parts of a dropped packet Gatekeeper looks at. Payloads are never stored. */
class PacketInfo(
    val protocol: Int,
    val source: InetAddress,
    val sourcePort: Int,
    val destination: InetAddress,
    val destinationPort: Int,
    /** Name asked for, if this is a plain DNS query. */
    val dnsQuestion: String?,
)

/**
 * Minimal IPv4/IPv6 + TCP/UDP header parser. Returns only packets that START something:
 * TCP SYNs and UDP datagrams. Retransmissions and everything else are ignored.
 */
object PacketParser {
    const val TCP = 6
    const val UDP = 17

    fun parse(buf: ByteArray, length: Int): PacketInfo? {
        if (length < 20) return null
        val version = (buf[0].toInt() ushr 4) and 0xF
        val protocol: Int
        val headerEnd: Int
        val src: InetAddress
        val dst: InetAddress
        when (version) {
            4 -> {
                val ihl = (buf[0].toInt() and 0xF) * 4
                if (ihl < 20 || length < ihl + 8) return null
                // Skip non-first fragments: they carry no transport header.
                val fragOffset = ((buf[6].toInt() and 0x1F) shl 8) or (buf[7].toInt() and 0xFF)
                if (fragOffset != 0) return null
                protocol = buf[9].toInt() and 0xFF
                src = InetAddress.getByAddress(buf.copyOfRange(12, 16))
                dst = InetAddress.getByAddress(buf.copyOfRange(16, 20))
                headerEnd = ihl
            }
            6 -> {
                if (length < 48) return null
                protocol = buf[6].toInt() and 0xFF // Extension headers are rare here; ignored.
                src = InetAddress.getByAddress(buf.copyOfRange(8, 24))
                dst = InetAddress.getByAddress(buf.copyOfRange(24, 40))
                headerEnd = 40
            }
            else -> return null
        }
        if (protocol != TCP && protocol != UDP) return null
        val srcPort = u16(buf, headerEnd)
        val dstPort = u16(buf, headerEnd + 2)

        if (protocol == TCP) {
            if (length < headerEnd + 14) return null
            val flags = buf[headerEnd + 13].toInt() and 0xFF
            val syn = flags and 0x02 != 0
            val ack = flags and 0x10 != 0
            if (!syn || ack) return null
            return PacketInfo(protocol, src, srcPort, dst, dstPort, null)
        }

        val question = if (dstPort == 53) dnsQuestion(buf, headerEnd + 8, length) else null
        return PacketInfo(protocol, src, srcPort, dst, dstPort, question)
    }

    /** Reads the first QNAME of a DNS query. */
    private fun dnsQuestion(buf: ByteArray, start: Int, end: Int): String? {
        if (end < start + 12) return null
        val flags = u16(buf, start + 2)
        if (flags and 0x8000 != 0) return null // A response, not a query.
        if (u16(buf, start + 4) == 0) return null // No questions.
        val name = StringBuilder()
        var i = start + 12
        while (i < end) {
            val len = buf[i].toInt() and 0xFF
            if (len == 0) break
            if (len and 0xC0 != 0 || i + 1 + len > end || name.length + len > 253) return null
            if (name.isNotEmpty()) name.append('.')
            for (j in i + 1..i + len) {
                val c = buf[j].toInt().toChar()
                name.append(if (c.isLetterOrDigit() || c == '-' || c == '_') c.lowercaseChar() else '?')
            }
            i += 1 + len
        }
        return name.toString().ifEmpty { null }
    }

    private fun u16(buf: ByteArray, offset: Int): Int =
        ((buf[offset].toInt() and 0xFF) shl 8) or (buf[offset + 1].toInt() and 0xFF)
}
