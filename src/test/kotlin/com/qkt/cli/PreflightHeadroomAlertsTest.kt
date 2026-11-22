package com.qkt.cli

import com.qkt.cli.daemon.StateDir
import java.nio.file.Files
import java.nio.file.Path
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class PreflightHeadroomAlertsTest {
    @TempDir
    lateinit var tmp: Path

    private fun evaluate(body: String): List<PreflightCheck> {
        val cfg = tmp.resolve("qkt.config.yaml").also { Files.writeString(it, body) }
        return ProductionPreflight.evaluate(cfg, StateDir.resolve(tmp.resolve("state").toString()), null)
    }

    private fun List<PreflightCheck>.named(name: String) = single { it.name == name }

    @Test
    fun `less free disk than the floor warns, and fails in production`() {
        val floor = "state:\n  disk_free_alert_gb: 1000000000\n"

        val dev = evaluate("account:\n  currency: USD\n$floor").named("state.disk_headroom")
        val production =
            evaluate(
                "account:\n  currency: USD\nruntime:\n  mode: production\n$floor",
            ).named("state.disk_headroom")

        assertThat(dev.status).isEqualTo(PreflightStatus.WARN)
        assertThat(dev.detail).endsWith("(< 1000000000 GB floor)")
        assertThat(production.status).isEqualTo(PreflightStatus.FAIL)
    }

    @Test
    fun `an enabled channel of any other type counts as a valid alert channel`() {
        val alerts =
            evaluate("account:\n  currency: USD\nnotify:\n  pager:\n    enabled: true\n").named("notify.alerts")

        assertThat(alerts.status).isEqualTo(PreflightStatus.PASS)
        assertThat(alerts.detail).isEqualTo("1 enabled channel(s)")
    }
}
