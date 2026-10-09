package app.raven.data.db

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.Transaction
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface IdentityDao {
    @Query("SELECT * FROM identity WHERE slot = 0")
    fun get(): IdentityEntity?

    @Query("SELECT * FROM identity WHERE slot = 0")
    fun observe(): Flow<IdentityEntity?>

    @Upsert
    fun put(identity: IdentityEntity)
}

@Dao
interface ContactDao {
    @Query("SELECT * FROM contacts WHERE peerId = :peerId")
    fun get(peerId: ByteArray): ContactEntity?

    @Query("SELECT * FROM contacts ORDER BY addedAt")
    fun all(): List<ContactEntity>

    @Query("SELECT * FROM contacts WHERE peerId = :peerId")
    fun observe(peerId: ByteArray): Flow<ContactEntity?>

    @Query("UPDATE contacts SET nickname = :nickname WHERE peerId = :peerId")
    fun setNickname(
        peerId: ByteArray,
        nickname: String,
    )

    @Query("UPDATE contacts SET avatar = :avatar WHERE peerId = :peerId")
    fun setAvatar(
        peerId: ByteArray,
        avatar: ByteArray?,
    )

    /** One row per contact with its latest chat message, newest conversations first. */
    @Query(
        """
        SELECT c.peerId, c.nickname, c.avatar, c.blocked,
               m.type AS lastType, m.text AS lastText, m.outgoing AS lastOutgoing, m.status AS lastStatus,
               COALESCE(m.receivedAt, c.addedAt) AS lastActivity,
               (SELECT COUNT(*) FROM messages u WHERE u.peerId = c.peerId AND u.outgoing = 0 AND u.status = 0
                    AND u.type != 2) AS unread
        FROM contacts c
        LEFT JOIN messages m ON m.messageId = (
            SELECT messageId FROM messages WHERE peerId = c.peerId AND type != 2
            ORDER BY receivedAt DESC, counter DESC LIMIT 1)
        ORDER BY lastActivity DESC
        """,
    )
    fun summaries(): Flow<List<ChatSummary>>

    @Upsert
    fun put(contact: ContactEntity)

    @Query("UPDATE contacts SET blocked = :blocked WHERE peerId = :peerId")
    fun setBlocked(
        peerId: ByteArray,
        blocked: Boolean,
    )

    @Query("DELETE FROM contacts WHERE peerId = :peerId")
    fun delete(peerId: ByteArray)
}

@Dao
interface MessageDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    fun insert(message: MessageEntity): Long

    @Query("SELECT * FROM messages WHERE peerId = :peerId ORDER BY receivedAt, counter")
    fun chat(peerId: ByteArray): List<MessageEntity>

    @Query("SELECT * FROM messages WHERE peerId = :peerId ORDER BY receivedAt, counter")
    fun observeChat(peerId: ByteArray): Flow<List<MessageEntity>>

    @Query("SELECT messageId FROM messages WHERE peerId = :peerId AND outgoing = 0 AND status = 0 AND type != 2")
    fun unreadIds(peerId: ByteArray): List<ByteArray>

    @Query("UPDATE messages SET status = 3 WHERE peerId = :peerId AND outgoing = 0 AND status = 0")
    fun markIncomingRead(peerId: ByteArray)

    @Query("UPDATE messages SET status = :status WHERE messageId = :messageId AND peerId = :peerId")
    fun setStatus(
        peerId: ByteArray,
        messageId: ByteArray,
        status: Int,
    )

    @Query("SELECT status FROM messages WHERE peerId = :peerId AND messageId = :messageId")
    fun status(
        peerId: ByteArray,
        messageId: ByteArray,
    ): Int?

    @Query("SELECT imageId FROM messages WHERE peerId = :peerId AND imageId IS NOT NULL")
    fun imageIds(peerId: ByteArray): List<ByteArray>

    @Query("DELETE FROM messages WHERE peerId = :peerId")
    fun deleteChat(peerId: ByteArray)
}

@Dao
interface OutboxDao {
    @Upsert
    fun put(entry: OutboxEntity)

    @Query("SELECT * FROM outbox WHERE messageId = :messageId")
    fun get(messageId: ByteArray): OutboxEntity?

    @Query("DELETE FROM outbox WHERE messageId = :messageId")
    fun remove(messageId: ByteArray)

    @Query("SELECT * FROM outbox WHERE gaveUp = 0 ORDER BY queuedAt")
    fun pending(): List<OutboxEntity>

    @Query("DELETE FROM outbox WHERE peerId = :peerId")
    fun deleteFor(peerId: ByteArray)
}

@Dao
interface ReceivedIdDao {
    /** -1 when the row already existed. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    fun insert(id: ReceivedIdEntity): Long

    @Query("SELECT COUNT(*) FROM received_ids WHERE peerId = :peerId AND messageId = :messageId")
    fun count(
        peerId: ByteArray,
        messageId: ByteArray,
    ): Int
}

@Dao
interface CounterDao {
    @Query("SELECT last FROM counters WHERE peerId = :peerId")
    fun last(peerId: ByteArray): Long?

    @Upsert
    fun put(counter: CounterEntity)

    @Transaction
    fun next(peerId: ByteArray): Long = ((last(peerId) ?: 0L) + 1).also { put(CounterEntity(peerId, it)) }

    @Query("DELETE FROM counters WHERE peerId = :peerId")
    fun delete(peerId: ByteArray)
}

/** Everything Raven stores, encrypted as a whole with SQLCipher (spec §7, D17). */
@Database(
    entities = [
        IdentityEntity::class,
        ContactEntity::class,
        MessageEntity::class,
        OutboxEntity::class,
        ReceivedIdEntity::class,
        CounterEntity::class,
    ],
    version = 1,
    exportSchema = false,
)
abstract class MeshDatabase : RoomDatabase() {
    abstract fun identity(): IdentityDao

    abstract fun contacts(): ContactDao

    abstract fun messages(): MessageDao

    abstract fun outbox(): OutboxDao

    abstract fun receivedIds(): ReceivedIdDao

    abstract fun counters(): CounterDao
}

/** A chat-list row (D84). [lastType] etc. are null when the chat is still empty. */
class ChatSummary(
    val peerId: ByteArray,
    val nickname: String,
    val avatar: ByteArray?,
    val blocked: Boolean,
    val lastType: Int?,
    val lastText: String?,
    val lastOutgoing: Boolean?,
    val lastStatus: Int?,
    val lastActivity: Long,
    val unread: Int,
)
