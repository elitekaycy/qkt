package com.qkt.cli.fetch

import com.qkt.cli.Args
import com.qkt.cli.ExitCodes
import com.qkt.cli.FetchCommand
import com.qkt.connector.gateway.FakeGateway
import com.qkt.connector.gateway.WireBar
import com.qkt.marketdata.store.LocalBarStore
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.math.BigDecimal
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDate
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/** Bars of a gateway venue come through the `type: gateway` account named for the prefix strategies use. */
class BybitGatewayFetchTest {
    @Test
    fun `a bybit_linear gateway account serves BYBIT_LINEAR bars, stored under that prefix`(
        @TempDir tmp: Path,
    ) {
        val fake = FakeGateway(listOf("BTCUSDT"))
        val minute = 60_000L
        val dayStart = 1_790_812_800_000L // 2026-10-01T00:00Z
        fake.bars["BTCUSDT" to minute] =
            listOf(
                WireBar(dayStart, "60000", "60010", "59990", "60005", "1.5"),
                WireBar(dayStart + minute, "60005", "60020", "60000", "60015", "2"),
            )
        val config = tmp.resolve("qkt.config.yaml")
        Files.writeString(
            config,
            "brokers:\n  bybit_linear:\n    type: gateway\n    gateway_url: ${fake.url}\n    api_key: secret\n" +
                "    expected_adapter: bybit\n    expected_account_login: \"7\"\n    expected_trade_mode: demo\n",
        )
        val args =
            arrayOf(
                "fetch",
                "BYBIT_LINEAR:BTCUSDT",
                "--tf",
                "1m",
                "--from",
                "2026-10-01",
                "--to",
                "2026-10-01",
                "--config",
                "$config",
                "--data-root",
                "$tmp",
            )

        try {
            assertThat(FetchCommand(Args(args)).run()).isEqualTo(ExitCodes.SUCCESS)
        } finally {
            fake.shutdown()
        }

        val bars = LocalBarStore(tmp).readDay("BYBIT_LINEAR", "BTCUSDT", "1m", LocalDate.parse("2026-10-01"))
        assertThat(bars.map { it.symbol }).containsOnly("BYBIT_LINEAR:BTCUSDT")
        assertThat(bars.map { it.close }).containsExactly(BigDecimal("60005"), BigDecimal("60015"))
    }

    @Test
    fun `a prefix with no brokers entry is refused, naming the entry to add`(
        @TempDir tmp: Path,
    ) {
        val config = tmp.resolve("qkt.config.yaml")
        Files.writeString(config, "brokers:\n  exness:\n    type: mt5\n")
        val err = ByteArrayOutputStream()
        val original = System.err

        val fetcher =
            try {
                System.setErr(PrintStream(err))
                buildFetcher("BYBIT_SPOT", config.toString())
            } finally {
                System.setErr(original)
            }

        assertThat(fetcher).isNull()
        assertThat(err.toString()).contains("'BYBIT_SPOT'", "named 'bybit_spot'", "type: gateway")
        assertThat(err.toString()).doesNotContain("qkt-venue-gateway")
    }
}
