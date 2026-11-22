package com.qkt.dsl.sets

/**
 * `NEXT_STRIKE(expr)` and `PREV_STRIKE(expr)`, written inside `FOR EACH m IN <set>` over a set of event contracts
 * whose members move (runtime membership plan §4.5): `expr` with the loop variable bound to the contract of the same
 * event one strike up ([step] +1) or down (-1), read from that member's latest value ([StrikeLadder] names the
 * neighbour). [SetFunctionLowering.lowerMoving] rewrites a call into its [moving] form; one left unlowered (a fixed
 * set, outside `FOR EACH`) is refused by the compiler.
 */
enum class StrikeNeighbour(
    val step: Int,
) {
    NEXT_STRIKE(1),
    PREV_STRIKE(-1),
    ;

    /** The name of this function lowered over a set whose members move, e.g. `NEXT_STRIKE.MOVING`. */
    val moving: String get() = name + SetFunction.MOVING_SUFFIX

    companion object {
        /** The names as a strategy writes them (upper case), for the editor surface. */
        val names: List<String> = entries.map { it.name }

        /** The function spelled [name] (any case), or null when [name] is not one. */
        fun named(name: String): StrikeNeighbour? = entries.firstOrNull { it.name.equals(name, ignoreCase = true) }

        /** The function whose moving name is [name], or null when [name] is not a moving call of one. */
        fun moving(name: String): StrikeNeighbour? = entries.firstOrNull { it.moving == name }
    }
}
