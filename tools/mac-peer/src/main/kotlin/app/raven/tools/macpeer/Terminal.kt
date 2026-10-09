package app.raven.tools.macpeer

import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import javax.imageio.IIOImage
import javax.imageio.ImageIO
import javax.imageio.ImageWriteParam

/** Terminal output for the Mac peer: a scannable QR code and the 6-digit code format. */
object Terminal {
    private const val BLACK_ON_WHITE = "\u001b[30;47m"
    private const val RESET = "\u001b[0m"
    private const val QUIET_ZONE = 2
    private const val CODE_SPLIT = 3

    /**
     * Prints [text] as a QR code: two rows of modules per line using half blocks, always black on white
     * (a dark terminal would otherwise invert it, and phone scanners don't read inverted codes reliably).
     */
    fun qr(text: String): String {
        val hints =
            mapOf(EncodeHintType.MARGIN to QUIET_ZONE, EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M)
        val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 0, 0, hints)
        val out = StringBuilder()
        for (y in 0 until matrix.height step 2) {
            out.append(BLACK_ON_WHITE)
            for (x in 0 until matrix.width) {
                val top = matrix.get(x, y)
                val bottom = y + 1 < matrix.height && matrix.get(x, y + 1)
                out.append(
                    when {
                        top && bottom -> '█'
                        top -> '▀'
                        bottom -> '▄'
                        else -> ' '
                    },
                )
            }
            out.append(RESET).append('\n')
        }
        return out.toString()
    }

    /** "482913" → "482 913", as the phone shows it. */
    fun code(code: String): String = code.chunked(CODE_SPLIT).joinToString(" ")
}

/**
 * Photos from the Mac: re-encoded as JPEG (which also drops all metadata) and shrunk until they fit the
 * chat photo limit, like the phone does (D25: ≤ 1024 px, ≤ 50 KB).
 */
object Photos {
    private const val MAX_SIDE = 1024
    private const val MAX_BYTES = 50 * 1024
    private const val START_QUALITY = 0.85f
    private const val MIN_QUALITY = 0.3f
    private const val QUALITY_STEP = 0.1f
    private const val SHRINK = 0.8

    fun forChat(file: File): ByteArray? {
        var image = ImageIO.read(file)?.let { scaled(it, MAX_SIDE) } ?: return null
        while (true) {
            var quality = START_QUALITY
            while (quality >= MIN_QUALITY) {
                val bytes = jpeg(image, quality)
                if (bytes.size <= MAX_BYTES) return bytes
                quality -= QUALITY_STEP
            }
            val side = (maxOf(image.width, image.height) * SHRINK).toInt()
            if (side < 2) return null
            image = scaled(image, side)
        }
    }

    private fun scaled(
        source: BufferedImage,
        maxSide: Int,
    ): BufferedImage {
        val factor = minOf(1.0, maxSide.toDouble() / maxOf(source.width, source.height))
        val width = maxOf(1, (source.width * factor).toInt())
        val height = maxOf(1, (source.height * factor).toInt())
        val target = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
        target.createGraphics().apply {
            setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
            drawImage(source, 0, 0, width, height, null)
            dispose()
        }
        return target
    }

    private fun jpeg(
        image: BufferedImage,
        quality: Float,
    ): ByteArray {
        val writer = ImageIO.getImageWritersByFormatName("jpeg").next()
        val out = ByteArrayOutputStream()
        ImageIO.createImageOutputStream(out).use { stream ->
            writer.output = stream
            val params =
                writer.defaultWriteParam.apply {
                    compressionMode = ImageWriteParam.MODE_EXPLICIT
                    compressionQuality = quality
                }
            writer.write(null, IIOImage(image, null, null), params)
        }
        writer.dispose()
        return out.toByteArray()
    }
}
