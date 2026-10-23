package app.gatekeeper.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/** A per-app rule. Apps without a row are treated as [Mode.ALLOWED], [Route.DIRECT], trackers allowed. */
@Entity(tableName = "rules")
data class AppRule(
    @PrimaryKey val packageName: String,
    val mode: Mode,
    /** Seconds the app keeps internet after leaving the screen (only for [Mode.WHILE_OPEN]). */
    val graceSeconds: Int = DEFAULT_GRACE_SECONDS,
    /** Direct or through Tor (when Tor mode is on). */
    @ColumnInfo(defaultValue = "DIRECT") val route: Route = Route.DIRECT,
    /** Drop this app's connections to known tracker servers. */
    @ColumnInfo(defaultValue = "0") val blockTrackers: Boolean = false,
) {
    companion object {
        const val DEFAULT_GRACE_SECONDS = 30
        const val MAX_GRACE_SECONDS = 120
    }
}
