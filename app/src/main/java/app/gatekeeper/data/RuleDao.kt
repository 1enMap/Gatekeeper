package app.gatekeeper.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface RuleDao {
    @Query("SELECT * FROM rules")
    fun observeAll(): Flow<List<AppRule>>

    @Query("SELECT * FROM rules WHERE packageName = :packageName")
    suspend fun get(packageName: String): AppRule?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(rule: AppRule)

    @Query("DELETE FROM rules WHERE packageName = :packageName")
    suspend fun delete(packageName: String)

    @Query("DELETE FROM rules WHERE packageName IN (:packageNames)")
    suspend fun deleteAll(packageNames: Collection<String>)
}
