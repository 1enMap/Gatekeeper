package app.gatekeeper.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface ConnectionDao {
    @Insert
    suspend fun insertAll(attempts: List<ConnectionAttempt>)

    @Query("SELECT * FROM connection_attempts WHERE timeMillis >= :since ORDER BY timeMillis DESC LIMIT :limit")
    fun observeSince(since: Long, limit: Int = 2000): Flow<List<ConnectionAttempt>>

    @Query("SELECT * FROM connection_attempts WHERE packageName = :packageName ORDER BY timeMillis DESC LIMIT :limit")
    fun observeForApp(packageName: String, limit: Int = 200): Flow<List<ConnectionAttempt>>

    @Query("DELETE FROM connection_attempts WHERE timeMillis < :before")
    suspend fun deleteOlderThan(before: Long)

    @Query("DELETE FROM connection_attempts")
    suspend fun clear()
}
