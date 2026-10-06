package com.qkt.broker.continuous

import com.qkt.broker.Broker
import com.qkt.broker.InstrumentSlippage
import com.qkt.broker.SubmitAck
import com.qkt.broker.exchange.ExchangeSimulator
import com.qkt.bus.EventBus
import com.qkt.common.FixedClock
import com.qkt.common.MonotonicSequenceGenerator
import com.qkt.common.Side
import com.qkt.derivatives.futures.ContinuousChains
import com.qkt.events.BrokerEvent
import com.qkt.events.CostIncurred
import com.qkt.events.TickEvent
import com.qkt.execution.OrderRequest
import com.qkt.execution.TimeInForce
import com.qkt.instrument.ContractCatalog
import com.qkt.instrument.ContractCatalogRegistry
import com.qkt.instrument.FuturesRoot
import com.qkt.instrument.ListedContract
import com.qkt.instrument.MarginTerms
import com.qkt.instrument.PriceAdjustment
import com.qkt.instrument.RollHistory
import com.qkt.instrument.RollPolicy
import com.qkt.instrument.RollRecord
import com.qkt.marketdata.Tick
import com.qkt.pnl.ContractFeeCommission
import com.qkt.pnl.NoCommission
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalTime

/**
 * BTCUSDT quarterlies with measured June, September and December 2024 front rolls (and the @next pairs) (the September roll moves the
 * series by 63000 - 63800 = -800), and a [ContinuousContractBroker] over an [ExchangeSimulator].
 */
internal class ContinuousFixture(
    adjust: PriceAdjustment = PriceAdjustment.PANAMA,
    startIso: String = "2024-09-20T00:00:00Z",
    slippageTicks: Int = 0,
    takerFeeRate: String = "0",
    refuseOpenLegs: Boolean = false,
    margin: MarginTerms? = null,
    deferLegs: Boolean = false,
) {
    private val deferred = ArrayDeque<() -> Unit>()
    val front = "BINANCE_UM:BTCUSDT@front"
    val clock = FixedClock(time = ms(startIso))
    val bus = EventBus(clock, MonotonicSequenceGenerator())
    val events = mutableListOf<BrokerEvent>()
    val costs = mutableListOf<CostIncurred>()
    val ledger = RollLedger()
    val fills = ContractFillLog()
    private val root =
        FuturesRoot(
            root = "BINANCE_UM:BTCUSDT",
            currency = "USDT",
            multiplier = BigDecimal.ONE,
            tickSize = BigDecimal("0.1"),
            volumeStep = BigDecimal("0.001"),
            volumeMin = BigDecimal("0.001"),
            volumeMax = null,
            calendar = null,
            exchangeFeePerContract = BigDecimal.ZERO,
            takerFeeRate = BigDecimal(takerFeeRate),
            margin = margin,
            roll = RollPolicy(8, LocalTime.of(8, 0), adjust),
            slippageTicks = slippageTicks,
        )
    val registry =
        ContractCatalogRegistry(
            listOf(root),
            mapOf(
                root.root to
                    ContractCatalog(
                        root.root,
                        listOf(
                            ListedContract("BTCUSDT_240628", ms("2024-06-28T08:00:00Z")),
                            ListedContract("BTCUSDT_240927", ms("2024-09-27T08:00:00Z")),
                            ListedContract("BTCUSDT_241227", ms("2024-12-27T08:00:00Z")),
                            ListedContract("BTCUSDT_250328", ms("2025-03-28T08:00:00Z")),
                        ),
                    ),
            ),
            mapOf(
                root.root to
                    RollHistory(
                        root.root,
                        "8d@08:00",
                        listOf(
                            RollRecord(
                                ms("2024-06-20T08:00:00Z"),
                                "BTCUSDT_240628",
                                "BTCUSDT_240927",
                                "65000",
                                "65000",
                            ),
                            RollRecord(
                                ms("2024-09-19T08:00:00Z"),
                                "BTCUSDT_240927",
                                "BTCUSDT_241227",
                                "63000",
                                "63800",
                            ),
                            RollRecord(
                                ms("2024-12-19T08:00:00Z"),
                                "BTCUSDT_241227",
                                "BTCUSDT_250328",
                                "97000",
                                "98500",
                            ),
                            RollRecord(
                                ms("2024-06-20T08:00:00Z"),
                                "BTCUSDT_240927",
                                "BTCUSDT_241227",
                                "66000",
                                "66700",
                            ),
                            RollRecord(
                                ms("2024-09-19T08:00:00Z"),
                                "BTCUSDT_241227",
                                "BTCUSDT_250328",
                                "63800",
                                "64700",
                            ),
                        ),
                    ),
            ),
        )
    val broker =
        ContinuousContractBroker(
            bus = bus,
            clock = clock,
            chains = ContinuousChains(requireNotNull(registry.futures())),
            symbols = setOf(front),
            ledger = ledger,
            fills = fills,
            venueFactory = { venueBus, prices, _ ->
                val fees = ContractFeeCommission(registry, NoCommission)
                val exchange = ExchangeSimulator(venueBus, clock, prices, registry, InstrumentSlippage, fees)
                val venue = if (refuseOpenLegs) RefusingOpenLegs(exchange, venueBus) else exchange
                ContractVenue(if (deferLegs) DeferringLegs(venue, deferred) else venue, exchange::onTick)
            },
        )

    init {
        bus.subscribe<BrokerEvent.OrderAccepted> { events += it }
        bus.subscribe<BrokerEvent.OrderRejected> { events += it }
        bus.subscribe<BrokerEvent.OrderCancelled> { events += it }
        bus.subscribe<BrokerEvent.OrderFilled> { events += it }
        bus.subscribe<CostIncurred> { costs += it }
    }

    /** Publishes an engine tick on the continuous stream at [price], at [atMs]. */
    fun tick(
        price: String,
        atMs: Long = clock.time,
    ) {
        clock.time = atMs
        bus.publish(TickEvent(Tick(front, BigDecimal(price), atMs)))
    }

    fun market(
        id: String,
        side: Side,
        qty: String = "0.01",
    ) = OrderRequest.Market(id, front, side, BigDecimal(qty), TimeInForce.GTC, clock.time, strategyId = "s")

    fun limit(
        id: String,
        side: Side,
        price: String,
    ) = OrderRequest.Limit(id, front, side, BigDecimal("0.01"), BigDecimal(price), TimeInForce.GTC, clock.time, "s")

    fun stop(
        id: String,
        side: Side,
        price: String,
    ) = OrderRequest.Stop(id, front, side, BigDecimal("0.01"), BigDecimal(price), TimeInForce.GTC, clock.time, "s")

    inline fun <reified T : BrokerEvent> only(): List<T> = events.filterIsInstance<T>()

    /** Lets the venue answer the roll legs it holds, one at a time, as a live venue answers later; returns how many. */
    fun releaseLegs(): Int {
        var released = 0
        while (deferred.isNotEmpty()) {
            deferred.removeFirst()()
            released++
        }
        return released
    }

    companion object {
        fun ms(iso: String): Long = Instant.parse(iso).toEpochMilli()
    }
}

/** A venue that refuses every roll's opening leg, as a venue out of margin or liquidity would. */
internal class RefusingOpenLegs(
    private val inner: Broker,
    private val venueBus: EventBus,
) : Broker by inner {
    override fun submit(request: OrderRequest): SubmitAck {
        if (!request.id.endsWith(":open")) return inner.submit(request)
        venueBus.publish(BrokerEvent.OrderRejected(request.id, null, "insufficient margin", request.strategyId))
        return SubmitAck(request.id, null, accepted = false, rejectReason = "insufficient margin")
    }
}

/** A venue that answers each roll leg only when released, after its submit returned, as a live venue does. */
internal class DeferringLegs(
    private val inner: Broker,
    private val held: ArrayDeque<() -> Unit>,
) : Broker by inner {
    override fun submit(request: OrderRequest): SubmitAck {
        if (!request.id.startsWith("roll:")) return inner.submit(request)
        held.addLast { inner.submit(request) }
        return SubmitAck(request.id, null, accepted = true)
    }
}
