package com.qkt.cli.fetch

import com.qkt.cli.Args
import com.qkt.cli.ExitCodes
import com.qkt.cli.FetchCommand
import com.qkt.connector.gateway.FakeGateway
import com.qkt.connector.gateway.WireFundingRate
import com.qkt.connector.gateway.WireInstrument
import com.qkt.instrument.FundingRateStore
import java.nio.file.Files
import java.nio.file.Path
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/** `qkt fetch --funding` stores a gateway account's perpetual funding rates, every page of them, once. */
class FundingFetchTest {
    private val code = "SOL_USDC-PERPETUAL"
    private val hour = 3_600_000L
    private val dayStart = 1_790_812_800_000L // 2026-10-01T00:00Z

    @Test
    fun `a gateway account's published rates are stored in the venue-neutral file, merged with what is there`(
        @TempDir tmp: Path,
    ) {
        val fake = FakeGateway(listOf(code))
        fake.instruments[code] = WireInstrument(code, "perpetual", "USDC", "1", "0.001", "0.1", "0.1")
        fake.rates[code] = (1..5).map { WireFundingRate(dayStart + it * hour, "0.0000${it}1", if (it == 3) null else "121.5") }
        val config = tmp.resolve("qkt.config.yaml")
        Files.writeString(
            config,
            "brokers:\n  deribit:\n    type: gateway\n    gateway_url: ${fake.url}\n    api_key: secret\n" +
                "    expected_adapter: fake\n    expected_account_login: \"7\"\n    expected_trade_mode: demo\n",
        )
        val fetch = { from: String, to: String ->
            FetchCommand(
                Args(
                    arrayOf(
                        "fetch", "DERIBIT:SOL_USDC_PERPETUAL", "--funding", "--from", from, "--to", to,
                        "--config", config.toString(), "--data-root", tmp.resolve("data").toString(),
                    ),
                ),
            ).run()
        }

        assertThat(fetch("2026-10-01", "2026-10-01")).isEqualTo(ExitCodes.SUCCESS)
        assertThat(fetch("2026-10-01", "2026-10-01")).isEqualTo(ExitCodes.SUCCESS)
        fake.shutdown()

        val stored = FundingRateStore(tmp.resolve("data")).read("DERIBIT:SOL_USDC_PERPETUAL")!!
        assertThat(stored.map { it.timeMs }).containsExactlyElementsOf((1..5).map { dayStart + it * hour })
        assertThat(stored[0].rate.toPlainString()).isEqualTo("0.000011")
        assertThat(stored[2].price).isNull()
        assertThat(Files.readAllLines(tmp.resolve("data/funding/DERIBIT/SOL_USDC_PERPETUAL.csv")).first())
            .isEqualTo("time,rate,price")
    }
}
