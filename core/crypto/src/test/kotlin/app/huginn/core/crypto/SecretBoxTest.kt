package app.huginn.core.crypto

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class SecretBoxTest {
    private val box = SecretBox(SecretBox.newKey())
    private val data = "photo bytes".toByteArray()

    @Test
    fun `seals and opens with the same key and associated data`() {
        val sealed = box.seal(data, "id-1".toByteArray())
        assertEquals(data.size + 40, sealed.size)
        assertArrayEquals(data, box.open(sealed, "id-1".toByteArray()))
    }

    @Test
    fun `another key, other associated data or a flipped bit all fail`() {
        val sealed = box.seal(data, "id-1".toByteArray())
        assertNull(SecretBox(SecretBox.newKey()).open(sealed, "id-1".toByteArray()))
        assertNull(box.open(sealed, "id-2".toByteArray())) // a file renamed to another photo's name
        assertNull(box.open(sealed.copyOf().also { it[30] = (it[30] + 1).toByte() }, "id-1".toByteArray()))
    }
}
