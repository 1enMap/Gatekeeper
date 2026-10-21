package app.gatekeeper.monitor

/**
 * Raw IPv4/IPv6 + TCP/UDP packet parsing and building for the live monitor's forwarder.
 * Only what a user-space TCP/UDP relay needs; no options beyond MSS are produced.
 */
class IpPacket(
    val v6: Boolean,
    val protocol: Int,
    val src: ByteArray,
    val dst: ByteArray,
    val srcPort: Int,
    val dstPort: Int,
    /** Offset of the TCP/UDP payload inside [buf]. */
    val payloadOffset: Int,
    val payloadLength: Int,
    val buf: ByteArray,
    // TCP only:
    val seq: Long = 0,
    val ack: Long = 0,
    val flags: Int = 0,
    val window: Int = 0,
    /** MSS option from a SYN, or 0. */
    val mss: Int = 0,
) {
    val isSyn get() = flags and SYN != 0
    val isAck get() = flags and ACK != 0
    val isFin get() = flags and FIN != 0
    val isRst get() = flags and RST != 0

    companion object {
        const val TCP = 6
        const val UDP = 17
        const val FIN = 0x01
        const val SYN = 0x02
        const val RST = 0x04
        const val PSH = 0x08
        const val ACK = 0x10

        fun parse(buf: ByteArray, length: Int): IpPacket? {
            if (length < 20) return null
            val version = (buf[0].toInt() ushr 4) and 0xF
            val v6: Boolean
            val protocol: Int
            val ipHeader: Int
            val end: Int
            val src: ByteArray
            val dst: ByteArray
            when (version) {
                4 -> {
                    ipHeader = (buf[0].toInt() and 0xF) * 4
                    val total = u16(buf, 2)
                    if (ipHeader < 20 || total > length || total < ipHeader) return null
                    if ((u16(buf, 6) and 0x3FFF) != 0) return null // Fragments are not handled.
                    v6 = false
                    protocol = buf[9].toInt() and 0xFF
                    src = buf.copyOfRange(12, 16)
                    dst = buf.copyOfRange(16, 20)
                    end = total
                }
                6 -> {
                    if (length < 40) return null
                    ipHeader = 40
                    val total = 40 + u16(buf, 4)
                    if (total > length) return null
                    v6 = true
                    protocol = buf[6].toInt() and 0xFF
                    src = buf.copyOfRange(8, 24)
                    dst = buf.copyOfRange(24, 40)
                    end = total
                }
                else -> return null
            }
            return when (protocol) {
                TCP -> {
                    if (end < ipHeader + 20) return null
                    val dataOffset = ((buf[ipHeader + 12].toInt() ushr 4) and 0xF) * 4
                    if (dataOffset < 20 || ipHeader + dataOffset > end) return null
                    val flags = buf[ipHeader + 13].toInt() and 0x3F
                    var mss = 0
                    if (flags and SYN != 0) mss = parseMss(buf, ipHeader + 20, ipHeader + dataOffset)
                    IpPacket(
                        v6, protocol, src, dst,
                        u16(buf, ipHeader), u16(buf, ipHeader + 2),
                        ipHeader + dataOffset, end - ipHeader - dataOffset, buf,
                        seq = u32(buf, ipHeader + 4), ack = u32(buf, ipHeader + 8),
                        flags = flags, window = u16(buf, ipHeader + 14), mss = mss,
                    )
                }
                UDP -> {
                    if (end < ipHeader + 8) return null
                    IpPacket(
                        v6, protocol, src, dst,
                        u16(buf, ipHeader), u16(buf, ipHeader + 2),
                        ipHeader + 8, end - ipHeader - 8, buf,
                    )
                }
                else -> null
            }
        }

        private fun parseMss(buf: ByteArray, start: Int, end: Int): Int {
            var i = start
            while (i < end) {
                when (val kind = buf[i].toInt() and 0xFF) {
                    0 -> return 0
                    1 -> i++
                    else -> {
                        if (i + 1 >= end) return 0
                        val len = buf[i + 1].toInt() and 0xFF
                        if (len < 2) return 0
                        if (kind == 2 && len == 4 && i + 3 < end) return u16(buf, i + 2)
                        i += len
                    }
                }
            }
            return 0
        }

        private var ipId = 0

        /** Builds a TCP segment from [src]:[srcPort] to [dst]:[dstPort]. */
        fun tcp(
            v6: Boolean, src: ByteArray, dst: ByteArray, srcPort: Int, dstPort: Int,
            seq: Long, ack: Long, flags: Int, window: Int,
            payload: ByteArray? = null, offset: Int = 0, length: Int = 0, mss: Int = 0,
        ): ByteArray {
            val options = if (mss > 0) 4 else 0
            val tcpLen = 20 + options + length
            val ipLen = if (v6) 40 else 20
            val out = ByteArray(ipLen + tcpLen)
            writeIpHeader(out, v6, TCP, src, dst, tcpLen)
            val t = ipLen
            put16(out, t, srcPort)
            put16(out, t + 2, dstPort)
            put32(out, t + 4, seq)
            put32(out, t + 8, ack)
            out[t + 12] = (((20 + options) / 4) shl 4).toByte()
            out[t + 13] = flags.toByte()
            put16(out, t + 14, window.coerceIn(0, 0xFFFF))
            if (mss > 0) {
                out[t + 20] = 2
                out[t + 21] = 4
                put16(out, t + 22, mss)
            }
            if (length > 0) System.arraycopy(payload!!, offset, out, t + 20 + options, length)
            put16(out, t + 16, transportChecksum(out, v6, TCP, src, dst, t, tcpLen))
            return out
        }

        /** Builds a UDP datagram. */
        fun udp(
            v6: Boolean, src: ByteArray, dst: ByteArray, srcPort: Int, dstPort: Int,
            payload: ByteArray, offset: Int, length: Int,
        ): ByteArray {
            val udpLen = 8 + length
            val ipLen = if (v6) 40 else 20
            val out = ByteArray(ipLen + udpLen)
            writeIpHeader(out, v6, UDP, src, dst, udpLen)
            val u = ipLen
            put16(out, u, srcPort)
            put16(out, u + 2, dstPort)
            put16(out, u + 4, udpLen)
            System.arraycopy(payload, offset, out, u + 8, length)
            var sum = transportChecksum(out, v6, UDP, src, dst, u, udpLen)
            if (sum == 0) sum = 0xFFFF // 0 means "no checksum" for UDP.
            put16(out, u + 6, sum)
            return out
        }

        private fun writeIpHeader(out: ByteArray, v6: Boolean, protocol: Int, src: ByteArray, dst: ByteArray, payloadLen: Int) {
            if (v6) {
                out[0] = 0x60
                put16(out, 4, payloadLen)
                out[6] = protocol.toByte()
                out[7] = 64
                System.arraycopy(src, 0, out, 8, 16)
                System.arraycopy(dst, 0, out, 24, 16)
            } else {
                out[0] = 0x45
                put16(out, 2, 20 + payloadLen)
                put16(out, 4, ipId++ and 0xFFFF)
                put16(out, 6, 0x4000) // Don't fragment.
                out[8] = 64
                out[9] = protocol.toByte()
                System.arraycopy(src, 0, out, 12, 4)
                System.arraycopy(dst, 0, out, 16, 4)
                put16(out, 10, checksum(out, 0, 20, 0))
            }
        }

        private fun transportChecksum(
            out: ByteArray, v6: Boolean, protocol: Int, src: ByteArray, dst: ByteArray, start: Int, length: Int,
        ): Int {
            var sum = 0L
            sum = add(sum, src, 0, src.size)
            sum = add(sum, dst, 0, dst.size)
            if (v6) {
                sum += (length.toLong() ushr 16) + (length.toLong() and 0xFFFF)
            } else {
                sum += length.toLong()
            }
            sum += protocol.toLong()
            return checksum(out, start, length, sum)
        }

        private fun add(initial: Long, data: ByteArray, start: Int, length: Int): Long {
            var sum = initial
            var i = start
            val end = start + length
            while (i + 1 < end) {
                sum += ((data[i].toInt() and 0xFF) shl 8 or (data[i + 1].toInt() and 0xFF)).toLong()
                i += 2
            }
            if (i < end) sum += ((data[i].toInt() and 0xFF) shl 8).toLong()
            return sum
        }

        fun checksum(data: ByteArray, start: Int, length: Int, initial: Long): Int {
            var sum = add(initial, data, start, length)
            while (sum ushr 16 != 0L) sum = (sum and 0xFFFF) + (sum ushr 16)
            return (sum.inv() and 0xFFFF).toInt()
        }

        fun u16(b: ByteArray, o: Int) = ((b[o].toInt() and 0xFF) shl 8) or (b[o + 1].toInt() and 0xFF)
        fun u32(b: ByteArray, o: Int): Long =
            ((b[o].toLong() and 0xFF) shl 24) or ((b[o + 1].toLong() and 0xFF) shl 16) or
                ((b[o + 2].toLong() and 0xFF) shl 8) or (b[o + 3].toLong() and 0xFF)

        private fun put16(b: ByteArray, o: Int, v: Int) {
            b[o] = (v ushr 8).toByte()
            b[o + 1] = v.toByte()
        }

        private fun put32(b: ByteArray, o: Int, v: Long) {
            b[o] = (v ushr 24).toByte()
            b[o + 1] = (v ushr 16).toByte()
            b[o + 2] = (v ushr 8).toByte()
            b[o + 3] = v.toByte()
        }
    }
}

/** 32-bit TCP sequence number arithmetic. */
object Seq {
    fun add(a: Long, n: Long) = (a + n) and 0xFFFFFFFFL
    fun diff(a: Long, b: Long): Long {
        val d = (a - b) and 0xFFFFFFFFL
        return if (d >= 0x80000000L) d - 0x100000000L else d
    }
}
