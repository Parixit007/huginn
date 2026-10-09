package app.raven.app

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.raven.app.media.ImageProcessing
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.util.zip.CRC32
import kotlin.random.Random

/** Photo rules (D25, D31, audit S4), on the device's real decoders. */
@RunWith(AndroidJUnit4::class)
class ImageProcessingTest {
    /** A noisy 3000×2000 JPEG with a fake EXIF block carrying a recognisable "location". */
    private fun cameraLikeJpeg(): ByteArray {
        val random = Random(1)
        val bitmap = Bitmap.createBitmap(3000, 2000, Bitmap.Config.ARGB_8888)
        for (y in 0 until 2000 step 4) {
            for (x in 0 until 3000 step 4) {
                bitmap.setPixel(x, y, Color.rgb(random.nextInt(256), random.nextInt(256), random.nextInt(256)))
            }
        }
        val jpeg = ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it) }.toByteArray()
        val exifPayload = "Exif\u0000\u0000GPS 48.8584N 2.2945E SECRET-LOCATION".toByteArray()
        val app1 =
            byteArrayOf(0xFF.toByte(), 0xE1.toByte()) +
                ByteBuffer.allocate(2).putShort((exifPayload.size + 2).toShort()).array() +
                exifPayload
        // Insert right after the JPEG start marker.
        return jpeg.copyOfRange(0, 2) + app1 + jpeg.copyOfRange(2, jpeg.size)
    }

    @Test
    fun chatPhotosAreSmallWebpWithoutMetadata() {
        val input = cameraLikeJpeg()
        assertTrue(input.containsSequence("SECRET-LOCATION".toByteArray()))
        val photo = assertNotNullAndGet(ImageProcessing.chatPhoto(input))
        assertTrue("≤ 50 KB, was ${photo.size}", photo.size <= 50 * 1024)
        assertEquals("RIFF", String(photo.copyOf(4)))
        assertFalse("metadata survived", photo.containsSequence("SECRET-LOCATION".toByteArray()))
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(photo, 0, photo.size, bounds)
        assertTrue("longest side ≤ 1024", maxOf(bounds.outWidth, bounds.outHeight) <= 1024)
    }

    @Test
    fun avatarsAreSquare192AndUnder20Kb() {
        val avatar = assertNotNullAndGet(ImageProcessing.avatar(cameraLikeJpeg()))
        assertTrue(avatar.size <= 20 * 1024)
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(avatar, 0, avatar.size, bounds)
        assertEquals(192, bounds.outWidth)
        assertEquals(192, bounds.outHeight)
    }

    @Test
    fun aTinyFileClaimingToBeHugeIsRejectedBeforeDecoding() {
        assertNull(ImageProcessing.decodeForDisplay(pngHeaderOnly(width = 40_000, height = 40_000)))
        assertNull(ImageProcessing.chatPhoto("not an image".toByteArray()))
    }

    /** A PNG with a valid header announcing [width]×[height] and no pixel data at all. */
    private fun pngHeaderOnly(
        width: Int,
        height: Int,
    ): ByteArray {
        val signature =
            byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 13, 10, 26, 10)
        val ihdr =
            ByteBuffer
                .allocate(13)
                .putInt(width)
                .putInt(height)
                .put(8)
                .put(6)
                .put(0)
                .put(0)
                .put(0)
                .array()
        return signature + chunk("IHDR", ihdr) + chunk("IEND", ByteArray(0))
    }

    private fun chunk(
        type: String,
        data: ByteArray,
    ): ByteArray {
        val crc = CRC32().apply { update(type.toByteArray() + data) }.value.toInt()
        return ByteBuffer.allocate(4).putInt(data.size).array() + type.toByteArray() + data +
            ByteBuffer.allocate(4).putInt(crc).array()
    }

    private fun assertNotNullAndGet(bytes: ByteArray?): ByteArray {
        assertNotNull(bytes)
        return bytes!!
    }

    private fun ByteArray.containsSequence(needle: ByteArray): Boolean =
        (0..size - needle.size).any { start -> needle.indices.all { this[start + it] == needle[it] } }
}
