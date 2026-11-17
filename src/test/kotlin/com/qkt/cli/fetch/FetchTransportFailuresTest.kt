package com.qkt.cli.fetch

import com.qkt.cli.Args
import com.qkt.cli.ExitCodes
import com.qkt.cli.captureCliRun
import com.qkt.common.FixedClock
import com.qkt.instrument.FundingRateSource
import com.qkt.marketdata.depth.BookDepthSource
import com.qkt.marketdata.flow.FlowKind
import com.qkt.marketdata.flow.PrintHistorySource
import com.qkt.marketdata.marks.MarkHistorySource
import com.qkt.marketdata.openinterest.OpenInterestSource
import java.io.IOException
import java.nio.file.Path
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/** A venue source that fails at the transport (an `IOException`) stops each fetch naming the series. */
class FetchTransportFailuresTest {
    @TempDir
    lateinit var tmp: Path

    private val target = "DERIBIT:BTC_USDC_PERPETUAL"
    private val oct3 = 1_790_985_600_000L
    private val clock = FixedClock(time = oct3 + 3 * 86_400_000L)

    private fun args(
        what: String,
        vararg more: String,
    ) = Args(
        arrayOf("fetch", target, *what.split(" ").toTypedArray(), "--from", "2026-10-03", "--to", "2026-10-03") +
            more + arrayOf("--data-root", tmp.resolve("data").toString()),
    )

    private fun io(): Nothing = throw IOException("socket closed")

    @Test
    fun `an IO failure reading marks tape funding depth or open interest is reported`() {
        val marks =
            captureCliRun {
                MarksFetch.run(
                    target,
                    args("--marks", "--tf", "1h"),
                    clock,
                    MarkHistorySource {
                        _,
                        _,
                        _,
                        _,
                        ->
                        io()
                    },
                )
            }
        val tape =
            captureCliRun {
                TapeFetch.run(target, FlowKind.TRADES, args("--tape"), clock, PrintHistorySource { _, _, _, _ -> io() })
            }
        val funding =
            captureCliRun {
                FundingFetch.run(
                    target,
                    args("--funding"),
                    FundingRateSource {
                        _,
                        _,
                        _,
                        ->
                        io()
                    },
                )
            }
        val depth = captureCliRun { BookDepthFetch.run(target, args("--depth"), BookDepthSource { _, _, _ -> io() }) }
        val oi =
            captureCliRun {
                OpenInterestFetch.run(
                    target,
                    args("--open-interest"),
                    OpenInterestSource {
                        _,
                        _,
                        _,
                        ->
                        io()
                    },
                )
            }

        assertThat(marks.err).contains("could not fetch the marks of $target for 2026-10-03: socket closed")
        assertThat(tape.err).contains("could not fetch the trades of $target for 2026-10-03: socket closed")
        assertThat(funding.err).contains("could not fetch the funding rates of $target: socket closed")
        assertThat(depth.err).contains("could not fetch the depth of $target: socket closed")
        assertThat(oi.err).contains("could not fetch the open interest of $target: socket closed")
        listOf(marks, tape, funding, depth, oi).forEach { assertThat(it.code).isEqualTo(ExitCodes.USER_ERROR) }
    }

    @Test
    fun `a day that is over and empty is stored without the not-yet-over note`() {
        val marks =
            captureCliRun {
                MarksFetch.run(
                    target,
                    args("--marks", "--tf", "1h"),
                    clock,
                    MarkHistorySource {
                        _,
                        _,
                        _,
                        _,
                        ->
                        emptyList()
                    },
                )
            }
        val tape =
            captureCliRun {
                TapeFetch.run(
                    target,
                    FlowKind.TRADES,
                    args("--tape"),
                    clock,
                    PrintHistorySource {
                        _,
                        _,
                        _,
                        _,
                        ->
                        emptyList()
                    },
                )
            }

        assertThat(marks.code).isEqualTo(ExitCodes.SUCCESS)
        assertThat(marks.out).contains("0 marks for $target every 1h over 1 day(s) ->")
        assertThat(tape.code).isEqualTo(ExitCodes.SUCCESS)
        assertThat(tape.out).doesNotContain("not yet over")
    }

    @Test
    fun `funding without a range is an argument error`() {
        val run =
            captureCliRun {
                FundingFetch.run(
                    target,
                    Args(arrayOf("fetch", target, "--funding")),
                    FundingRateSource {
                        _,
                        _,
                        _,
                        ->
                        emptyList()
                    },
                )
            }

        assertThat(run.code).isEqualTo(ExitCodes.ARG_ERROR)
    }
}
