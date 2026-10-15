package com.qkt.cli

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.qkt.candles.TimeWindow
import com.qkt.common.Money
import com.qkt.marketdata.Candle
import com.qkt.marketdata.store.BinaryBarStore
import com.qkt.positions.StrategyPositionTracker
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.slf4j.LoggerFactory

/**
 * A bracket's exits belong to the leg that opened them: another child's CLOSE on the same symbol leaves them,
 * and the leg's own close by a plain opposite order takes them with it. Replayed from one-minute bars that
 * end by falling through the stop, so a stripped stop shows as a position never stopped out, and a stale one
 * as a fill booked against a leg that is gone.
 */
class BracketExitOwnershipTest {
    @TempDir
    lateinit var dir: Path

    private val day = LocalDate.parse("2026-08-10")
    private val start = day.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()

    private fun bar(
        minute: Int,
        close: String,
        low: String = close,
    ) = Candle(
        "BACKTEST:EURUSD",
        Money.of(close),
        Money.of(close),
        Money.of(low),
        Money.of(close),
        Money.of("1"),
        start + minute * 60_000L,
        start + (minute + 1) * 60_000L,
    )

    private fun backtest(file: Path): List<String> {
        val dataRoot = dir.resolve("data")
        val bars =
            listOf("1.1000", "1.1010", "1.1030", "1.1030").mapIndexed { m, c -> bar(m, c) } +
                listOf(bar(4, "1.0750", low = "1.0700"), bar(5, "1.0750"), bar(6, "1.0750"))
        BinaryBarStore(dataRoot).writeDay("BACKTEST", "EURUSD", TimeWindow.ONE_MINUTE, day, bars)
        val report = dir.resolve("report")
        val original = System.out
        try {
            System.setOut(PrintStream(ByteArrayOutputStream()))
            val args =
                arrayOf(
                    "backtest",
                    file.toString(),
                    "--from",
                    Instant.ofEpochMilli(start).toString(),
                    "--to",
                    Instant.ofEpochMilli(start + 7 * 60_000L).toString(),
                    "--data-root",
                    dataRoot.toString(),
                    "--no-fetch",
                    "--allow-incomplete",
                    "--bars",
                    "--bar-tf",
                    "1m",
                    "--report-dir",
                    report.toString(),
                )
            assertThat(BacktestCommand(Args(args)).run()).isEqualTo(ExitCodes.SUCCESS)
        } finally {
            System.setOut(original)
        }
        return Files.readAllLines(report.resolve("trades.csv")).drop(1).map { it.split(',').take(8).joinToString(",") }
    }

    private fun write(
        name: String,
        text: String,
    ): Path = dir.resolve(name).also { Files.writeString(it, text.trimIndent()) }

    @Test
    fun `a child's CLOSE leaves its sibling's stop working on the shared symbol`() {
        write(
            "a.qkt",
            """
            STRATEGY a VERSION 1
            SYMBOLS
                eur = BACKTEST:EURUSD EVERY 1m
            RULES
                WHEN eur.close = 1.1 AND POSITION.eur = 0
                THEN BUY eur SIZING 0.01 BRACKET { STOP_LOSS AT 1.08, TAKE_PROFIT AT 1.2 }
            """,
        )
        write(
            "b.qkt",
            """
            STRATEGY b VERSION 1
            SYMBOLS
                eur = BACKTEST:EURUSD EVERY 1m
            RULES
                WHEN eur.close = 1.101 AND POSITION.eur = 0
                THEN BUY eur SIZING 0.01 BRACKET { STOP_LOSS AT 1.05, TAKE_PROFIT AT 1.2 }
                WHEN eur.close = 1.103 AND POSITION.eur > 0
                THEN CLOSE eur
            """,
        )
        val book =
            write(
                "book.qkt",
                "PORTFOLIO pf VERSION 1\nIMPORT 'a.qkt' AS a\nIMPORT 'b.qkt' AS b\nRULES\n  RUN a\n  RUN b",
            )

        val fills = backtest(book)

        assertThat(fills).hasSize(4)
        // The falling bar opens below the stop, so the stop fills at that gap open.
        assertThat(fills.last())
            .contains("pf:a")
            .contains("SELL")
            .contains("Stop")
            .endsWith("1.07500000")
    }

    @Test
    fun `a plain opposite order closes the leg and its bracket, so the stop never fires later`() {
        val logger = LoggerFactory.getLogger(StrategyPositionTracker::class.java) as Logger
        val appender = ListAppender<ILoggingEvent>().also { it.start() }
        logger.addAppender(appender)
        val strategy =
            write(
                "s.qkt",
                """
                STRATEGY s VERSION 1
                SYMBOLS
                    eur = BACKTEST:EURUSD EVERY 1m
                RULES
                    WHEN eur.close = 1.1 AND POSITION.eur = 0
                    THEN BUY eur SIZING 0.01 BRACKET { STOP_LOSS AT 1.08, TAKE_PROFIT AT 1.2 }
                    WHEN eur.close = 1.103 AND POSITION.eur > 0
                    THEN SELL eur SIZING 0.01
                """,
            )

        val fills =
            try {
                backtest(strategy)
            } finally {
                logger.detachAppender(appender)
            }

        assertThat(fills).hasSize(2)
        assertThat(appender.list.map { it.formattedMessage }).noneMatch { it.contains("the book does not hold") }
    }
}
