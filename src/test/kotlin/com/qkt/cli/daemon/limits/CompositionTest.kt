package com.qkt.cli.daemon.limits

import com.qkt.cli.daemon.StartupDeploys
import com.qkt.cli.daemon.portfolio.ChildRiskTarget
import com.qkt.cli.daemon.portfolio.PortfolioRiskAggregator
import com.qkt.common.FixedClock
import com.qkt.risk.DaemonComposition
import com.qkt.risk.DaemonRiskScope
import com.qkt.risk.HaltDecision
import com.qkt.risk.HaltRule
import com.qkt.risk.HaltScope
import com.qkt.risk.RiskState
import java.nio.file.Files
import java.nio.file.Path
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class CompositionTest {
    private val clock = FixedClock(DAY_MS * 20_758)

    @Test
    fun `a daemon is mixed for the day once both kinds were there, and after that by what it runs`() {
        val composition = DaemonComposition(clock)
        var running = true to true
        composition.members { running }
        assertThat(composition.mixed()).isTrue()

        running = false to true
        assertThat(composition.mixed()).describedAs("the last standalone left today").isTrue()

        clock.time += DAY_MS
        assertThat(composition.mixed()).describedAs("a new day reads what runs now").isFalse()
    }

    @Test
    fun `one kind alone is never mixed, nor two portfolios`() {
        val composition = DaemonComposition(clock)
        composition.noteBook()
        composition.noteBook()
        assertThat(composition.mixed()).isFalse()
        composition.noteStandalone()
        assertThat(composition.mixed()).isTrue()
    }

    @Test
    fun `the startup directory declares a mix before anything deploys`(
        @TempDir tmp: Path,
    ) {
        Files.writeString(
            tmp.resolve("solo.qkt"),
            "STRATEGY solo VERSION 1\nSYMBOLS\n    x = BACKTEST:S EVERY 1m\nRULES\n    WHEN x.close > 0 THEN BUY x SIZING 1\n",
        )
        Files.writeString(
            tmp.resolve("bk.qkt"),
            "PORTFOLIO bk VERSION 1 CAPITAL 1000\nSYMBOLS\n    x = BACKTEST:S EVERY 1m\n" +
                "IMPORT 'solo.qkt' AS a\nRULES\n    RUN a WEIGHT 1\n",
        )
        Files.writeString(tmp.resolve("broken.qkt"), "not a strategy")
        val scope = DaemonRiskScope(clock)
        StartupDeploys(tmp.toString()).declareComposition(scope)
        assertThat(scope.composition.mixed()).isTrue()
    }

    @Test
    fun `a persistent book breach carries past the daily halt the daemon's total put on a child`() {
        val child =
            object : ChildRiskTarget {
                var scope: HaltScope? = HaltScope.DAILY
                var why: String? = "daily loss 9 exceeds max 8 across the daemon"
                var flattens = 0

                override fun flatten() {
                    flattens++
                }

                override fun halt(
                    reason: String,
                    scope: HaltScope,
                ) {
                    this.scope = scope
                    why = reason
                }

                override fun resume() {
                    scope = null
                    why = null
                }

                override fun isHalted() = scope != null

                override fun haltReason() = why

                override fun haltScope() = scope
            }
        val drawdown =
            object : HaltRule {
                override fun evaluate(riskState: RiskState) =
                    HaltDecision.Halt("drawdown", scope = HaltScope.PERSISTENT)
            }
        PortfolioRiskAggregator(listOf(child), RiskState.noOp(clock), listOf(drawdown), clock).evaluate()

        assertThat(child.flattens).isEqualTo(1)
        assertThat(child.scope).isEqualTo(HaltScope.PERSISTENT)
        assertThat(child.why).isEqualTo("drawdown")
    }
}
