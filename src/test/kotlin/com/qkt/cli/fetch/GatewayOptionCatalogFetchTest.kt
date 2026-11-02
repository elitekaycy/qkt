package com.qkt.cli.fetch

import com.qkt.cli.Args
import com.qkt.cli.ExitCodes
import com.qkt.cli.FetchCommand
import com.qkt.connector.gateway.FakeGateway
import com.qkt.connector.gateway.WireInstrument
import com.qkt.instrument.ContractCatalogStore
import com.qkt.instrument.OptionCatalogStore
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/**
 * `qkt fetch <ROOT> --catalog` builds a gateway account's option catalog from that account's own listing,
 * whatever the broker is named (live, 2026-10-29: `PAPER_DERIBIT_B` listed 3,074 options and got only its
 * futures), and a `DERIBIT` gateway account's from its own listing too, not Deribit's public one.
 */
class GatewayOptionCatalogFetchTest {
    private val expiry = 1_793_347_200_000L // 2026-10-30T08:00Z
    private val fake = FakeGateway(listOf("BTC_USDC-30OCT26-80000-P", "BTC_USDC-30OCT26-92500-C", "BTC_USDC-30OCT26"))

    init {
        for ((code, strike, right) in listOf(
            Triple("BTC_USDC-30OCT26-80000-P", "80000", "put"),
            Triple("BTC_USDC-30OCT26-92500-C", "92500", "call"),
        )) {
            fake.instruments[code] =
                WireInstrument(code, "option", "USDC", "1", "5", "0.01", "0.01", expiry, strike, right, "BTC_USDC")
        }
        fake.instruments["BTC_USDC-30OCT26"] =
            WireInstrument("BTC_USDC-30OCT26", "future", "USDC", "1", "2.5", "0.0001", "0.0001", expiry)
    }

    @AfterEach
    fun stop() = fake.shutdown()

    private fun fetch(
        tmp: Path,
        broker: String,
        instruments: String,
    ): Pair<Int, String> {
        val config = tmp.resolve("qkt.config.yaml")
        Files.writeString(
            config,
            "brokers:\n  $broker:\n    type: gateway\n    gateway_url: ${fake.url}\n    api_key: secret\n" +
                "    expected_adapter: fake\n    expected_account_login: \"7\"\n    expected_trade_mode: demo\n",
        )
        Files.writeString(tmp.resolve("instruments.yaml"), instruments)
        val root = "${broker.uppercase()}:BTC_USDC"
        val err = ByteArrayOutputStream()
        val saved = System.err
        System.setErr(PrintStream(err, true))
        val code =
            try {
                FetchCommand(
                    Args(arrayOf("fetch", root, "--catalog", "--config", "$config", "--data-root", "$tmp")),
                ).run()
            } finally {
                System.setErr(saved)
            }
        return code to err.toString()
    }

    private fun options(venue: String) =
        "options:\n  - root: $venue:BTC_USDC\n    currency: USDC\n    contractSize: 1\n    tickSize: 5\n" +
            "    volumeStep: 0.01\n    volumeMin: 0.01\n    underlyingIndex: btc_usdc\n"

    private fun futures(venue: String) =
        "futures:\n  - root: $venue:BTC_USDC\n    currency: USDC\n    multiplier: 1\n    tickSize: 2.5\n" +
            "    volumeStep: 0.0001\n    volumeMin: 0.0001\n"

    @Test
    fun `a broker not named DERIBIT gets its options and its futures from its own gateway listing`(
        @TempDir tmp: Path,
    ) {
        val (code, _) = fetch(tmp, "paper_deribit_b", futures("PAPER_DERIBIT_B") + options("PAPER_DERIBIT_B"))

        assertThat(code).isEqualTo(ExitCodes.SUCCESS)
        assertThat(OptionCatalogStore(tmp).read("PAPER_DERIBIT_B:BTC_USDC")?.contracts?.map { it.symbol })
            .containsExactly("BTC_USDC-30OCT26-80000-P", "BTC_USDC-30OCT26-92500-C")
        assertThat(ContractCatalogStore(tmp).read("PAPER_DERIBIT_B:BTC_USDC")?.contracts?.map { it.symbol })
            .containsExactly("BTC_USDC_30OCT26")
    }

    @Test
    fun `a DERIBIT gateway account's catalog is its own listing, which need not match the public venue's`(
        @TempDir tmp: Path,
    ) {
        val (code, _) = fetch(tmp, "deribit", options("DERIBIT"))

        assertThat(code).isEqualTo(ExitCodes.SUCCESS)
        assertThat(OptionCatalogStore(tmp).read("DERIBIT:BTC_USDC")?.contracts?.map { it.symbol })
            .containsExactly("BTC_USDC-30OCT26-80000-P", "BTC_USDC-30OCT26-92500-C")
    }

    @Test
    fun `a venue listing options of a root declared only as futures says it catalogued none`(
        @TempDir tmp: Path,
    ) {
        val (code, err) = fetch(tmp, "paper_deribit", futures("PAPER_DERIBIT"))

        assertThat(code).isEqualTo(ExitCodes.SUCCESS)
        assertThat(err).contains("PAPER_DERIBIT lists 2 options of PAPER_DERIBIT:BTC_USDC").contains("options:")
        assertThat(OptionCatalogStore(tmp).read("PAPER_DERIBIT:BTC_USDC")).isNull()
    }

    @Test
    fun `a declared option root the venue lists options of, of which none can be catalogued, is refused`(
        @TempDir tmp: Path,
    ) {
        val (code, err) =
            fetch(
                tmp,
                "paper_deribit_b",
                options("PAPER_DERIBIT_B").replace("currency: USDC", "currency: USDT"),
            )

        assertThat(code).isEqualTo(ExitCodes.USER_ERROR)
        assertThat(err).contains("lists 2 options of PAPER_DERIBIT_B:BTC_USDC").contains("none")
        assertThat(OptionCatalogStore(tmp).read("PAPER_DERIBIT_B:BTC_USDC")).isNull()
    }
}
