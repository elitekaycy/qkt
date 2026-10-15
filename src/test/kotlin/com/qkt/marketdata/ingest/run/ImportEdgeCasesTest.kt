package com.qkt.marketdata.ingest.run

import com.qkt.marketdata.ingest.ImportRefusal
import com.qkt.marketdata.ingest.IngestFixtures
import com.qkt.marketdata.ingest.plan.BarLabel
import com.qkt.marketdata.ingest.plan.ImportDescriptor
import com.qkt.marketdata.ingest.plan.IngestKind
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDate
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class ImportEdgeCasesTest {
    @TempDir
    lateinit var dir: Path

    private val root get() = dir.resolve("root")

    private fun file(
        name: String,
        text: String,
    ): Path =
        dir
            .resolve("in")
            .resolve(name)
            .also {
                Files.createDirectories(it.parent)
            }.also { Files.writeString(it, text) }

    private val bars =
        ImportDescriptor(
            kind = IngestKind.BARS,
            time = listOf("t"),
            fields = mapOf("open" to "o", "high" to "h", "low" to "l", "close" to "c"),
        )

    private fun minutes(
        startMs: Long,
        count: Int,
    ) = (0 until count).joinToString("") { "${startMs + it * 60_000},1,2,1,2\n" }

    @Test
    fun `files of one dataset in milliseconds and in microseconds each read in their own unit`() {
        val ms = IngestFixtures.sample("binance-klines.csv", dir.resolve("in"))
        val us = IngestFixtures.sample("binance-klines-us.csv", dir.resolve("in"))

        val report = IngestFixtures.import(root, listOf(ms, us), "BINANCE:BTCUSDT")

        assertThat(report.timeFormats).containsExactly("epoch:ms", "epoch:us")
        assertThat(report.plan).contains("timeFormat: \"epoch\"")
        assertThat(report.days).contains(LocalDate.of(2020, 6, 1), LocalDate.of(2025, 6, 1))
    }

    @Test
    fun `a newest-first file is read in time order`() {
        IngestFixtures.import(
            root,
            listOf(IngestFixtures.sample("alphavantage-intraday.csv", dir.resolve("in"))),
            "AV:IBM",
        )

        val rows =
            IngestFixtures
                .lines(
                    root.resolve("bars/AV/IBM/5m/2026-10-13.csv"),
                ).drop(1)
                .map { it.substringBefore(',') }
        assertThat(rows).containsExactly("1791935100000", "1791935400000", "1791935700000")
    }

    @Test
    fun `a file that goes back and forth in time refuses unless told to sort`() {
        val shuffled = file("s.csv", "t,o,h,l,c\n1704153660000,1,2,1,2\n1704153600000,1,2,1,2\n1704153720000,1,2,1,2\n")

        assertThatThrownBy {
            IngestFixtures.import(
                root,
                listOf(shuffled),
                "SRC:X",
                bars,
            )
        }.hasMessageContaining("--sort")
        assertThat(IngestFixtures.import(root, listOf(shuffled), "SRC:X", bars, sort = true).records).isEqualTo(3)
    }

    @Test
    fun `two tick files covering the same hours refuse, since their ticks cannot be told apart`() {
        val ticks = ImportDescriptor(kind = IngestKind.TICKS, time = listOf("t"), fields = mapOf("price" to "p"))
        val a = file("a.csv", "t,p\n1704153600000,1\n1704153700000,2\n")
        val b = file("b.csv", "t,p\n1704153650000,1\n1704153750000,2\n")

        assertThatThrownBy {
            IngestFixtures.import(root, listOf(a, b), "X", ticks)
        }.hasMessageContaining("overlapping tick files")
    }

    @Test
    fun `close-labelled bars move back one bar to their open time`() {
        val closeStamped = file("c.csv", "t,o,h,l,c\n" + minutes(1_704_153_660_000L, 3))
        val report =
            IngestFixtures.import(
                root,
                listOf(closeStamped),
                "SRC:X",
                bars.copy(label = BarLabel.CLOSE, timeframe = "1m"),
            )

        assertThat(
            report.preview
                .first()
                .second.timeMs,
        ).isEqualTo(1_704_153_600_000L)
    }

    @Test
    fun `broker H4 bars cut at broker midnight are off the UTC grid and refuse`() {
        val h4 =
            (0 until 6).joinToString("") {
                "2024.01.0${3 + it / 6} ${"%02d".format(it * 4)}:00:00\t1\t2\t1\t2\t10\t0\t20\n"
            }
        val text =
            "<DATE>\t<TIME>\t<OPEN>\t<HIGH>\t<LOW>\t<CLOSE>\t<TICKVOL>\t<VOL>\t<SPREAD>\n" + h4.replace(" ", "\t")

        assertThatThrownBy { IngestFixtures.import(root, listOf(file("h4.csv", text)), "BROKER:EURUSD") }
            .isInstanceOf(ImportRefusal::class.java)
            .satisfies({ assertThat((it as ImportRefusal).lines.first()).contains("not on the UTC 240-minute grid") })
    }

    @Test
    fun `a published series without an availability time refuses, naming the look-ahead`() {
        val series = ImportDescriptor(kind = IngestKind.SERIES, time = listOf("date"), fields = mapOf("value" to "v"))
        val f = file("cot.csv", "date,v\n2024-01-02,5\n2024-01-09,6\n")

        assertThatThrownBy {
            IngestFixtures.import(
                root,
                listOf(f),
                "CFTC:X",
                series,
            )
        }.hasMessageContaining("leaks the future")
        val lagged = IngestFixtures.import(root, listOf(f), "CFTC:X", series.copy(availableLag = "3d"))
        assertThat(lagged.days).containsExactly(LocalDate.of(2024, 1, 5), LocalDate.of(2024, 1, 12))
    }

    @Test
    fun `a generic file imports from flags alone, and a missing field names the columns`() {
        val f =
            file(
                "my-broker.csv",
                "Date;Time;Last;Qty\n02/01/2024;10:00:00;1,10024;5\n03/01/2024;10:00:00;1,10031;7\n15/01/2024;10:00:00;1,1003;7\n",
            )
        val ticks =
            ImportDescriptor(
                kind = IngestKind.TICKS,
                time = listOf("Date", "Time"),
                clock = "UTC",
                fields =
                    mapOf(
                        "price" to "Last",
                        "volume" to "Qty",
                    ),
            )

        val report = IngestFixtures.import(root, listOf(f), "EURUSD", ticks)
        assertThat(report.timeFormats).containsExactly("dd/MM/yyyy HH:mm:ss")
        assertThat(IngestFixtures.firstStoredRow(root)).isEqualTo("1704189600000,EURUSD,1.10024000,5.00000000,,,,")

        assertThatThrownBy {
            IngestFixtures.import(
                dir.resolve("r2"),
                listOf(f),
                "EURUSD",
                ticks.copy(
                    fields =
                        mapOf(
                            "volume" to "Qty",
                        ),
                ),
            )
        }.hasMessageContaining("price, or bid and ask")
            .hasMessageContaining("Last")
    }

    @Test
    fun `a MetaTrader tick before both sides are known is a row error`() {
        val text =
            "<DATE>\t<TIME>\t<BID>\t<ASK>\n" +
                "2024.03.11\t00:00:01.112\t2180.512\t\n" +
                "2024.03.11\t00:00:01.317\t2180.530\t2180.70\n"

        assertThatThrownBy {
            IngestFixtures.import(root, listOf(file("t.csv", text)), "XAUUSD")
        }.hasMessageContaining("only one side")
    }
}
