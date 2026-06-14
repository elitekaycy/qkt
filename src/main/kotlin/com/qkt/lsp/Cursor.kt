package com.qkt.lsp

/**
 * Cursor arithmetic over raw document text. The language features reason about the
 * identifier at or just before a cursor; qkt's AST has no source spans, so this text-level
 * scan (cheap — `.qkt` files are small) is how completion and hover locate the word under
 * the caret without depending on a successful parse.
 */
internal object Cursor {
    /** Character offset of the 0-based [line]/[character] position within [text]. */
    fun offset(
        text: String,
        line: Int,
        character: Int,
    ): Int {
        var remaining = line
        var i = 0
        while (remaining > 0 && i < text.length) {
            if (text[i] == '\n') remaining--
            i++
        }
        return (i + character).coerceIn(0, text.length)
    }

    /** Index where the identifier ending at [end] begins, walking left over `[A-Za-z0-9_]`. */
    fun identStart(
        text: String,
        end: Int,
    ): Int {
        var start = end
        while (start > 0 && isIdentChar(text[start - 1])) start--
        return start
    }

    /** The identifier covering [offset], or null when the offset is not on an identifier. */
    fun identAround(
        text: String,
        offset: Int,
    ): String? {
        val start = identStart(text, offset)
        var end = offset
        while (end < text.length && isIdentChar(text[end])) end++
        return if (start == end) null else text.substring(start, end)
    }

    fun isIdentChar(c: Char): Boolean = c.isLetterOrDigit() || c == '_'
}
