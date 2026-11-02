package com.qkt.observe

import com.qkt.events.CandleEvent
import com.qkt.events.Event
import com.qkt.events.StreamCandleEvent
import com.qkt.events.TickEvent
import com.qkt.events.WarmupTickEvent

/**
 * Whether [event] is market data (a tick or a candle), which the audit journal may shed under load,
 * as opposed to a decision event, which it never sheds.
 */
internal fun isAuditMarketData(event: Event): Boolean =
    event is TickEvent || event is WarmupTickEvent || event is CandleEvent || event is StreamCandleEvent

/** The instrument a market-data [event] quotes, or null for every other event. */
internal fun marketDataSymbol(event: Event): String? =
    when (event) {
        is TickEvent -> event.tick.symbol
        is WarmupTickEvent -> event.tick.symbol
        is CandleEvent -> event.candle.symbol
        is StreamCandleEvent -> event.candle.symbol
        else -> null
    }
