package com.qkt.cli

import ch.qos.logback.classic.LoggerContext
import ch.qos.logback.classic.joran.JoranConfigurator
import ch.qos.logback.core.ConsoleAppender
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.slf4j.Logger

/** Command output owns stdout (`--json`, `qkt lsp`), so qkt's console log writes to stderr. */
class ConsoleLoggingTest {
    @Test
    fun `the shipped console log appender writes to stderr`() {
        val context = LoggerContext()
        JoranConfigurator().apply { setContext(context) }.doConfigure(javaClass.getResource("/logback.xml"))
        val root = context.getLogger(Logger.ROOT_LOGGER_NAME)
        val consoles =
            root
                .iteratorForAppenders()
                .asSequence()
                .filterIsInstance<ConsoleAppender<*>>()
                .toList()

        assertThat(consoles).isNotEmpty
        assertThat(consoles.map { it.target }).containsOnly("System.err")
        context.stop()
    }
}
