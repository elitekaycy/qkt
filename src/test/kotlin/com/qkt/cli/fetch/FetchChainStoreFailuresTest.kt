package com.qkt.cli.fetch

import com.qkt.cli.Args
import com.qkt.cli.ExitCodes
import com.qkt.cli.captureCliRun
import com.qkt.derivatives.options.chain.ChainQuote
import com.qkt.derivatives.options.chain.ChainSnapshot
import com.qkt.derivatives.options.chain.OptionTrade
import com.qkt.instrument.OptionCatalog
import com.qkt.instrument.OptionCatalogStore
import com.qkt.instrument.OptionListing
import com.qkt.instrument.QuoteSource
import com.qkt.marketdata.store.deribit.DeribitBookSnapshot
import java.io.IOException
import java.math.BigDecimal
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.time.LocalDate
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/** A chain or catalog fetch whose store cannot be written, or whose venue fails at the transport, stops naming it. */
class FetchChainStoreFailuresTest {
    @TempDir
    lateinit var tmp: Path

    private fun io(): Nothing = throw IOException("socket closed")

    private fun declareOptions(dir: Path): String {
        val root = "DERIBIT:BTC_USDC"
        Files.writeString(
            dir.resolve("instruments.yaml"),
            "options:\n  - { root: $root, currency: USDC, contractSize: 1, tickSize: 5, volumeStep: 0.01, " +
                "volumeMin: 0.01, underlyingIndex: btc_usdc }\n",
        )
        val listing =
            OptionListing(
                "BTC_USDC-25DEC26-92000-C",
                "92000",
                "call",
                Instant.parse("2026-12-25T08:00:00Z").toEpochMilli(),
            )
        OptionCatalogStore(dir).write(OptionCatalog(root, listOf(listing)))
        Files.writeString(dir.resolve("chains"), "not a directory")
        return root
    }

    @Test
    fun `a chain that cannot be written to disk stops the fetch naming the day`() {
        val root = declareOptions(tmp)
        val call = "BTC_USDC-25DEC26-92000-C"
        val feed =
            listOf(
                OptionTrade(
                    "1",
                    Instant.parse("2026-09-29T20:00:00Z").toEpochMilli(),
                    1,
                    call,
                    BigDecimal("1500"),
                    BigDecimal("50"),
                    BigDecimal("83000"),
                ),
                OptionTrade(
                    "2",
                    Instant.parse("2026-09-30T10:30:00Z").toEpochMilli(),
                    1,
                    call,
                    BigDecimal("1500"),
                    BigDecimal("50"),
                    BigDecimal("83000"),
                ),
            )

        val run =
            captureCliRun {
                ChainFetch.run(
                    root,
                    tmp,
                    ChainFetch.Window(
                        LocalDate.parse("2026-09-30"),
                        LocalDate.parse("2026-09-30"),
                        3_600_000L,
                        86_400_000L,
                    ),
                    Instant.parse("2026-10-01T00:10:00Z").toEpochMilli(),
                ) { _, _, _ -> feed }
            }

        assertThat(run.code).isEqualTo(ExitCodes.USER_ERROR)
        assertThat(run.err).contains("qkt: could not fetch the $root chain for 2026-09-30: ")
    }

    @Test
    fun `a live book snapshot that cannot be written stops the fetch`() {
        val root = declareOptions(tmp)
        val at = Instant.parse("2026-10-01T09:00:00Z").toEpochMilli()
        val quote =
            ChainQuote(
                at,
                "BTC_USDC-25DEC26-92000-C",
                BigDecimal("1490"),
                BigDecimal("1510"),
                BigDecimal("1500"),
                BigDecimal("50"),
                BigDecimal("83000"),
                null,
                0,
                QuoteSource.BOOK,
            )
        val taken = DeribitBookSnapshot.Taken(ChainSnapshot(root, at, listOf(quote)), emptySet())

        val run = captureCliRun { ChainLiveFetch.run(root, tmp) { _, _ -> taken } }

        assertThat(run.code).isEqualTo(ExitCodes.USER_ERROR)
        assertThat(run.err).contains("qkt: could not snapshot the $root chain: ")
    }

    @Test
    fun `an IO failure building the option catalog is reported and a malformed chain interval is refused`() {
        val root = declareOptions(tmp)

        val catalog = captureCliRun { OptionCatalogFetch.run(root, tmp, build = { _, _ -> io() }) }
        val every =
            captureCliRun {
                ChainFetch.run(
                    root,
                    Args(
                        arrayOf(
                            "fetch",
                            root,
                            "--chains",
                            "--last",
                            "2d",
                            "--every",
                            "x",
                            "--data-root",
                            tmp.toString(),
                        ),
                    ),
                )
            }

        assertThat(catalog.code).isEqualTo(ExitCodes.USER_ERROR)
        assertThat(catalog.err).contains("could not build the option catalog for $root: socket closed")
        assertThat(every.code).isEqualTo(ExitCodes.ARG_ERROR)
        assertThat(every.err).contains("--every must be a duration like 1h or 1d")
    }
}
