package com.qkt.cli.fetch

import com.qkt.cli.Args
import com.qkt.cli.Config
import com.qkt.cli.ExitCodes
import com.qkt.cli.FetchCommand
import com.qkt.cli.accountMarketDataRoutes
import com.qkt.cli.captureCliRun
import com.qkt.common.FixedClock
import java.nio.file.Files
import java.nio.file.Path
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/** A `brokers:` entry whose `type` no connector provides is reported by every fetch that opens the account. */
class FetchUnknownBrokerTypeTest {
    @TempDir
    lateinit var tmp: Path

    private val config by lazy {
        tmp.resolve("qkt.config.yaml").also { Files.writeString(it, "brokers:\n  deribit:\n    type: nosuch\n") }
    }

    private fun fetch(
        what: String,
        target: String = "DERIBIT:BTC_USDC_PERPETUAL",
    ) = captureCliRun {
        val argv =
            arrayOf("fetch", target, *what.split(" ").toTypedArray(), "--from", "2026-10-03", "--to", "2026-10-03") +
                arrayOf("--config", config.toString(), "--data-root", tmp.resolve("data").toString())
        FetchCommand(Args(argv), FixedClock(time = 1_790_985_600_000L + 3 * 86_400_000L)).run()
    }

    @Test
    fun `an unknown account type is reported by each fetch that needs the account`() {
        listOf("--marks --tf 1h", "--funding", "--tape", "--open-interest", "--depth", "--catalog").forEach { flags ->
            val run = fetch(flags)

            assertThat(run.code).withFailMessage(flags).isEqualTo(ExitCodes.USER_ERROR)
            assertThat(run.err)
                .withFailMessage("$flags: ${run.err}")
                .contains("failed to open broker 'DERIBIT': brokers.deribit has type 'nosuch'")
        }
    }

    @Test
    fun `price routes from accounts the connectors reject are empty with a warning`() {
        val run = captureCliRun { Config.load(config).accountMarketDataRoutes().size }

        assertThat(run.out).contains("[WARN] broker account load failed: brokers.deribit has type 'nosuch'")
    }
}
