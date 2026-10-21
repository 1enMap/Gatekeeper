package app.gatekeeper.insights

import android.content.Context
import android.net.ConnectivityManager
import android.os.Process
import android.util.Log
import android.util.LruCache
import app.gatekeeper.data.ConnectionAttempt
import app.gatekeeper.data.ConnectionDao
import java.net.InetSocketAddress

/**
 * Records which servers blocked apps tried to reach. Called on the tunnel drainer thread for
 * every dropped packet; must stay cheap. Rows are buffered and written by [flush].
 */
class TrafficObserver(
    context: Context,
    private val dao: ConnectionDao,
    /** Packages currently routed into the tunnel; used when Android can't name the owner. */
    private val blockedPackages: () -> Set<String>,
) {
    private val connectivity = context.getSystemService(ConnectivityManager::class.java)
    private val packageManager = context.packageManager
    private val ownUid = Process.myUid()

    /** Flow (5-tuple) → owning package, so retransmissions don't cost another lookup. */
    private val owners = LruCache<String, Owner>(1024)
    private val pending = LinkedHashMap<String, ConnectionAttempt>()
    private val lock = Any()

    private class Owner(val packageName: String?)

    fun onPacket(buf: ByteArray, length: Int) {
        val p = try {
            PacketParser.parse(buf, length)
        } catch (_: Exception) {
            null
        } ?: return

        val flowKey = "${p.protocol}|${p.source.hostAddress}|${p.sourcePort}|${p.destination.hostAddress}|${p.destinationPort}"
        val owner = owners.get(flowKey) ?: Owner(ownerOf(p)).also { owners.put(flowKey, it) }

        val protocol = when {
            p.dnsQuestion != null || p.destinationPort == 53 || p.destinationPort == 853 -> "DNS"
            p.protocol == PacketParser.TCP -> "TCP"
            else -> "UDP"
        }
        val address = p.destination.hostAddress ?: return
        val key = "${owner.packageName}|$protocol|$address|${p.destinationPort}|${p.dnsQuestion}"
        synchronized(lock) {
            val existing = pending[key]
            if (existing != null) {
                // UDP flows (e.g. QUIC) send many packets; count each flow once per batch.
                if (p.protocol == PacketParser.TCP || p.dnsQuestion != null) {
                    pending[key] = existing.copy(count = existing.count + 1)
                }
            } else if (pending.size < MAX_PENDING) {
                pending[key] = ConnectionAttempt(
                    timeMillis = System.currentTimeMillis(),
                    packageName = owner.packageName,
                    protocol = protocol,
                    address = address,
                    port = p.destinationPort,
                    domain = p.dnsQuestion,
                )
            }
        }
    }

    /** Writes buffered attempts to the database. */
    suspend fun flush() {
        val batch = synchronized(lock) {
            if (pending.isEmpty()) return
            pending.values.toList().also { pending.clear() }
        }
        try {
            dao.insertAll(batch)
        } catch (e: Exception) {
            Log.w(TAG, "Could not store connection attempts", e)
        }
    }

    private fun ownerOf(p: PacketInfo): String? {
        val uid = try {
            connectivity.getConnectionOwnerUid(
                p.protocol,
                InetSocketAddress(p.source, p.sourcePort),
                InetSocketAddress(p.destination, p.destinationPort),
            )
        } catch (_: Exception) {
            Process.INVALID_UID
        }
        val blocked = blockedPackages()
        if (uid != Process.INVALID_UID && uid != ownUid) {
            val packages = packageManager.getPackagesForUid(uid)
            if (!packages.isNullOrEmpty()) return packages.firstOrNull { it in blocked } ?: packages.first()
        }
        // DNS look-ups are sometimes made on the app's behalf by the system; if only one app is
        // blocked it must be that one.
        return blocked.singleOrNull()
    }

    private companion object {
        const val TAG = "TrafficObserver"
        const val MAX_PENDING = 2000
    }
}
