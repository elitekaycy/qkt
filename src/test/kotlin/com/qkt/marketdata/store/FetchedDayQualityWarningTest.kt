package com.qkt.marketdata.store

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDate
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.slf4j.LoggerFactory

class FetchedDayQualityWarningTest {
    @TempDir
    lateinit var dir: Path

    private val logger = LoggerFactory.getLogger("fetched-day-quality-test") as Logger
    private val appender = ListAppender<ILoggingEvent>()
    private val day = LocalDate.of(2026, 1, 5)
    private val header = "timestamp,symbol,price,volume,bid,ask,bidVolume,askVolume"

    @BeforeEach
    fun attach() {
        appender.start()
        logger.addAppender(appender)
    }

    @AfterEach
    fun detach() {
        logger.detachAppender(appender)
    }

    private fun warnings() = appender.list.map { it.formattedMessage }

    @Test
    fun `a fetch that left no file is reported with its path`() {
        val missing = dir.resolve("absent.csv")

        warnOnLowQuality(logger, "X:SYM", day, missing)

        assertThat(warnings()).containsExactly("data integrity: fetch of X:SYM $day produced no file at $missing")
    }

    @Test
    fun `a day with a gap of hours is reported as possible partial data`() {
        val file = dir.resolve("day.csv")
        Files.write(file, listOf(header, "0,X,100,,,,,", "${7 * 3_600_000L},X,101,,,,,"))

        warnOnLowQuality(logger, "X:SYM", day, file)

        assertThat(warnings()).containsExactly(
            "data integrity: X:SYM $day has a 7h intra-day gap across 2 ticks — possible partial data",
        )
    }

    @Test
    fun `a day with ticks close together is not reported`() {
        val file = dir.resolve("day.csv")
        Files.write(file, listOf(header, "0,X,100,,,,,", "60000,X,101,,,,,"))

        warnOnLowQuality(logger, "X:SYM", day, file)

        assertThat(warnings()).isEmpty()
    }
}
