package com.qkt.broker

import com.qkt.execution.ExitReason

/**
 * Why a position was reduced or closed, the same for every connector: a stop or a target, the
 * strategy's own signal, an operator ([MANUAL]: `qkt bot` or the venue's terminal), a timed hold
 * (`EXIT AFTER`), the venue's margin stop-out, a liquidation, or a contract's expiry.
 */
enum class CloseReason {
    STOP,
    TAKE_PROFIT,
    SIGNAL,
    MANUAL,
    TIMED,
    STOP_OUT,
    LIQUIDATION,
    EXPIRY,
    ;

    companion object {
        private val TIMED_CLOSE = Regex("-close(-r\\d+)?$")

        /**
         * The reason a closing fill of order [orderId] carries, given the [exitReason] its connector
         * reported: a venue-stated reason wins, then what qkt's own order ids say (`bot-` one-shots,
         * `-sl`/`-tp` bracket exits, `-close` timed exits), else the strategy's signal.
         */
        fun of(
            exitReason: ExitReason?,
            orderId: String?,
        ): CloseReason =
            when (exitReason) {
                ExitReason.STOP -> STOP
                ExitReason.TAKE_PROFIT -> TAKE_PROFIT
                ExitReason.LIQUIDATION -> LIQUIDATION
                ExitReason.EXPIRY, ExitReason.ROLL_FAILED -> EXPIRY
                ExitReason.CLOSE, null -> fromOrderId(orderId) ?: SIGNAL
            }

        /**
         * What qkt's order id [orderId] says about why it closes: `bot-…` is [MANUAL], `…-sl` [STOP],
         * `…-tp` [TAKE_PROFIT], `…-close` (or a retry `…-close-r2`) [TIMED]; null when it says nothing.
         */
        fun fromOrderId(orderId: String?): CloseReason? =
            when {
                orderId.isNullOrBlank() -> null
                orderId.startsWith("bot-") -> MANUAL
                orderId.endsWith("-sl") -> STOP
                orderId.endsWith("-tp") -> TAKE_PROFIT
                TIMED_CLOSE.containsMatchIn(orderId) -> TIMED
                else -> null
            }
    }
}
