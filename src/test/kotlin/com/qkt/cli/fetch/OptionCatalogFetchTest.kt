package com.qkt.cli.fetch

import com.qkt.cli.ExitCodes
import com.qkt.instrument.OptionCatalog
import com.qkt.instrument.OptionCatalogStore
import com.qkt.instrument.OptionListing
import java.nio.file.Files
import java.nio.file.Path
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class OptionCatalogFetchTest {
    private fun declare(dir: Path) =
        Files.writeString(
            dir.resolve("instruments.yaml"),
            "options:\n  - { root: DERIBIT:BTC_USDC, currency: USDC, contractSize: 1, tickSize: 5, volumeStep: 0.01, " +
                "volumeMin: 0.01, underlyingIndex: btc_usdc }\n",
        )

    @Test
    fun `the catalog of a declared option root is built and stored`(
        @TempDir dir: Path,
    ) {
        declare(dir)
        val built =
            OptionCatalog(
                "DERIBIT:BTC_USDC",
                listOf(OptionListing("BTC_USDC-27SEP24-60000-C", "60000", "call", 1727424000000)),
            )

        val code =
            OptionCatalogFetch.run("DERIBIT:BTC_USDC", dir) { root, _ ->
                built.also {
                    assertThat(root.underlyingIndex).isEqualTo("btc_usdc")
                }
            }

        assertThat(code).isEqualTo(ExitCodes.SUCCESS)
        assertThat(OptionCatalogStore(dir).read("DERIBIT:BTC_USDC")).isEqualTo(built)
    }

    @Test
    fun `a root that is not declared under options is a user error`(
        @TempDir dir: Path,
    ) {
        declare(dir)

        assertThat(
            OptionCatalogFetch.run("DERIBIT:ETH_USDC", dir) { _, _ -> error("not called") },
        ).isEqualTo(ExitCodes.USER_ERROR)
    }
}
