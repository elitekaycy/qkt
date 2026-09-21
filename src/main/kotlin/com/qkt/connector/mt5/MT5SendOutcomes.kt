package com.qkt.connector.mt5

/**
 * Reads what a failed order or close reply says about the venue, so a failure that may have
 * executed is never reported as a plain rejection. E.g. `"IO error: timeout"` after the request was
 * sent is ambiguous (the order may be live), while `"HTTP 400 invalid volume"` is a real refusal.
 */
internal object MT5SendOutcomes {
    /** `POSITION_CLOSED` / `FROZEN` retcodes as gateways embed them in a non-2xx error body. */
    private val VENUE_OWNED_CLOSE_RETCODE_IN_BODY: Regex =
        Regex(""""retcode"\s*:\s*(?:$MT5_TRADE_RETCODE_POSITION_CLOSED|$MT5_TRADE_RETCODE_FROZEN)\b""")

    /**
     * True when a close acknowledgement says the venue owns this position's exit: the
     * position is already gone (`TRADE_RETCODE_POSITION_CLOSED`) or the market sits inside
     * the freeze level of its venue-side stop (`TRADE_RETCODE_FROZEN`), which the venue is
     * about to execute itself. Recognised whether the gateway surfaced the code in the
     * parsed result or only inside a non-2xx error body.
     */
    fun venueOwnsClose(
        resp: MT5OrderResponse,
        errorMessage: String,
    ): Boolean =
        resp.result.retcode == MT5_TRADE_RETCODE_POSITION_CLOSED ||
            resp.result.retcode == MT5_TRADE_RETCODE_FROZEN ||
            VENUE_OWNED_CLOSE_RETCODE_IN_BODY.containsMatchIn(errorMessage)

    /** True for failures where the request may have reached the venue despite the error. */
    fun isAmbiguousSendFailure(errorMessage: String): Boolean =
        errorMessage.startsWith("IO error") ||
            errorMessage.startsWith("HTTP 409") ||
            errorMessage.startsWith("HTTP 5") ||
            errorMessage.startsWith("invalid gateway response after send")
}
