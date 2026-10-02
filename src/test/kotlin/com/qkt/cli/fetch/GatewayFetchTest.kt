package com.qkt.cli.fetch

import com.qkt.cli.Args
import com.qkt.cli.ExitCodes
import com.qkt.cli.FetchCommand
import com.qkt.connector.gateway.FakeGateway
import com.qkt.connector.gateway.WireBar
import com.qkt.marketdata.store.LocalBarStore
import java.math.BigDecimal
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDate
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/** `qkt fetch` stores a gateway account's venue bars under the account, as it stores an MT5 broker's. */
class GatewayFetchTest {
    @Test
    fun `fetching a gateway account's contract stores the venue's closed bars for the day`(
        @TempDir tmp: Path,
    ) {
        val code = "BTC_USDC-PERPETUAL"
        val fake = FakeGateway(listOf(code))
        val minute = 60_000L
        val dayStart = 1_790_812_800_000L // 2026-10-01T00:00Z
        fake.bars[code to minute] =
            listOf(
                WireBar(dayStart, "100", "101", "99", "100.5", "2"),
                WireBar(dayStart + minute, "100.5", "102", "100", "101", "3"),
                WireBar(dayStart + 2 * minute, "101", "101", "98", "99", "1"),
            )
        val config = tmp.resolve("qkt.config.yaml")
        Files.writeString(
            config,
            """
            brokers:
              deribit:
                type: gateway
                gateway_url: ${fake.url}
                api_key: secret
                expected_adapter: fake
                expected_account_login: "7"
                expected_trade_mode: demo
            """.trimIndent(),
        )
        val args =
            Args(
                arrayOf(
                    "fetch",
                    "DERIBIT:BTC_USDC_PERPETUAL",
                    "--tf",
                    "1m",
                    "--from",
                    "2026-10-01",
                    "--to",
                    "2026-10-01",
                    "--config",
                    config.toString(),
                    "--data-root",
                    tmp.toString(),
                ),
            )
        try {
            assertThat(FetchCommand(args).run()).isEqualTo(ExitCodes.SUCCESS)
        } finally {
            fake.shutdown()
        }

        val bars = LocalBarStore(tmp).readDay("DERIBIT", "BTC_USDC_PERPETUAL", "1m", LocalDate.parse("2026-10-01"))
        assertThat(bars.map { it.startTime }).containsExactly(dayStart, dayStart + minute, dayStart + 2 * minute)
        assertThat(bars.map { it.close }).containsExactly(BigDecimal("100.5"), BigDecimal("101"), BigDecimal("99"))
        assertThat(bars.map { it.volume }).containsExactly(BigDecimal("2"), BigDecimal("3"), BigDecimal("1"))
    }

    @Test
    fun `a gateway account is never read as an MT5 broker profile`(
        @TempDir tmp: Path,
    ) {
        val config = tmp.resolve("qkt.config.yaml")
        Files.writeString(config, "brokers:\n  deribit:\n    type: gateway\n")

        assertThat(buildFetcher("DERIBIT", config.toString())).isNull()
    }
}
