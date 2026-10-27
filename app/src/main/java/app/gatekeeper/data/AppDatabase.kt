package app.gatekeeper.data

import android.content.Context
import androidx.room.AutoMigration
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [AppRule::class, ConnectionAttempt::class, SensorEvent::class],
    version = 5,
    exportSchema = true,
    autoMigrations = [AutoMigration(from = 1, to = 2), AutoMigration(from = 2, to = 3), AutoMigration(from = 3, to = 4), AutoMigration(from = 4, to = 5)],
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun ruleDao(): RuleDao
    abstract fun connectionDao(): ConnectionDao
    abstract fun sensorDao(): SensorDao

    companion object {
        fun create(context: Context): AppDatabase =
            Room.databaseBuilder(context, AppDatabase::class.java, "gatekeeper.db").build()
    }
}
