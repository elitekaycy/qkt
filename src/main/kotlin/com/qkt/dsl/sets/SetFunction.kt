package com.qkt.dsl.sets

/**
 * A cross-member function over a symbol set: `RANK_OF(expr, set)`, `SUM_OF`, `AVG_OF`, `MAX_OF`, `MIN_OF` and
 * `COUNT_OF(test, set)`, written inside `FOR EACH m IN <set>` with `expr` naming the loop variable (design §3.5).
 *
 * While a set's members are fixed, [SetFunctionLowering] rewrites each call into a [lowered] call whose arguments
 * are `expr` once per member ([selfFirst]: this member's own value comes first), and the compiler evaluates it with
 * set semantics: members whose value is Undefined are left out. The [lowered] name holds a `.`, which no identifier
 * can, so a hand-written `RANK_OF(a, b, c)` keeps its strict meaning. Over a set whose members move,
 * [SetFunctionLowering.lowerMoving] rewrites it into a [moving] call instead, read from the set's shared
 * cross-section at run time.
 */
enum class SetFunction(
    val selfFirst: Boolean,
) {
    RANK_OF(true),
    SUM_OF(false),
    AVG_OF(false),
    MAX_OF(false),
    MIN_OF(false),
    COUNT_OF(false),
    ;

    /** The name of this function once lowered over a set's members, e.g. `SUM_OF.SET`. */
    val lowered: String get() = name + LOWERED_SUFFIX

    /** The name of this function over a set whose members move, e.g. `SUM_OF.MOVING`. */
    val moving: String get() = name + MOVING_SUFFIX

    companion object {
        /** Marks a lowered call; `.` cannot appear in a function name a strategy writes. */
        const val LOWERED_SUFFIX: String = ".SET"

        /** Marks a call over a set whose members move. */
        const val MOVING_SUFFIX: String = ".MOVING"

        /** The names of the set functions as a strategy writes them (upper case), for the editor surface. */
        val names: List<String> = entries.map { it.name }

        /** The set function spelled [name] (any case), or null when [name] is not one. */
        fun named(name: String): SetFunction? = entries.firstOrNull { it.name.equals(name, ignoreCase = true) }

        /** The set function whose lowered name is [name], or null when [name] is not a lowered call. */
        fun lowered(name: String): SetFunction? = entries.firstOrNull { it.lowered == name }

        /** The set function whose moving name is [name], or null when [name] is not a moving call. */
        fun moving(name: String): SetFunction? = entries.firstOrNull { it.moving == name }
    }
}
