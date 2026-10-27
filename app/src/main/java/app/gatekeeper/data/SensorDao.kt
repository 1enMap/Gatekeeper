package app.gatekeeper.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface SensorDao {
    @Insert
    suspend fun insert(event: SensorEvent): Long

    @Query("UPDATE sensor_events SET endMillis = :end WHERE id = :id")
    suspend fun end(id: Long, end: Long)

    /** Sessions left open by a crash or restart are closed at their start time. */
    @Query("UPDATE sensor_events SET endMillis = startMillis WHERE endMillis IS NULL")
    suspend fun closeDangling()

    @Query("SELECT * FROM sensor_events WHERE startMillis >= :since ORDER BY startMillis DESC")
    fun observeSince(since: Long): Flow<List<SensorEvent>>

    @Query("DELETE FROM sensor_events WHERE startMillis < :before")
    suspend fun deleteOlderThan(before: Long)

    @Query("DELETE FROM sensor_events")
    suspend fun clear()
}
