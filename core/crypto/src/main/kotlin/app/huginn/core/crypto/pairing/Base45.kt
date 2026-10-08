package app.huginn.core.crypto.pairing

/**
 * Base45 (RFC 9285). Its alphabet matches QR codes' compact alphanumeric mode, which gives smaller,
 * easier-to-scan codes than Base64 (spec D60).
 */
object Base45 {
    private const val ALPHABET = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ $%*+-./:"
    private const val BASE = 45
    private const val BASE_SQUARED = BASE * BASE
    private const val BYTE_BITS = 8
    private const val BYTE_MASK = 0xFF
    private const val PAIR_MAX = 0xFFFF
    private const val SINGLE_MAX = 0xFF
    private const val GROUP = 3

    fun encode(bytes: ByteArray): String =
        buildString {
            var i = 0
            while (i + 1 < bytes.size) {
                val n = ((bytes[i].toInt() and BYTE_MASK) shl BYTE_BITS) or (bytes[i + 1].toInt() and BYTE_MASK)
                append(ALPHABET[n % BASE]).append(ALPHABET[n / BASE % BASE]).append(ALPHABET[n / BASE_SQUARED])
                i += 2
            }
            if (i < bytes.size) {
                val n = bytes[i].toInt() and BYTE_MASK
                append(ALPHABET[n % BASE]).append(ALPHABET[n / BASE])
            }
        }

    /** Null for characters outside the alphabet, a dangling single character, or out-of-range groups. */
    fun decode(text: String): ByteArray? {
        val values = text.map { ALPHABET.indexOf(it) }
        if (values.any { it < 0 } || values.size % GROUP == 1) return null
        val groups = values.chunked(GROUP).map(::decodeGroup)
        return if (groups.contains(null)) null else groups.filterNotNull().flatMap { it.asList() }.toByteArray()
    }

    /** Three characters give two bytes, a final pair gives one byte; null if the value doesn't fit. */
    private fun decodeGroup(group: List<Int>): ByteArray? {
        val n = group.foldIndexed(0) { index, acc, v -> acc + v * pow45(index) }
        return when {
            group.size == GROUP && n <= PAIR_MAX -> byteArrayOf((n ushr BYTE_BITS).toByte(), (n and BYTE_MASK).toByte())
            group.size == GROUP - 1 && n <= SINGLE_MAX -> byteArrayOf(n.toByte())
            else -> null
        }
    }

    private fun pow45(exponent: Int): Int =
        when (exponent) {
            0 -> 1
            1 -> BASE
            else -> BASE_SQUARED
        }
}
