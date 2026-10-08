package app.huginn.core.model

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class IdsTest {
    @Test
    fun `ids compare by content and copy their bytes`() {
        val bytes = ByteArray(DeviceId.SIZE) { it.toByte() }
        val id = DeviceId(bytes)
        bytes[0] = 99
        assertEquals(DeviceId(ByteArray(DeviceId.SIZE) { it.toByte() }), id)
        id.toByteArray()[1] = 99
        assertEquals("DeviceId(0001020304050607)", id.toString())
    }

    @Test
    fun `ids of different types are never equal`() {
        val bytes = ByteArray(8)
        assertNotEquals(DeviceId(bytes), PacketId(bytes))
    }

    @Test
    fun `wrong sizes are rejected`() {
        assertThrows<IllegalArgumentException> { DeviceId(ByteArray(7)) }
        assertThrows<IllegalArgumentException> { MessageId(ByteArray(17)) }
    }

    @Test
    fun `random ids use the given source`() {
        val id = MessageId.random { size -> ByteArray(size) { 7 } }
        assertEquals(MessageId(ByteArray(16) { 7 }), id)
    }
}

class NicknameTest {
    @Test
    fun `plain names and emoji are kept`() {
        assertEquals("Ravi 🦉", Nickname.clean("Ravi 🦉")?.value)
        assertEquals("Zoë", Nickname.clean("  Zoë ")?.value)
    }

    @Test
    fun `direction overrides, zero-width and control characters are removed`() {
        // U+202E would make "evil‮gnp.exe" display as "evilexe.png"
        assertEquals("evilgnp.exe", Nickname.clean("evil‮gnp.exe")?.value)
        assertEquals("Bob", Nickname.clean("B​o\u0000b﻿")?.value)
        assertEquals("ab", Nickname.clean("a\nb")?.value)
    }

    @Test
    fun `empty and too-long names are rejected`() {
        assertNull(Nickname.clean(""))
        assertNull(Nickname.clean("​‮ "))
        assertEquals(32, Nickname.clean("x".repeat(32))?.value?.length)
        assertNull(Nickname.clean("x".repeat(33)))
        // 32 emoji are 64 UTF-16 chars but 32 characters: allowed
        assertEquals(64, Nickname.clean("🦉".repeat(32))?.value?.length)
    }

    @Test
    fun `strict accepts only already-clean names`() {
        assertEquals("Ravi", Nickname.strict("Ravi")?.value)
        assertNull(Nickname.strict(" Ravi"))
        assertNull(Nickname.strict("Ra‮vi"))
    }
}
