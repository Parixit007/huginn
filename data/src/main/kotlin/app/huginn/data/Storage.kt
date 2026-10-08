package app.huginn.data

import android.content.Context
import androidx.room.Room
import app.huginn.core.crypto.SecretBox
import app.huginn.core.crypto.pairing.PairedContact
import app.huginn.core.mesh.routing.CarryStore
import app.huginn.core.model.DeviceId
import app.huginn.core.model.ImageId
import app.huginn.core.model.Nickname
import app.huginn.core.model.RandomBytes
import app.huginn.data.db.ContactEntity
import app.huginn.data.db.IdentityEntity
import app.huginn.data.db.MeshDatabase
import app.huginn.data.security.DatabaseKey
import app.huginn.data.store.EncryptedFileCarryStore
import app.huginn.data.store.EncryptedImageStore
import app.huginn.data.store.RoomContactDirectory
import app.huginn.data.store.RoomCounterStore
import app.huginn.data.store.RoomOutbox
import app.huginn.data.store.RoomReplayGuard
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory
import java.io.File

/**
 * Everything Huginn keeps on the phone (spec §7): one SQLCipher database whose key is wrapped by the Android
 * Keystore (D80), encrypted photo files, and carried packets that become unreadable on restart (D69).
 * Call from a background thread.
 */
class Storage private constructor(
    private val context: Context,
    val database: MeshDatabase,
) {
    val replayGuard = RoomReplayGuard(database)
    val counters = RoomCounterStore(database)
    val outbox = RoomOutbox(database)
    private var directory: RoomContactDirectory? = null

    /** This phone's identity, or null before onboarding. */
    fun identity(): IdentityEntity? = database.identity().get()

    /** Onboarding (spec §3): a random device ID (D15) and a random key for photo files. */
    fun createIdentity(
        nickname: Nickname,
        random: RandomBytes = RandomBytes.secure,
    ): IdentityEntity {
        check(identity() == null) { "identity already exists" }
        val identity =
            IdentityEntity(
                deviceId = DeviceId.random(random).toByteArray(),
                nickname = nickname.value,
                avatar = null,
                fileKey = SecretBox.newKey(random),
            )
        database.identity().put(identity)
        return identity
    }

    /** The mesh engine's view of contacts (blocked ones excluded, D30). */
    @Synchronized
    fun contactDirectory(): RoomContactDirectory {
        directory?.let { return it }
        val me = DeviceId(checkNotNull(identity()) { "no identity yet" }.deviceId)
        return RoomContactDirectory(database, me).also { directory = it }
    }

    fun images(): EncryptedImageStore =
        EncryptedImageStore(File(context.filesDir, IMAGES_DIR), checkNotNull(identity()) { "no identity yet" }.fileKey)

    /** A fresh store for carried packets: anything left from before this start is deleted (D69). */
    fun newCarryStore(maxBytes: Long): CarryStore =
        EncryptedFileCarryStore(File(context.noBackupFilesDir, CARRY_DIR), maxBytes)

    fun addContact(
        contact: PairedContact,
        nowMillis: Long,
    ) {
        // Re-pairing replaces the old entry and key (spec §5).
        database.contacts().put(
            ContactEntity(
                peerId = contact.peerId.toByteArray(),
                nickname = contact.peerNickname.value,
                rootKey = contact.root.toByteArray(),
                blocked = false,
                avatar = null,
                addedAt = nowMillis,
            ),
        )
        directory?.invalidate(contact.peerId)
    }

    fun setBlocked(
        peer: DeviceId,
        blocked: Boolean,
    ) {
        database.contacts().setBlocked(peer.toByteArray(), blocked)
        directory?.invalidate(peer)
    }

    /**
     * Delete contact (D30): removes the key, the chat, queued messages and photo files. Received message IDs
     * stay (D77), so recorded old packets can't bring deleted messages back.
     */
    fun deleteContact(peer: DeviceId) {
        val peerBytes = peer.toByteArray()
        val photos = database.messages().imageIds(peerBytes)
        database.runInTransaction {
            database.contacts().delete(peerBytes)
            database.messages().deleteChat(peerBytes)
            database.outbox().deleteFor(peerBytes)
            database.counters().delete(peerBytes)
        }
        val images = images()
        photos.forEach { images.delete(ImageId(it)) }
        directory?.invalidate(peer)
    }

    fun close() = database.close()

    companion object {
        /** Brand-neutral file names (D47): a rename must not lose data. */
        const val DATABASE_NAME = "mesh.db"
        const val IMAGES_DIR = "images"
        const val CARRY_DIR = "carry"

        fun open(
            context: Context,
            key: DatabaseKey = DatabaseKey(context),
            databaseName: String = DATABASE_NAME,
        ): Storage {
            System.loadLibrary("sqlcipher")
            val database =
                Room
                    .databaseBuilder(context.applicationContext, MeshDatabase::class.java, databaseName)
                    .openHelperFactory(SupportOpenHelperFactory(key.load()))
                    .build()
            return Storage(context.applicationContext, database)
        }
    }
}
