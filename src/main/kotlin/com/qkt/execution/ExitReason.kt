package com.qkt.execution

/**
 * Why a position-closing fill occurred, for DSL exit-hook dispatch. [EXPIRY] is a dated futures
 * contract settled by the exchange; [ROLL_FAILED] is a continuous futures position the venue no
 * longer holds because its roll to the next contract failed; [LIQUIDATION] is a futures or option
 * position a backtest venue closed because account equity fell below maintenance margin. All three
 * run `ON CLOSE` hooks.
 */
enum class ExitReason {
    STOP,
    TAKE_PROFIT,
    CLOSE,
    EXPIRY,
    ROLL_FAILED,
    LIQUIDATION,
}
