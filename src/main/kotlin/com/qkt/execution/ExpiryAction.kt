package com.qkt.execution

/** What a [OrderRequest.TimeExit] does to its target order when the deadline passes. */
enum class ExpiryAction {
    /** Cancel the target if it's still working. */
    CANCEL,

    /** Cancel the target if still working AND flatten any resulting position at market. */
    CLOSE_AT_MARKET,
}
