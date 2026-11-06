package com.qkt.cli

import java.nio.file.Path
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/** Every subcommand reaches its own command through [runMain]: each answers a bad invocation in its own words. */
class MainDispatchTest {
    @TempDir
    lateinit var tmp: Path

    private var savedStateProperty: String? = null

    @BeforeEach
    fun save() {
        savedStateProperty = System.getProperty("QKT_STATE_DIR")
    }

    @AfterEach
    fun restore() {
        savedStateProperty?.let { System.setProperty("QKT_STATE_DIR", it) } ?: System.clearProperty("QKT_STATE_DIR")
    }

    private fun main(vararg argv: String) = captureCliRun { runMain(arrayOf(*argv)) }

    private fun missing() = tmp.resolve("nope.qkt").toString()

    @Test
    fun `strategy-file commands report the missing file`() {
        listOf("calendars", "sweep", "walkforward", "research", "run", "deploy", "resync").forEach { command ->
            val extra =
                if (command == "deploy" ||
                    command == "resync"
                ) {
                    arrayOf("--state-dir", tmp.toString())
                } else {
                    emptyArray()
                }
            val run = main(command, missing(), *extra)
            assertThat(run.code).withFailMessage("$command: ${run.err}").isNotEqualTo(ExitCodes.SUCCESS)
            assertThat(run.err).withFailMessage(command).contains("not found").contains("nope.qkt")
        }
        val backtest = main("backtest", missing(), "--from", "2024-01-01", "--to", "2024-01-02")
        assertThat(backtest.code).isNotEqualTo(ExitCodes.SUCCESS)
        assertThat(backtest.err).contains("nope.qkt")
    }

    @Test
    fun `daemon control commands report that no daemon is running`() {
        listOf(
            arrayOf("list"),
            arrayOf("stop", "alpha"),
            arrayOf("start", "book/a"),
            arrayOf("halt"),
            arrayOf("kill"),
            arrayOf("reconcile", "alpha"),
            arrayOf("resume"),
            arrayOf("logs", "alpha"),
            arrayOf("status"),
        ).forEach { argv ->
            val run = main(*argv, "--state-dir", tmp.resolve("state").toString())
            assertThat(run.code).withFailMessage(argv.first()).isEqualTo(ExitCodes.USER_ERROR)
            assertThat(run.err).withFailMessage("${argv.first()}: ${run.err}").contains("no daemon running")
        }
    }

    @Test
    fun `tooling commands answer an unknown action with their own usage`() {
        mapOf(
            arrayOf("dsl", "grammar") to "unknown dsl subcommand 'grammar'",
            arrayOf("experiment") to "usage: qkt experiment run",
            arrayOf("brokers", "add") to "unknown brokers subcommand",
            arrayOf("instruments", "check") to "qkt instruments verify|pull",
            arrayOf("editor", "reinstall") to "unknown editor subcommand",
            arrayOf("audit-ticks") to "--symbol is required",
            arrayOf("promotion", "promote") to "unknown promotion action",
            arrayOf("incident", "open") to "unknown incident action 'open'",
            arrayOf("golden", "replay") to "unknown golden action 'replay'",
            arrayOf("observe") to "--strategy",
        ).forEach { (argv, message) ->
            val run = main(*argv)
            assertThat(run.code).withFailMessage(argv.first()).isNotEqualTo(ExitCodes.SUCCESS)
            assertThat(run.err).withFailMessage("${argv.first()}: ${run.err}").contains(message)
        }
    }

    @Test
    fun `commands with a required argument fail as argument errors`() {
        listOf(arrayOf("preflight"), arrayOf("soak", "check")).forEach { argv ->
            val run = main(*argv)
            assertThat(run.code).withFailMessage("${argv.first()}: ${run.err}").isEqualTo(ExitCodes.ARG_ERROR)
            assertThat(run.err).withFailMessage(argv.first()).isNotBlank()
        }
        val create = main("create")
        assertThat(create.code).isEqualTo(ExitCodes.USER_ERROR)
        assertThat(create.err).contains("usage: qkt create template <path>")
        val fetch = main("fetch")
        assertThat(fetch.code).isNotEqualTo(ExitCodes.SUCCESS)
        assertThat(fetch.err).contains("usage: qkt fetch")
        val data = main("data")
        assertThat(data.code).isNotEqualTo(ExitCodes.SUCCESS)
        assertThat(data.err).contains("usage: qkt data")
    }

    @Test
    fun `exitCodeOf runs the real entry point by default`() {
        val run = captureCliRun { exitCodeOf(arrayOf("--version")) }

        assertThat(run.code).isEqualTo(ExitCodes.SUCCESS)
        assertThat(run.out.trim()).isEqualTo(BuildInfo.versionLine())
    }
}
