package com.example.db

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "chat_sessions")
data class ChatSession(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val provider: String,
    val modelName: String,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)

@Entity(
    tableName = "chat_messages",
    foreignKeys = [
        ForeignKey(
            entity = ChatSession::class,
            parentColumns = ["id"],
            childColumns = ["sessionId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("sessionId")]
)
data class ChatMessageEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sessionId: Long,
    val role: String, // "user", "agent", "system", "task_execution"
    val content: String,
    val actionType: String? = null,
    val actionTarget: String? = null,
    val status: String? = null,
    val resultSnippet: String? = null,
    val timestamp: Long = System.currentTimeMillis()
)

data class ChatSessionWithMessages(
    @Embedded val session: ChatSession,
    @Relation(
        parentColumn = "id",
        entityColumn = "sessionId"
    )
    val messages: List<ChatMessageEntity>
)

@Dao
interface ChatDao {
    @Query("SELECT * FROM chat_sessions ORDER BY updatedAt DESC")
    fun getAllSessions(): Flow<List<ChatSession>>

    @Transaction
    @Query("SELECT * FROM chat_sessions WHERE id = :sessionId")
    fun getSessionWithMessages(sessionId: Long): Flow<ChatSessionWithMessages?>

    @Transaction
    @Query("SELECT * FROM chat_sessions WHERE id = :sessionId")
    suspend fun getSessionWithMessagesSync(sessionId: Long): ChatSessionWithMessages?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSession(session: ChatSession): Long

    @Update
    suspend fun updateSession(session: ChatSession)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMessage(message: ChatMessageEntity): Long

    @Query("DELETE FROM chat_sessions WHERE id = :sessionId")
    suspend fun deleteSession(sessionId: Long)
    
    @Query("UPDATE chat_sessions SET updatedAt = :timestamp WHERE id = :sessionId")
    suspend fun updateSessionTimestamp(sessionId: Long, timestamp: Long)

    @Query("DELETE FROM chat_messages WHERE sessionId = :sessionId")
    suspend fun deleteMessagesForSession(sessionId: Long)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMessages(messages: List<ChatMessageEntity>)

    @Transaction
    suspend fun replaceSessionMessages(sessionId: Long, messages: List<ChatMessageEntity>) {
        deleteMessagesForSession(sessionId)
        if (messages.isNotEmpty()) {
            insertMessages(messages)
        }
        updateSessionTimestamp(sessionId, System.currentTimeMillis())
    }
}
