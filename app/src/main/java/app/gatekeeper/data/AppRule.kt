package app.gatekeeper.data

import androidx.room.Entity
import androidx.room.PrimaryKey

/** A per-app rule. Apps without a row are treated as [Mode.ALLOWED]. */
@Entity(tableName = "rules")
data class AppRule(
    @PrimaryKey val packageName: String,
    val mode: Mode,
    /** Seconds the app keeps internet after leaving the screen (only for [Mode.WHILE_OPEN]). */
    val graceSeconds: Int = DEFAULT_GRACE_SECONDS,
) {
    companion object {
        const val DEFAULT_GRACE_SECONDS = 30
        const val MAX_GRACE_SECONDS = 120
    }
}
