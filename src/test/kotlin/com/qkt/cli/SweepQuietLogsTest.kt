package com.qkt.cli

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import java.nio.file.Path
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.slf4j.LoggerFactory

/** A sweep prints its ranking, not the engine's per-order log: 73,584 INFO lines for six trials before (#1471). */
class SweepQuietLogsTest : SweepCommandFixture() {
    private fun infoLinesDuring(block: () -> Unit): Int {
        val root = LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME) as Logger
        val appender = ListAppender<ILoggingEvent>().apply { start() }
        root.addAppender(appender)
        try {
            block()
        } finally {
            root.detachAppender(appender)
        }
        return appender.list.count { it.level == Level.INFO && it.loggerName.startsWith("com.qkt.app") }
    }

    private fun sweep(
        dir: Path,
        vararg extra: String,
    ) = SweepCommand(
        Args(
            arrayOf(
                "sweep",
                strategy(dir).toString(),
                "--from",
                "2026-06-04",
                "--to",
                "2026-06-05",
                "--data-root",
                dir.resolve("data").toString(),
                "--param",
                "fast=2,3",
                *extra,
            ),
        ),
        fetcherOverride = FakeXauFetcher,
    ).run()

    @Test
    fun `a sweep keeps the engine's order log quiet unless --verbose`(
        @TempDir dir: Path,
    ) {
        assertThat(infoLinesDuring { sweep(dir) }).isZero()
        assertThat(infoLinesDuring { sweep(dir, "--verbose") }).isPositive()
    }
}
