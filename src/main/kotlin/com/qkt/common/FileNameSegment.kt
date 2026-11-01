package com.qkt.common

/**
 * Turns an id into one file or directory name the platform accepts.
 *
 * Ids such as the stream `CME:ES` or the portfolio child `book:a` hold characters Windows forbids
 * in file names. On Windows each of `% < > : " | ? *` (and control characters) is
 * percent-encoded, so `book:a` is stored as `book%3Aa`. Path separators are left alone, so an id
 * that nests directories still nests them. Elsewhere names are left exactly as they are, so
 * existing state and journal directories keep working.
 */
object FileNameSegment {
    private const val RESERVED = "%<>:\"|?*"

    val isWindows: Boolean = System.getProperty("os.name", "").lowercase().contains("win")

    fun encode(
        id: String,
        windows: Boolean = isWindows,
    ): String {
        if (!windows) return id
        return buildString {
            for (c in id) {
                if (c in RESERVED || c.code < 0x20) append('%').append("%02X".format(c.code)) else append(c)
            }
        }
    }

    fun decode(
        name: String,
        windows: Boolean = isWindows,
    ): String {
        if (!windows) return name
        return buildString {
            var i = 0
            while (i < name.length) {
                val code =
                    if (name[i] == '%' &&
                        i + 3 <= name.length
                    ) {
                        name.substring(i + 1, i + 3).toIntOrNull(16)
                    } else {
                        null
                    }
                if (code != null) {
                    append(code.toChar())
                    i += 3
                } else {
                    append(name[i])
                    i++
                }
            }
        }
    }
}
