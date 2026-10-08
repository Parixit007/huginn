package app.huginn.core.model

/**
 * A fixed-length byte value, such as an ID. Compared by content, copied on the way in and out so callers
 * can't change it, and printed as hex.
 */
abstract class FixedBytes protected constructor(
    bytes: ByteArray,
    size: Int,
) {
    init {
        require(bytes.size == size) { "${this::class.simpleName} needs $size bytes, got ${bytes.size}" }
    }

    private val value: ByteArray = bytes.copyOf()

    val size: Int get() = value.size

    fun toByteArray(): ByteArray = value.copyOf()

    override fun equals(other: Any?): Boolean =
        other is FixedBytes && other::class == this::class && value.contentEquals(other.value)

    override fun hashCode(): Int = value.contentHashCode()

    override fun toString(): String = "${this::class.simpleName}(${value.toHex()})"
}

fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
