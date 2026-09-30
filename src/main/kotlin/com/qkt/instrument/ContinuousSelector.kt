package com.qkt.instrument

/**
 * Which contract of a root a continuous stream follows: `front` is the nearest contract not yet
 * rolled out of, `next` the one after it. Written after `@` in a stream symbol (`CME:ES@front`).
 */
enum class ContinuousSelector(
    val token: String,
) {
    FRONT("front"),
    NEXT("next"),
    ;

    /** The continuous symbol of [root] under this selector, e.g. `CME:ES@front`. */
    fun symbolFor(root: String): String = "$root@$token"

    companion object {
        /** The selector spelled [token] (lower case), or null for anything else. */
        fun parse(token: String): ContinuousSelector? = entries.firstOrNull { it.token == token }
    }
}
