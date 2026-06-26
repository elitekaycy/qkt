package com.qkt.cli.daemon

import com.qkt.cli.Args
import com.qkt.cli.DaemonCommand
import com.qkt.cli.ExitCodes
import com.qkt.marketdata.Tick
import com.qkt.marketdata.TickFeed
import com.qkt.marketdata.source.MarketSource
import com.qkt.marketdata.source.MarketSourceCapability
import java.nio.file.Files
import java.nio.file.Path
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class DaemonProductionPreflightTest {
    private class UnusedSource : MarketSource {
        override val name: String = "unused"
        override val capabilities: Set<MarketSourceCapability> = setOf(MarketSourceCapability.LIVE_TICKS)

        override fun supports(symbol: String): Boolean = true

        override fun liveTicks(symbols: List<String>): TickFeed =
            object : TickFeed {
                override fun next(): Tick? = null

                override fun close() {}
            }
    }

    @Test
    fun `production daemon refuses startup when mandatory controls fail preflight`(
        @TempDir tmp: Path,
    ) {
        val cfg = tmp.resolve("qkt.config.yaml")
        Files.writeString(
            cfg,
            """
            runtime:
              mode: production
            state:
              enabled: false
            risk:
              max_daily_loss: 100
            brokers:
              bybit:
                type: bybit
            notify:
              telegram:
                enabled: true
                events: [halted]
            """.trimIndent(),
        )
        val stateRoot = tmp.resolve("state")
        val code =
            DaemonCommand(
                Args(
                    arrayOf(
                        "daemon",
                        "--config",
                        cfg.toString(),
                        "--state-dir",
                        stateRoot.toString(),
                    ),
                ),
                sourceFactory = { UnusedSource() },
            ).run()

        assertThat(code).isEqualTo(ExitCodes.USER_ERROR)
        assertThat(StateDir.resolve(stateRoot.toString()).controlPortFile).doesNotExist()
    }

    @Test
    fun `production daemon refuses startup when append only journal is unavailable`(
        @TempDir tmp: Path,
    ) {
        val cfg = tmp.resolve("qkt.config.yaml")
        Files.writeString(
            cfg,
            """
            runtime:
              mode: production
              waivers:
                alerts:
                  reason: "integration test"
            state:
              enabled: true
            risk:
              max_daily_loss: 100
            brokers:
              bybit:
                type: bybit
            """.trimIndent(),
        )
        val stateRoot = tmp.resolve("state")
        val stateDir = StateDir.resolve(stateRoot.toString())
        Files.createDirectories(stateDir.stateRoot)
        Files.writeString(stateDir.stateRoot.resolve("journal"), "not a directory")

        val code =
            DaemonCommand(
                Args(
                    arrayOf(
                        "daemon",
                        "--config",
                        cfg.toString(),
                        "--state-dir",
                        stateRoot.toString(),
                    ),
                ),
                sourceFactory = { UnusedSource() },
            ).run()

        assertThat(code).isEqualTo(ExitCodes.USER_ERROR)
        assertThat(stateDir.controlPortFile).doesNotExist()
    }
}
