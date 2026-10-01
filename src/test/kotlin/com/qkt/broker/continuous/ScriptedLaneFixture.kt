package com.qkt.broker.continuous

import com.qkt.broker.Broker
import com.qkt.broker.OrderTypeCapability
import com.qkt.broker.SubmitAck
import com.qkt.bus.EventBus
import com.qkt.common.FixedClock
import com.qkt.common.MonotonicSequenceGenerator
import com.qkt.common.Side
import com.qkt.derivatives.futures.ContinuousChains
import com.qkt.events.BrokerEvent
import com.qkt.events.TickEvent
import com.qkt.execution.OrderRequest
import com.qkt.execution.TimeInForce
import com.qkt.instrument.ContractCatalog
import com.qkt.instrument.ContractCatalogRegistry
import com.qkt.instrument.FuturesRoot
import com.qkt.instrument.ListedContract
import com.qkt.instrument.PriceAdjustment
import com.qkt.instrument.RollHistory
import com.qkt.instrument.RollPolicy
import com.qkt.instrument.RollRecord
import com.qkt.marketdata.Tick
import com.qkt.positions.PositionProvider
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalTime

/** A continuous stream whose venue only records what it is sent; the test publishes the venue's answers. */
internal class ScriptedLaneFixture {
    private fun ms(iso: String) = Instant.parse(iso).toEpochMilli()

    val front = "BINANCE_UM:BTCUSDT@front"
    val sep = "BINANCE_UM:BTCUSDT_240927"
    val dec = "BINANCE_UM:BTCUSDT_241227"
    val roll = ms("2024-09-19T08:00:00Z")
    private val root =
        FuturesRoot(
            "BINANCE_UM:BTCUSDT",
            "USDT",
            BigDecimal.ONE,
            BigDecimal("0.1"),
            BigDecimal("0.001"),
            BigDecimal("0.001"),
            null,
            null,
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            null,
            RollPolicy(8, LocalTime.of(8, 0), PriceAdjustment.PANAMA),
        )
    private val registry =
        ContractCatalogRegistry(
            listOf(root),
            mapOf(
                root.root to
                    ContractCatalog(
                        root.root,
                        listOf(
                            "240628" to "2024-06-28",
                            "240927" to "2024-09-27",
                            "241227" to "2024-12-27",
                        ).map { (c, d) -> ListedContract("BTCUSDT_$c", ms("${d}T08:00:00Z")) },
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
                            RollRecord(roll, "BTCUSDT_240927", "BTCUSDT_241227", "63000", "63800"),
                        ),
                    ),
            ),
        )

    /** A venue that only records what it is sent; the test publishes its answers. */
    class Scripted : Broker {
        override val name = "scripted"
        override val capabilities = setOf(OrderTypeCapability.MARKET)
        val sent = mutableListOf<OrderRequest>()

        override fun submit(request: OrderRequest): SubmitAck {
            sent += request
            return SubmitAck(request.id, null, accepted = true)
        }

        override fun cancel(orderId: String) {}
    }

    val clock = FixedClock(time = ms("2024-09-19T07:45:00Z"))
    private val bus = EventBus(clock, MonotonicSequenceGenerator())
    val venue = Scripted()
    lateinit var venueBus: EventBus
    lateinit var lanePositions: PositionProvider
    val ledger = RollLedger()
    val engine = mutableListOf<BrokerEvent>()
    val broker =
        ContinuousContractBroker(
            bus,
            clock,
            ContinuousChains(requireNotNull(registry.futures())),
            setOf(front),
            ledger,
            ContractFillLog(),
        ) { b, _, p ->
            venueBus = b
            lanePositions = p
            ContractVenue(venue)
        }

    init {
        bus.subscribe<BrokerEvent.OrderFilled> { engine += it }
        bus.subscribe<BrokerEvent.OrderPartiallyFilled> { engine += it }
        bus.subscribe<BrokerEvent.OrderRejected> { engine += it }
        bus.publish(TickEvent(Tick(front, BigDecimal("63010"), clock.time)))
    }

    fun slice(
        id: String,
        contract: String,
        side: Side,
        quantity: String,
        cumulative: String,
        price: String,
    ) = venueBus.publish(
        BrokerEvent.OrderPartiallyFilled(
            id,
            "v",
            contract,
            side,
            BigDecimal(price),
            BigDecimal(quantity),
            BigDecimal(cumulative),
            "s",
            clock.time,
        ),
    )

    fun last(
        id: String,
        contract: String,
        side: Side,
        quantity: String,
        price: String,
    ) = venueBus.publish(
        BrokerEvent.OrderFilled(id, "v", contract, side, BigDecimal(price), BigDecimal(quantity), "s", clock.time),
    )

    fun cancelled(id: String) = venueBus.publish(BrokerEvent.OrderCancelled(id, "v", "price band", "s", clock.time))

    /** Enters 0.010 long on September, filled in two slices, then ticks into the roll. */
    fun holdIntoRoll() {
        broker.submit(
            OrderRequest.Market("entry", front, Side.BUY, BigDecimal("0.010"), TimeInForce.GTC, clock.time, "s"),
        )
        slice("entry", sep, Side.BUY, "0.004", "0.004", "63000")
        last("entry", sep, Side.BUY, "0.006", "63010")
        clock.time = roll
        bus.publish(TickEvent(Tick(front, BigDecimal("63000"), clock.time)))
    }

    fun leg(suffix: String) = venue.sent.single { it.id.startsWith("roll:") && it.id.endsWith(suffix) }
}
