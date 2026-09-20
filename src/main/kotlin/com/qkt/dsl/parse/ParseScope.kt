package com.qkt.dsl.parse

/**
 * Grammar context that changes how a token is read, set by an enclosing clause while its body
 * is parsed and restored when the body ends.
 *
 * The same words mean different things in different places: bare `entry` is an ordinary
 * reference in a rule but the parent's entry price inside a stack layer or an `ON_FILL` child,
 * and `LIMIT WITH <distance>` is only legal inside an exit hook. One shared scope lets the
 * clause that opens the context and the expression or order parser that reads it stay in
 * separate components.
 */
internal class ParseScope {
    /** Inside a `STACK [ ... ]` layer's trigger price, where `entry` is the parent entry price. */
    var inStackLayerAt: Boolean = false

    /** Inside an `ON_FILL { ... }` child, where `entry` is the parent's fill price. */
    var inOtoChildPrice: Boolean = false

    /** Inside an `ON_STOP`/`ON_TP`/`ON_CLOSE` block, where LIMIT/STOP may be priced WITH/AGAINST. */
    var inExitHook: Boolean = false
}
