package com.qkt.execution

/**
 * Lifecycle state of a [ManagedOrder] from creation to termination.
 *
 * Terminal states ([FILLED], [CANCELLED], [REJECTED]) are sinks — once an order reaches
 * any of them, no further state transitions occur. [isTerminal] is the cheap predicate.
 */
enum class OrderState {
    /** Created locally; not yet submitted. */
    CREATED,

    /** Engine-managed parent waiting for a trigger condition before submission. */
    PENDING,

    /** Submitted to the broker; awaiting acknowledgement. */
    SUBMITTED,

    /** Acknowledged and active on the venue, awaiting fill. */
    WORKING,

    /** Some quantity has filled; the rest is still working. */
    PARTIALLY_FILLED,

    /** Fully filled. Terminal. */
    FILLED,

    /** Cancelled by strategy, engine, or venue. Terminal. */
    CANCELLED,

    /** Refused by the venue. Terminal. */
    REJECTED,
}

/** `true` iff this state is a terminal sink — no further transitions occur. */
val OrderState.isTerminal: Boolean
    get() = this == OrderState.FILLED || this == OrderState.CANCELLED || this == OrderState.REJECTED
