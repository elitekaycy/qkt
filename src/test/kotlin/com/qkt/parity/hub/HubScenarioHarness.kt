package com.qkt.parity.hub

import com.qkt.backtest.Backtest
import com.qkt.candles.TimeWindow
import com.qkt.common.Money
import com.qkt.common.TimeRange
import com.qkt.common.TradingCalendar
import com.qkt.dsl.compile.AstCompiler
import com.qkt.dsl.compile.DslCompiledStrategy
import com.qkt.dsl.parse.Dsl
import com.qkt.dsl.parse.ParseResult
import com.qkt.marketdata.Candle
import com.qkt.marketdata.Tick
import com.qkt.marketdata.hub.HubMarketSource
import com.qkt.marketdata.hub.HubPolicy
import com.qkt.marketdata.source.CompositeMarketSource
import com.qkt.marketdata.source.MarketRequest
import com.qkt.marketdata.source.MarketSource
import com.qkt.marketdata.source.MarketSourceCapability
import com.qkt.marketdata.source.SymbolPattern
import com.qkt.parity.DslParityHarness
import com.qkt.strategy.WarmupStream
import java.math.BigDecimal
import java.nio.file.Path
import java.time.Instant

/**
 * Runs one strategy that reacts to hub facts through every path a fact can take into a decision:
 * the tick backtest, the `--bars` research tier, and a live session fed the exact ticks the hub's
 * live tail emits (that the tail emits exactly these is proven by [HubTailEquivalenceTest]).
 */
internal object HubScenarioHarness {
    data class Fill(
        val at: Instant,
        val symbol: String,
        val side: String,
    ) {
        override fun toString() = "$at $side $symbol"
    }

    data class Runs(
        val ticks: List<Fill>,
        val bars: List<Fill>,
        val live: List<Fill>?,
    )

    /** One price tick per [stepMs] per symbol, skipping instants [closed] says the market is shut. */
    fun tape(
        prices: Map<String, String>,
        from: Instant,
        to: Instant,
        stepMs: Long = 60_000L,
        closed: (Long) -> Boolean = { false },
    ): List<Tick> =
        (from.toEpochMilli() until to.toEpochMilli() step stepMs)
            .filterNot(closed)
            .flatMap { t -> prices.map { (symbol, price) -> Tick(symbol, Money.of(price), t) } }

    private class Tape(
        private val ticks: List<Tick>,
    ) : MarketSource {
        override val name = "tape"
        override val capabilities = setOf(MarketSourceCapability.TICKS, MarketSourceCapability.BARS)

        override fun supports(symbol: String) = ticks.any { it.symbol == symbol }

        override fun ticks(
            symbol: String,
            range: TimeRange,
        ): Sequence<Tick> =
            ticks.asSequence().filter {
                it.symbol == symbol &&
                    it.timestamp >= range.from.toEpochMilli() &&
                    it.timestamp < range.to.toEpochMilli()
            }

        override fun bars(
            symbol: String,
            window: TimeWindow,
            range: TimeRange,
        ): Sequence<Candle> =
            ticks(symbol, range)
                .groupBy { it.timestamp / window.durationMs * window.durationMs }
                .map { (start, group) ->
                    val p = group.first().price
                    Candle(symbol, p, p, p, p, Money.ZERO, start, start + window.durationMs)
                }.asSequence()
    }

    private fun compile(text: String): DslCompiledStrategy =
        AstCompiler().compile((Dsl.parse(text) as ParseResult.Success).value) as DslCompiledStrategy

    fun run(
        root: Path,
        strategy: String,
        prices: List<Tick>,
        from: Instant,
        to: Instant,
        window: String = "1m",
        policy: HubPolicy = HubPolicy(),
        calendar: TradingCalendar = TradingCalendar.crypto(),
        withLive: Boolean = true,
    ): Runs {
        val streams = compile(strategy).declaredStreams.values.distinct()
        val symbols = streams.map { it.qktSymbol }.distinct()
        val hub = HubMarketSource(root, policy)
        val source = CompositeMarketSource(listOf(SymbolPattern.prefix("HUB:") to hub), fallback = Tape(prices))

        fun backtest(forceBars: Boolean): List<Fill> =
            Backtest
                .fromSource(
                    strategies = listOf("s" to compile(strategy)),
                    calendar = calendar,
                    source = source,
                    request = MarketRequest(symbols = symbols, from = from, to = to),
                    candleWindow = TimeWindow.parse(window),
                    startingBalance = BigDecimal("100000"),
                    forceBars = forceBars,
                    barWindows =
                        if (forceBars) {
                            streams.associate {
                                it.qktSymbol to
                                    TimeWindow.parse(
                                        it.timeframe,
                                    )
                            }
                        } else {
                            emptyMap()
                        },
                ).run()
                .trades
                .map { Fill(Instant.ofEpochMilli(it.trade.timestamp), it.trade.symbol, it.trade.side.name) }
        val live =
            if (!withLive) {
                null
            } else {
                val range = TimeRange(from, to)
                val hubTicks = symbols.filter { it.startsWith("HUB:") }.flatMap { hub.ticks(it, range).toList() }
                // A market tick and a hub fact on the same millisecond: the market tick first, as the backtest merge does.
                val merged =
                    (prices.filter { it.timestamp >= from.toEpochMilli() } + hubTicks).sortedWith(
                        compareBy({ it.timestamp }, { it.symbol.startsWith("HUB:") }),
                    )
                // What the live session's warmup reads before it starts: the store's records, and the
                // market's bars before `from` (ticks before it are history, not live).
                val history = TimeRange(from.minusSeconds(400L * 86_400L), from)
                val warmup =
                    streams.associate { key ->
                        val tf = TimeWindow.parse(key.timeframe)
                        val bars = if (key.qktSymbol.startsWith("HUB:")) hub else Tape(prices)
                        WarmupStream(key.qktSymbol, tf) to bars.bars(key.qktSymbol, tf, history).toList()
                    }
                DslParityHarness
                    .run(
                        strategyId = "s",
                        source = strategy,
                        ticks = merged,
                        warmupByStream = warmup,
                        candleWindow = TimeWindow.parse(window),
                    ).live
                    .trades
                    .map { Fill(Instant.ofEpochMilli(it.timestamp), it.symbol, it.side) }
            }
        return Runs(backtest(forceBars = false), backtest(forceBars = true), live)
    }
}
