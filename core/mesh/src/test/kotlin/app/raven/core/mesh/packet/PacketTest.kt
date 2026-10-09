package app.raven.core.mesh.packet

import app.raven.core.crypto.ContactRootKey
import app.raven.core.crypto.StaticKeyV1
import app.raven.core.model.DeviceId
import app.raven.core.model.ImageId
import app.raven.core.model.MessageId
import app.raven.core.model.Nickname
import app.raven.core.model.PacketId
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

private fun inner(content: Content) =
    InnerPacket(MessageId.random(), counter = 1, timestampMillis = 1_760_000_000_000, content)

class InnerPacketTest {
    @Test
    fun `a read receipt and a short text are padded to the same size`() {
        val receipt = inner(Content.AckRead(listOf(MessageId.random()))).encode()
        val text = inner(Content.Text("ok")).encode()
        assertEquals(128, receipt.size)
        assertEquals(128, text.size)
    }

    @Test
    fun `sizes follow the padding buckets`() {
        assertEquals(128, inner(Content.Text("x".repeat(128 - 35))).encode().size)
        assertEquals(256, inner(Content.Text("x".repeat(128 - 35 + 1))).encode().size)
        assertEquals(8192, inner(Content.Text("🦉".repeat(2000))).encode().size)
    }

    @Test
    fun `every content type round-trips`() {
        val image = ImageId.random()
        val contents =
            listOf(
                Content.Text("Hello, नमस्ते, 🦉"),
                Content.Reaction(MessageId.random(), "👍🏽"),
                Content.AckDelivered(List(255) { MessageId.random() }),
                Content.AckRead(listOf(MessageId.random())),
                Content.ImageManifest(image, 50 * 1024, 100, ByteArray(32) { 5 }, Content.Purpose.CHAT_IMAGE),
                Content.ImageChunk(image, 99, ByteArray(500) { it.toByte() }),
                Content.ChunkRequest(image, listOf(0, 7, 99)),
                Content.Profile(Nickname.clean("Ravi")!!, avatar = image),
                Content.Profile(Nickname.clean("Ravi")!!, avatar = null),
            )
        for (content in contents) {
            val encoded = inner(content).encode()
            val decoded = checkNotNull(InnerPacket.decode(encoded)) { content::class.simpleName!! }
            assertArrayEquals(encoded, decoded.encode(), content::class.simpleName)
            assertEquals(content.type, decoded.content.type)
        }
    }

    @Test
    fun `protocol limits are enforced when building content`() {
        assertThrows<IllegalArgumentException> { Content.Text("") }
        assertThrows<IllegalArgumentException> { Content.Text("x".repeat(2001)) }
        assertThrows<IllegalArgumentException> { Content.AckRead(emptyList()) }
        assertThrows<IllegalArgumentException> { Content.AckRead(List(256) { MessageId.random() }) }
        assertThrows<IllegalArgumentException> {
            Content.ImageManifest(ImageId.random(), 20 * 1024 + 1, 1, ByteArray(32), Content.Purpose.AVATAR)
        }
        assertThrows<IllegalArgumentException> { InnerPacket(MessageId.random(), 0, 0, Content.Text("x")) }
    }

    @Test
    fun `decoding is strict - wrong size, non-zero padding, oversized bucket, zero counter`() {
        val good = inner(Content.Text("hello")).encode()
        assertNotNull(InnerPacket.decode(good))
        assertNull(InnerPacket.decode(good.copyOf(127)))
        assertNull(InnerPacket.decode(good.copyOf().also { it[127] = 1 })) // padding must be zeros
        assertNull(InnerPacket.decode(good.copyOf(256))) // fits in 128, so 256 is not canonical
        val zeroCounter = good.copyOf().also { for (i in 17 until 25) it[i] = 0 }
        assertNull(InnerPacket.decode(zeroCounter))
        val unknownType = good.copyOf().also { it[0] = 99 }
        assertNull(InnerPacket.decode(unknownType))
    }
}

class OuterPacketTest {
    private val sender = DeviceId.random()
    private val recipient = DeviceId.random()

    private fun handshake(hops: Int = 1) =
        OuterPacket(
            OuterPacket.Kind.HANDSHAKE,
            hops,
            PacketId.random(),
            sender,
            recipient,
            ByteArray(20) {
                1
            },
        )

    @Test
    fun `round-trips and has a 27-byte header`() {
        val packet = handshake()
        val encoded = packet.encode()
        assertEquals(27 + 20, encoded.size)
        val decoded = checkNotNull(OuterPacket.decode(encoded))
        assertArrayEquals(encoded, decoded.encode())
        assertEquals(sender, decoded.sender)
        assertEquals(recipient, decoded.recipient)
    }

    @Test
    fun `hops_left is not part of the authenticated data`() {
        val id = PacketId.random()
        val a = OuterPacket(OuterPacket.Kind.HANDSHAKE, 8, id, sender, recipient, ByteArray(5))
        val b = OuterPacket(OuterPacket.Kind.HANDSHAKE, 3, id, sender, recipient, ByteArray(5))
        assertArrayEquals(a.associatedData(), b.associatedData())
    }

    @Test
    fun `rejects bad version, kind, hop counts and body sizes`() {
        val good = handshake().encode()
        assertNull(OuterPacket.decode(good.copyOf().also { it[0] = 2 }))
        assertNull(OuterPacket.decode(good.copyOf().also { it[1] = 4 })) // 1-3 are DATA, HANDSHAKE, LINK
        assertNull(OuterPacket.decode(good.copyOf().also { it[2] = 0 }))
        assertNull(OuterPacket.decode(good.copyOf().also { it[2] = 9 }))
        assertNull(OuterPacket.decode(good.copyOf(27))) // empty body
        assertNull(OuterPacket.decode(good.copyOf(27) + ByteArray(513))) // handshake too big
        val dataHeader = good.copyOf(27).also { it[1] = 1 }
        assertNull(OuterPacket.decode(dataHeader + ByteArray(100))) // DATA body must be a bucket + 40
        assertNotNull(OuterPacket.decode(dataHeader + ByteArray(128 + 40)))
        assertNull(OuterPacket.decode(ByteArray(0)))
    }
}

class DataPacketsTest {
    private val alice = DeviceId.random()
    private val bob = DeviceId.random()
    private val root = ContactRootKey(ByteArray(32) { 3 })
    private val aliceCipher = StaticKeyV1.contactCipher(root, alice, bob)
    private val bobCipher = StaticKeyV1.contactCipher(root, bob, alice)

    @Test
    fun `seal then open, and the wire size is header + overhead + bucket`() {
        val message = inner(Content.Text("hi Bob"))
        val packet = DataPackets.seal(aliceCipher, PacketId.random(), alice, bob, message)
        val wire = packet.encode()
        assertEquals(27 + 40 + 128, wire.size)
        val opened = checkNotNull(DataPackets.open(bobCipher, checkNotNull(OuterPacket.decode(wire))))
        assertEquals(message.messageId, opened.messageId)
        assertEquals("hi Bob", (opened.content as Content.Text).text)
    }

    @Test
    fun `a relay can lower hops_left without breaking the packet`() {
        val packet = DataPackets.seal(aliceCipher, PacketId.random(), alice, bob, inner(Content.Text("x")))
        val relayed =
            OuterPacket(packet.kind, packet.hopsLeft - 1, packet.packetId, packet.sender, packet.recipient, packet.body)
        assertNotNull(DataPackets.open(bobCipher, relayed))
    }

    @Test
    fun `changing sender, recipient or packet id breaks the packet`() {
        val packet = DataPackets.seal(aliceCipher, PacketId.random(), alice, bob, inner(Content.Text("x")))
        val other = DeviceId.random()
        val k = packet.kind
        val h = packet.hopsLeft
        assertNull(DataPackets.open(bobCipher, OuterPacket(k, h, packet.packetId, other, bob, packet.body)))
        assertNull(DataPackets.open(bobCipher, OuterPacket(k, h, packet.packetId, alice, other, packet.body)))
        assertNull(DataPackets.open(bobCipher, OuterPacket(k, h, PacketId.random(), alice, bob, packet.body)))
    }

    @Test
    fun `a retry with a new packet id looks completely different on the air`() {
        val message = inner(Content.Text("same message"))
        val first = DataPackets.seal(aliceCipher, PacketId.random(), alice, bob, message).encode()
        val retry = DataPackets.seal(aliceCipher, PacketId.random(), alice, bob, message).encode()
        assertFalse(first.copyOfRange(3, first.size).contentEquals(retry.copyOfRange(3, retry.size)))
    }
}
