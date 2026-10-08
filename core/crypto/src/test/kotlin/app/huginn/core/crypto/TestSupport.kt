package app.huginn.core.crypto

fun hex(text: String): ByteArray {
    val clean = text.filterNot { it.isWhitespace() }
    require(clean.length % 2 == 0)
    return ByteArray(clean.length / 2) { clean.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
}
