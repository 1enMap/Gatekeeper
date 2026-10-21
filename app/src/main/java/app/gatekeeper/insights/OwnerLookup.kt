package app.gatekeeper.insights

import android.content.Context
import android.net.ConnectivityManager
import android.os.Process
import android.util.LruCache
import app.gatekeeper.monitor.ForwardingEngine
import java.net.InetAddress
import java.net.InetSocketAddress

/** Asks Android which app owns a connection seen in the tunnel (only works for the active VPN). */
class OwnerLookup(context: Context) : ForwardingEngine.Owners {
    private val connectivity = context.getSystemService(ConnectivityManager::class.java)
    private val packageManager = context.packageManager
    private val ownUid = Process.myUid()
    private val packagesByUid = LruCache<Int, Array<String>>(256)

    /** UID of the app that opened the connection, or [Process.INVALID_UID]. */
    override fun uidOf(protocol: Int, src: InetAddress, srcPort: Int, dst: InetAddress, dstPort: Int): Int = try {
        connectivity.getConnectionOwnerUid(protocol, InetSocketAddress(src, srcPort), InetSocketAddress(dst, dstPort))
    } catch (_: Exception) {
        Process.INVALID_UID
    }

    override fun packagesOf(uid: Int): Array<String> {
        if (uid == Process.INVALID_UID || uid == ownUid) return emptyArray()
        packagesByUid.get(uid)?.let { return it }
        val packages = packageManager.getPackagesForUid(uid) ?: emptyArray()
        packagesByUid.put(uid, packages)
        return packages
    }
}
