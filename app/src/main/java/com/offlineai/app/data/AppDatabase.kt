package com.offlineai.app.data

import androidx.room.*

/**
 * 对话消息实体
 */
@Entity(tableName = "messages")
data class MessageEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "session_id") val sessionId: String,
    @ColumnInfo(name = "role") val role: String,      // "user" 或 "assistant"
    @ColumnInfo(name = "content") val content: String,
    @ColumnInfo(name = "timestamp") val timestamp: Long = System.currentTimeMillis()
)

/**
 * 消息 DAO
 */
@Dao
interface MessageDao {
    @Query("SELECT * FROM messages WHERE session_id = :sessionId ORDER BY timestamp ASC")
    suspend fun getSessionMessages(sessionId: String): List<MessageEntity>

    @Query("SELECT * FROM messages ORDER BY timestamp DESC LIMIT 1")
    suspend fun getLatestMessage(): MessageEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(message: MessageEntity): Long

    @Delete
    suspend fun delete(message: MessageEntity)

    @Query("DELETE FROM messages WHERE session_id = :sessionId")
    suspend fun deleteSession(sessionId: String)
}

/**
 * Room 数据库
 */
@Database(entities = [MessageEntity::class], version = 1, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun messageDao(): MessageDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getInstance(context: android.content.Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "offlineai.db"
                )
                    .fallbackToDestructiveMigration()
                    .build()
                    .also { INSTANCE = it }
            }
        }
    }
}
