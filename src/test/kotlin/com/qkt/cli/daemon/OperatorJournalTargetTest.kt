package com.qkt.cli.daemon

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import java.nio.file.Files
import java.nio.file.Path
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.slf4j.LoggerFactory

/** Where an operator action with no affected strategy is journaled, and how a daemon line without a level logs. */
class OperatorJournalTargetTest {
    @Test
    fun `an action that affected nothing is journaled under its target`(
        @TempDir tmp: Path,
    ) {
        val stateDir = StateDir.resolve(tmp.toString())

        OperatorJournal(stateDir, "telegram").record("halt", target = "trend", affected = emptyList())

        val journal =
            Files.walk(stateDir.stateRoot.resolve("journal")).use { f ->
                f.filter { Files.isRegularFile(it) }.toList()
            }
        assertThat(journal).isNotEmpty
        assertThat(journal.joinToString { it.toString() }).contains("trend")
        assertThat(journal.joinToString { Files.readString(it) }).contains("operator_action", "telegram", "halt")
    }

    @Test
    fun `a daemon line without a level prefix logs at info as written`() {
        val logger = LoggerFactory.getLogger("com.qkt.daemon") as Logger
        val captured = ListAppender<ILoggingEvent>().apply { start() }
        logger.addAppender(captured)
        try {
            DaemonLog.line("plain message")
            DaemonLog.line("[INFO] tagged message")
        } finally {
            logger.detachAppender(captured)
        }

        assertThat(captured.list.map { it.level.toString() to it.formattedMessage })
            .containsExactly("INFO" to "plain message", "INFO" to "tagged message")
    }
}
