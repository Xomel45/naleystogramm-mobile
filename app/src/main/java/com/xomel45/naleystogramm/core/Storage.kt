package com.xomel45.naleystogramm.core

import android.content.Context
import androidx.room.*
import kotlinx.coroutines.flow.Flow

// Mirrors: src/core/storage.h — Room replaces SQLite/SQLCipher direct calls.

// ── Entities ──────────────────────────────────────────────────────────────────

@Entity(tableName = "contacts")
data class ContactEntity(
    @PrimaryKey val peerId: String,
    val displayName: String,
    val publicKey: String,      // Base64 IK (filled after X3DH)
    val address: String,        // last known host:port
    val lastSeen: Long,
    val isOnline: Boolean = false
)

@Entity(tableName = "messages", indices = [Index("peerId")])
data class MessageEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val peerId: String,
    val content: String,        // plaintext after decrypt; never stored encrypted
    val timestamp: Long,
    val isOutgoing: Boolean,
    val isRead: Boolean = false,
    val type: String = "text"   // text | image | audio | file
)

@Entity(tableName = "file_transfers")
data class FileTransferEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val peerId: String,
    val filename: String,
    val size: Long,
    val status: String,         // pending | active | done | failed | cancelled
    val timestamp: Long,
    val isOutgoing: Boolean,
    val localPath: String = ""
)

// ── DAOs ──────────────────────────────────────────────────────────────────────

@Dao
interface ContactDao {
    @Query("SELECT * FROM contacts ORDER BY displayName ASC")
    fun all(): Flow<List<ContactEntity>>

    @Query("SELECT * FROM contacts WHERE peerId = :id")
    suspend fun byId(id: String): ContactEntity?

    @Upsert
    suspend fun upsert(contact: ContactEntity)

    @Query("DELETE FROM contacts WHERE peerId = :id")
    suspend fun delete(id: String)

    @Query("UPDATE contacts SET isOnline = :online WHERE peerId = :id")
    suspend fun setOnline(id: String, online: Boolean)

    @Query("UPDATE contacts SET address = :address, lastSeen = :lastSeen WHERE peerId = :id")
    suspend fun updateAddress(id: String, address: String, lastSeen: Long)
}

@Dao
interface MessageDao {
    @Query("SELECT * FROM messages WHERE peerId = :peerId ORDER BY timestamp ASC")
    fun forPeer(peerId: String): Flow<List<MessageEntity>>

    @Query("SELECT * FROM messages WHERE peerId = :peerId ORDER BY timestamp DESC LIMIT 1")
    suspend fun lastFor(peerId: String): MessageEntity?

    @Insert
    suspend fun insert(msg: MessageEntity): Long

    @Query("UPDATE messages SET isRead = 1 WHERE peerId = :peerId AND isOutgoing = 0 AND isRead = 0")
    suspend fun markRead(peerId: String)

    @Query("SELECT COUNT(*) FROM messages WHERE peerId = :peerId AND isRead = 0 AND isOutgoing = 0")
    fun unreadCount(peerId: String): Flow<Int>

    @Query("DELETE FROM messages WHERE peerId = :peerId")
    suspend fun deleteAll(peerId: String)
}

@Dao
interface FileTransferDao {
    @Query("SELECT * FROM file_transfers ORDER BY timestamp DESC")
    fun all(): Flow<List<FileTransferEntity>>

    @Query("SELECT * FROM file_transfers WHERE peerId = :peerId ORDER BY timestamp DESC")
    fun forPeer(peerId: String): Flow<List<FileTransferEntity>>

    @Insert
    suspend fun insert(transfer: FileTransferEntity): Long

    @Query("UPDATE file_transfers SET status = :status, localPath = :path WHERE id = :id")
    suspend fun updateStatus(id: Long, status: String, path: String = "")
}

// ── Database ──────────────────────────────────────────────────────────────────

@Database(
    entities = [ContactEntity::class, MessageEntity::class, FileTransferEntity::class],
    version = 1,
    exportSchema = false
)
abstract class NaleystogrammDatabase : RoomDatabase() {
    abstract fun contactDao(): ContactDao
    abstract fun messageDao(): MessageDao
    abstract fun fileTransferDao(): FileTransferDao
}

// ── Storage — entry point ─────────────────────────────────────────────────────

object Storage {
    private lateinit var db: NaleystogrammDatabase

    fun init(context: Context) {
        db = Room.databaseBuilder(
            context.applicationContext,
            NaleystogrammDatabase::class.java,
            "naleystogramm.db"
        ).build()
        Logger.i("Storage", "Room database opened")
    }

    val contacts: ContactDao       get() = db.contactDao()
    val messages: MessageDao       get() = db.messageDao()
    val fileTransfers: FileTransferDao get() = db.fileTransferDao()
}
