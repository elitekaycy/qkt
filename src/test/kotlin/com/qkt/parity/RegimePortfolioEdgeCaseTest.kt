package com.qkt.parity

import com.qkt.backtest.Backtest
import com.qkt.common.FixedClock
import com.qkt.common.Money
import com.qkt.common.Side
import com.qkt.common.TradingCalendar
import com.qkt.dsl.portfolio.PortfolioGate
import com.qkt.dsl.portfolio.PortfolioLoader
import com.qkt.instrument.InstrumentMeta
import com.qkt.instrument.InstrumentRegistry
import com.qkt.marketdata.Tick
import com.qkt.risk.book.Allocation
import com.qkt.risk.book.AllocationMethod
import com.qkt.risk.book.BookRiskConfig
import java.math.BigDecimal
import java.nio.file.Files
import java.nio.file.Path
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/**
 * Edge-case and combination tests for regime-adaptive portfolio backtests.
 *
 * These scenarios stress the parity path introduced in [RegimeAdaptiveBacktestParityTest]:
 * short-side flatten, simultaneous multi-child ("stack") regimes, mixed child timeframes,
 * HOLD reactivation, and config-driven book-risk wiring.
 */
class RegimePortfolioEdgeCaseTest {
    private val sym = "BACKTEST:BTCUSDT"
    private val firstTs = 1_700_000_000_000L

    private fun unitRegistry(): InstrumentRegistry =
        object : InstrumentRegistry {
            override fun lookup(qktSymbol: String) =
                InstrumentMeta(
                    qktSymbol = qktSymbol,
                    contractSize = BigDecimal.ONE,
                    volumeStep = BigDecimal("0.001"),
                    volumeMin = BigDecimal("0.001"),
                    volumeMax = BigDecimal("1000"),
                    pointSize = BigDecimal("0.01"),
                    digits = 2,
                    tradeStopsLevelPoints = 0,
                )
        }

    /** Builds the gate helpers exactly as [BacktestContext.buildPortfolio] does. */
    private fun gateHelpers(compiled: com.qkt.dsl.portfolio.PortfolioCompiled): GateHelpers {
        val aliasToStrategyId = compiled.children.associate { it.alias to it.strategyId }
        val portfolioGate =
            PortfolioGate(
                ast = compiled.ast,
                clock = FixedClock(time = firstTs),
                calendar = TradingCalendar.crypto(),
            ).also {
                it.prepare()
                it.initialState()
            }
        return GateHelpers(
            gateFor = { strategyId ->
                portfolioGate.currentState().activeByAlias[strategyId.substringAfter(":")] == true
            },
            preCandle = { candle -> portfolioGate.onCandle(candle) },
            regimeWeights = {
                portfolioGate.currentState().weightByAlias.mapKeys { (alias, _) ->
                    aliasToStrategyId[alias] ?: alias
                }
            },
        )
    }

    private data class GateHelpers(
        val gateFor: (String) -> Boolean,
        val preCandle: (com.qkt.marketdata.Candle) -> Unit,
        val regimeWeights: () -> Map<String, BigDecimal>,
    )

    @Test
    fun `short child flattens with forced buy when regime deactivates it`(
        @TempDir tmp: Path,
    ) {
        writeShortPortfolio(tmp)
        val compiled = PortfolioLoader.load(tmp.resolve("book.qkt"))
        val gate = gateHelpers(compiled)

        // Three 1m bars. Bar 1 close=100 (low regime -> shortChild active) sells short at next open.
        // Bar 2 close=300 (high regime -> shortChild deactivated) flattens the short.
        // Bar 3 close=300 keeps longChild active; no new short re-entry because gate is closed.
        val backtest =
            Backtest(
                strategies =
                    compiled.children.map { child ->
                        child.strategyId to
                            com.qkt.backtest.GatedChild(
                                strategyId = child.strategyId,
                                inner = child.compiled,
                                hold = child.hold,
                                gateFor = gate.gateFor,
                                flattenSymbols = child.symbols,
                            )
                    },
                ticks =
                    listOf(
                        Tick(sym, Money.of("100"), firstTs),
                        Tick(sym, Money.of("100"), firstTs + 60_000L),
                        Tick(sym, Money.of("300"), firstTs + 120_000L),
                        Tick(sym, Money.of("300"), firstTs + 180_000L),
                    ),
                candleWindow = com.qkt.candles.TimeWindow.ONE_MINUTE,
                initialTimestamp = firstTs,
                startingBalance = BigDecimal("10000"),
                bookCapital = BigDecimal("10000"),
                instruments = unitRegistry(),
                gateFor = gate.gateFor,
                preCandle = gate.preCandle,
                regimeWeights = gate.regimeWeights,
                bookRiskConfig =
                    BookRiskConfig(
                        capital = BigDecimal("10000"),
                        allocation = Allocation(method = AllocationMethod.REGIME_WEIGHTED),
                    ),
            )

        val result = backtest.run()
        val shortTrades = result.trades.filter { it.strategyId == "book:shortChild" }

        // One short entry (SELL) and one forced flatten (BUY) from the same child.
        assertThat(shortTrades).hasSize(2)
        assertThat(shortTrades[0].trade.side).isEqualTo(Side.SELL)
        assertThat(shortTrades[1].trade.side).isEqualTo(Side.BUY)
        val shortFinalQty =
            result.finalPositionsByStrategy["book:shortChild"]?.get(sym)?.quantity ?: BigDecimal.ZERO
        assertThat(shortFinalQty).isEqualByComparingTo(BigDecimal.ZERO)
    }

    @Test
    fun `stacked regime runs multiple children at full weight simultaneously`(
        @TempDir tmp: Path,
    ) {
        writeStackPortfolio(tmp)
        val compiled = PortfolioLoader.load(tmp.resolve("book.qkt"))
        val gate = gateHelpers(compiled)

        val backtest =
            Backtest(
                strategies =
                    compiled.children.map { child ->
                        child.strategyId to
                            com.qkt.backtest.GatedChild(
                                strategyId = child.strategyId,
                                inner = child.compiled,
                                hold = child.hold,
                                gateFor = gate.gateFor,
                                flattenSymbols = child.symbols,
                            )
                    },
                ticks =
                    listOf(
                        Tick(sym, Money.of("100"), firstTs),
                        Tick(sym, Money.of("300"), firstTs + 60_000L),
                        Tick(sym, Money.of("300"), firstTs + 120_000L),
                    ),
                candleWindow = com.qkt.candles.TimeWindow.ONE_MINUTE,
                initialTimestamp = firstTs,
                startingBalance = BigDecimal("10000"),
                bookCapital = BigDecimal("10000"),
                instruments = unitRegistry(),
                gateFor = gate.gateFor,
                preCandle = gate.preCandle,
                regimeWeights = gate.regimeWeights,
                bookRiskConfig =
                    BookRiskConfig(
                        capital = BigDecimal("10000"),
                        allocation = Allocation(method = AllocationMethod.REGIME_WEIGHTED),
                    ),
            )

        val result = backtest.run()
        val tradesByChild = result.trades.groupBy { it.strategyId }

        // Both children are weighted 1.0 in the 'up' regime, so both enter on bar 2.
        assertThat(tradesByChild["book:trend"]).hasSize(1)
        assertThat(tradesByChild["book:meanrev"]).hasSize(1)
        assertThat(result.finalPositionsByStrategy["book:trend"]?.get(sym)?.quantity)
            .isGreaterThan(BigDecimal.ZERO)
        assertThat(result.finalPositionsByStrategy["book:meanrev"]?.get(sym)?.quantity)
            .isGreaterThan(BigDecimal.ZERO)
    }

    @Test
    fun `mixed child timeframes share account and book risk`(
        @TempDir tmp: Path,
    ) {
        writeMixedTimeframePortfolio(tmp)
        val compiled = PortfolioLoader.load(tmp.resolve("book.qkt"))
        val gate = gateHelpers(compiled)

        // 5m child gets a bar every 5 minutes; 1m child every minute. Regime flips on 1m bars.
        val ticks =
            (0..10).flatMap { m ->
                listOf(
                    Tick(sym, Money.of(if (m < 5) "100" else "300"), firstTs + m * 60_000L),
                )
            }

        val backtest =
            Backtest(
                strategies =
                    compiled.children.map { child ->
                        child.strategyId to
                            com.qkt.backtest.GatedChild(
                                strategyId = child.strategyId,
                                inner = child.compiled,
                                hold = child.hold,
                                gateFor = gate.gateFor,
                                flattenSymbols = child.symbols,
                            )
                    },
                ticks = ticks,
                candleWindow = com.qkt.candles.TimeWindow.ONE_MINUTE,
                initialTimestamp = firstTs,
                startingBalance = BigDecimal("10000"),
                bookCapital = BigDecimal("10000"),
                instruments = unitRegistry(),
                gateFor = gate.gateFor,
                preCandle = gate.preCandle,
                regimeWeights = gate.regimeWeights,
                bookRiskConfig =
                    BookRiskConfig(
                        capital = BigDecimal("10000"),
                        allocation = Allocation(method = AllocationMethod.REGIME_WEIGHTED),
                    ),
            )

        val result = backtest.run()
        assertThat(result.trades).isNotEmpty
        assertThat(result.bookRisk).isNotNull
        assertThat(result.perStrategy.keys).containsExactlyInAnyOrder("book:fast", "book:slow")
    }

    @Test
    fun `HOLD child survives deactivation and reactivates without duplicate flatten`(
        @TempDir tmp: Path,
    ) {
        writeHoldReactivationPortfolio(tmp)
        val compiled = PortfolioLoader.load(tmp.resolve("book.qkt"))
        val gate = gateHelpers(compiled)

        // low -> b enters; high -> b holds (no flatten), a enters; low -> b still holds, a flattens.
        val backtest =
            Backtest(
                strategies =
                    compiled.children.map { child ->
                        child.strategyId to
                            com.qkt.backtest.GatedChild(
                                strategyId = child.strategyId,
                                inner = child.compiled,
                                hold = child.hold,
                                gateFor = gate.gateFor,
                                flattenSymbols = child.symbols,
                            )
                    },
                ticks =
                    listOf(
                        Tick(sym, Money.of("100"), firstTs),
                        Tick(sym, Money.of("100"), firstTs + 60_000L),
                        Tick(sym, Money.of("300"), firstTs + 120_000L),
                        Tick(sym, Money.of("300"), firstTs + 180_000L),
                        Tick(sym, Money.of("100"), firstTs + 240_000L),
                        Tick(sym, Money.of("100"), firstTs + 300_000L),
                    ),
                candleWindow = com.qkt.candles.TimeWindow.ONE_MINUTE,
                initialTimestamp = firstTs,
                startingBalance = BigDecimal("10000"),
                bookCapital = BigDecimal("10000"),
                instruments = unitRegistry(),
                gateFor = gate.gateFor,
                preCandle = gate.preCandle,
                regimeWeights = gate.regimeWeights,
                bookRiskConfig =
                    BookRiskConfig(
                        capital = BigDecimal("10000"),
                        allocation = Allocation(method = AllocationMethod.REGIME_WEIGHTED),
                    ),
            )

        val result = backtest.run()
        val aTrades = result.trades.filter { it.strategyId == "book:a" }
        val bTrades = result.trades.filter { it.strategyId == "book:b" }

        // a is non-HOLD: enters in high, flattens in low.
        assertThat(aTrades).hasSize(2)
        assertThat(aTrades[0].trade.side).isEqualTo(Side.BUY)
        assertThat(aTrades[1].trade.side).isEqualTo(Side.SELL)

        // b is HOLD: only one entry (in low) and no flatten trades across regime switches.
        assertThat(bTrades).hasSize(1)
        assertThat(bTrades[0].trade.side).isEqualTo(Side.BUY)
        assertThat(result.finalPositionsByStrategy["book:b"]?.get(sym)?.quantity)
            .isGreaterThan(BigDecimal.ZERO)
    }

    private fun writeShortPortfolio(tmp: Path) {
        Files.writeString(
            tmp.resolve("shortChild.qkt"),
            """
            STRATEGY short_child VERSION 1
            SYMBOLS
                x = BACKTEST:BTCUSDT EVERY 1m
            RULES
                WHEN x.close > 0 AND POSITION.x = 0
                THEN SELL x SIZING 1
            """.trimIndent(),
        )
        Files.writeString(
            tmp.resolve("longChild.qkt"),
            """
            STRATEGY long_child VERSION 1
            SYMBOLS
                x = BACKTEST:BTCUSDT EVERY 1m
            RULES
                WHEN x.close > 0 AND POSITION.x = 0
                THEN BUY x SIZING 1
            """.trimIndent(),
        )
        Files.writeString(
            tmp.resolve("book.qkt"),
            """
            PORTFOLIO book VERSION 1 CAPITAL 10000
            SYMBOLS
                btc = BACKTEST:BTCUSDT EVERY 1m
            IMPORT 'shortChild.qkt' AS shortChild
            IMPORT 'longChild.qkt'  AS longChild
            REGIMES
                NAME r
                STATE high WHEN btc.close > 200
                STATE low DEFAULT
            ALLOCATE
                METHOD regime_weighted
                high -> longChild 1.0
                low  -> shortChild 1.0
            RULES
                WHEN btc.close > 200 RUN longChild
                WHEN btc.close <= 200 RUN shortChild
            """.trimIndent(),
        )
    }

    private fun writeStackPortfolio(tmp: Path) {
        Files.writeString(
            tmp.resolve("trend.qkt"),
            """
            STRATEGY trend VERSION 1
            SYMBOLS
                x = BACKTEST:BTCUSDT EVERY 1m
            RULES
                WHEN x.close > 0 AND POSITION.x = 0
                THEN BUY x SIZING 1
            """.trimIndent(),
        )
        Files.writeString(
            tmp.resolve("meanrev.qkt"),
            """
            STRATEGY meanrev VERSION 1
            SYMBOLS
                x = BACKTEST:BTCUSDT EVERY 1m
            RULES
                WHEN x.close > 0 AND POSITION.x = 0
                THEN BUY x SIZING 1
            """.trimIndent(),
        )
        Files.writeString(
            tmp.resolve("book.qkt"),
            """
            PORTFOLIO book VERSION 1 CAPITAL 10000
            SYMBOLS
                btc = BACKTEST:BTCUSDT EVERY 1m
            IMPORT 'trend.qkt'   AS trend
            IMPORT 'meanrev.qkt' AS meanrev
            REGIMES
                NAME r
                STATE up   WHEN btc.close > 200
                STATE down DEFAULT
            ALLOCATE
                METHOD regime_weighted
                up   -> trend 0.5, meanrev 0.5
                down -> trend 0.0, meanrev 0.0
            RULES
                RUN trend
                RUN meanrev
            """.trimIndent(),
        )
    }

    private fun writeMixedTimeframePortfolio(tmp: Path) {
        Files.writeString(
            tmp.resolve("fast.qkt"),
            """
            STRATEGY fast VERSION 1
            SYMBOLS
                x = BACKTEST:BTCUSDT EVERY 1m
            RULES
                WHEN x.close > 0 AND POSITION.x = 0
                THEN BUY x SIZING 1
            """.trimIndent(),
        )
        Files.writeString(
            tmp.resolve("slow.qkt"),
            """
            STRATEGY slow VERSION 1
            SYMBOLS
                x = BACKTEST:BTCUSDT EVERY 5m
            RULES
                WHEN x.close > 0 AND POSITION.x = 0
                THEN BUY x SIZING 1
            """.trimIndent(),
        )
        Files.writeString(
            tmp.resolve("book.qkt"),
            """
            PORTFOLIO book VERSION 1 CAPITAL 10000
            SYMBOLS
                btc = BACKTEST:BTCUSDT EVERY 1m
            IMPORT 'fast.qkt' AS fast
            IMPORT 'slow.qkt' AS slow
            REGIMES
                NAME r
                STATE up   WHEN btc.close > 200
                STATE down DEFAULT
            ALLOCATE
                METHOD regime_weighted
                up   -> fast 0.5, slow 0.5
                down -> fast 0.0, slow 0.0
            RULES
                RUN fast
                RUN slow
            """.trimIndent(),
        )
    }

    private fun writeHoldReactivationPortfolio(tmp: Path) {
        Files.writeString(
            tmp.resolve("a.qkt"),
            """
            STRATEGY child_a VERSION 1
            SYMBOLS
                x = BACKTEST:BTCUSDT EVERY 1m
            RULES
                WHEN x.close > 0 AND POSITION.x = 0
                THEN BUY x SIZING 1
            """.trimIndent(),
        )
        Files.writeString(
            tmp.resolve("b.qkt"),
            """
            STRATEGY child_b VERSION 1
            SYMBOLS
                x = BACKTEST:BTCUSDT EVERY 1m
            RULES
                WHEN x.close > 0 AND POSITION.x = 0
                THEN BUY x SIZING 1
            """.trimIndent(),
        )
        Files.writeString(
            tmp.resolve("book.qkt"),
            """
            PORTFOLIO book VERSION 1 CAPITAL 10000
            SYMBOLS
                btc = BACKTEST:BTCUSDT EVERY 1m
            IMPORT 'a.qkt' AS a
            IMPORT 'b.qkt' AS b HOLD
            REGIMES
                NAME r
                STATE high WHEN btc.close > 200
                STATE low DEFAULT
            ALLOCATE
                METHOD regime_weighted
                high -> a 1.0
                low  -> b 1.0
            RULES
                WHEN btc.close > 200 RUN a
                WHEN btc.close <= 200 RUN b
            """.trimIndent(),
        )
    }
}
