package com.honor.share.history

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "transfers")
data class HistoryEntity(
    @PrimaryKey val id: String,
    val direction: String,
    val deviceName: String,
    val fileCount: Int,
    val totalBytes: Long,
    val status: String,
    val createdAt: Long,
)

@Entity(tableName = "shared_files")
data class SharedFileEntity(
    @PrimaryKey val id: String,
    val name: String,
    val mimeType: String,
    val size: Long,
    val uri: String,
    val direction: String,
    val deviceName: String,
    val transferId: String,
    val createdAt: Long,
)

@Dao
interface HistoryDao {
    @Query("SELECT * FROM transfers ORDER BY createdAt DESC")
    fun observe(): Flow<List<HistoryEntity>>

    @Insert
    suspend fun insert(entity: HistoryEntity)

    @Query("DELETE FROM transfers")
    suspend fun clear()

    @Query("SELECT COUNT(*) FROM transfers")
    suspend fun count(): Int
}

@Dao
interface SharedFileDao {
    @Query("SELECT * FROM shared_files ORDER BY createdAt DESC")
    fun observe(): Flow<List<SharedFileEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(files: List<SharedFileEntity>)

    @Query("DELETE FROM shared_files WHERE id = :id")
    suspend fun delete(id: String)

    @Query("DELETE FROM shared_files")
    suspend fun clear()
}

@Database(
    entities = [HistoryEntity::class, SharedFileEntity::class],
    version = 2,
    exportSchema = false,
)
abstract class HistoryDb : RoomDatabase() {
    abstract fun dao(): HistoryDao
    abstract fun files(): SharedFileDao
}

private val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS shared_files (
                id TEXT NOT NULL PRIMARY KEY,
                name TEXT NOT NULL,
                mimeType TEXT NOT NULL,
                size INTEGER NOT NULL,
                uri TEXT NOT NULL,
                direction TEXT NOT NULL,
                deviceName TEXT NOT NULL,
                transferId TEXT NOT NULL,
                createdAt INTEGER NOT NULL
            )
            """.trimIndent(),
        )
    }
}

class HistoryRepository(context: Context) {
    private val db = Room.databaseBuilder(context, HistoryDb::class.java, "honor-share-history.db")
        .addMigrations(MIGRATION_1_2)
        .build()
    val dao: HistoryDao = db.dao()
    val files: SharedFileDao = db.files()
}
