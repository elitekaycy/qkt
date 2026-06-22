package com.qkt.cli

import com.qkt.backtest.BrokerKind
import com.qkt.backtest.sweep.SweepReplay
import com.qkt.dsl.parse.Dsl
import com.qkt.dsl.parse.ParseResult
import java.nio.file.Files
import java.nio.file.Path
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/**
 * The per-scenario overrides on [BacktestContext.backtest] must be wired exactly like baking the same
 * value into [BacktestContext.build], so a fan-out scenario is byte-identical to a standalone run. The
 * broker axis is the non-vacuous signal: mt5-sim fills at bid/ask, paper at the mid (see
 * BacktestMt5SimTest), so over FakeXauFetcher data the override demonstrably changes the result.
 */
class BacktestContextScenarioParityTest {
    private fun strategyFile(dir: Path): Path {
        val strat = dir.resolve("s.qkt")
        Files.writeString(
            strat,
            """
            STRATEGY s VERSION 1
            SYMBOLS
                gold = BACKTEST:XAUUSD EVERY 1m
            LET fast = 3
            RULES
                WHEN ema(gold.close, fast) CROSSES ABOVE ema(gold.close, 9)
                THEN BUY gold SIZING 0.1
                WHEN ema(gold.close, fast) CROSSES BELOW ema(gold.close, 9)
                THEN CLOSE gold
            """.trimIndent(),
        )
        return strat
    }

    private fun buildCtx(
        dir: Path,
        broker: String? = null,
    ): BacktestContext {
        val strat = strategyFile(dir)
        val ast = (Dsl.parseFile(strat) as ParseResult.Success).value
        val argv =
            mutableListOf(
                "backtest",
                strat.toString(),
                "--from",
                "2026-06-04",
                "--to",
                "2026-06-05",
                "--data-root",
                dir.resolve("data").toString(),
            )
        if (broker != null) argv += listOf("--broker", broker)
        val ctx = BacktestContext.build(Args(argv.toTypedArray()), ast, fetcherOverride = FakeXauFetcher)
        ctx.provision()
        return ctx
    }

    @Test
    fun `broker override matches a context built with that broker as default`(
        @TempDir dir: Path,
    ) {
        val ctxPaper = buildCtx(dir, broker = null)
        val ctxSim = buildCtx(dir, broker = "mt5-sim")

        val paper = ctxPaper.backtest(emptyMap()).run()
        val simViaOverride = ctxPaper.backtest(emptyMap(), brokerKind = BrokerKind.MT5_SIM).run()
        val simBaked = ctxSim.backtest(emptyMap()).run()

        // Override threads through to the same wiring as baking the broker into build().
        assertThat(simViaOverride.global.totalPnL).isEqualByComparingTo(simBaked.global.totalPnL)
        assertThat(simViaOverride.trades).isEqualTo(simBaked.trades)
        // And it is not silently dropped: mt5-sim fills at bid/ask, paper at the mid.
        assertThat(simViaOverride.global.totalPnL).isNotEqualByComparingTo(paper.global.totalPnL)
    }

    @Test
    fun `passing explicit context defaults reproduces the default backtest`(
        @TempDir dir: Path,
    ) {
        val ctx = buildCtx(dir)
        val implicit = ctx.backtest(emptyMap()).run()
        val explicit =
            ctx
                .backtest(
                    emptyMap(),
                    brokerKind = ctx.brokerKind,
                    instruments = ctx.instruments,
                ).run()
        assertThat(explicit.global.totalPnL).isEqualByComparingTo(implicit.global.totalPnL)
        assertThat(explicit.trades).isEqualTo(implicit.trades)
    }

    @Test
    fun `scenario sweep fan-out is bit-identical to standalone backtests`(
        @TempDir dir: Path,
    ) {
        val ctx = buildCtx(dir)
        val scenarios =
            listOf(
                ScenarioSpec(label = "fast2", params = mapOf("fast" to "2")),
                ScenarioSpec(label = "fast5", params = mapOf("fast" to "5")),
                ScenarioSpec(label = "fast2-sim", params = mapOf("fast" to "2"), brokerKind = BrokerKind.MT5_SIM),
            )
        val (sharedFeed, engineFor) = ctx.scenarioEngines()
        val fan =
            SweepReplay(
                configs = scenarios.map { it.label to it },
                sharedFeed = sharedFeed,
                engineFor = { _, s -> engineFor(s) },
                parallelism = 1,
            ).run().runs

        for (s in scenarios) {
            val standalone = ctx.backtest(s.params, brokerKind = s.brokerKind ?: ctx.brokerKind).run()
            val combo = fan.first { it.label == s.label }.result
            assertThat(combo.global.totalPnL).isEqualByComparingTo(standalone.global.totalPnL)
            assertThat(combo.global.maxDailyDrawdown).isEqualByComparingTo(standalone.global.maxDailyDrawdown)
            assertThat(combo.trades).isEqualTo(standalone.trades)
        }
        // Non-vacuous: the scenarios genuinely differ (the broker axis guarantees two distinct values).
        assertThat(fan.map { it.result.global.totalPnL.toPlainString() }.distinct().size).isGreaterThan(1)
    }
}
