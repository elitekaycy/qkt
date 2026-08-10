package com.qkt.dsl.compile

import com.qkt.backtest.Backtest
import com.qkt.backtest.TradeRecord
import com.qkt.candles.TimeWindow
import com.qkt.dsl.parse.Dsl
import com.qkt.dsl.parse.ParseResult
import com.qkt.events.RiskEvent
import com.qkt.events.RiskRejectedEvent
import com.qkt.instrument.InstrumentRegistry
import com.qkt.instrument.NoopInstrumentRegistry
import com.qkt.marketdata.Candle
import com.qkt.marketdata.Tick
import com.qkt.marketdata.source.InMemoryMarketSource
import com.qkt.marketdata.source.MarketRequest
import com.qkt.marketdata.source.MarketSource
import com.qkt.marketdata.source.MarketSourceCapability
import com.qkt.parity.DslParityHarness
import com.qkt.risk.DailyDrawdownBasis
import com.qkt.risk.DrawdownBasis
import com.qkt.risk.HaltRule
import com.qkt.risk.StrategyRiskLimits
import com.qkt.risk.book.BookRiskConfig
import com.qkt.strategy.PerStreamWarmable
import com.qkt.strategy.Strategy
import com.qkt.strategy.WarmupSpec
import com.qkt.strategy.WarmupStream
import java.math.BigDecimal
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import org.assertj.core.api.Assertions.assertThat

internal object GeneratedStrategyReplay {
    private const val SYMBOL = "BACKTEST:X"

    fun compile(path: Path): Strategy = namedStrategy(path).second

    private fun namedStrategy(path: Path): Pair<String, Strategy> {
        val parsed = Dsl.parseFile(path)
        assertThat(parsed).isInstanceOf(ParseResult.Success::class.java)
        val ast = (parsed as ParseResult.Success).value
        return ast.name to AstCompiler().compile(ast)
    }

    fun assertTickBarAndLiveParity(
        path: Path,
        closes: List<String>,
        expectedTradeCount: Int = 1,
        expectedRejectionCount: Int = 0,
        expectedHaltCount: Int = 0,
        startingBalance: BigDecimal = BigDecimal.ZERO,
        bookCapital: BigDecimal? = null,
        bookRiskConfig: BookRiskConfig? = null,
        instruments: InstrumentRegistry = NoopInstrumentRegistry,
        strategyRiskLimits: StrategyRiskLimits = StrategyRiskLimits(),
        maxOrderQty: BigDecimal = com.qkt.risk.rules.PreTradeControls.DEFAULT_MAX_ORDER_QTY,
        maxOrderNotional: BigDecimal = com.qkt.risk.rules.PreTradeControls.DEFAULT_MAX_ORDER_NOTIONAL,
        dailyDdBasis: DailyDrawdownBasis = DailyDrawdownBasis.BALANCE,
        totalDdBasis: DrawdownBasis = DrawdownBasis.STATIC,
        haltRules: () -> List<HaltRule> = { emptyList() },
    ): DslParityHarness.Result {
        val candles =
            (closes + closes.last()).mapIndexed { index, close -> candle(close, index) }
        return assertTickBarAndLiveParity(
            path,
            mapOf(SYMBOL to candles),
            window = TimeWindow.ONE_MINUTE,
            closeOnlyTicks = true,
            expectedTradeCount = expectedTradeCount,
            expectedRejectionCount = expectedRejectionCount,
            expectedHaltCount = expectedHaltCount,
            startingBalance = startingBalance,
            bookCapital = bookCapital,
            bookRiskConfig = bookRiskConfig,
            instruments = instruments,
            strategyRiskLimits = strategyRiskLimits,
            maxOrderQty = maxOrderQty,
            maxOrderNotional = maxOrderNotional,
            dailyDdBasis = dailyDdBasis,
            totalDdBasis = totalDdBasis,
            haltRules = haltRules,
        )
    }

    fun assertTickBarAndLiveParity(
        path: Path,
        candlesBySymbol: Map<String, List<Candle>>,
        window: TimeWindow,
        closeOnlyTicks: Boolean = false,
        expectedTradeCount: Int = 1,
        expectedRejectionCount: Int = 0,
        expectedHaltCount: Int = 0,
        startingBalance: BigDecimal = BigDecimal.ZERO,
        bookCapital: BigDecimal? = null,
        bookRiskConfig: BookRiskConfig? = null,
        instruments: InstrumentRegistry = NoopInstrumentRegistry,
        strategyRiskLimits: StrategyRiskLimits = StrategyRiskLimits(),
        maxOrderQty: BigDecimal = com.qkt.risk.rules.PreTradeControls.DEFAULT_MAX_ORDER_QTY,
        maxOrderNotional: BigDecimal = com.qkt.risk.rules.PreTradeControls.DEFAULT_MAX_ORDER_NOTIONAL,
        dailyDdBasis: DailyDrawdownBasis = DailyDrawdownBasis.BALANCE,
        totalDdBasis: DrawdownBasis = DrawdownBasis.STATIC,
        haltRules: () -> List<HaltRule> = { emptyList() },
    ): DslParityHarness.Result {
        val symbols = candlesBySymbol.keys.toList()
        val allCandles = candlesBySymbol.values.flatten()
        val ticks =
            allCandles
                .flatMap { candle -> if (closeOnlyTicks) closeOnlyTicks(candle) else ohlcvTicks(candle) }
                .sortedWith(compareBy<Tick> { it.timestamp }.thenBy { symbols.indexOf(it.symbol) })

        val tickResult =
            Backtest(
                strategies = listOf(namedStrategy(path)),
                haltRules = haltRules(),
                ticks = ticks,
                candleWindow = window,
                startingBalance = startingBalance,
                startingBalances = mapOf(namedStrategy(path).first to startingBalance),
                bookCapital = bookCapital,
                bookRiskConfig = bookRiskConfig,
                instruments = instruments,
                strategyRiskLimits = mapOf(namedStrategy(path).first to strategyRiskLimits),
                maxOrderQty = maxOrderQty,
                maxOrderNotional = maxOrderNotional,
                dailyDdBasis = dailyDdBasis,
                totalDdBasis = totalDdBasis,
            ).run()

        val barSource =
            object : InMemoryMarketSource() {
                override val capabilities: Set<MarketSourceCapability> =
                    super.capabilities + MarketSourceCapability.VOLUME
            }
        candlesBySymbol.forEach { (symbol, candles) -> barSource.seedBars(symbol, window, candles) }
        val barResult =
            Backtest
                .fromSource(
                    strategies = listOf(namedStrategy(path)),
                    haltRules = haltRules(),
                    source = barSource,
                    request =
                        MarketRequest(
                            symbols = symbols,
                            from = Instant.ofEpochMilli(allCandles.minOf { it.startTime }),
                            to = Instant.ofEpochMilli(allCandles.maxOf { it.endTime }),
                        ),
                    candleWindow = window,
                    startingBalance = startingBalance,
                    startingBalances = mapOf(namedStrategy(path).first to startingBalance),
                    bookCapital = bookCapital,
                    bookRiskConfig = bookRiskConfig,
                    instruments = instruments,
                    strategyRiskLimits = mapOf(namedStrategy(path).first to strategyRiskLimits),
                    maxOrderQty = maxOrderQty,
                    maxOrderNotional = maxOrderNotional,
                    dailyDdBasis = dailyDdBasis,
                    totalDdBasis = totalDdBasis,
                ).run()

        val tickResolvedSource =
            TickResolvedSource(
                candlesBySymbol.mapValues { (_, candles) -> candles.dropLast(1) },
                ticks,
            )
        val tickResolvedRequest =
            MarketRequest(
                symbols = symbols,
                from = Instant.ofEpochMilli(ticks.first().timestamp),
                to = Instant.ofEpochMilli(Math.addExact(ticks.last().timestamp, 1L)),
            )
        val sourceTickResult =
            Backtest
                .fromSource(
                    strategies = listOf(namedStrategy(path)),
                    haltRules = haltRules(),
                    source = tickResolvedSource,
                    request = tickResolvedRequest,
                    candleWindow = window,
                    startingBalance = startingBalance,
                    startingBalances = mapOf(namedStrategy(path).first to startingBalance),
                    bookCapital = bookCapital,
                    bookRiskConfig = bookRiskConfig,
                    instruments = instruments,
                    strategyRiskLimits = mapOf(namedStrategy(path).first to strategyRiskLimits),
                    maxOrderQty = maxOrderQty,
                    maxOrderNotional = maxOrderNotional,
                    dailyDdBasis = dailyDdBasis,
                    totalDdBasis = totalDdBasis,
                ).run()
        val tickResolvedResult =
            Backtest
                .fromSource(
                    strategies = listOf(namedStrategy(path)),
                    haltRules = haltRules(),
                    source = tickResolvedSource,
                    request = tickResolvedRequest,
                    candleWindow = window,
                    startingBalance = startingBalance,
                    startingBalances = mapOf(namedStrategy(path).first to startingBalance),
                    bookCapital = bookCapital,
                    bookRiskConfig = bookRiskConfig,
                    instruments = instruments,
                    strategyRiskLimits = mapOf(namedStrategy(path).first to strategyRiskLimits),
                    maxOrderQty = maxOrderQty,
                    maxOrderNotional = maxOrderNotional,
                    dailyDdBasis = dailyDdBasis,
                    totalDdBasis = totalDdBasis,
                    forceBars = true,
                    tickFills = true,
                ).run()

        assertThat(tickResult.rejections).hasSize(expectedRejectionCount)
        assertThat(barResult.rejections).hasSize(expectedRejectionCount)
        assertThat(barResult.rejections.map(::canonical))
            .isEqualTo(tickResult.rejections.map(::canonical))
        assertThat(tickResult.halts).hasSize(expectedHaltCount)
        assertThat(barResult.halts).hasSize(expectedHaltCount)
        assertThat(barResult.halts.map(::canonical))
            .isEqualTo(tickResult.halts.map(::canonical))
        assertThat(tickResult.trades).hasSize(expectedTradeCount)
        assertThat(barResult.trades).hasSize(expectedTradeCount)
        assertThat(barResult.trades.map(::canonical))
            .isEqualTo(tickResult.trades.map(::canonical))
        assertThat(sourceTickResult.trades.map(::canonical))
            .isEqualTo(tickResult.trades.map(::canonical))
        assertThat(sourceTickResult.rejections.map(::canonical))
            .isEqualTo(tickResult.rejections.map(::canonical))
        assertThat(sourceTickResult.halts.map(::canonical))
            .isEqualTo(tickResult.halts.map(::canonical))
        assertThat(sourceTickResult.finalPositionsByStrategy)
            .isEqualTo(tickResult.finalPositionsByStrategy)
        assertThat(sourceTickResult.accounting).isEqualTo(tickResult.accounting)
        assertThat(sourceTickResult.bookRisk).isEqualTo(tickResult.bookRisk)
        assertThat(tickResolvedResult.trades.map(::canonical))
            .isEqualTo(sourceTickResult.trades.map(::canonical))
        assertThat(tickResolvedResult.rejections.map(::canonical))
            .isEqualTo(sourceTickResult.rejections.map(::canonical))
        assertThat(tickResolvedResult.halts.map(::canonical))
            .isEqualTo(sourceTickResult.halts.map(::canonical))
        assertThat(tickResolvedResult.finalPositionsByStrategy)
            .isEqualTo(sourceTickResult.finalPositionsByStrategy)
        assertThat(tickResolvedResult.global).isEqualTo(sourceTickResult.global)
        assertThat(tickResolvedResult.perStrategy).isEqualTo(sourceTickResult.perStrategy)
        assertThat(tickResolvedResult.accounting).isEqualTo(sourceTickResult.accounting)
        assertThat(tickResolvedResult.bookRisk).isEqualTo(sourceTickResult.bookRisk)

        val strategyId = namedStrategy(path).first
        val liveParity =
            DslParityHarness.run(
                strategyId = strategyId,
                source = Files.readString(path),
                ticks = ticks,
                warmupByStream = generatedWarmup(namedStrategy(path).second, ticks),
                candleWindow = window,
                startingBalance = startingBalance,
                instruments = instruments,
                strategyRiskLimits = strategyRiskLimits,
                bookCapital = bookCapital,
                bookRiskConfig = bookRiskConfig,
                maxOrderQty = maxOrderQty,
                maxOrderNotional = maxOrderNotional,
                dailyDdBasis = dailyDdBasis,
                totalDdBasis = totalDdBasis,
                haltRules = haltRules,
            )
        assertThat(liveParity.live).isEqualTo(liveParity.backtest)
        assertThat(liveParity.backtest.rejections).hasSize(expectedRejectionCount)
        assertThat(liveParity.backtest.halts).hasSize(expectedHaltCount)
        assertThat(liveParity.backtest.trades).hasSize(expectedTradeCount)
        return liveParity
    }

    private fun generatedWarmup(
        strategy: Strategy,
        ticks: List<Tick>,
    ): Map<WarmupStream, List<Candle>> {
        val specs = (strategy as? PerStreamWarmable)?.perStreamWarmup.orEmpty()
        val upperMs = specs.keys.associateWith { stream -> stream.window.windowStartFor(ticks.first().timestamp) }
        return specs.mapValues { (stream, spec) ->
            when (spec) {
                WarmupSpec.None -> emptyList()
                is WarmupSpec.Bars -> {
                    val base = ticks.first { it.symbol == stream.symbol }.price
                    val step = base.abs().max(BigDecimal.ONE).movePointLeft(4)
                    (0 until spec.count).map { index ->
                        val offset = step.multiply(BigDecimal(index % 7 - 3))
                        val open = base.subtract(offset)
                        val close = base.add(offset)
                        val startTime = upperMs.getValue(stream) - (spec.count - index) * spec.window.durationMs
                        Candle(
                            symbol = stream.symbol,
                            open = open,
                            high = open.max(close).add(step),
                            low = open.min(close).subtract(step),
                            close = close,
                            volume = BigDecimal(index + 1),
                            startTime = startTime,
                            endTime = startTime + spec.window.durationMs,
                        )
                    }
                }
                is WarmupSpec.Duration,
                is WarmupSpec.Ticks,
                -> error("compiled DSL stream must declare bar warmup: $stream=$spec")
            }
        }
    }

    private fun canonical(record: TradeRecord): List<Any> =
        with(record.trade) {
            listOf(record.strategyId, symbol, side, quantity, price, timestamp)
        }

    private fun canonical(event: RiskRejectedEvent): List<Any> =
        with(event.request) {
            listOf(strategyId, symbol, side, quantity, event.reason, event.timestamp)
        }

    private fun canonical(event: RiskEvent.Halted): List<Any?> =
        listOf(event.reason, event.strategyId, event.cancelWorkingOrders, event.timestamp)

    private class TickResolvedSource(
        private val candlesBySymbol: Map<String, List<Candle>>,
        private val ticks: List<Tick>,
    ) : MarketSource {
        override val name: String = "GeneratedTickResolved"
        override val capabilities: Set<MarketSourceCapability> =
            setOf(
                MarketSourceCapability.TICKS,
                MarketSourceCapability.BARS,
                MarketSourceCapability.VOLUME,
            )

        override fun supports(symbol: String): Boolean = symbol in candlesBySymbol

        override fun bars(
            symbol: String,
            window: TimeWindow,
            range: com.qkt.common.TimeRange,
        ): Sequence<Candle> =
            candlesBySymbol
                .getValue(symbol)
                .asSequence()
                .filter { it.startTime >= range.from.toEpochMilli() && it.endTime <= range.to.toEpochMilli() }

        override fun ticks(
            symbol: String,
            range: com.qkt.common.TimeRange,
        ): Sequence<Tick> =
            ticks.asSequence().filter {
                it.symbol == symbol &&
                    it.timestamp >= range.from.toEpochMilli() &&
                    it.timestamp < range.to.toEpochMilli()
            }
    }

    private fun closeOnlyTicks(candle: Candle): List<Tick> =
        listOf(Tick(candle.symbol, candle.close, candle.startTime, volume = candle.volume))

    private fun ohlcvTicks(candle: Candle): List<Tick> {
        val step = (candle.endTime - candle.startTime) / 4
        return listOf(
            Tick(candle.symbol, candle.open, candle.startTime),
            Tick(candle.symbol, candle.low, candle.startTime + step),
            Tick(candle.symbol, candle.high, candle.startTime + 2 * step),
            Tick(candle.symbol, candle.close, candle.endTime - 1, volume = candle.volume),
        )
    }

    private fun candle(
        close: String,
        index: Int,
    ): Candle =
        Candle(
            symbol = SYMBOL,
            open = BigDecimal(close),
            high = BigDecimal(close),
            low = BigDecimal(close),
            close = BigDecimal(close),
            volume = BigDecimal.ONE,
            startTime = index * 60_000L,
            endTime = (index + 1) * 60_000L,
        )
}
