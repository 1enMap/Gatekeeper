package app.gatekeeper.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * One connection an app made or tried to make, as seen in Gatekeeper's tunnel. Blocked attempts
 * were dropped there; [allowed] connections were relayed by the live monitor.
 */
@Entity(
    tableName = "connection_attempts",
    indices = [Index("timeMillis"), Index("packageName")],
)
data class ConnectionAttempt(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val timeMillis: Long,
    /** null when Android could not tell which app opened the connection. */
    val packageName: String?,
    /** "TCP", "UDP" or "DNS". */
    val protocol: String,
    val address: String,
    val port: Int,
    /** Host name, from a DNS query or the app's own lookup. null if unknown. */
    val domain: String?,
    /** How many identical attempts were folded into this row (retries within a minute). */
    val count: Int = 1,
    /** True if the connection was let through (live monitor), false if it was blocked. */
    @ColumnInfo(defaultValue = "0") val allowed: Boolean = false,
    /** Bytes sent + received (live monitor only). */
    @ColumnInfo(defaultValue = "0") val bytes: Long = 0,
)
