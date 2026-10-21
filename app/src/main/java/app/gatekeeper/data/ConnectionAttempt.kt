package app.gatekeeper.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * One connection a blocked app tried to make. Seen only because the app's packets were routed
 * into Gatekeeper's tunnel and dropped there; nothing ever left the phone.
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
)
