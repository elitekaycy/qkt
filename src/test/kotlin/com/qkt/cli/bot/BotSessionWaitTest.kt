package com.qkt.cli.bot

import com.qkt.cli.ExitCodes
import com.qkt.trade.session.BotSessionDescriptor
import com.qkt.trade.session.BotSessionFiles
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Path
import kotlin.concurrent.thread
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/**
 * `session start` runs in the foreground, so a script backgrounds it and goes on: without a way to wait, its next verb
 * can run before the session is up, and a verb that finds no session acts one-shot on the configured venue.
 */
class BotSessionWaitTest {
    private fun run(vararg argv: String): Pair<Int, String> {
        // Through the CLI entry point, as a script runs it: its option checking runs too.
        val out = ByteArrayOutputStream()
        val (original, originalErr) = System.out to System.err
        System.setOut(PrintStream(out))
        System.setErr(PrintStream(out))
        return try {
            com.qkt.cli.runMain(arrayOf("bot", "session", "wait", *argv)) to out.toString()
        } finally {
            System.setOut(original)
            System.setErr(originalErr)
        }
    }

    @Test
    fun `waits until the named session answers, then prints its status`(
        @TempDir state: Path,
    ) {
        val server = MockWebServer()
        server.enqueue(MockResponse().setBody("""{"ok":true,"api":2,"run":"demo"}"""))
        server.start()
        val starting =
            thread {
                Thread.sleep(700)
                BotSessionFiles.write(
                    state.resolve("state"),
                    BotSessionDescriptor("demo", server.port, "t", "backtest", ProcessHandle.current().pid()),
                )
            }

        val (code, text) = run("--run", "demo", "--state-dir", state.toString(), "--timeout", "10", "--json")
        starting.join()

        assertThat(code).isEqualTo(ExitCodes.SUCCESS)
        assertThat(text).contains("\"run\":\"demo\"")
        server.shutdown()
    }

    @Test
    fun `a session that never comes up fails after the timeout, naming it`(
        @TempDir state: Path,
    ) {
        val started = System.nanoTime()

        val (code, text) = run("--run", "demo", "--state-dir", state.toString(), "--timeout", "1", "--json")

        assertThat(code).isNotEqualTo(ExitCodes.SUCCESS)
        assertThat(text).contains("demo").contains("did not answer")
        assertThat((System.nanoTime() - started) / 1_000_000).isLessThan(5_000)
    }

    @Test
    fun `waiting needs the run it waits for`(
        @TempDir state: Path,
    ) {
        val (code, text) = run("--state-dir", state.toString(), "--json")

        assertThat(code).isNotEqualTo(ExitCodes.SUCCESS)
        assertThat(text).contains("--run")
    }
}
