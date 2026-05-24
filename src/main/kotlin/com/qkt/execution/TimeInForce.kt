package com.qkt.execution

/**
 * How long an order remains active on a venue.
 *
 * Not every venue honors every value; brokers translate to the closest supported mode
 * or reject if no equivalent exists.
 */
enum class TimeInForce {
    /** Expires at the end of the venue's trading session. */
    DAY,

    /** Good-till-cancelled — stays open indefinitely until filled or cancelled. */
    GTC,

    /** Immediate-or-cancel — any unfilled portion cancels immediately. */
    IOC,

    /** Fill-or-kill — must fill entirely on submission or cancel. */
    FOK,

    /** Good-til-date — stays open until [OrderRequest.expiresAt] passes (epoch millis). */
    GTD,
}
