package com.qkt.cli.daemon

import java.nio.file.Files
import java.nio.file.Path
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.slf4j.LoggerFactory
import org.slf4j.MDC

class PerStrategyAppenderTest {
    private var savedQktStateDir: String? = null

    @BeforeEach
    fun setProperty() {
        savedQktStateDir = System.getProperty("QKT_STATE_DIR")
    }

    @AfterEach
    fun restore() {
        if (savedQktStateDir ==
            null
        ) {
            System.clearProperty("QKT_STATE_DIR")
        } else {
            System.setProperty("QKT_STATE_DIR", savedQktStateDir!!)
        }
        MDC.clear()
        // Reset logback to release file handles before TempDir teardown.
        val ctx =
            LoggerFactory.getILoggerFactory() as ch.qos.logback.classic.LoggerContext
        ctx.reset()
        ch.qos.logback.classic.util
            .ContextInitializer(ctx)
            .autoConfig(Thread.currentThread().contextClassLoader)
    }

    @Test
    fun `MDC strategy key routes log lines to per-strategy files`(
        @TempDir tmp: Path,
    ) {
        // The SiftingAppender resolves ${QKT_STATE_DIR} from system properties / env.
        // Setting it as a system property and resetting logback ensures the test directory is honored.
        System.setProperty("QKT_STATE_DIR", tmp.toString())
        val ctx =
            LoggerFactory.getILoggerFactory() as ch.qos.logback.classic.LoggerContext
        ctx.reset()
        ch.qos.logback.classic.util
            .ContextInitializer(ctx)
            .autoConfig(Thread.currentThread().contextClassLoader)

        val log = LoggerFactory.getLogger("com.qkt.dsl.strategy.test")

        MDC.put("strategy", "alpha")
        try {
            log.info("hello from alpha")
        } finally {
            MDC.remove("strategy")
        }

        MDC.put("strategy", "beta")
        try {
            log.info("hello from beta")
        } finally {
            MDC.remove("strategy")
        }

        // Allow flush via logger context stop/restart in afterEach; for now read files.
        // Logback FileAppender is synchronous, so by this point the file is written.
        val alphaLog = tmp.resolve("logs/alpha.log")
        val betaLog = tmp.resolve("logs/beta.log")
        assertThat(Files.exists(alphaLog)).isTrue
        assertThat(Files.exists(betaLog)).isTrue
        val alphaText = Files.readString(alphaLog)
        val betaText = Files.readString(betaLog)
        assertThat(alphaText).contains("hello from alpha")
        assertThat(alphaText).doesNotContain("hello from beta")
        assertThat(betaText).contains("hello from beta")
        assertThat(betaText).doesNotContain("hello from alpha")
    }
}
