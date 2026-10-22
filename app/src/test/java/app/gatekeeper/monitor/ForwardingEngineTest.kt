package app.gatekeeper.monitor

import app.gatekeeper.data.ConnectionAttempt
import app.gatekeeper.tor.TorRoute
import java.io.DataInputStream
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Drives the relay with hand-made packets, as an app's TCP stack would, against real local
 * servers. Every packet the engine produces is checksum-verified.
 */
class ForwardingEngineTest {

    private val fromEngine = LinkedBlockingQueue<ByteArray>()
    private val dropPlan = AtomicInteger(-1) // Index of a data segment to drop once.
    private var dataSegmentsSeen = 0
    private val blockedPackets = AtomicInteger()
    private var engine: ForwardingEngine? = null

    private val sink = object : OutputStream() {
        override fun write(b: Int) = throw UnsupportedOperationException()
        override fun write(b: ByteArray, off: Int, len: Int) {
            val packet = b.copyOfRange(off, off + len)
            val p = IpPacket.parse(packet, packet.size)
            if (p != null && p.protocol == IpPacket.TCP && p.payloadLength > 0) {
                if (dataSegmentsSeen++ == dropPlan.get()) return // Simulated loss.
            }
            fromEngine.add(packet)
        }
    }

    private fun start(blocked: Set<String> = emptySet(), torMode: Boolean = false, route: TorRoute? = null): ForwardingEngine {
        val e = ForwardingEngine(
            protector = object : ForwardingEngine.Protector {
                override fun protect(socket: Socket) = true
                override fun protect(socket: DatagramSocket) = true
            },
            toTunnel = sink,
            owners = object : ForwardingEngine.Owners {
                override fun uidOf(protocol: Int, src: InetAddress, srcPort: Int, dst: InetAddress, dstPort: Int) = 10123
                override fun packagesOf(uid: Int) = arrayOf("com.test.app")
            },
            initialBlocked = blocked,
            onBlockedPacket = { _, _ -> blockedPackets.incrementAndGet() },
            finished = ConcurrentLinkedQueue<ConnectionAttempt>(),
            torMode = torMode,
            initialTorRoute = route,
            clock = { System.nanoTime() / 1_000_000 },
        )
        e.start()
        engine = e
        return e
    }

    @After
    fun tearDown() {
        engine?.shutdown()
    }

    // ---- helpers ----

    private val app4 = byteArrayOf(10, 111, 222.toByte(), 1)
    private val loop4 = byteArrayOf(127, 0, 0, 1)

    private fun next(timeoutMs: Long = 3000): IpPacket {
        val raw = fromEngine.poll(timeoutMs, TimeUnit.MILLISECONDS) ?: throw AssertionError("no packet from engine")
        verifyChecksums(raw)
        return IpPacket.parse(raw, raw.size) ?: throw AssertionError("unparseable packet")
    }

    private fun verifyChecksums(raw: ByteArray) {
        val v6 = (raw[0].toInt() ushr 4) == 6
        val ipLen = if (v6) 40 else 20
        if (!v6) assertEquals("IPv4 header checksum", 0, IpPacket.checksum(raw, 0, 20, 0))
        val protocol = if (v6) raw[6].toInt() else raw[9].toInt()
        val length = raw.size - ipLen
        var pseudo = 0L
        val addrStart = if (v6) 8 else 12
        val addrLen = if (v6) 32 else 8
        for (i in addrStart until addrStart + addrLen step 2) pseudo += IpPacket.u16(raw, i).toLong()
        pseudo += length.toLong() + protocol.toLong()
        assertEquals("transport checksum", 0, IpPacket.checksum(raw, ipLen, length, pseudo))
    }

    private fun tcp(
        src: ByteArray, dst: ByteArray, sport: Int, dport: Int, seq: Long, ack: Long, flags: Int,
        data: ByteArray = ByteArray(0), window: Int = 65535, mss: Int = 0,
    ) = IpPacket.tcp(src.size == 16, src, dst, sport, dport, seq, ack, flags, window, data, 0, data.size, mss)

    private fun send(e: ForwardingEngine, packet: ByteArray) = e.enqueue(packet, packet.size)

    /** Runs a full client exchange: handshake, request, receive everything, close. */
    private fun exchange(
        e: ForwardingEngine, appAddr: ByteArray, serverAddr: ByteArray, port: Int, request: ByteArray, sport: Int,
    ): ByteArray {
        val isn = 1000L
        send(e, tcp(appAddr, serverAddr, sport, port, isn, 0, IpPacket.SYN, mss = 1460))
        val synAck = next()
        assertTrue("SYN-ACK", synAck.isSyn && synAck.isAck)
        assertEquals(isn + 1, synAck.ack)
        var rcvNext = Seq.add(synAck.seq, 1)
        var sndNext = isn + 1
        send(e, tcp(appAddr, serverAddr, sport, port, sndNext, rcvNext, IpPacket.ACK))
        send(e, tcp(appAddr, serverAddr, sport, port, sndNext, rcvNext, IpPacket.ACK or IpPacket.PSH, request))
        sndNext += request.size

        val received = ByteArrayOutputStream()
        var finSeen = false
        val deadline = System.currentTimeMillis() + 20_000
        while (!finSeen && System.currentTimeMillis() < deadline) {
            val p = next(5000)
            if (p.isRst) throw AssertionError("unexpected RST")
            if (p.payloadLength > 0) {
                if (p.seq == rcvNext) {
                    received.write(p.buf, p.payloadOffset, p.payloadLength)
                    rcvNext = Seq.add(rcvNext, p.payloadLength.toLong())
                }
                // In order or not, acknowledge what we have (dup ACKs on gaps).
                send(e, tcp(appAddr, serverAddr, sport, port, sndNext, rcvNext, IpPacket.ACK))
            }
            if (p.isFin && Seq.add(p.seq, p.payloadLength.toLong()) == rcvNext) {
                rcvNext = Seq.add(rcvNext, 1)
                finSeen = true
            }
        }
        assertTrue("FIN from server", finSeen)
        send(e, tcp(appAddr, serverAddr, sport, port, sndNext, rcvNext, IpPacket.FIN or IpPacket.ACK))
        val last = next()
        assertTrue(last.isAck)
        assertEquals(Seq.add(sndNext, 1), last.ack)
        return received.toByteArray()
    }

    private fun server(address: InetAddress, response: (ByteArray) -> ByteArray): ServerSocket {
        val server = ServerSocket(0, 50, address)
        thread(isDaemon = true) {
            server.accept().use { s ->
                val buf = ByteArray(4096)
                val n = s.getInputStream().read(buf)
                s.getOutputStream().write(response(buf.copyOf(maxOf(n, 0))))
                s.shutdownOutput()
                s.getInputStream().read() // Wait for the client's FIN.
            }
        }
        return server
    }

    // ---- tests ----

    @Test
    fun tcpRequestAndSmallResponse() {
        val server = server(InetAddress.getByName("127.0.0.1")) { req -> "echo:".toByteArray() + req }
        val e = start()
        val reply = exchange(e, app4, loop4, server.localPort, "hello".toByteArray(), 40000)
        assertEquals("echo:hello", String(reply))
    }

    @Test
    fun tcpLargeDownloadWithLoss() {
        val payload = ByteArray(2 * 1024 * 1024) { (it * 31 + 7).toByte() }
        val server = server(InetAddress.getByName("127.0.0.1")) { payload }
        dropPlan.set(10) // Lose one segment; the engine must retransmit it.
        val e = start()
        val reply = exchange(e, app4, loop4, server.localPort, "GET".toByteArray(), 40001)
        assertEquals(payload.size, reply.size)
        assertArrayEquals(payload, reply)
    }

    @Test
    fun tcpOverIpv6() {
        val loop6 = InetAddress.getByName("::1")
        val server = try {
            server(loop6) { "v6 ok".toByteArray() }
        } catch (_: Exception) {
            return // No IPv6 loopback on this machine.
        }
        val app6 = InetAddress.getByName("fd00:1:2:3::1").address
        val e = start()
        val reply = exchange(e, app6, loop6.address, server.localPort, "hi".toByteArray(), 40002)
        assertEquals("v6 ok", String(reply))
    }

    /** IPv6 packets end to end. The destination is IPv4-mapped so it works without IPv6 here. */
    @Test
    fun ipv6PacketsWithMappedDestination() {
        val payload = ByteArray(300_000) { (it % 251).toByte() }
        val server = server(InetAddress.getByName("127.0.0.1")) { payload }
        val app6 = InetAddress.getByName("fd00:1:2:3::1").address
        val mapped = ByteArray(16).also { it[10] = -1; it[11] = -1; it[12] = 127; it[15] = 1 }
        val e = start()
        val reply = exchange(e, app6, mapped, server.localPort, "v6".toByteArray(), 40007)
        assertArrayEquals(payload, reply)
    }

    @Test
    fun tcpLargeUpload() {
        val size = 1024 * 1024
        val server = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
        thread(isDaemon = true) {
            server.accept().use { s ->
                var total = 0L
                var sum = 0L
                val buf = ByteArray(65536)
                while (true) {
                    val n = s.getInputStream().read(buf)
                    if (n < 0) break
                    for (i in 0 until n) sum += buf[i].toLong() and 0xFF
                    total += n
                }
                s.getOutputStream().write("$total/$sum".toByteArray())
            }
        }
        val e = start()
        val port = server.localPort
        send(e, tcp(app4, loop4, 40008, port, 1, 0, IpPacket.SYN, mss = 1460))
        val synAck = next()
        var rcvNext = Seq.add(synAck.seq, 1)
        var sndNext = 2L
        send(e, tcp(app4, loop4, 40008, port, sndNext, rcvNext, IpPacket.ACK))
        val data = ByteArray(size) { (it * 7).toByte() }
        var offset = 0
        var acked = sndNext
        var window = 65535
        val deadline = System.currentTimeMillis() + 20_000
        while (offset < size && System.currentTimeMillis() < deadline) {
            // Send as much as the engine's window allows, then wait for ACKs.
            while (offset < size && Seq.diff(sndNext, acked) + 1400 <= window) {
                val len = minOf(1400, size - offset)
                send(e, tcp(app4, loop4, 40008, port, sndNext, rcvNext, IpPacket.ACK, data.copyOfRange(offset, offset + len)))
                sndNext = Seq.add(sndNext, len.toLong()); offset += len
            }
            val p = next(5000)
            if (p.isAck && Seq.diff(p.ack, acked) >= 0) { acked = p.ack; window = p.window }
            if (window < 1400 && Seq.diff(sndNext, acked) == 0L) Thread.sleep(5)
        }
        while (Seq.diff(sndNext, acked) > 0) { val p = next(5000); if (p.isAck) acked = p.ack }
        send(e, tcp(app4, loop4, 40008, port, sndNext, rcvNext, IpPacket.FIN or IpPacket.ACK))
        val reply = ByteArrayOutputStream()
        while (true) {
            val p = next(5000)
            if (p.payloadLength > 0 && p.seq == rcvNext) {
                reply.write(p.buf, p.payloadOffset, p.payloadLength)
                rcvNext = Seq.add(rcvNext, p.payloadLength.toLong())
                send(e, tcp(app4, loop4, 40008, port, Seq.add(sndNext, 1), rcvNext, IpPacket.ACK))
            }
            if (p.isFin) break
        }
        val expectedSum = data.sumOf { it.toLong() and 0xFF }
        assertEquals("$size/$expectedSum", String(reply.toByteArray()))
    }

    @Test
    fun serverNameIsReadFromTlsClientHello() {
        val server = server(InetAddress.getByName("127.0.0.1")) { "x".toByteArray() }
        val e = start()
        exchange(e, app4, loop4, server.localPort, clientHello("graph.facebook.com"), 40003)
        val deadline = System.currentTimeMillis() + 3000
        var host: String? = null
        while (host == null && System.currentTimeMillis() < deadline) {
            host = LiveMonitor.entries.value.firstOrNull { it.port == server.localPort }?.host
            Thread.sleep(50)
        }
        assertEquals("graph.facebook.com", host)
    }

    @Test
    fun udpIsRelayedBothWays() {
        val echo = DatagramSocket(0, InetAddress.getByName("127.0.0.1"))
        thread(isDaemon = true) {
            val buf = ByteArray(1500)
            val p = DatagramPacket(buf, buf.size)
            echo.receive(p)
            echo.send(DatagramPacket("pong".toByteArray(), 4, p.socketAddress))
        }
        val e = start()
        val ping = "ping".toByteArray()
        send(e, IpPacket.udp(false, app4, loop4, 5555, echo.localPort, ping, 0, ping.size))
        val reply = next()
        assertEquals(IpPacket.UDP, reply.protocol)
        assertTrue("reply is IPv4", !reply.v6)
        assertEquals(echo.localPort, reply.srcPort)
        assertEquals(5555, reply.dstPort)
        assertArrayEquals(app4, reply.dst)
        assertEquals("pong", String(reply.buf, reply.payloadOffset, reply.payloadLength))
    }

    @Test
    fun blockedAppsAreDroppedNotRelayed() {
        val e = start(blocked = setOf("com.test.app"))
        send(e, tcp(app4, loop4, 40004, 9, 1, 0, IpPacket.SYN, mss = 1460))
        assertNull(fromEngine.poll(500, TimeUnit.MILLISECONDS))
        assertEquals(1, blockedPackets.get())
    }

    @Test
    fun unknownConnectionGetsReset() {
        val e = start()
        send(e, tcp(app4, loop4, 40005, 443, 5000, 777, IpPacket.ACK, "stale".toByteArray()))
        val rst = next()
        assertTrue(rst.isRst)
        assertEquals(777L, rst.seq)
    }

    @Test
    fun refusedConnectionGetsReset() {
        val unused = ServerSocket(0).use { it.localPort } // Nothing listens here any more.
        val e = start()
        send(e, tcp(app4, loop4, 40006, unused, 1, 0, IpPacket.SYN, mss = 1460))
        val p = next()
        assertTrue("RST for refused connect", p.isRst)
        assertEquals(2L, p.ack)
    }

    @Test
    fun splitClientHelloStillYieldsName() {
        val hello = clientHello("example.org", padding = 2500) // Larger than one segment.
        assertNull(HostSniffer.tlsServerName(hello, 0, 1400).takeIf { false })
        assertEquals("example.org", HostSniffer.tlsServerName(hello, 0, hello.size))
        assertNotNull(HostSniffer.httpHost("GET / HTTP/1.1\r\nHost: a.b.c:8080\r\n\r\n".toByteArray(), 0, 36))
    }

    /** Minimal TLS 1.2-style ClientHello with an SNI extension placed after [padding] bytes. */
    private fun clientHello(host: String, padding: Int = 0): ByteArray {
        val name = host.toByteArray()
        val ext = ByteArrayOutputStream().apply {
            if (padding > 0) { // Padding extension (21) first, pushing SNI past the first segment.
                write(byteArrayOf(0, 21, (padding shr 8).toByte(), padding.toByte())); write(ByteArray(padding))
            }
            val listLen = name.size + 3
            write(byteArrayOf(0, 0, ((listLen + 2) shr 8).toByte(), (listLen + 2).toByte()))
            write(byteArrayOf((listLen shr 8).toByte(), listLen.toByte(), 0, (name.size shr 8).toByte(), name.size.toByte()))
            write(name)
        }.toByteArray()
        val body = ByteArrayOutputStream().apply {
            write(byteArrayOf(3, 3)); write(ByteArray(32)) // version + random
            write(0) // session id
            write(byteArrayOf(0, 2, 0x13, 0x01)) // one cipher suite
            write(byteArrayOf(1, 0)) // compression
            write(byteArrayOf((ext.size shr 8).toByte(), ext.size.toByte())); write(ext)
        }.toByteArray()
        val handshake = byteArrayOf(1, 0, (body.size shr 8).toByte(), body.size.toByte()) + body
        return byteArrayOf(22, 3, 1, (handshake.size shr 8).toByte(), handshake.size.toByte()) + handshake
    }

    // ---- Tor mode ----

    /** What the fake Tor saw on one SOCKS connection. */
    private class SocksSeen(val username: String, val password: String, val address: ByteArray, val port: Int)

    /**
     * A stand-in for Tor's SOCKS port: checks the handshake, records it, then answers like the
     * remote server would. [replyCode] != 0 refuses the CONNECT. The success reply is sent in the
     * same write as a server banner, to check the engine doesn't swallow data after the reply.
     */
    private fun fakeTor(seen: AtomicReference<SocksSeen>, replyCode: Int = 0): ServerSocket {
        val server = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
        thread(isDaemon = true) {
            server.accept().use { s ->
                val input = DataInputStream(s.getInputStream())
                val out = s.getOutputStream()
                val greeting = ByteArray(3).also { input.readFully(it) }
                assertArrayEquals(byteArrayOf(5, 1, 2), greeting)
                out.write(byteArrayOf(5, 2))
                assertEquals(1, input.readUnsignedByte())
                val user = ByteArray(input.readUnsignedByte()).also { input.readFully(it) }
                val pass = ByteArray(input.readUnsignedByte()).also { input.readFully(it) }
                out.write(byteArrayOf(1, 0))
                val head = ByteArray(4).also { input.readFully(it) }
                assertEquals(5, head[0].toInt())
                assertEquals(1, head[1].toInt())
                val address = ByteArray(if (head[3].toInt() == 4) 16 else 4).also { input.readFully(it) }
                val port = input.readUnsignedShort()
                seen.set(SocksSeen(String(user), String(pass), address, port))
                if (replyCode != 0) {
                    out.write(byteArrayOf(5, replyCode.toByte(), 0, 1, 0, 0, 0, 0, 0, 0))
                    return@use
                }
                out.write(byteArrayOf(5, 0, 0, 1, 0, 0, 0, 0, 0, 0) + "BANNER ".toByteArray())
                val buf = ByteArray(4096)
                val n = input.read(buf)
                out.write("via-tor:".toByteArray() + buf.copyOf(maxOf(n, 0)))
                s.shutdownOutput()
                input.read() // Wait for the client's FIN.
            }
        }
        return server
    }

    private val remote4 = byteArrayOf(203.toByte(), 0, 113, 7) // TEST-NET-3: never reached directly.

    @Test
    fun torModeRelaysThroughSocksWithAppAsUsername() {
        val seen = AtomicReference<SocksSeen>()
        val tor = fakeTor(seen)
        val e = start(torMode = true, route = TorRoute(socksPort = tor.localPort, dnsPort = 1, epoch = 3))
        val reply = exchange(e, app4, remote4, 443, "hello".toByteArray(), 41000)
        assertEquals("BANNER via-tor:hello", String(reply))
        val s = seen.get()
        assertNotNull(s)
        assertEquals("com.test.app", s.username) // One app, one set of circuits.
        assertEquals("gatekeeper-3", s.password) // The identity epoch.
        assertArrayEquals(remote4, s.address)
        assertEquals(443, s.port)
    }

    @Test
    fun torModeRefusesConnectionsUntilTorIsReady() {
        val e = start(torMode = true, route = null)
        send(e, tcp(app4, remote4, 41001, 443, 1, 0, IpPacket.SYN, mss = 1460))
        val p = next()
        assertTrue("RST, never a direct connection", p.isRst)
        assertEquals(2L, p.ack)
    }

    @Test
    fun torModeFailedCircuitResetsTheApp() {
        val seen = AtomicReference<SocksSeen>()
        val tor = fakeTor(seen, replyCode = 4) // Host unreachable.
        val e = start(torMode = true, route = TorRoute(tor.localPort, 1))
        send(e, tcp(app4, remote4, 41002, 80, 1, 0, IpPacket.SYN, mss = 1460))
        val p = next(5000)
        assertTrue("RST instead of SYN-ACK", p.isRst && !p.isSyn)
        assertEquals(2L, p.ack)
    }

    @Test
    fun torModeDropsNonDnsUdp() {
        val target = DatagramSocket(0, InetAddress.getByName("127.0.0.1")).apply { soTimeout = 500 }
        val e = start(torMode = true, route = TorRoute(1, 1))
        val data = "voice".toByteArray()
        send(e, IpPacket.udp(false, app4, loop4, 5556, target.localPort, data, 0, data.size))
        assertNull(fromEngine.poll(500, TimeUnit.MILLISECONDS))
        val got = runCatching { target.receive(DatagramPacket(ByteArray(100), 100)) }
        assertTrue("UDP must not leave around Tor", got.isFailure)
    }

    @Test
    fun torModeSendsDnsToTorResolver() {
        val resolver = DatagramSocket(0, InetAddress.getByName("127.0.0.1"))
        thread(isDaemon = true) {
            val buf = ByteArray(1500)
            val p = DatagramPacket(buf, buf.size)
            resolver.receive(p)
            resolver.send(DatagramPacket("answer".toByteArray(), 6, p.socketAddress))
        }
        val tunnelDns = byteArrayOf(10, 111, 222.toByte(), 2)
        val e = start(torMode = true, route = TorRoute(1, resolver.localPort))
        val query = "query".toByteArray()
        send(e, IpPacket.udp(false, app4, tunnelDns, 5557, 53, query, 0, query.size))
        val reply = next()
        assertEquals(IpPacket.UDP, reply.protocol)
        assertArrayEquals("reply appears to come from the server the app asked", tunnelDns, reply.src)
        assertEquals(53, reply.srcPort)
        assertEquals(5557, reply.dstPort)
        assertEquals("answer", String(reply.buf, reply.payloadOffset, reply.payloadLength))
    }

    @Test
    fun torModeStillDropsBlockedApps() {
        val e = start(blocked = setOf("com.test.app"), torMode = true, route = TorRoute(1, 1))
        send(e, tcp(app4, remote4, 41003, 443, 1, 0, IpPacket.SYN, mss = 1460))
        assertNull(fromEngine.poll(500, TimeUnit.MILLISECONDS))
        assertEquals(1, blockedPackets.get())
    }
}
