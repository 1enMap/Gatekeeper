package app.gatekeeper.monitor

import android.os.Process
import android.os.SystemClock
import android.util.Log
import app.gatekeeper.data.ConnectionAttempt
import java.io.IOException
import java.io.OutputStream
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.channels.DatagramChannel
import java.nio.channels.SelectionKey
import java.nio.channels.Selector
import java.nio.channels.SocketChannel
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.random.Random

/**
 * User-space relay used while the live monitor is on. Every app's packets arrive from the tunnel;
 * for TCP the engine completes the handshake itself and relays the byte stream over a real
 * (protected) socket, for UDP it relays datagrams. Blocked apps' packets are still dropped.
 *
 * Single-threaded: all flow state is touched only on this thread. The tunnel reader hands packets
 * over through [enqueue].
 */
class ForwardingEngine(
    /** Excludes the engine's own sockets from the VPN (VpnService.protect). */
    private val protector: Protector,
    /** Where packets for apps are written (the tunnel). One write = one packet. */
    private val toTunnel: OutputStream,
    /** Which app owns a connection. */
    private val owners: Owners,
    initialBlocked: Set<String>,
    /** Records attempts by blocked apps (connection log). */
    private val onBlockedPacket: (ByteArray, Int) -> Unit,
    /** Finished connections waiting to be written to the connection log. */
    private val finished: ConcurrentLinkedQueue<ConnectionAttempt>,
    /** Monotonic milliseconds. */
    private val clock: () -> Long = { SystemClock.elapsedRealtime() },
) : Thread("monitor-engine") {

    interface Protector {
        fun protect(socket: Socket): Boolean
        fun protect(socket: DatagramSocket): Boolean
    }

    interface Owners {
        fun uidOf(protocol: Int, src: InetAddress, srcPort: Int, dst: InetAddress, dstPort: Int): Int
        fun packagesOf(uid: Int): Array<String>
    }
    private val selector: Selector = Selector.open()
    private val inbox = ConcurrentLinkedQueue<ByteArray>()
    private val pendingBlocked = AtomicReference<Set<String>?>(null)
    private var blocked: Set<String> = initialBlocked

    @Volatile private var running = true

    private val tcpFlows = HashMap<String, TcpFlow>()
    private val udpFlows = HashMap<String, UdpFlow>()
    /** Flows whose owner is blocked: key → package, so retries skip the owner lookup. */
    private val blockedFlows = Lru<String, String>(1024)
    /** QUIC flows already recorded; their packets are dropped silently. */
    private val quicFlows = Lru<String, Boolean>(1024)
    /** Address → name, learned from DNS answers relayed for apps. */
    private val dnsNames = Lru<String, String>(4096)

    private val records = LinkedHashMap<Long, Record>()
    private val nextId = AtomicLong(1)
    private var dirty = false
    private var lastPublish = 0L
    private var lastTick = 0L
    private val readBuffer: ByteBuffer = ByteBuffer.allocate(64 * 1024)

    init {
        isDaemon = true
    }

    // ---- Called from other threads ----------------------------------------------------------

    fun enqueue(buf: ByteArray, length: Int) {
        if (inbox.size > MAX_INBOX) return // Overloaded: drop; TCP will retransmit.
        val wasEmpty = inbox.isEmpty()
        inbox.add(buf.copyOf(length))
        if (wasEmpty) selector.wakeup()
    }

    fun updateBlocked(packages: Set<String>) {
        pendingBlocked.set(packages)
        selector.wakeup()
    }

    fun shutdown() {
        running = false
        selector.wakeup()
        try {
            join(2_000)
        } catch (_: InterruptedException) {
        }
    }

    // ---- Engine thread ----------------------------------------------------------------------

    override fun run() {
        try {
            while (running) {
                selector.select(TICK_MS)
                pendingBlocked.getAndSet(null)?.let { applyBlocked(it) }

                val keys = selector.selectedKeys().iterator()
                while (keys.hasNext()) {
                    val key = keys.next()
                    keys.remove()
                    when (val flow = key.attachment()) {
                        is TcpFlow -> flow.onReady(key)
                        is UdpFlow -> flow.onReadable()
                    }
                }

                var handled = 0
                while (handled < 512) {
                    val packet = inbox.poll() ?: break
                    handlePacket(packet)
                    handled++
                }
                if (inbox.isNotEmpty()) selector.wakeup()

                val now = clock()
                if (now - lastTick >= TICK_MS) {
                    lastTick = now
                    tick(now)
                }
                if (dirty && now - lastPublish >= PUBLISH_MS) publish(now)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Engine stopped", e)
        } finally {
            tcpFlows.values.toList().forEach { it.close(reset = true) }
            udpFlows.values.toList().forEach { it.close() }
            runCatching { selector.close() }
            publish(clock(), force = true)
        }
    }

    private fun handlePacket(raw: ByteArray) {
        val p = IpPacket.parse(raw, raw.size) ?: return
        val key = "${p.protocol}|${addr(p.src)}|${p.srcPort}|${addr(p.dst)}|${p.dstPort}"
        when (p.protocol) {
            IpPacket.TCP -> handleTcp(p, key, raw)
            IpPacket.UDP -> handleUdp(p, key, raw)
        }
    }

    /** Owner package of a new flow, or null for system traffic. Remembers blocked flows. */
    private fun ownerOf(p: IpPacket, key: String): Pair<String?, Boolean> {
        blockedFlows.get(key)?.let { return it to true }
        val src = InetAddress.getByAddress(p.src)
        val dst = InetAddress.getByAddress(p.dst)
        val uid = owners.uidOf(p.protocol, src, p.srcPort, dst, p.dstPort)
        val packages = owners.packagesOf(uid)
        val blockedPkg = packages.firstOrNull { it in blocked }
        if (blockedPkg != null) {
            blockedFlows.put(key, blockedPkg)
            return blockedPkg to true
        }
        return (packages.firstOrNull() ?: if (uid == Process.SYSTEM_UID || uid == 0) "android" else null) to false
    }

    // ---- TCP --------------------------------------------------------------------------------

    private fun handleTcp(p: IpPacket, key: String, raw: ByteArray) {
        val existing = tcpFlows[key]
        if (existing != null) {
            existing.onPacket(p)
            return
        }
        if (blockedFlows.get(key) != null) {
            onBlockedPacket(raw, raw.size)
            return
        }
        if (p.isRst) return
        if (!p.isSyn || p.isAck) {
            // Unknown connection (e.g. from before the monitor started): tell the app to give up.
            sendRst(p)
            return
        }
        val (owner, isBlocked) = ownerOf(p, key)
        val record = newRecord(owner, "TCP", p.dst, p.dstPort, blocked = isBlocked)
        if (isBlocked) {
            onBlockedPacket(raw, raw.size)
            return
        }
        val flow = TcpFlow(key, p, owner, record)
        try {
            flow.start()
            tcpFlows[key] = flow
        } catch (e: Exception) {
            Log.d(TAG, "connect failed: ${e.message}")
            flow.abandon()
            sendRst(p)
        }
    }

    private fun sendRst(p: IpPacket) {
        val ack = Seq.add(p.seq, (p.payloadLength + (if (p.isSyn) 1 else 0) + (if (p.isFin) 1 else 0)).toLong())
        val seq = if (p.isAck) p.ack else 0L
        write(IpPacket.tcp(p.v6, p.dst, p.src, p.dstPort, p.srcPort, seq, ack, IpPacket.RST or IpPacket.ACK, 0))
    }

    private inner class TcpFlow(val key: String, syn: IpPacket, val owner: String?, val record: Record) {
        private val v6 = syn.v6
        private val app = syn.src
        private val appPort = syn.srcPort
        private val remote = syn.dst
        private val remotePort = syn.dstPort
        private val channel: SocketChannel = SocketChannel.open()
        private lateinit var selectionKey: SelectionKey

        private val isn = Random.nextLong() and 0xFFFFFFFFL
        private var appNext = Seq.add(syn.seq, 1) // Next byte expected from the app.
        private var sndNext = Seq.add(isn, 1) // Next sequence number we send.
        private var sndUna = isn // Oldest byte the app hasn't acknowledged.
        private var appWindow = syn.window
        private val segment = minOf(if (syn.mss > 0) syn.mss else 536, if (v6) 1440 else 1460)

        private var connected = false
        private var synAcked = false
        private val unacked = ByteQueue()
        private val toRemote = ArrayDeque<ByteBuffer>()
        private var toRemoteBytes = 0
        private var advertisedSmall = false

        private var remoteEof = false
        private var finSent = false
        private var finAcked = false
        private var appFin = false
        private var outputShut = false

        private var lastAckProgress = clock()
        private var lastActivity = lastAckProgress
        private val created = lastAckProgress
        private var retries = 0

        private var sniff: ByteQueue? = ByteQueue(SNIFF_LIMIT)

        fun start() {
            channel.configureBlocking(false)
            protector.protect(channel.socket())
            val done = channel.connect(InetSocketAddress(InetAddress.getByAddress(remote), remotePort))
            selectionKey = channel.register(selector, if (done) 0 else SelectionKey.OP_CONNECT, this)
            if (done) onConnected()
        }

        /** Start failed before the flow was registered. */
        fun abandon() {
            runCatching { channel.close() }
            finish(record)
        }

        private fun ourWindow() = (WINDOW - toRemoteBytes).coerceAtLeast(0)

        private fun send(flags: Int, payload: ByteArray? = null, offset: Int = 0, length: Int = 0, seq: Long = sndNext, mss: Int = 0) {
            val window = ourWindow()
            advertisedSmall = window < segment
            write(IpPacket.tcp(v6, remote, app, remotePort, appPort, seq, appNext, flags, window, payload, offset, length, mss))
        }

        private fun onConnected() {
            connected = true
            send(IpPacket.SYN or IpPacket.ACK, seq = isn, mss = if (v6) 1440 else 1460)
            updateInterest()
        }

        fun onReady(key: SelectionKey) {
            try {
                if (!key.isValid) return
                if (key.isConnectable) {
                    if (channel.finishConnect()) onConnected()
                    return
                }
                if (key.isWritable) flushToRemote()
                if (key.isValid && key.isReadable) readFromRemote()
            } catch (e: IOException) {
                close(reset = true)
            }
        }

        fun onPacket(p: IpPacket) {
            try {
                handle(p)
            } catch (e: IOException) {
                close(reset = true)
            }
        }

        private fun handle(p: IpPacket) {
            lastActivity = clock()
            if (p.isRst) {
                close(reset = false)
                return
            }
            if (p.isSyn && !p.isAck) {
                // The app retransmitted its SYN: our SYN-ACK was lost or it's still connecting.
                if (connected && !synAcked) send(IpPacket.SYN or IpPacket.ACK, seq = isn, mss = if (v6) 1440 else 1460)
                return
            }
            if (p.isAck) onAck(p.ack, p.window)
            if (!synAcked) return

            if (p.payloadLength > 0) onData(p)
            if (p.isFin) {
                val finSeq = Seq.add(p.seq, p.payloadLength.toLong())
                if (Seq.diff(finSeq, appNext) == 0L && !appFin) {
                    appFin = true
                    appNext = Seq.add(appNext, 1)
                    send(IpPacket.ACK)
                    if (toRemoteBytes == 0) shutOutput()
                } else if (appFin) {
                    send(IpPacket.ACK)
                }
            }
            maybeFinish()
        }

        private fun onAck(ack: Long, window: Int) {
            appWindow = window
            val advance = Seq.diff(ack, sndUna)
            if (advance <= 0 || Seq.diff(ack, sndNext) > 0) {
                updateInterest() // May be a pure window update that lets us send again.
                return
            }
            var n = advance
            if (!synAcked) {
                synAcked = true
                n -= 1
            }
            val data = minOf(n, unacked.size.toLong()).toInt()
            unacked.drop(data)
            n -= data
            if (finSent && n > 0) finAcked = true
            sndUna = ack
            lastAckProgress = clock()
            retries = 0
            updateInterest()
        }

        private fun onData(p: IpPacket) {
            val d = Seq.diff(p.seq, appNext)
            if (d > 0) { // A gap: ask again for what we're missing.
                send(IpPacket.ACK)
                return
            }
            val skip = (-d).toInt()
            val length = p.payloadLength - skip
            if (length <= 0) { // Pure retransmission.
                send(IpPacket.ACK)
                return
            }
            val accept = minOf(length, maxOf(ourWindow(), 0))
            if (accept <= 0) {
                send(IpPacket.ACK)
                return
            }
            val start = p.payloadOffset + skip
            sniff?.let { buffer ->
                // A TLS ClientHello can span several segments; look at up to SNIFF_LIMIT bytes.
                buffer.append(p.buf, start, minOf(accept, SNIFF_LIMIT - buffer.size))
                val host = buffer.copy(0, buffer.size).let { HostSniffer.hostOf(it, 0, it.size) }
                if (host != null) record.host = host
                if (host != null || buffer.size >= SNIFF_LIMIT) sniff = null
            }
            toRemote.addLast(ByteBuffer.wrap(p.buf.copyOfRange(start, start + accept)))
            toRemoteBytes += accept
            appNext = Seq.add(appNext, accept.toLong())
            record.bytesOut += accept
            touch()
            flushToRemote()
            send(IpPacket.ACK)
        }

        private fun flushToRemote() {
            if (!connected) return
            while (toRemote.isNotEmpty()) {
                val buf = toRemote.first()
                val written = channel.write(buf)
                toRemoteBytes -= written
                if (buf.hasRemaining()) break
                toRemote.removeFirst()
            }
            if (toRemote.isEmpty()) {
                if (appFin) shutOutput()
                if (advertisedSmall) send(IpPacket.ACK) // Window update.
            }
            updateInterest()
        }

        private fun readFromRemote() {
            val inFlight = unacked.size
            val room = minOf(appWindow, WINDOW) - inFlight
            if (room <= 0) {
                updateInterest()
                return
            }
            readBuffer.clear()
            readBuffer.limit(minOf(room, readBuffer.capacity()))
            val n = channel.read(readBuffer)
            if (n < 0) {
                remoteEof = true
                maybeSendFin()
                updateInterest()
                return
            }
            if (n == 0) return
            val bytes = readBuffer.array()
            unacked.append(bytes, 0, n)
            var offset = 0
            while (offset < n) {
                val len = minOf(segment, n - offset)
                send(IpPacket.ACK or IpPacket.PSH, bytes, offset, len)
                sndNext = Seq.add(sndNext, len.toLong())
                offset += len
            }
            record.bytesIn += n
            touch()
            updateInterest()
        }

        private fun maybeSendFin() {
            if (remoteEof && !finSent && unacked.size == 0 && synAcked) {
                send(IpPacket.FIN or IpPacket.ACK)
                sndNext = Seq.add(sndNext, 1)
                finSent = true
                lastAckProgress = clock()
            }
        }

        private fun shutOutput() {
            if (outputShut || !connected) return
            outputShut = true
            runCatching { channel.shutdownOutput() }
        }

        private fun maybeFinish() {
            if (finSent && finAcked && appFin) close(reset = false)
        }

        fun tick(now: Long) {
            if (!connected) {
                if (now - created > CONNECT_TIMEOUT_MS) close(reset = true)
                return
            }
            if (!synAcked) {
                if (now - lastAckProgress > RTO_MS) {
                    if (++retries > MAX_RETRIES) return close(reset = true)
                    send(IpPacket.SYN or IpPacket.ACK, seq = isn, mss = if (v6) 1440 else 1460)
                    lastAckProgress = now
                }
                return
            }
            if (unacked.size > 0 && now - lastAckProgress > RTO_MS * (1 shl minOf(retries, 4))) {
                // Retransmit everything not yet acknowledged.
                if (++retries > MAX_RETRIES) return close(reset = true)
                var offset = 0
                val limit = minOf(unacked.size, maxOf(appWindow, segment))
                while (offset < limit) {
                    val len = minOf(segment, limit - offset)
                    send(IpPacket.ACK or IpPacket.PSH, unacked.copy(offset, len), 0, len, seq = Seq.add(sndUna, offset.toLong()))
                    offset += len
                }
                lastAckProgress = now
            } else if (finSent && !finAcked && now - lastAckProgress > RTO_MS * (1 shl minOf(retries, 4))) {
                if (++retries > MAX_RETRIES) return close(reset = true)
                send(IpPacket.FIN or IpPacket.ACK, seq = Seq.add(sndNext, -1))
                lastAckProgress = now
            }
            maybeSendFin()
            val idle = now - lastActivity
            if (idle > if (appFin || remoteEof) HALF_CLOSED_TIMEOUT_MS else IDLE_TIMEOUT_MS) close(reset = true)
        }

        private fun updateInterest() {
            if (!selectionKey.isValid) return
            var ops = 0
            if (!connected) ops = SelectionKey.OP_CONNECT
            else {
                if (toRemote.isNotEmpty()) ops = ops or SelectionKey.OP_WRITE
                if (synAcked && !remoteEof && unacked.size < minOf(appWindow, WINDOW)) ops = ops or SelectionKey.OP_READ
            }
            selectionKey.interestOps(ops)
        }

        private fun touch() {
            lastActivity = clock()
            record.lastMillis = System.currentTimeMillis()
            dirty = true
        }

        fun close(reset: Boolean) {
            if (tcpFlows.remove(key) == null) return
            if (reset) send(IpPacket.RST or IpPacket.ACK)
            runCatching { selectionKey.cancel() }
            runCatching { channel.close() }
            finish(record)
        }
    }

    // ---- UDP --------------------------------------------------------------------------------

    private fun handleUdp(p: IpPacket, key: String, raw: ByteArray) {
        val existing = udpFlows[key]
        if (existing != null) {
            existing.send(p)
            return
        }
        if (blockedFlows.get(key) != null) {
            onBlockedPacket(raw, raw.size)
            return
        }
        if (quicFlows.get(key) != null) return
        val (owner, isBlocked) = ownerOf(p, key)
        if (isBlocked) {
            newRecord(owner, if (p.dstPort == 53) "DNS" else "UDP", p.dst, p.dstPort, blocked = true)
            onBlockedPacket(raw, raw.size)
            return
        }
        if (p.dstPort == 443) {
            // QUIC hides the server name; dropping it makes apps fall back to TCP, where the
            // name is visible. Recorded once per flow.
            quicFlows.put(key, true)
            newRecord(owner, "QUIC", p.dst, p.dstPort, blocked = false).apply {
                open = false
                host = dnsNames.get(addr(p.dst))
            }
            return
        }
        try {
            val flow = UdpFlow(key, p, owner, newRecord(owner, if (p.dstPort == 53) "DNS" else "UDP", p.dst, p.dstPort, blocked = false))
            udpFlows[key] = flow
            flow.send(p)
        } catch (e: IOException) {
            Log.d(TAG, "UDP open failed: ${e.message}")
        }
    }

    private inner class UdpFlow(val key: String, first: IpPacket, val owner: String?, val record: Record) {
        private val v6 = first.v6
        private val app = first.src
        private val appPort = first.srcPort
        private val remote = first.dst
        private val remotePort = first.dstPort
        private val channel: DatagramChannel = DatagramChannel.open()
        private val selectionKey: SelectionKey
        private var lastActivity = clock()
        private val isDns = remotePort == 53

        init {
            channel.configureBlocking(false)
            protector.protect(channel.socket())
            channel.connect(InetSocketAddress(InetAddress.getByAddress(remote), remotePort))
            selectionKey = channel.register(selector, SelectionKey.OP_READ, this)
        }

        fun send(p: IpPacket) {
            if (isDns && record.host == null) record.host = DnsAnswers.question(p.buf, p.payloadOffset, p.payloadLength)
            try {
                channel.write(ByteBuffer.wrap(p.buf, p.payloadOffset, p.payloadLength))
                record.bytesOut += p.payloadLength
                touch()
            } catch (e: IOException) {
                close()
            }
        }

        fun onReadable() {
            try {
                while (true) {
                    readBuffer.clear()
                    val n = channel.read(readBuffer)
                    if (n <= 0) break
                    val bytes = readBuffer.array()
                    if (isDns) {
                        DnsAnswers.parse(bytes, 0, n).forEach { (address, name) -> address.hostAddress?.let { dnsNames.put(it, name) } }
                    }
                    write(IpPacket.udp(v6, remote, app, remotePort, appPort, bytes, 0, n))
                    record.bytesIn += n
                    touch()
                }
            } catch (e: IOException) {
                close()
            }
        }

        private fun touch() {
            lastActivity = clock()
            record.lastMillis = System.currentTimeMillis()
            dirty = true
        }

        fun tick(now: Long) {
            if (now - lastActivity > if (isDns) DNS_TIMEOUT_MS else UDP_TIMEOUT_MS) close()
        }

        fun close() {
            if (udpFlows.remove(key) == null) return
            runCatching { selectionKey.cancel() }
            runCatching { channel.close() }
            finish(record)
        }
    }

    // ---- Shared -----------------------------------------------------------------------------

    private fun write(packet: ByteArray) {
        try {
            toTunnel.write(packet)
        } catch (_: IOException) {
            // Tunnel full or closed; TCP retransmission covers the former.
        }
    }

    private fun tick(now: Long) {
        tcpFlows.values.toList().forEach { it.tick(now) }
        udpFlows.values.toList().forEach { it.tick(now) }
    }

    private fun applyBlocked(packages: Set<String>) {
        blocked = packages
        blockedFlows.clear()
        tcpFlows.values.filter { it.owner != null && it.owner in packages }.forEach { it.close(reset = true) }
        udpFlows.values.filter { it.owner != null && it.owner in packages }.forEach { it.close() }
    }

    private class Record(
        val id: Long,
        val startMillis: Long,
        val packageName: String?,
        val protocol: String,
        val address: String,
        val port: Int,
        val blocked: Boolean,
    ) {
        var host: String? = null
        var lastMillis = startMillis
        var bytesOut = 0L
        var bytesIn = 0L
        var open = !blocked
    }

    private fun newRecord(owner: String?, protocol: String, dst: ByteArray, port: Int, blocked: Boolean): Record {
        val now = System.currentTimeMillis()
        val address = addr(dst)
        val record = Record(nextId.getAndIncrement(), now, owner, protocol, address, port, blocked)
        record.host = dnsNames.get(address)
        records[record.id] = record
        if (records.size > MAX_RECORDS) {
            // Forget the oldest finished connections first.
            val iterator = records.values.iterator()
            while (records.size > MAX_RECORDS && iterator.hasNext()) {
                if (!iterator.next().open) iterator.remove()
            }
        }
        dirty = true
        return record
    }

    private fun finish(record: Record) {
        record.open = false
        record.lastMillis = System.currentTimeMillis()
        if (record.host == null) record.host = dnsNames.get(record.address)
        dirty = true
        finished.add(
            ConnectionAttempt(
                timeMillis = record.startMillis,
                packageName = record.packageName,
                protocol = record.protocol,
                address = record.address,
                port = record.port,
                domain = record.host,
                allowed = true,
                bytes = record.bytesIn + record.bytesOut,
            ),
        )
    }

    private fun publish(now: Long, force: Boolean = false) {
        if (!dirty && !force) return
        dirty = false
        lastPublish = now
        LiveMonitor.publish(
            records.values.toList().asReversed().map { r ->
                LiveEntry(
                    id = r.id,
                    startMillis = r.startMillis,
                    lastMillis = r.lastMillis,
                    packageName = r.packageName,
                    protocol = r.protocol,
                    host = r.host ?: dnsNames.get(r.address),
                    address = r.address,
                    port = r.port,
                    bytesOut = r.bytesOut,
                    bytesIn = r.bytesIn,
                    open = r.open && running,
                    blocked = r.blocked,
                )
            },
        )
    }

    private fun addr(raw: ByteArray): String = InetAddress.getByAddress(raw).hostAddress ?: ""

    private companion object {
        const val TAG = "ForwardingEngine"
        const val TICK_MS = 100L
        const val PUBLISH_MS = 500L
        const val WINDOW = 65_535
        const val RTO_MS = 400L
        const val MAX_RETRIES = 8
        const val CONNECT_TIMEOUT_MS = 20_000L
        const val IDLE_TIMEOUT_MS = 10 * 60_000L
        const val HALF_CLOSED_TIMEOUT_MS = 60_000L
        const val UDP_TIMEOUT_MS = 60_000L
        const val DNS_TIMEOUT_MS = 15_000L
        const val MAX_INBOX = 10_000
        const val MAX_RECORDS = 500
        const val SNIFF_LIMIT = 4096
    }
}

/** Small LRU map (android.util.LruCache isn't available in JVM unit tests). */
private class Lru<K, V>(private val max: Int) {
    private val map = object : LinkedHashMap<K, V>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<K, V>?) = size > max
    }
    fun get(key: K): V? = map[key]
    fun put(key: K, value: V) {
        map[key] = value
    }
    fun clear() = map.clear()
}
