package com.qkt.cli.daemon

import com.qkt.observe.insights.InsightsConfig
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.concurrent.atomic.AtomicReference
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class DaemonWiringTest : ListRouteFixture() {
    @TempDir
    lateinit var tmp: Path

    private fun registry(brokers: Map<String, String>) =
        StrategyRegistry(stubFactory(StateDir.resolve(tmp.toString()), streamBrokers = brokers))

    @Test
    fun `an account's strategies are the deployed ones with a stream routed to it, in any case`() {
        val registry = registry(mapOf("gold" to "Exness_A"))
        registry.deploy("alpha", tmp.resolve("alpha.qkt"))
        registry.deploy("beta", tmp.resolve("beta.qkt"))

        assertThat(strategiesOnAccount(registry, "exness_a")).containsExactlyInAnyOrder("alpha", "beta")
        assertThat(strategiesOnAccount(registry, "bybit")).isEmpty()
        assertThat(strategiesOnAccount(null, "exness_a")).isEmpty()
    }

    @Test
    fun `the account lookup the broker context gets reads the registry held when it asks`() {
        val ref = AtomicReference<StrategyRegistry?>(null)
        val lookup = strategiesOnAccount(ref)
        assertThat(lookup("exness_a")).isEmpty()

        val registry = registry(mapOf("gold" to "Exness_A"))
        registry.deploy("alpha", tmp.resolve("alpha.qkt"))
        ref.set(registry)

        assertThat(lookup("exness_a")).containsExactly("alpha")
    }

    @Test
    fun `the deployed names come from each strategy's declared name and are empty before the registry exists`() {
        val registry = registry(emptyMap())
        registry.deploy("alpha", tmp.resolve("alpha.qkt"))

        assertThat(deployedStrategyNames(registry)).containsExactly("alpha")
        assertThat(deployedStrategyNames(null)).isEmpty()
    }

    @Test
    fun `a daily summary covers the live sessions of the registry`() {
        val registry = registry(emptyMap())
        registry.deploy("alpha", tmp.resolve("alpha.qkt"))

        val summary = dailySummaryOf(registry, nowMs = 1_705_276_800_000L)

        assertThat(summary.asOfUtc).startsWith("2024-01-15")
        assertThat(summary.strategies).isEmpty()
    }

    @Test
    fun `a daily summary without a time is as of now`() {
        val registry = registry(emptyMap())
        val before = LocalDate.now(ZoneOffset.UTC).toString()

        val summary = dailySummaryOf(registry)

        val after = LocalDate.now(ZoneOffset.UTC).toString()
        assertThat(summary.asOfUtc.take(10)).isIn(before, after)
    }

    @Test
    fun `the insights journal is off, configured, or under the state root`() {
        val on = InsightsConfig.DISABLED.copy(journalEnabled = true, journalDir = "")

        assertThat(insightsJournalDir(InsightsConfig.DISABLED.copy(journalEnabled = false), tmp)).isNull()
        assertThat(insightsJournalDir(on, tmp)).isEqualTo(tmp.resolve("insights-journal"))
        assertThat(insightsJournalDir(on.copy(journalDir = "/var/spool/j"), tmp)).isEqualTo(Path.of("/var/spool/j"))
    }

    @Test
    fun `the load-dir deployer redeploys through the startup path and reports a file that does not parse`() {
        val registry = registry(emptyMap())
        val good = tmp.resolve("good.qkt")
        Files.writeString(
            good,
            "STRATEGY good VERSION 1\nSYMBOLS\n    x = BACKTEST:X EVERY 1m\nRULES\n    WHEN x.close > 0 THEN BUY x SIZING 1\n",
        )
        val broken = tmp.resolve("broken.qkt").also { Files.writeString(it, "STRATEGY nope") }
        val deploy = loadDirDeployer(registry, null)

        deploy("good", good)
        assertThat(registry.get("good")).isNotNull()
        assertThatThrownBy { deploy("broken", broken) }.hasMessageContaining("parse failure for $broken:")
    }
}
