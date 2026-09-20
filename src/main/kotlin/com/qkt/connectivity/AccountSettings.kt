package com.qkt.connectivity

/**
 * The keys a `brokers:` entry may carry. An unknown key is refused rather than ignored, because a
 * misspelled setting silently falls back to its default — and for an identity check such as
 * `expected_account_login`, the default is "do not check".
 */
object AccountSettings {
    /** Keys every entry may carry whatever its connector: the selector and the shared nested blocks. */
    val SHARED: Set<String> = setOf("type", "calendars", "aliases", "capability_restrictions", "instrument_overrides")

    /** Refuses any key of [entryName] outside [known] and [SHARED], suggesting the closest known key. */
    fun requireKnown(
        entryName: String,
        keys: Set<String>,
        known: Set<String>,
    ) {
        val allowed = known + SHARED
        val unknown = keys.filterNot { it in allowed }
        require(unknown.isEmpty()) {
            unknown.joinToString(prefix = "brokers.$entryName has ", separator = "; ") { key ->
                val closest =
                    allowed
                        .minByOrNull {
                            distance(
                                key,
                                it,
                            )
                        }?.takeIf { distance(key, it) <= MAX_TYPO_DISTANCE }
                "unknown setting '$key'" + (closest?.let { " (did you mean '$it'?)" } ?: "")
            } + ". Known settings: ${allowed.sorted().joinToString()}"
        }
    }

    /** Levenshtein edit distance. */
    private fun distance(
        a: String,
        b: String,
    ): Int {
        var previous = IntArray(b.length + 1) { it }
        for (i in 1..a.length) {
            val current = IntArray(b.length + 1)
            current[0] = i
            for (j in 1..b.length) {
                val substitution = previous[j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1
                current[j] = minOf(substitution, previous[j] + 1, current[j - 1] + 1)
            }
            previous = current
        }
        return previous[b.length]
    }

    private const val MAX_TYPO_DISTANCE = 3
}
