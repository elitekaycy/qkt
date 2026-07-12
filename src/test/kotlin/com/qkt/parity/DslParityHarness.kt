package com.qkt.parity

import com.qkt.app.LiveSession
import com.qkt.backtest.Backtest
import com.qkt.bus.EventBus
import com.qkt.candles.TimeWindow
import com.qkt.common.FixedClock
import com.qkt.common.MonotonicSequenceGenerator
import com.qkt.common.TradingCalendar
import com.qkt.dsl.compile.AstCompiler
import com.qkt.dsl.parse.Dsl
import com.qkt.dsl.parse.ParseResult
import com.qkt.events.RiskEvent
import com.qkt.execution.Trade
import com.qkt.marketdata.Candle
import com.qkt.marketdata.Tick
import com.qkt.marketdata.TickFeed
import com.qkt.marketdata.source.MarketSource
import com.qkt.marketdata.source.MarketSourceCapability
import com.qkt.risk.HaltRule
import com.qkt.strategy.Strategy
import java.math.BigDecimal
import java.time.Duration

internal object DslParityHarness {
    data class TradeState(
        val strategyId: String,
        val orderId: String,
        val symbol: String,
        val side: String,
        val quantity: String,
        val price: String,
        val timestamp: Long,
        val realized: String,
    )

    data class PositionState(
        val symbol: String,
        val quantity: String,
        val avgEntryPrice: String,
        val openedAt: Long?,
    )

    data class PnlState(
        val realized: String,
        val unrealized: String,
        val total: String,
    )

    data class HaltState(
        val reason: String,
        val strategyId: String?,
        val timestamp: Long,
    )

    data class Snapshot(
        val trades: List<TradeState>,
        val positions: List<PositionState>,
        val pnl: PnlState,
        val halts: List<HaltState>,
    )

    data class Result(
        val backtest: Snapshot,
        val live: Snapshot,
    )

    fun run(
        strategyId: String,
        source: String,
        ticks: List<Tick>,
        candleWindow: TimeWindow = TimeWindow.ONE_MINUTE,
        startingBalance: BigDecimal = BigDecimal("10000"),
        haltRules: () -> List<HaltRule> = { emptyList() },
    ): Result {
        require(ticks.isNotEmpty()) { "parity tape must not be empty" }
        val symbols = ticks.map { it.symbol }.distinct()
        val backtestResult =
            Backtest(
                strategies = listOf(strategyId to compile(source)),
                haltRules = haltRules(),
                ticks = ticks,
                candleWindow = candleWindow,
                initialTimestamp = ticks.first().timestamp,
                startingBalance = startingBalance,
            ).run()
        val backtest =
            Snapshot(
                trades =
                    backtestResult.trades.map {
                        tradeState(it.strategyId, it.trade, it.realized)
                    },
                positions =
                    backtestResult.finalPositionsByStrategy
                        .getValue(strategyId)
                        .values
                        .map(::positionState)
                        .sortedBy { it.symbol },
                pnl =
                    PnlState(
                        realized = number(backtestResult.perStrategy.getValue(strategyId).realizedTotal),
                        unrealized = number(backtestResult.perStrategy.getValue(strategyId).unrealizedTotal),
                        total = number(backtestResult.perStrategy.getValue(strategyId).totalPnL),
                    ),
                halts =
                    backtestResult.halts.map {
                        HaltState(it.reason, it.strategyId, it.timestamp)
                    },
            )

        val liveClock = FixedClock(ticks.first().timestamp)
        val liveBus = EventBus(liveClock, MonotonicSequenceGenerator())
        val liveHalts = mutableListOf<RiskEvent.Halted>()
        liveBus.subscribe<RiskEvent.Halted> { liveHalts.add(it) }
        val liveTrades = mutableListOf<TradeState>()
        val handle =
            LiveSession(
                strategies = listOf(strategyId to compile(source)),
                haltRules = haltRules(),
                source = TapeSource(ticks),
                symbols = symbols,
                candleWindow = candleWindow,
                clock = liveClock,
                calendar = TradingCalendar.crypto(),
                onTrade = { trade, realized, owner -> liveTrades.add(tradeState(owner, trade, realized)) },
                initialBalance = startingBalance,
                startingBalances = mapOf(strategyId to startingBalance),
                busOverride = liveBus,
            ).start()
        check(handle.awaitTermination(Duration.ofSeconds(10))) { "live parity session did not terminate" }
        val livePnl = handle.pnlSnapshot(strategyId)
        val live =
            Snapshot(
                trades = liveTrades.toList(),
                positions = handle.positionsFor(strategyId).map(::positionState).sortedBy { it.symbol },
                pnl =
                    PnlState(
                        realized = number(livePnl.realized),
                        unrealized = number(livePnl.unrealized),
                        total = number(livePnl.realized.add(livePnl.unrealized)),
                    ),
                halts = liveHalts.map { HaltState(it.reason, it.strategyId, it.timestamp) },
            )
        return Result(backtest, live)
    }

    private fun compile(source: String): Strategy =
        when (val parsed = Dsl.parse(source)) {
            is ParseResult.Success -> AstCompiler().compile(parsed.value)
            is ParseResult.Failure ->
                error(
                    parsed.errors.joinToString("\n") { error ->
                        "${error.line}:${error.col} ${error.message}"
                    },
                )
        }

    private fun tradeState(
        strategyId: String,
        trade: Trade,
        realized: BigDecimal,
    ): TradeState =
        TradeState(
            strategyId = strategyId,
            orderId = trade.orderId,
            symbol = trade.symbol,
            side = trade.side.name,
            quantity = number(trade.quantity),
            price = number(trade.price),
            timestamp = trade.timestamp,
            realized = number(realized),
        )

    private fun positionState(position: com.qkt.positions.Position): PositionState =
        PositionState(
            symbol = position.symbol,
            quantity = number(position.quantity),
            avgEntryPrice = number(position.avgEntryPrice),
            openedAt = position.openedAt,
        )

    private fun number(value: BigDecimal): String = value.stripTrailingZeros().toPlainString()

    private class TapeSource(
        private val ticks: List<Tick>,
    ) : MarketSource {
        override val name: String = "DslParityTape"
        override val capabilities: Set<MarketSourceCapability> =
            setOf(MarketSourceCapability.LIVE_TICKS, MarketSourceCapability.BARS)

        override fun supports(symbol: String): Boolean = true

        override fun liveTicks(symbols: List<String>): TickFeed =
            object : TickFeed {
                private var index = 0

                override fun next(): Tick? = if (index < ticks.size) ticks[index++] else null

                override fun close() = Unit
            }

        override fun bars(
            symbol: String,
            window: TimeWindow,
            range: com.qkt.common.TimeRange,
        ): Sequence<Candle> = emptySequence()
    }
}
