package app.huginn.core.model

/**
 * A display name, cleaned of characters that can hide or disguise text (hardening item H6):
 * control characters, invisible "format" characters (zero-width spaces, direction overrides such as
 * U+202E), private-use and unassigned code points. At most [MAX_CHARACTERS] characters (spec D59).
 */
class Nickname private constructor(
    val value: String,
) {
    override fun equals(other: Any?): Boolean = other is Nickname && other.value == value

    override fun hashCode(): Int = value.hashCode()

    override fun toString(): String = "Nickname($value)"

    companion object {
        const val MAX_CHARACTERS = 32

        /** Most UTF-8 bytes a valid nickname can take (4 bytes per character at most). */
        const val MAX_BYTES = MAX_CHARACTERS * 4

        /** For text the user typed: cleans it. Null if nothing is left or it is too long. */
        fun clean(raw: String): Nickname? {
            val cleaned =
                buildString {
                    raw.codePoints().filter(::isAllowed).forEach { appendCodePoint(it) }
                }.trim()
            val length = cleaned.codePointCount(0, cleaned.length)
            return if (cleaned.isEmpty() || length > MAX_CHARACTERS) null else Nickname(cleaned)
        }

        /**
         * For nicknames received from other phones: accepted only if already clean, so every valid
         * encoding has exactly one meaning.
         */
        fun strict(received: String): Nickname? = clean(received)?.takeIf { it.value == received }

        private fun isAllowed(codePoint: Int): Boolean =
            when (Character.getType(codePoint).toByte()) {
                Character.CONTROL,
                Character.FORMAT,
                Character.PRIVATE_USE,
                Character.SURROGATE,
                Character.UNASSIGNED,
                Character.LINE_SEPARATOR,
                Character.PARAGRAPH_SEPARATOR,
                -> false

                else -> true
            }
    }
}
