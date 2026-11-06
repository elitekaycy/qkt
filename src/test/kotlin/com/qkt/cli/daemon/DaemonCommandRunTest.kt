package com.qkt.cli.daemon

import ch.qos.logback.classic.Logger
import com.qkt.cli.Args
import com.qkt.cli.DaemonCommand
import com.qkt.cli.ExitCodes
import com.qkt.observe.insights.InsightsLogAppender
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicInteger
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.slf4j.LoggerFactory

/** A daemon booted with insights, notify channels and a failing auto-deploy; `daemon stop|status` against it. */
class DaemonCommandRunTest {
    @TempDir lateinit var tmp: Path

    private val sd by lazy { tmp.resolve("sd") }

    private fun daemon(vararg argv: String): Triple<Int, String, String> {
        val out = ByteArrayOutputStream()
        val err = ByteArrayOutputStream()
        val prevOut = System.out
        val prevErr = System.err
        System.setOut(PrintStream(out))
        System.setErr(PrintStream(err))
        val code =
            try {
                DaemonCommand(Args(arrayOf("daemon", *argv, "--state-dir", sd.toString()))).run()
            } finally {
                System.setOut(prevOut)
                System.setErr(prevErr)
            }
        return Triple(code, out.toString(), err.toString())
    }

    @Test
    fun `a daemon with insights notify and a broken load-dir file boots degraded and stops on request`() {
        val loadDir = Files.createDirectories(tmp.resolve("load"))
        Files.writeString(loadDir.resolve("broken.qkt"), "STRATEGY nope")
        val cfg = tmp.resolve("qkt.config.yaml")
        Files.writeString(
            cfg,
            """
            state:
              enabled: false
              disk_free_alert_gb: 1000000
            insights:
              enabled: true
              url: http://127.0.0.1:1
              journal_enabled: true
              events: [trade, log]
            notify:
              telegram:
                enabled: true
                events: [daemon_started, strategy_error, disk_space_low]
                daily_summary_utc: "23:59"
            """.trimIndent(),
        )
        val exit = AtomicInteger(-1)
        val args =
            arrayOf(
                "daemon",
                "--config",
                cfg.toString(),
                "--state-dir",
                sd.toString(),
                "--load-dir",
                loadDir.toString(),
            )
        val thread = Thread { exit.set(DaemonCommand(Args(args)).run()) }.apply { isDaemon = true }
        val root = LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME) as Logger
        thread.start()
        try {
            val stateDir = StateDir.resolve(sd.toString())
            val end = System.currentTimeMillis() + 30_000L
            while (stateDir.readControlPort() == null && thread.isAlive && System.currentTimeMillis() < end) {
                Thread.sleep(50)
            }
            val (statusCode, health, _) = daemon("status", "--json")

            assertThat(statusCode).isEqualTo(ExitCodes.SUCCESS)
            assertThat(
                health.trim(),
            ).startsWith("{\"status\":\"degraded\"").contains("\"name\":\"broken\"", "\"attempts\":")
            assertThat(stateDir.stateRoot.resolve("insights-journal")).isDirectory()
            assertThat(root.getAppender(InsightsLogAppender.NAME)).isNotNull
            assertThat(daemon("stop").first).isEqualTo(ExitCodes.SUCCESS)
            thread.join(15_000L)
            assertThat(exit.get()).isEqualTo(ExitCodes.SUCCESS)
        } finally {
            thread.interrupt()
            thread.join(10_000L)
            root.detachAppender(InsightsLogAppender.NAME)
        }
    }

    @Test
    fun `stop and status report a daemon that answers with an error`() {
        val server = MockWebServer().apply { start() }
        try {
            Files.createDirectories(sd)
            Files.writeString(sd.resolve("control.port"), server.port.toString())
            server.enqueue(MockResponse().setResponseCode(500).setBody("nope"))
            server.enqueue(MockResponse().setResponseCode(503).setBody("down"))
            server.enqueue(MockResponse().setBody("""{"status":"ok"}"""))

            val stop = daemon("stop")
            val status = daemon("status")
            val text = daemon("status")

            assertThat(stop.first).isEqualTo(ExitCodes.USER_ERROR)
            assertThat(stop.third).contains("shutdown failed (500): nope")
            assertThat(status.first).isEqualTo(ExitCodes.USER_ERROR)
            assertThat(status.third).contains("status failed (503): down")
            assertThat(text.second).contains("control port: ${server.port}", "control.port", """{"status":"ok"}""")
        } finally {
            server.shutdown()
        }
    }
}
