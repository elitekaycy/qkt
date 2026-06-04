package com.qkt.cli.daemon

import com.qkt.execution.Trade
import java.math.BigDecimal
import org.slf4j.LoggerFactory

/**
 * Structured per-fill log line so `/logs` carries realized P&L for the observe verifier (#33).
 *
 * e.g. a SELL of 0.2 XAUUSD at 2351 closing +12.50 renders as
 * `trade SELL EXNESS:XAUUSD qty=0.2 px=2351 realized=12.50`.
 */
object TradeLog {
    private val log = LoggerFactory.getLogger("qkt.trade")

    fun line(
        trade: Trade,
        realized: BigDecimal,
    ): String =
        "trade ${trade.side} ${trade.symbol} qty=${trade.quantity.toPlainString()} " +
            "px=${trade.price.toPlainString()} realized=${realized.toPlainString()}"

    /** Emit the line at INFO under the caller's current strategy MDC. */
    fun emit(
        trade: Trade,
        realized: BigDecimal,
    ) = log.info(line(trade, realized))
}
