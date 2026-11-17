package com.qkt.cli

import com.qkt.cli.observe.PortPrinter
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/** The behaviour of calls that leave their optional arguments out, as the commands themselves do. */
class CliDefaultArgumentsTest {
    @TempDir
    lateinit var tmp: Path

    @Test
    fun `a reproduction records the command line, the strategy source and no resolved values by default`() {
        val strategy = Files.writeString(tmp.resolve("my strategy.qkt"), "STRATEGY s VERSION 1\n")

        val info =
            reproductionInfo(
                Args(arrayOf("backtest", strategy.toString(), "--config", tmp.resolve("none.yaml").toString())),
                strategy,
            )

        assertThat(info.commandLine).startsWith("qkt backtest '").contains("--config")
        assertThat(info.strategySource).isEqualTo("STRATEGY s VERSION 1\n")
        assertThat(info.resolved).isEmpty()
        assertThat(info.configSource).isNull()
    }

    @Test
    fun `the report directory defaults under the runs home, named for the strategy`() {
        val dir = BacktestReportSink.resolve(Args(arrayOf("backtest", "x.qkt")), Path.of("strategies/My Strategy.qkt"))

        assertThat(dir).isNotNull()
        assertThat(dir!!.parent).isEqualTo(BacktestReportSink.defaultHome().resolve(".qkt").resolve("runs"))
        assertThat(dir.fileName.toString()).endsWith("-My-Strategy")
    }

    @Test
    fun `the promotion store lives under the state home`() {
        assertThat(PromotionStore.defaultRoot().toString()).endsWith("state${java.io.File.separator}promotion")
    }

    @Test
    fun `a waiver is active until its expiry, and a bad expiry is not active`() {
        fun waiver(expiresAt: String?) = PromotionWaiver(listOf("all"), "r", "me", "2026-01-01T00:00:00Z", expiresAt)

        assertThat(waiver(null).active()).isTrue()
        assertThat(waiver("2999-01-01T00:00:00Z").active()).isTrue()
        assertThat(waiver("2000-01-01T00:00:00Z").active()).isFalse()
        assertThat(waiver("soon").active()).isFalse()
        assertThat(waiver("2026-06-01T00:00:00Z").active(Instant.parse("2026-05-01T00:00:00Z"))).isTrue()
    }

    @Test
    fun `an announcement without a port file only prints`() {
        val run = captureCliRun { PortPrinter.announce("127.0.0.1", 8123).let { 0 } }

        assertThat(run.out).isEqualTo("[INFO] observability: http://127.0.0.1:8123\nQKT_PORT=8123\n")
    }

    @Test
    fun `a config that cannot be read is a failed check not a stack trace`() {
        val broken = Files.writeString(tmp.resolve("qkt.config.yaml"), "account: [unterminated\n")

        val loaded = loadPreflightConfig(broken)

        assertThat(loaded).isInstanceOf(PreflightConfig.Failed::class.java)
        assertThat((loaded as PreflightConfig.Failed).check.name).isEqualTo("config.load")
        assertThat(loaded.check.status).isEqualTo(PreflightStatus.FAIL)
    }

    @Test
    fun `a notify channel type nothing provides is warned about and skipped`() {
        val cfg = Files.writeString(tmp.resolve("qkt.config.yaml"), "notify:\n  pager:\n    enabled: true\n")

        val run = captureCliRun { configNotifier(Config.load(cfg)).channels.size }

        assertThat(run.out).contains("[WARN] unknown notify channel type: pager")
        assertThat(run.code).isZero()
    }
}
