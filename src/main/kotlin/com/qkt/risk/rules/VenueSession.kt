package com.qkt.risk.rules

import com.qkt.execution.OrderRequest
import com.qkt.positions.PositionProvider
import com.qkt.risk.Decision
import com.qkt.risk.RiskRule
import java.time.Instant

/**
 * Refuses an order sent while its symbol is outside its trading session, in backtest and live alike. A venue refuses
 * every order then, closing ones included: Exness quotes US stock CFDs from 06:00 New York but answers MT5 retcode
 * 10044 "session closed" until the regular open. A strategy's close refused here is decided again on its next bar, as
 * it would be after the venue's refusal; the venue's own protective orders are not orders sent and are held, not
 * refused, by the venue models ([com.qkt.broker.Broker.acceptsOrders]).
 *
 * [acceptsOrders] answers for a symbol at the order's [OrderRequest.timestamp]: the run's broker, which a replay gives
 * the run's calendars ([com.qkt.backtest.ReplaySessions]) and a live MT5 account its profile's.
 */
class VenueSession(
    private val acceptsOrders: (symbol: String, atMs: Long) -> Boolean,
) : RiskRule {
    override fun evaluate(
        request: OrderRequest,
        positions: PositionProvider,
    ): Decision {
        if (acceptsOrders(request.symbol, request.timestamp)) return Decision.Approve
        val at = Instant.ofEpochMilli(request.timestamp)
        return Decision.Reject(
            "${request.symbol} is outside its trading session at $at: the venue takes no order until it opens",
        )
    }
}
