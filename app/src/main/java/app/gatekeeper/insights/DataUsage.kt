package app.gatekeeper.insights

import android.app.usage.NetworkStats
import android.app.usage.NetworkStatsManager
import android.content.Context
import android.net.ConnectivityManager
import android.os.Process
import android.util.Log

/** Bytes an app (Linux UID) sent and received, split by whether it was on screen. */
data class UsageRow(
    val uid: Int,
    val packages: List<String>,
    val foregroundRx: Long = 0,
    val foregroundTx: Long = 0,
    val backgroundRx: Long = 0,
    val backgroundTx: Long = 0,
    val wifi: Long = 0,
    val mobile: Long = 0,
) {
    val background get() = backgroundRx + backgroundTx
    val foreground get() = foregroundRx + foregroundTx
    val total get() = background + foreground
}

/**
 * Reads Android's own per-app network counters (the same data as Settings → Data usage).
 * Needs Usage Access; Gatekeeper itself never touches the network.
 */
object DataUsage {
    private const val TAG = "DataUsage"

    fun query(context: Context, startMillis: Long, endMillis: Long): List<UsageRow> {
        val stats = context.getSystemService(NetworkStatsManager::class.java)
        val rows = HashMap<Int, UsageRow>()
        @Suppress("DEPRECATION") // NetworkStatsManager still takes the legacy type constants.
        for (type in listOf(ConnectivityManager.TYPE_WIFI, ConnectivityManager.TYPE_MOBILE)) {
            val result = try {
                stats.querySummary(type, null, startMillis, endMillis)
            } catch (e: Exception) {
                Log.w(TAG, "querySummary($type) failed", e)
                continue
            }
            result.closing { buckets ->
                val bucket = NetworkStats.Bucket()
                while (buckets.hasNextBucket()) {
                    buckets.getNextBucket(bucket)
                    val bytes = bucket.rxBytes + bucket.txBytes
                    if (bytes == 0L) continue
                    val uid = bucket.uid
                    val row = rows[uid] ?: UsageRow(uid, packagesFor(context, uid))
                    val foreground = bucket.state == NetworkStats.Bucket.STATE_FOREGROUND
                    @Suppress("DEPRECATION")
                    rows[uid] = row.copy(
                        foregroundRx = row.foregroundRx + if (foreground) bucket.rxBytes else 0,
                        foregroundTx = row.foregroundTx + if (foreground) bucket.txBytes else 0,
                        backgroundRx = row.backgroundRx + if (foreground) 0 else bucket.rxBytes,
                        backgroundTx = row.backgroundTx + if (foreground) 0 else bucket.txBytes,
                        wifi = row.wifi + if (type == ConnectivityManager.TYPE_WIFI) bytes else 0,
                        mobile = row.mobile + if (type == ConnectivityManager.TYPE_MOBILE) bytes else 0,
                    )
                }
            }
        }
        return rows.values.sortedByDescending { it.background }
    }

    private fun packagesFor(context: Context, uid: Int): List<String> =
        context.packageManager.getPackagesForUid(uid)?.toList().orEmpty()

    /** Name for UIDs that aren't a normal app. */
    fun specialName(uid: Int): String? = when (uid) {
        Process.SYSTEM_UID -> "Android system"
        0 -> "Android system (root)"
        NetworkStats.Bucket.UID_REMOVED -> "Uninstalled apps"
        NetworkStats.Bucket.UID_TETHERING -> "Hotspot & tethering"
        else -> null
    }
}

private inline fun <T> NetworkStats.closing(block: (NetworkStats) -> T): T = try {
    block(this)
} finally {
    close()
}
