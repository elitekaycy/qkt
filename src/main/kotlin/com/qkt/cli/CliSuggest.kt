package com.qkt.cli

/**
 * "Did you mean ...?" suggestions for mistyped subcommands and flags (#1380): edit distance
 * (Levenshtein) against the known names, accepting only close matches so a wild guess never
 * points at an unrelated command.
 */
internal object CliSuggest {
    /** Closest candidate within [maxDistance] edits, or null when nothing is close. */
    fun suggest(
        input: String,
        candidates: Collection<String>,
        maxDistance: Int = 2,
    ): String? =
        candidates
            .filter { it.length >= 2 }
            .map { it to distance(input, it) }
            .filter { it.second <= maxDistance }
            .minWithOrNull(compareBy({ it.second }, { it.first }))
            ?.first

    private fun distance(
        a: String,
        b: String,
    ): Int {
        if (a == b) return 0
        var prev = IntArray(b.length + 1) { it }
        var curr = IntArray(b.length + 1)
        for (i in 1..a.length) {
            curr[0] = i
            for (j in 1..b.length) {
                curr[j] =
                    minOf(
                        prev[j] + 1,
                        curr[j - 1] + 1,
                        prev[j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1,
                    )
            }
            val tmp = prev
            prev = curr
            curr = tmp
        }
        return prev[b.length]
    }
}
