package com.qkt.broker.options

import com.qkt.bus.EventBus
import com.qkt.common.FixedClock
import com.qkt.common.MonotonicSequenceGenerator
import com.qkt.common.Side
import com.qkt.derivatives.options.chain.ChainQuoteLookup
import com.qkt.derivatives.options.chain.OptionChainFixture
import com.qkt.derivatives.options.chain.OptionChainFixture.Companion.ms
import com.qkt.events.BrokerEvent
import com.qkt.execution.OrderRequest
import com.qkt.execution.TimeInForce
import com.qkt.marketdata.Tick
import java.math.BigDecimal
import java.nio.file.Path

/** An [OptionExchange] over [OptionChainFixture]'s call, with a capturing bus and a settable clock. */
internal class OptionExchangeFixture(
    dir: Path,
) {
    val chain = OptionChainFixture(dir, takerFeeRate = "0.0003", feeCapRate = "0.125")
    val symbol = chain.symbol
    val clock = FixedClock(time = ms("2026-10-01T00:00:00Z"))
    val bus = EventBus(clock, MonotonicSequenceGenerator())
    val events = mutableListOf<BrokerEvent>()
    val exchange = OptionExchange(bus, clock, chain.registry, ChainQuoteLookup(dir, chain.registry))
    private var ids = 0

    init {
        bus.subscribe<BrokerEvent.OrderAccepted> { events += it }
        bus.subscribe<BrokerEvent.OrderRejected> { events += it }
        bus.subscribe<BrokerEvent.OrderCancelled> { events += it }
        bus.subscribe<BrokerEvent.OrderFilled> { events += it }
    }

    /** Moves the clock to [at] and hands the venue a tick of [onSymbol] there. */
    fun tick(
        at: String,
        onSymbol: String = symbol,
    ) {
        clock.time = ms(at)
        exchange.onTick(Tick(onSymbol, BigDecimal.ONE, clock.time))
    }

    fun market(
        side: Side,
        quantity: String = "0.1",
    ) = OrderRequest.Market("o${++ids}", symbol, side, BigDecimal(quantity), TimeInForce.GTC, clock.time, "s1")

    fun limit(
        side: Side,
        price: String,
        tif: TimeInForce = TimeInForce.GTC,
    ) = OrderRequest.Limit("o${++ids}", symbol, side, BigDecimal("0.1"), BigDecimal(price), tif, clock.time, "s1")

    inline fun <reified T : BrokerEvent> last(): T = events.filterIsInstance<T>().last()
}
