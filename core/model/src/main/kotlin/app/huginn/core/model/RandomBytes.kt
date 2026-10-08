package app.huginn.core.model

import java.security.SecureRandom

/** Source of random bytes. Production code uses [secure]; tests can pass a predictable source. */
fun interface RandomBytes {
    fun next(size: Int): ByteArray

    companion object {
        val secure: RandomBytes = SecureRandomBytes
    }
}

private object SecureRandomBytes : RandomBytes {
    private val random = SecureRandom()

    override fun next(size: Int): ByteArray = ByteArray(size).also(random::nextBytes)
}
