package app.raven.data

import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteException
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.raven.core.crypto.ContactRootKey
import app.raven.core.crypto.pairing.PairedContact
import app.raven.core.mesh.messaging.OutboxEntry
import app.raven.core.mesh.packet.Content
import app.raven.core.mesh.packet.InnerPacket
import app.raven.core.model.DeviceId
import app.raven.core.model.ImageId
import app.raven.core.model.MessageId
import app.raven.core.model.Nickname
import app.raven.data.db.MessageEntity
import app.raven.data.security.DatabaseKey
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.KeyStore
import java.util.UUID

/** Build plan 3.5, on the Android 17 emulator (D81). */
@RunWith(AndroidJUnit4::class)
class StorageTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val tag = UUID.randomUUID().toString()
    private val dbName = "test-$tag.db"
    private val keyFile = "test-$tag.key"
    private val alias = "test-$tag"
    private lateinit var storage: Storage

    private fun open(
        keyAlias: String = alias,
        keyFileName: String = keyFile,
    ) = Storage.open(context, DatabaseKey(context, alias = keyAlias, fileName = keyFileName), dbName)

    private val bob = DeviceId.random()

    private fun pairBob() =
        storage.addContact(
            PairedContact(bob, Nickname.clean("Bob")!!, ContactRootKey(ByteArray(32) { 7 })),
            nowMillis = 1,
        )

    @Before
    fun setUp() {
        storage = open()
        storage.createIdentity(Nickname.clean("Alice")!!)
    }

    @After
    fun tearDown() {
        storage.close()
        context.deleteDatabase(dbName)
        File(context.noBackupFilesDir, keyFile).delete()
        KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry(alias)
    }

    @Test
    fun theDatabaseFileIsUnreadableWithoutTheKey() {
        pairBob()
        storage.close()
        val file = context.getDatabasePath(dbName)
        val raw = file.readBytes()
        assertFalse("plain SQLite header found", String(raw.copyOf(15)).startsWith("SQLite format 3"))
        assertFalse("nickname visible in the file", raw.containsSequence("Alice".toByteArray()))
        try {
            SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
                db.rawQuery("SELECT * FROM identity", null).use { it.count }
            }
            fail("plain SQLite could read the database")
        } catch (expected: SQLiteException) {
            // "file is not a database": exactly what we want
        }
        storage = open()
        assertEquals("Alice", storage.identity()?.nickname)
    }

    @Test
    fun aDifferentKeyCannotOpenTheDatabase() {
        storage.close()
        val otherAlias = "$alias-other"
        val otherFile = "$keyFile-other"
        val intruder = open(keyAlias = otherAlias, keyFileName = otherFile)
        try {
            intruder.identity()
            fail("opened with the wrong key")
        } catch (expected: SQLiteException) {
            // wrong key: SQLCipher refuses
        } finally {
            intruder.close()
            File(context.noBackupFilesDir, otherFile).delete()
            KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry(otherAlias)
        }
        storage = open()
        assertEquals("Alice", storage.identity()?.nickname)
    }

    @Test
    fun theWrappedKeyFileIsNotTheKey() {
        val key = DatabaseKey(context, alias = alias, fileName = keyFile).load()
        val wrapped = File(context.noBackupFilesDir, keyFile).readBytes()
        assertEquals(32, key.size)
        assertFalse(wrapped.containsSequence(key))
        assertArrayEquals(key, DatabaseKey(context, alias = alias, fileName = keyFile).load())
    }

    @Test
    fun dataSurvivesARestart() {
        pairBob()
        assertEquals(1L, storage.counters.next(bob))
        storage.close()
        storage = open()
        assertEquals("Alice", storage.identity()?.nickname)
        assertNotNull(storage.contactDirectory().cipherFor(bob))
        assertEquals(2L, storage.counters.next(bob))
    }

    @Test
    fun deletingAContactWipesItsKeyChatOutboxAndPhotosButKeepsReplayIds() {
        pairBob()
        val photo = ImageId.random()
        storage.images().save(photo, ByteArray(1000) { 1 })
        val received = MessageId.random()
        storage.database.messages().insert(message(received, imageId = photo))
        storage.replayGuard.firstTime(bob, received)
        val queued = inner(Content.Text("not sent yet"))
        storage.outbox.put(OutboxEntry(bob, queued, null, 0, gaveUp = false))

        storage.deleteContact(bob)

        assertNull(storage.database.contacts().get(bob.toByteArray()))
        assertNull(storage.contactDirectory().cipherFor(bob))
        assertTrue(
            storage.database
                .messages()
                .chat(bob.toByteArray())
                .isEmpty(),
        )
        assertNull(storage.outbox.get(queued.messageId))
        assertNull(storage.images().load(photo))
        assertTrue("replay IDs must stay (D77)", storage.replayGuard.contains(bob, received))
    }

    @Test
    fun theOutboxKeepsPhotosAndTheGivenUpFlag() {
        val photo = ByteArray(20 * 1024) { (it % 251).toByte() }
        val manifest =
            inner(Content.ImageManifest(ImageId.random(), photo.size, 45, ByteArray(32), Content.Purpose.CHAT_IMAGE))
        val text = inner(Content.Text("hello"))
        storage.outbox.put(OutboxEntry(bob, manifest, photo, 10, gaveUp = false))
        storage.outbox.put(OutboxEntry(bob, text, null, 20, gaveUp = true))

        val loaded = checkNotNull(storage.outbox.get(manifest.messageId))
        assertArrayEquals(photo, loaded.image)
        assertArrayEquals(manifest.encode(), loaded.message.encode())
        assertEquals(listOf(manifest.messageId), storage.outbox.pending().map { it.id })
        assertTrue(checkNotNull(storage.outbox.get(text.messageId)).gaveUp)
    }

    @Test
    fun blockedContactsGetNoCipherAndReplayGuardWorks() {
        pairBob()
        assertNotNull(storage.contactDirectory().cipherFor(bob))
        storage.setBlocked(bob, true)
        assertNull(storage.contactDirectory().cipherFor(bob))
        val id = MessageId.random()
        assertTrue(storage.replayGuard.firstTime(bob, id))
        assertFalse(storage.replayGuard.firstTime(bob, id))
    }

    @Test
    fun photosAreEncryptedOnDisk() {
        val photo = ImageId.random()
        val bytes = "a very recognisable photo payload".repeat(20).toByteArray()
        storage.images().save(photo, bytes)
        val onDisk =
            File(File(context.filesDir, Storage.IMAGES_DIR), photo.toByteArray().joinToString("") { "%02x".format(it) })
        assertFalse(onDisk.readBytes().containsSequence("recognisable".toByteArray()))
        assertArrayEquals(bytes, storage.images().load(photo))
    }

    private fun inner(content: Content) = InnerPacket(MessageId.random(), 1, 0, content)

    private fun message(
        id: MessageId,
        imageId: ImageId?,
    ) = MessageEntity(
        id.toByteArray(),
        bob.toByteArray(),
        false,
        Content.IMAGE_MANIFEST,
        null,
        null,
        imageId?.toByteArray(),
        1,
        0,
        0,
        0,
    )
}

fun ByteArray.containsSequence(needle: ByteArray): Boolean =
    (0..size - needle.size).any { start -> needle.indices.all { this[start + it] == needle[it] } }
