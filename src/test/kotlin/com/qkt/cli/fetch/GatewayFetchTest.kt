package com.qkt.cli.fetch

import com.qkt.cli.Args
import com.qkt.cli.ExitCodes
import com.qkt.cli.FetchCommand
import com.qkt.connector.gateway.FakeGateway
import com.qkt.connector.gateway.WireBar
import com.qkt.connector.gateway.WireInstrument
import com.qkt.instrument.ContractCatalog
import com.qkt.instrument.ContractCatalogStore
import com.qkt.instrument.ListedContract
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
    fun `a declared futures root's catalog comes from the gateway, keeping contracts it stopped listing`(
        @TempDir tmp: Path,
    ) {
        val fake = FakeGateway(listOf("BTC_USDC-30OCT26"))
        fake.instruments["BTC_USDC-30OCT26"] =
            WireInstrument("BTC_USDC-30OCT26", "future", "USDC", "1", "2.5", "0.0001", "0.0001", 1_793_347_200_000L)
        val config = tmp.resolve("qkt.config.yaml")
        Files.writeString(
            config,
            "brokers:\n  deribit:\n    type: gateway\n    gateway_url: ${fake.url}\n    api_key: secret\n" +
                "    expected_adapter: fake\n    expected_account_login: \"7\"\n    expected_trade_mode: demo\n",
        )
        Files.writeString(
            tmp.resolve("instruments.yaml"),
            "futures:\n  - root: DERIBIT:BTC_USDC\n    currency: USDC\n    multiplier: 1\n    tickSize: 2.5\n" +
                "    volumeStep: 0.0001\n    volumeMin: 0.0001\n",
        )
        val gone = ListedContract("BTC_USDC_28AUG26", 1_787_904_000_000L, "61000")
        ContractCatalogStore(tmp).write(ContractCatalog("DERIBIT:BTC_USDC", listOf(gone)))
        val args = arrayOf("fetch", "DERIBIT:BTC_USDC", "--catalog", "--config", "$config", "--data-root", "$tmp")

        try {
            assertThat(FetchCommand(Args(args)).run()).isEqualTo(ExitCodes.SUCCESS)
        } finally {
            fake.shutdown()
        }

        assertThat(ContractCatalogStore(tmp).read("DERIBIT:BTC_USDC")?.contracts).containsExactly(
            gone,
            ListedContract("BTC_USDC_30OCT26", 1_793_347_200_000L),
        )
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
