package app.raven.tools.macpeer

import app.raven.core.crypto.ContactRootKey
import app.raven.core.crypto.StaticKeyV1
import app.raven.core.mesh.DeliveryStatus
import app.raven.core.mesh.MeshListener
import app.raven.core.mesh.MeshNode
import app.raven.core.mesh.messaging.ContactDirectory
import app.raven.core.mesh.packet.Content
import app.raven.core.mesh.packet.InnerPacket
import app.raven.core.model.DeviceId
import app.raven.core.model.MessageId
import app.raven.core.transport.LinkId
import app.raven.core.transport.TransportListener
import app.raven.core.transport.link.Framing
import app.raven.transport.fake.VirtualScheduler
import com.google.zxing.BinaryBitmap
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.random.Random

class MacPeerTest {
    /** Records what the transport writes to the helper; the test plays the helper's side. */
    private class FakeHelper : RadioHelper {
        val sent = mutableListOf<String>()
        lateinit var deliver: (String) -> Unit

        override fun start(onLine: (String) -> Unit) {
            deliver = onLine
        }

        override fun send(line: String) {
            sent += line
        }

        override fun stop() = Unit
    }

    private class Recorder : TransportListener {
        val up = mutableListOf<LinkId>()
        val down = mutableListOf<LinkId>()
        val packets = mutableListOf<ByteArray>()

        override fun onLinkUp(link: LinkId) {
            up += link
        }

        override fun onLinkDown(link: LinkId) {
            down += link
        }

        override fun onReceive(
            link: LinkId,
            packet: ByteArray,
        ) {
            packets += packet
        }
    }

    private fun transport(helper: RadioHelper) = HelperTransport(helper, engine = { it() }, now = { 0 }, onState = {})

    @Test
    fun `the helper protocol - link up, one write at a time, packets back, link down`() {
        val helper = FakeHelper()
        val recorder = Recorder()
        val transport = transport(helper)
        transport.start(recorder)

        helper.deliver("UP 1 182")
        assertEquals(listOf(LinkId(1)), recorder.up)

        val packet = Random(1).nextBytes(1000)
        assertTrue(transport.send(LinkId(1), packet))
        assertEquals(1, helper.sent.size, "only one write may be in flight")
        repeat(10) { helper.deliver("DONE 1") }
        val fragments = helper.sent.map { it.split(' ')[2].hexToBytesOrNull()!! }
        val expected = Framing.split(packet, 182)
        assertEquals(expected.size, fragments.size)
        expected.zip(fragments).forEach { (want, got) -> assertArrayEquals(want, got) }

        // The phone notifies a packet back, fragment by fragment.
        Framing.split(packet, 182).forEach { helper.deliver("RX 1 ${it.toHex()}") }
        assertArrayEquals(packet, recorder.packets.single())

        // A neighbour announcing an impossible size is closed.
        helper.deliver("RX 1 80ffff00")
        assertEquals("CLOSE 1", helper.sent.last())
        helper.deliver("DOWN 1")
        assertEquals(listOf(LinkId(1)), recorder.down)
        assertTrue(transport.links.isEmpty())
    }

    @Test
    fun `two real engines talk through the helper protocol`() {
        val scheduler = VirtualScheduler()
        val alice = DeviceId(ByteArray(8) { 1 })
        val bob = DeviceId(ByteArray(8) { 2 })
        val root = ContactRootKey(ByteArray(ContactRootKey.SIZE) { 7 })
        val aliceHelper = FakeHelper()
        val bobHelper = FakeHelper()
        val aliceTransport = HelperTransport(aliceHelper, { scheduler.schedule(1, it) }, scheduler::now, onState = {})
        val bobTransport = HelperTransport(bobHelper, { scheduler.schedule(1, it) }, scheduler::now, onState = {})
        val texts = mutableListOf<String>()
        val statuses = mutableListOf<DeliveryStatus>()
        val aliceNode =
            MeshNode(
                alice,
                aliceTransport,
                scheduler,
                ContactDirectory { if (it == bob) StaticKeyV1.contactCipher(root, alice, bob) else null },
                object : MeshListener {
                    override fun onStatus(
                        peer: DeviceId,
                        messageId: MessageId,
                        status: DeliveryStatus,
                    ) {
                        statuses += status
                    }
                },
            )
        val bobNode =
            MeshNode(
                bob,
                bobTransport,
                scheduler,
                ContactDirectory { if (it == alice) StaticKeyV1.contactCipher(root, bob, alice) else null },
                object : MeshListener {
                    override fun onMessage(
                        from: DeviceId,
                        message: InnerPacket,
                    ) {
                        (message.content as? Content.Text)?.let { texts += it.text }
                    }
                },
            )
        aliceNode.start()
        bobNode.start()
        // The "radio": each TX becomes an RX on the other side, and the writer gets its DONE.
        val wire = { from: FakeHelper, to: FakeHelper ->
            val pending = from.sent.toList()
            from.sent.clear()
            pending.filter { it.startsWith("TX") }.forEach {
                to.deliver("RX 1 ${it.split(' ')[2]}")
                from.deliver("DONE 1")
            }
        }
        aliceHelper.deliver("UP 1 509")
        bobHelper.deliver("UP 1 509")
        scheduler.advanceBy(10)
        aliceNode.sendText(bob, "hello from the Mac")
        repeat(200) {
            scheduler.advanceBy(5)
            wire(aliceHelper, bobHelper)
            wire(bobHelper, aliceHelper)
        }
        assertEquals(listOf("hello from the Mac"), texts)
        assertTrue(DeliveryStatus.DELIVERED in statuses, "no delivered receipt: $statuses")
    }

    @Test
    fun `the terminal QR code decodes back to the exact pairing text`() {
        val text = "MSH1:" + "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789 \$%*+-./:".repeat(4)
        val printed =
            Terminal
                .qr(text)
                .lines()
                .filter {
                    it.isNotEmpty()
                }.map { it.removePrefix("\u001b[30;47m").removeSuffix("\u001b[0m") }
        // Rebuild an image from the half blocks, 4 px per module, and read it like a phone would.
        val width = printed.first().length
        val image = BufferedImage(width * 4, printed.size * 8, BufferedImage.TYPE_INT_RGB)
        printed.forEachIndexed { row, line ->
            line.forEachIndexed { x, c ->
                val top = c == '█' || c == '▀'
                val bottom = c == '█' || c == '▄'
                for (dx in 0 until 4) {
                    for (dy in 0 until 4) {
                        image.setRGB(x * 4 + dx, row * 8 + dy, if (top) 0 else 0xFFFFFF)
                        image.setRGB(x * 4 + dx, row * 8 + 4 + dy, if (bottom) 0 else 0xFFFFFF)
                    }
                }
            }
        }
        val pixels = image.getRGB(0, 0, image.width, image.height, null, 0, image.width)
        val source = RGBLuminanceSource(image.width, image.height, pixels)
        val decoded = QRCodeReader().decode(BinaryBitmap(HybridBinarizer(source))).text
        assertEquals(text, decoded)
        assertEquals("482 913", Terminal.code("482913"))
    }

    @Test
    fun `photos are shrunk to 50 KB and 1024 px`(
        @TempDir dir: File,
    ) {
        val random = Random(3)
        val big = BufferedImage(3000, 2000, BufferedImage.TYPE_INT_RGB)
        for (y in 0 until 2000 step 2) for (x in 0 until 3000 step 2) big.setRGB(x, y, random.nextInt(0xFFFFFF))
        val file = File(dir, "big.png").also { ImageIO.write(big, "png", it) }
        val bytes = Photos.forChat(file)!!
        assertTrue(bytes.size <= 50 * 1024, "size ${bytes.size}")
        val decoded = ImageIO.read(bytes.inputStream())
        assertTrue(maxOf(decoded.width, decoded.height) <= 1024)
        assertEquals(null, Photos.forChat(File(dir, "missing.png").also { it.writeText("not an image") }))
    }
}
