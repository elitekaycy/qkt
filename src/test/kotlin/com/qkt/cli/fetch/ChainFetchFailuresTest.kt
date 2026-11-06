package com.qkt.cli.fetch

import com.qkt.cli.Args
import com.qkt.cli.ExitCodes
import com.qkt.cli.captureCliRun
import com.qkt.instrument.OptionCatalog
import com.qkt.instrument.OptionCatalogStore
import com.qkt.instrument.OptionListing
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.time.LocalDate
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class ChainFetchFailuresTest {
    @TempDir
    lateinit var dir: Path

    private val root = "DERIBIT:BTC_USDC"

    private fun ms(iso: String) = Instant.parse(iso).toEpochMilli()

    private fun declare() {
        Files.writeString(
            dir.resolve("instruments.yaml"),
            "options:\n  - { root: $root, currency: USDC, contractSize: 1, tickSize: 5, volumeStep: 0.01, " +
                "volumeMin: 0.01, underlyingIndex: btc_usdc }\n",
        )
        val listing = OptionListing("BTC_USDC-25DEC26-92000-C", "92000", "call", ms("2026-12-25T08:00:00Z"))
        OptionCatalogStore(dir).write(OptionCatalog(root, listOf(listing)))
    }

    private fun fetchWith(failure: Exception) =
        captureCliRun {
            ChainFetch.run(
                root,
                dir,
                ChainFetch.Window(
                    LocalDate.parse("2026-09-30"),
                    LocalDate.parse("2026-09-30"),
                    3_600_000L,
                    86_400_000L,
                ),
                ms("2026-10-01T00:10:00Z"),
            ) { _, _, _ -> throw failure }
        }

    private fun chains(vararg argv: String) =
        captureCliRun {
            ChainFetch.run(
                argv.first(),
                Args(arrayOf("fetch", *argv, "--chains", "--data-root", dir.toString())),
            )
        }

    @Test
    fun `a venue failure of any kind stops the fetch naming the day`() {
        declare()
        listOf(
            IOException("socket closed"),
            IllegalStateException("bad page"),
            IllegalArgumentException("bad id"),
        ).forEach {
            val run = fetchWith(it)
            assertThat(run.code).isEqualTo(ExitCodes.USER_ERROR)
            assertThat(run.err.trim()).isEqualTo("qkt: could not fetch the $root chain for 2026-09-30: ${it.message}")
        }
    }

    @Test
    fun `chains are only for the option venue`() {
        val run = chains("EXNESS:XAUUSD", "--from", "2026-09-30", "--to", "2026-09-30")

        assertThat(run.code).isEqualTo(ExitCodes.USER_ERROR)
        assertThat(run.err).contains("--chains is available for DERIBIT option roots, not EXNESS:XAUUSD")
    }

    @Test
    fun `the command line range and intervals reach the chain fetch`() {
        val run = chains(root, "--from", "2026-09-30", "--to", "2026-09-30", "--every", "30m", "--max-mark-age", "2h")

        assertThat(run.code).isEqualTo(ExitCodes.USER_ERROR)
        assertThat(run.err).isNotBlank()
    }

    @Test
    fun `a missing range or a malformed interval is an argument error`() {
        assertThat(chains(root).code).isEqualTo(ExitCodes.ARG_ERROR)

        val every = chains(root, "--last", "2d", "--every", "often")
        assertThat(every.code).isEqualTo(ExitCodes.ARG_ERROR)
        assertThat(every.err).contains("--every must be a duration like 1h or 1d")

        val age = chains(root, "--last", "2d", "--max-mark-age", "old")
        assertThat(age.code).isEqualTo(ExitCodes.ARG_ERROR)
        assertThat(age.err).contains("--max-mark-age must be a duration like 1h or 1d")
    }
}
