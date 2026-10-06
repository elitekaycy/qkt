package com.qkt.app

import com.qkt.broker.FakeBroker
import com.qkt.broker.OrderTypeCapability
import com.qkt.bus.EventBus
import com.qkt.candles.TimeWindow
import com.qkt.common.FixedClock
import com.qkt.common.MonotonicSequenceGenerator
import com.qkt.common.SequentialIdGenerator
import com.qkt.common.TradingCalendar
import com.qkt.dsl.compile.AstCompiler
import com.qkt.dsl.compile.DslCompiledStrategy
import com.qkt.dsl.parse.Dsl
import com.qkt.dsl.parse.ParseResult
import com.qkt.engine.Engine
import com.qkt.events.BrokerEvent
import com.qkt.execution.OrderRequest
import com.qkt.marketdata.MarketPriceTracker
import com.qkt.marketdata.Tick
import com.qkt.marketdata.source.NullMarketSource
import com.qkt.persistence.NoopStatePersistor
import com.qkt.persistence.StatePersistor
import com.qkt.pnl.PnLCalculator
import com.qkt.pnl.StrategyPnL
import com.qkt.positions.StrategyPositionTracker
import com.qkt.risk.RiskEngine
import com.qkt.risk.RiskState
import com.qkt.strategy.Mode
import java.math.BigDecimal

/**
 * One live DSL strategy over a [FakeBroker] that never fills on its own: the test plays the venue,
 * filling, part-filling or cancelling each order, and closes 1m bars with a tick in the next minute.
 */
internal class ExitRetryHarness(
    source: String,
    persistor: StatePersistor = NoopStatePersistor(),
) {
    val symbol = "DERIBIT:BTC_USDC_PERPETUAL"
    val clock = FixedClock(0L)
    val bus = EventBus(clock, MonotonicSequenceGenerator())
    val broker = FakeBroker(bus, clock, setOf(OrderTypeCapability.MARKET))
    val strategyPositions = StrategyPositionTracker()
    val alerts = mutableListOf<Pair<String, String>>()
    val strategy: DslCompiledStrategy = compile(source)
    private var minute = 0L
    val pipeline: TradingPipeline =
        run {
            val prices = MarketPriceTracker()
            val positions = strategyPositions.account
            val pnl = PnLCalculator(positions, prices)
            val strategyPnl = StrategyPnL(strategyPositions, prices)
            TradingPipeline(
                clock = clock,
                ids = SequentialIdGenerator(),
                sequencer = MonotonicSequenceGenerator(),
                priceTracker = prices,
                positions = positions,
                pnl = pnl,
                strategyPositions = strategyPositions,
                strategyPnL = strategyPnl,
                bus = bus,
                broker = broker,
                engine = Engine(bus, prices),
                strategies = listOf(STRATEGY to strategy),
                riskEngine = RiskEngine(rules = emptyList(), positions = positions),
                riskState = RiskState(pnl, strategyPnl, clock, bus),
                mode = Mode.LIVE,
                calendar = TradingCalendar.crypto(),
                source = NullMarketSource,
                candleWindow = TimeWindow.ONE_MINUTE,
                persistor = persistor,
                onProtectionFailure = { id, message -> alerts += id to message },
            )
        }

    /** Close the current 1m bar: a tick inside it, then one in the next minute. */
    fun closeBar() {
        tick(minute * MINUTE + 1_000L)
        minute++
        tick(minute * MINUTE)
    }

    private fun tick(at: Long) {
        clock.advanceTo(at)
        pipeline.ingest(Tick(symbol, BigDecimal("60000"), at))
    }

    /** The order submitted most recently. */
    fun last(): OrderRequest = broker.submits.last()

    fun fill(
        order: OrderRequest,
        qty: BigDecimal = order.quantity,
    ) = bus.publish(
        BrokerEvent.OrderFilled(
            order.id,
            "v-${order.id}",
            symbol,
            order.side,
            BigDecimal("60000"),
            qty,
            STRATEGY,
            timestamp = clock.now(),
        ),
    )

    fun partFill(
        order: OrderRequest,
        qty: BigDecimal,
    ) = bus.publish(
        BrokerEvent.OrderPartiallyFilled(
            order.id,
            "v-${order.id}",
            symbol,
            order.side,
            BigDecimal("60000"),
            qty,
            qty,
            STRATEGY,
            timestamp = clock.now(),
        ),
    )

    fun cancel(order: OrderRequest) =
        bus.publish(
            BrokerEvent.OrderCancelled(order.id, "v-${order.id}", "cancelled at the venue", STRATEGY, clock.now()),
        )

    fun reject(order: OrderRequest) =
        bus.publish(BrokerEvent.OrderRejected(order.id, "v-${order.id}", "price band", STRATEGY, clock.now()))

    fun position(): BigDecimal = strategyPositions.positionFor(STRATEGY, symbol)?.quantity ?: BigDecimal.ZERO

    companion object {
        const val STRATEGY = "exit_retry"
        const val MINUTE = 60_000L

        fun compile(source: String): DslCompiledStrategy =
            when (val parsed = Dsl.parse(source)) {
                is ParseResult.Success -> AstCompiler().compile(parsed.value) as DslCompiledStrategy
                is ParseResult.Failure -> error(parsed.errors.joinToString { it.message })
            }

        /** Enters once, then exits with [exit] on every bar it holds. */
        fun source(exit: String): String =
            """
            STRATEGY exit_retry VERSION 1

            SYMBOLS
                btc = DERIBIT:BTC_USDC_PERPETUAL EVERY 1m

            RULES
                WHEN btc.close > 0 AND POSITION.btc = 0 AND TRADES.today = 0 THEN BUY btc SIZING 1
                WHEN POSITION.btc != 0 THEN $exit
            """.trimIndent()
    }
}
