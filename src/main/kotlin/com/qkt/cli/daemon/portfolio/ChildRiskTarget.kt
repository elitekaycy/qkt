package com.qkt.cli.daemon.portfolio

import com.qkt.risk.HaltScope

/** A child the book can act on when the account-level limit trips. */
interface ChildRiskTarget {
    /** Closes the child's open exposure. */
    fun flatten()

    /** Legs the child holds with no close working. Blocks on its engine; throws when it does not answer. */
    fun unclosedLegs(): Int

    /** Applies a halt with the book rule's [scope]. */
    fun halt(
        reason: String,
        scope: HaltScope,
    )

    /** Clears the child's active global halt. */
    fun resume()

    /** True when any global halt currently suppresses the child. */
    fun isHalted(): Boolean

    /** Active global halt reason, when available. */
    fun haltReason(): String?

    /** Active global halt scope, when available. */
    fun haltScope(): HaltScope?
}
