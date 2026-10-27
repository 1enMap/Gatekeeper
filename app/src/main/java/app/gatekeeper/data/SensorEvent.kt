package app.gatekeeper.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * One period during which the camera or microphone was in use by some app. Android doesn't tell
 * ordinary apps *which* app it was, so [foregroundPackage] is the app that was on screen then.
 */
@Entity(tableName = "sensor_events", indices = [Index("startMillis")])
data class SensorEvent(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** [SENSOR_CAMERA] or [SENSOR_MIC]. */
    val sensor: String,
    val startMillis: Long,
    /** null while still in use. */
    val endMillis: Long?,
    /** App on screen when it started, or null if none (screen off, home screen). */
    val foregroundPackage: String?,
    val screenOn: Boolean,
    val locked: Boolean,
    /** Started while the screen was off or no app was open: worth a look. */
    val suspicious: Boolean,
) {
    companion object {
        const val SENSOR_CAMERA = "camera"
        const val SENSOR_MIC = "mic"
    }
}
