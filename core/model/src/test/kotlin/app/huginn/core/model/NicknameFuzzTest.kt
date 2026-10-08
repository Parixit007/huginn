package app.huginn.core.model

import com.code_intelligence.jazzer.api.FuzzedDataProvider
import com.code_intelligence.jazzer.junit.FuzzTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue

class NicknameFuzzTest {
    @FuzzTest(maxDuration = "5m")
    fun `cleaned nicknames are always safe and stable`(data: FuzzedDataProvider) {
        val raw = data.consumeRemainingAsString()
        val nickname = Nickname.clean(raw) ?: return
        val value = nickname.value
        assertTrue(value.isNotEmpty())
        assertTrue(value.codePointCount(0, value.length) <= Nickname.MAX_CHARACTERS)
        assertTrue(value.toByteArray(Charsets.UTF_8).size <= Nickname.MAX_BYTES)
        value.codePoints().forEach { codePoint ->
            val type = Character.getType(codePoint).toByte()
            assertTrue(type != Character.FORMAT && type != Character.CONTROL) { "kept U+%04X".format(codePoint) }
        }
        assertEquals(nickname, Nickname.strict(value))
    }
}
