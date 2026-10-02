package com.qkt.broker.exchange

import com.qkt.broker.InstrumentSlippage
import com.qkt.bus.EventBus
import com.qkt.common.FixedClock
import com.qkt.common.MonotonicSequenceGenerator
import com.qkt.common.Side
import com.qkt.events.BrokerEvent
import com.qkt.execution.OrderRequest
import com.qkt.execution.TimeInForce
import com.qkt.instrument.ContractCatalog
import com.qkt.instrument.ContractCatalogRegistry
import com.qkt.instrument.FuturesRoot
import com.qkt.instrument.ListedContract
import com.qkt.marketdata.MarketPriceTracker
import com.qkt.marketdata.Tick
import com.qkt.pnl.ContractFeeCommission
import com.qkt.pnl.NoCommission
import java.math.BigDecimal
import java.time.Instant

/** One BTCUSDT root with two listed quarterlies, its perpetual, and an [ExchangeSimulator] over them. */
internal class ExchangeFixture(
    slippageTicks: Int = 2,
    takerFeeRate: String = "0",
    expiryGuardHours: Int = 24,
) {
    val perp = "BINANCE_UM:BTCUSDT"
    val sep = "BINANCE_UM:BTCUSDT_240927"
    val dec = "BINANCE_UM:BTCUSDT_241227"
    val sepExpiry = ms("2024-09-27T08:00:00Z")
    val decExpiry = ms("2024-12-27T08:00:00Z")
    val clock = FixedClock(time = ms("2024-09-20T00:00:00Z"))
    val bus = EventBus(clock, MonotonicSequenceGenerator())
    val prices = MarketPriceTracker()
    val events = mutableListOf<BrokerEvent>()
    private val root =
        FuturesRoot(
            root = "BINANCE_UM:BTCUSDT",
            currency = "USDT",
            multiplier = BigDecimal.ONE,
            tickSize = BigDecimal("0.1"),
            volumeStep = BigDecimal("0.001"),
            volumeMin = BigDecimal("0.001"),
            volumeMax = BigDecimal("100"),
            calendar = null,
            exchangeFeePerContract = BigDecimal.ZERO,
            takerFeeRate = BigDecimal(takerFeeRate),
            margin = null,
            slippageTicks = slippageTicks,
            expiryGuardHours = expiryGuardHours,
            perpetual = "BTCUSDT",
        )
    val registry =
        ContractCatalogRegistry(
            listOf(root),
            mapOf(
                root.root to
                    ContractCatalog(
                        root.root,
                        listOf(
                            ListedContract("BTCUSDT_240927", sepExpiry, deliveryPrice = "63000.5"),
                            ListedContract("BTCUSDT_241227", decExpiry),
                        ),
                    ),
            ),
        )
    val settlements = SettlementLog()
    val sim =
        ExchangeSimulator(
            bus,
            clock,
            prices,
            registry,
            slippage = InstrumentSlippage,
            fees = ContractFeeCommission(registry, NoCommission),
            settlements = settlements,
        )

    init {
        bus.subscribe<BrokerEvent.OrderAccepted> { events += it }
        bus.subscribe<BrokerEvent.OrderRejected> { events += it }
        bus.subscribe<BrokerEvent.OrderCancelled> { events += it }
        bus.subscribe<BrokerEvent.OrderFilled> { events += it }
    }

    /** Moves the clock to [atMs], marks [symbol] at [price] and hands the tick to the simulator. */
    fun tick(
        symbol: String,
        price: String,
        atMs: Long = clock.time,
    ) {
        clock.time = atMs
        val tick = Tick(symbol, BigDecimal(price), atMs)
        prices.update(tick)
        sim.onTick(tick)
    }

    fun market(
        id: String,
        side: Side,
        qty: String,
        symbol: String = sep,
    ) = OrderRequest.Market(id, symbol, side, BigDecimal(qty), TimeInForce.GTC, clock.time, strategyId = "s")

    fun limit(
        id: String,
        side: Side,
        price: String,
        qty: String = "0.01",
    ) = OrderRequest.Limit(id, sep, side, BigDecimal(qty), BigDecimal(price), TimeInForce.GTC, clock.time, "s")

    fun stop(
        id: String,
        side: Side,
        price: String,
        qty: String = "0.01",
    ) = OrderRequest.Stop(id, sep, side, BigDecimal(qty), BigDecimal(price), TimeInForce.GTC, clock.time, "s")

    inline fun <reified T : BrokerEvent> only(): List<T> = events.filterIsInstance<T>()

    companion object {
        fun ms(iso: String): Long = Instant.parse(iso).toEpochMilli()
    }
}
