package com.qkt.cli.fetch

import com.qkt.cli.ExitCodes
import com.qkt.cli.captureCliRun
import com.qkt.derivatives.options.chain.ChainQuote
import com.qkt.derivatives.options.chain.ChainSnapshot
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
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class ChainLiveFetchFailuresTest {
    @TempDir
    lateinit var dir: Path

    private val root = "DERIBIT:BTC_USDC"
    private val call = "BTC_USDC-25DEC26-92000-C"

    @BeforeEach
    fun declare() {
        Files.writeString(
            dir.resolve("instruments.yaml"),
            "options:\n  - { root: $root, currency: USDC, contractSize: 1, tickSize: 5, volumeStep: 0.01, " +
                "volumeMin: 0.01, underlyingIndex: btc_usdc }\n",
        )
        OptionCatalogStore(dir).write(OptionCatalog(root, listOf(OptionListing(call, "92000", "call", 1798185600000))))
    }

    @Test
    fun `a venue failure of any kind is reported and nothing is stored`() {
        listOf(
            IOException("socket closed"),
            IllegalStateException("bad book"),
            IllegalArgumentException("bad id"),
        ).forEach {
            val run = captureCliRun { ChainLiveFetch.run(root, dir) { _, _ -> throw it } }
            assertThat(run.code).isEqualTo(ExitCodes.USER_ERROR)
            assertThat(run.err).contains(it.message!!)
        }
        assertThat(dir.resolve("chains")).doesNotExist()
    }

    @Test
    fun `listed contracts missing from the catalog are stored with a refresh warning`() {
        val at = Instant.parse("2026-10-01T09:00:00Z").toEpochMilli()
        val quote =
            ChainQuote(
                at,
                call,
                BigDecimal("1490"),
                BigDecimal("1510"),
                BigDecimal("1500"),
                BigDecimal("50"),
                BigDecimal("83000"),
                null,
                0,
                QuoteSource.BOOK,
            )
        val taken = DeribitBookSnapshot.Taken(ChainSnapshot(root, at, listOf(quote)), setOf("BTC_USDC-25DEC26-99000-C"))

        val run = captureCliRun { ChainLiveFetch.run(root, dir) { _, _ -> taken } }

        assertThat(run.code).isEqualTo(ExitCodes.SUCCESS)
        assertThat(run.out).contains("qkt fetch: 1 quotes of $root at 2026-10-01T09:00:00Z")
        assertThat(
            run.err,
        ).contains("1 listed contracts are missing from the catalog (refresh with: qkt fetch $root --catalog)")
    }

    @Test
    fun `a catalog build that fails is reported`() {
        listOf(IllegalStateException("listing broke"), IllegalArgumentException("bad strike")).forEach {
            val run = captureCliRun { OptionCatalogFetch.run(root, dir, build = { _, _ -> throw it }) }
            assertThat(run.code).isEqualTo(ExitCodes.USER_ERROR)
            assertThat(run.err).contains(it.message!!)
        }
    }
}
