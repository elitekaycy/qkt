package com.qkt.cli

import com.qkt.cli.daemon.ListRouteFixture
import com.qkt.cli.daemon.StartupDeploys
import com.qkt.cli.daemon.StateDir
import com.qkt.cli.daemon.StrategyRegistry
import com.qkt.risk.DaemonRiskScope
import java.math.BigDecimal
import java.nio.file.Path
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class DaemonRiskGuardWiringTest : ListRouteFixture() {
    @TempDir
    lateinit var tmp: Path

    private fun registry() = StrategyRegistry(stubFactory(StateDir.resolve(tmp.toString())))

    @Test
    fun `the hard limit watches every deployed session by name`() {
        val registry = registry()
        registry.deploy("alpha", tmp.resolve("alpha.qkt"))
        registry.deploy("beta", tmp.resolve("beta.qkt"))

        assertThat(registry.guardedSessions().map { it.name }).containsExactlyInAnyOrder("alpha", "beta")
    }

    @Test
    fun `a hard daily loss starts a guard over the registry and no limit starts none`() {
        val registry = registry()
        val off = watchDaemonRisk(DaemonRiskScope(), registry, StartupDeploys(null))
        val on = watchDaemonRisk(DaemonRiskScope(hardDailyLoss = BigDecimal("500")), registry, StartupDeploys(null))

        assertThat(off).isNull()
        assertThat(on).isNotNull()
        on?.close()
    }

    @Test
    fun `live money outside production warns with the account names and the mode`() {
        assertThat(liveMoneyWarning(RuntimeMode.PAPER, listOf("exness_a", "bybit")))
            .isEqualTo(
                "[WARN] live-money account(s) exness_a, bybit detected while runtime.mode=paper; " +
                    "production governance checks are not active",
            )
        assertThat(liveMoneyWarning(RuntimeMode.PRODUCTION, listOf("exness_a"))).isNull()
        assertThat(liveMoneyWarning(RuntimeMode.DEV, emptyList())).isNull()
    }
}
