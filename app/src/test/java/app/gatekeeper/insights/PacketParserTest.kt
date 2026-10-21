package app.gatekeeper.insights

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PacketParserTest {

    private fun ipv4(protocol: Int, transport: ByteArray): ByteArray {
        val header = byteArrayOf(
            0x45, 0, 0, 0, 0, 0, 0x40, 0, 64, protocol.toByte(), 0, 0,
            10, 111, 222.toByte(), 1, // source 10.111.222.1
            94.toByte(), 140.toByte(), 14, 14, // destination 94.140.14.14
        )
        return header + transport
    }

    private fun ipv6(protocol: Int, transport: ByteArray): ByteArray {
        val header = ByteArray(40)
        header[0] = 0x60
        header[6] = protocol.toByte()
        header[8] = 0xfd.toByte(); header[23] = 1 // fd00::1
        header[24] = 0x2a; header[25] = 0x03; header[39] = 0x35 // 2a03::35
        return header + transport
    }

    private fun tcp(srcPort: Int, dstPort: Int, flags: Int): ByteArray {
        val t = ByteArray(20)
        t[0] = (srcPort shr 8).toByte(); t[1] = srcPort.toByte()
        t[2] = (dstPort shr 8).toByte(); t[3] = dstPort.toByte()
        t[12] = 0x50
        t[13] = flags.toByte()
        return t
    }

    private fun udp(srcPort: Int, dstPort: Int, payload: ByteArray): ByteArray {
        val h = byteArrayOf(
            (srcPort shr 8).toByte(), srcPort.toByte(), (dstPort shr 8).toByte(), dstPort.toByte(),
            0, (8 + payload.size).toByte(), 0, 0,
        )
        return h + payload
    }

    private fun dnsQuery(name: String, response: Boolean = false): ByteArray {
        val header = byteArrayOf(0x12, 0x34, if (response) 0x81.toByte() else 0x01, 0, 0, 1, 0, 0, 0, 0, 0, 0)
        val qname = name.split('.').flatMap { listOf(it.length.toByte()) + it.toByteArray().toList() } + 0.toByte()
        return header + qname.toByteArray() + byteArrayOf(0, 1, 0, 1)
    }

    @Test
    fun tcpSynIsReported() {
        val p = PacketParser.parse(ipv4(6, tcp(40000, 443, 0x02)).let { it }, 40)!!
        assertEquals(PacketParser.TCP, p.protocol)
        assertEquals("94.140.14.14", p.destination.hostAddress)
        assertEquals(443, p.destinationPort)
        assertEquals(40000, p.sourcePort)
        assertNull(p.dnsQuestion)
    }

    @Test
    fun tcpAckAndSynAckAreIgnored() {
        assertNull(PacketParser.parse(ipv4(6, tcp(40000, 443, 0x10)), 40))
        assertNull(PacketParser.parse(ipv4(6, tcp(40000, 443, 0x12)), 40))
    }

    @Test
    fun dnsQueryNameIsExtracted() {
        val packet = ipv4(17, udp(5353, 53, dnsQuery("App-Measurement.com")))
        val p = PacketParser.parse(packet, packet.size)!!
        assertEquals("app-measurement.com", p.dnsQuestion)
        assertEquals(53, p.destinationPort)
    }

    @Test
    fun dnsResponseHasNoQuestion() {
        val packet = ipv4(17, udp(5353, 53, dnsQuery("example.com", response = true)))
        assertNull(PacketParser.parse(packet, packet.size)!!.dnsQuestion)
    }

    @Test
    fun ipv6SynIsReported() {
        val packet = ipv6(6, tcp(50000, 5222, 0x02))
        val p = PacketParser.parse(packet, packet.size)!!
        assertEquals(5222, p.destinationPort)
        assertEquals("2a03:0:0:0:0:0:0:35", p.destination.hostAddress)
    }

    @Test
    fun garbageIsIgnored() {
        assertNull(PacketParser.parse(ByteArray(10), 10))
        assertNull(PacketParser.parse(ByteArray(60) { 0x7f }, 60))
        val icmp = ipv4(1, ByteArray(8))
        assertNull(PacketParser.parse(icmp, icmp.size))
    }

    @Test
    fun truncatedDnsDoesNotCrash() {
        val packet = ipv4(17, udp(5353, 53, dnsQuery("example.com")))
        for (len in 20 until packet.size) PacketParser.parse(packet, len)
    }
}
