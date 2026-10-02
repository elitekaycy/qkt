package com.qkt.cli

import ch.qos.logback.classic.LoggerContext
import ch.qos.logback.classic.joran.JoranConfigurator
import ch.qos.logback.core.ConsoleAppender
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.slf4j.Logger

/** Command output owns stdout (`--json`, `qkt lsp`), so qkt's console log writes to stderr. */
class ConsoleLoggingTest {
    private fun consoleTargets(): List<String> {
        val context = LoggerContext()
        JoranConfigurator().apply { setContext(context) }.doConfigure(javaClass.getResource("/logback.xml"))
        val root = context.getLogger(Logger.ROOT_LOGGER_NAME)
        return root
            .iteratorForAppenders()
            .asSequence()
            .filterIsInstance<ConsoleAppender<*>>()
            .map { it.target }
            .toList()
            .also { context.stop() }
    }

    @Test
    fun `the shipped console log appender writes to stderr unless QKT_CONSOLE_TARGET says otherwise`() {
        // The test JVM sets the override (test logs stay out of the reports); the CLI never does.
        val override = System.clearProperty("QKT_CONSOLE_TARGET")
        try {
            assertThat(consoleTargets()).containsExactly("System.err")
            System.setProperty("QKT_CONSOLE_TARGET", "System.out")
            assertThat(consoleTargets()).containsExactly("System.out")
        } finally {
            if (override != null) {
                System.setProperty("QKT_CONSOLE_TARGET", override)
            } else {
                System.clearProperty("QKT_CONSOLE_TARGET")
            }
        }
    }
}
