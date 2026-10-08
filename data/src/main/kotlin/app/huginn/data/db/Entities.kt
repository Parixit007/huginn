package app.huginn.data.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** This phone (one row). [fileKey] encrypts photos on disk; it is protected by the database's own encryption. */
@Entity(tableName = "identity")
class IdentityEntity(
    @PrimaryKey val slot: Int = 0,
    val deviceId: ByteArray,
    val nickname: String,
    val avatar: ByteArray?,
    val fileKey: ByteArray,
)

/** A paired contact (spec D37). [rootKey] is the static contact key (D10). */
@Entity(tableName = "contacts")
class ContactEntity(
    @PrimaryKey val peerId: ByteArray,
    val nickname: String,
    val rootKey: ByteArray,
    val blocked: Boolean,
    val avatar: ByteArray?,
    val addedAt: Long,
)

/** Chat history. Photos are stored as encrypted files named by [imageId]. */
@Entity(tableName = "messages", indices = [Index("peerId")])
class MessageEntity(
    @PrimaryKey val messageId: ByteArray,
    val peerId: ByteArray,
    val outgoing: Boolean,
    val type: Int,
    val text: String?,
    val reactionTarget: ByteArray?,
    val imageId: ByteArray?,
    val counter: Long,
    val sentAt: Long,
    val receivedAt: Long,
    val status: Int,
)

/** Messages still trying, or given up (spec D75, D78). [packet] is the encoded inner packet. */
@Entity(tableName = "outbox")
class OutboxEntity(
    @PrimaryKey val messageId: ByteArray,
    val peerId: ByteArray,
    val packet: ByteArray,
    val image: ByteArray?,
    val queuedAt: Long,
    val gaveUp: Boolean,
)

/** Replay protection (spec D36). Kept when a chat is deleted (D77). */
@Entity(tableName = "received_ids", primaryKeys = ["peerId", "messageId"])
class ReceivedIdEntity(
    val peerId: ByteArray,
    val messageId: ByteArray,
)

/** Per-chat sending counter (spec D36). */
@Entity(tableName = "counters")
class CounterEntity(
    @PrimaryKey val peerId: ByteArray,
    val last: Long,
)
