package app.huginn.app.media

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.os.Build
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Photos in and out (spec D25, D31, audit S4):
 * - outgoing photos are always re-encoded from their pixels, so EXIF data (GPS location, phone model, time)
 *   never leaves the phone;
 * - every image's declared size is checked **before** it is decoded, so a tiny file claiming to be enormous
 *   (a "decompression bomb") is rejected instead of exhausting memory.
 */
object ImageProcessing {
    const val CHAT_MAX_BYTES = 50 * 1024
    const val CHAT_MAX_SIDE = 1024
    const val AVATAR_MAX_BYTES = 20 * 1024
    const val AVATAR_SIDE = 192

    /** Largest width or height we agree to decode. */
    private const val MAX_DECODE_SIDE = 12_000
    private const val START_QUALITY = 85
    private const val MIN_QUALITY = 25
    private const val QUALITY_STEP = 10
    private const val SHRINK = 0.8f
    private const val MAX_SHRINKS = 6

    /** A chat photo: longest side ≤ 1024 px, WebP, ≤ 50 KB (D25). Null if it isn't a usable image. */
    fun chatPhoto(
        input: ByteArray,
        rotationDegrees: Int = 0,
    ): ByteArray? {
        val bitmap = decode(input, CHAT_MAX_SIDE)?.rotated(rotationDegrees) ?: return null
        return encodeWithin(bitmap, CHAT_MAX_BYTES)
    }

    /** An avatar: centre square, 192×192, WebP, ≤ 20 KB (D31). */
    fun avatar(
        input: ByteArray,
        rotationDegrees: Int = 0,
    ): ByteArray? {
        val bitmap = decode(input, AVATAR_SIDE * 2)?.rotated(rotationDegrees) ?: return null
        val side = min(bitmap.width, bitmap.height)
        val square = Bitmap.createBitmap(bitmap, (bitmap.width - side) / 2, (bitmap.height - side) / 2, side, side)
        return encodeWithin(Bitmap.createScaledBitmap(square, AVATAR_SIDE, AVATAR_SIDE, true), AVATAR_MAX_BYTES)
    }

    /** Received photos and avatars, for display. Their size is checked before decoding. */
    fun decodeForDisplay(
        bytes: ByteArray,
        maxSide: Int = CHAT_MAX_SIDE,
    ): Bitmap? = decode(bytes, maxSide)

    private fun decode(
        input: ByteArray,
        maxSide: Int,
    ): Bitmap? =
        try {
            if (Build.VERSION.SDK_INT >=
                Build.VERSION_CODES.P
            ) {
                decodeModern(input, maxSide)
            } else {
                decodeLegacy(input, maxSide)
            }
        } catch (expected: IOException) {
            null // not an image, or larger than we allow
        } catch (expected: IllegalArgumentException) {
            null
        }

    /** ImageDecoder also applies the photo's EXIF rotation before the metadata is thrown away. */
    @androidx.annotation.RequiresApi(Build.VERSION_CODES.P)
    private fun decodeModern(
        input: ByteArray,
        maxSide: Int,
    ): Bitmap =
        ImageDecoder.decodeBitmap(ImageDecoder.createSource(ByteBuffer.wrap(input))) { decoder, info, _ ->
            val width = info.size.width
            val height = info.size.height
            if (width !in 1..MAX_DECODE_SIDE || height !in 1..MAX_DECODE_SIDE) throw IOException("image too large")
            val scale = min(1f, maxSide.toFloat() / max(width, height))
            decoder.setTargetSize(
                (width * scale).roundToInt().coerceAtLeast(1),
                (height * scale).roundToInt().coerceAtLeast(1),
            )
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        }

    private fun decodeLegacy(
        input: ByteArray,
        maxSide: Int,
    ): Bitmap {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(input, 0, input.size, bounds)
        val width = bounds.outWidth
        val height = bounds.outHeight
        if (width !in 1..MAX_DECODE_SIDE || height !in 1..MAX_DECODE_SIDE) throw IOException("image too large")
        var sample = 1
        while (max(width, height) / (sample * 2) >= maxSide) sample *= 2
        val decoded =
            BitmapFactory.decodeByteArray(input, 0, input.size, BitmapFactory.Options().apply { inSampleSize = sample })
                ?: throw IOException("not an image")
        val scale = min(1f, maxSide.toFloat() / max(decoded.width, decoded.height))
        return if (scale < 1f) {
            Bitmap.createScaledBitmap(
                decoded,
                (decoded.width * scale).roundToInt(),
                (decoded.height * scale).roundToInt(),
                true,
            )
        } else {
            decoded
        }
    }

    private fun Bitmap.rotated(degrees: Int): Bitmap =
        if (degrees % FULL_TURN ==
            0
        ) {
            this
        } else {
            Bitmap.createBitmap(this, 0, 0, width, height, Matrix().apply { postRotate(degrees.toFloat()) }, true)
        }

    /** Lowers quality, then size, until the WebP fits in [maxBytes]. */
    private fun encodeWithin(
        start: Bitmap,
        maxBytes: Int,
    ): ByteArray? {
        var bitmap = start
        repeat(MAX_SHRINKS) {
            for (quality in START_QUALITY downTo MIN_QUALITY step QUALITY_STEP) {
                val bytes = webp(bitmap, quality)
                if (bytes.size <= maxBytes) return bytes
            }
            bitmap =
                Bitmap.createScaledBitmap(
                    bitmap,
                    (bitmap.width * SHRINK).roundToInt(),
                    (bitmap.height * SHRINK).roundToInt(),
                    true,
                )
        }
        return null
    }

    private fun webp(
        bitmap: Bitmap,
        quality: Int,
    ): ByteArray {
        val format =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                Bitmap.CompressFormat.WEBP_LOSSY
            } else {
                @Suppress("DEPRECATION")
                Bitmap.CompressFormat.WEBP
            }
        return ByteArrayOutputStream().also { bitmap.compress(format, quality, it) }.toByteArray()
    }

    private const val FULL_TURN = 360
}
