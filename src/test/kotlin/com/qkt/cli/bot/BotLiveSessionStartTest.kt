package com.qkt.cli.bot

import com.qkt.cli.Args
import com.qkt.cli.ExitCodes
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicInteger
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/** `qkt bot session start` without `--backtest`: a live session over a configured MT5 account, start to finish. */
class BotLiveSessionStartTest {
    private val mt5 = BotMt5Stub()

    @TempDir lateinit var dir: Path

    @AfterEach
    fun stop() = mt5.close()

    private fun venue() {
        mt5.responses["/account"] = 200 to BotMt5Stub.ACCOUNT
        mt5.responses["/get_positions"] = 200 to "[]"
        mt5.responses["/orders"] = 200 to "[]"
        mt5.responses["/history_deals_get"] = 200 to "[]"
        mt5.responses["/symbol_info_tick/XAUUSDm"] = 200 to """{"bid":2650.0,"ask":2650.5,"time":1}"""
        mt5.responses["/symbol_info/XAUUSDm"] =
            200 to
            """{"ask":2650.5,"bid":2650.0,"digits":2,"point":0.01,"trade_stops_level":0,""" +
            """"volume_min":0.01,"volume_step":0.01,"trade_contract_size":100}"""
    }

    @Test
    fun `a live session serves venue positions and writes its record at finish`() {
        venue()
        val out = dir.resolve("out")
        val argv =
            arrayOf(
                "bot",
                "session",
                "start",
                "--symbols",
                "EXNESS:XAUUSD",
                "--tf",
                "1h",
                "--identities",
                "brain",
                "--run",
                "live1",
                "--out",
                out.toString(),
                "--history-bars",
                "5",
                "--config",
                mt5.config(dir).toString(),
                "--state-dir",
                dir.toString(),
                "--json",
            )
        val exit = AtomicInteger(-1)
        val session = Thread { exit.set(BotCommand(Args(argv)).run()) }.apply { isDaemon = true }
        session.start()
        try {
            assertThat(mt5.bot(dir, "session", "wait", "--run", "live1", "--timeout", "60").code)
                .isEqualTo(ExitCodes.SUCCESS)
            val positions = mt5.bot(dir, "positions", "--run", "live1", "--json")
            val status = mt5.bot(dir, "session", "status", "--run", "live1", "--json")
            val finish = mt5.bot(dir, "session", "finish", "--run", "live1", "--json")
            session.join(30_000L)

            assertThat(positions.out.trim()).isEqualTo("[]")
            assertThat(status.out).contains("\"run\":\"live1\"", "\"identities\":[\"brain\"]")
            assertThat(finish.code).isEqualTo(ExitCodes.SUCCESS)
            assertThat(exit.get()).isEqualTo(ExitCodes.SUCCESS)
            assertThat(out.resolve("summary.json")).exists()
            assertThat(Files.readString(out.resolve("instruments.yaml"))).contains("EXNESS:XAUUSD")
            assertThat(mt5.requests).anyMatch { it.startsWith("GET /history_deals_get") }
        } finally {
            if (session.isAlive) mt5.bot(dir, "session", "finish", "--run", "live1")
            session.join(10_000L)
        }
    }

    @Test
    fun `a live session with no broker accounts is refused`() {
        Files.writeString(dir.resolve("empty.yaml"), "state:\n  enabled: false\n")

        val run = start("--config", dir.resolve("empty.yaml").toString())

        assertThat(run.first).isEqualTo(ExitCodes.USER_ERROR)
        assertThat(run.second).contains("no broker accounts in config")
    }

    @Test
    fun `a production session whose preflight fails is refused`() {
        Files.writeString(dir.resolve("prod.yaml"), "runtime:\n  mode: production\n")

        val run = start("--config", dir.resolve("prod.yaml").toString())

        assertThat(run.first).isEqualTo(ExitCodes.USER_ERROR)
        assertThat(run.second).contains("production preflight failed: ")
    }

    @Test
    fun `an identity a daemon on the state directory deploys is refused`() {
        val daemon = okhttp3.mockwebserver.MockWebServer().apply { start() }
        try {
            daemon.enqueue(okhttp3.mockwebserver.MockResponse().setBody("""[{"name":"brain","state":"running"}]"""))
            Files.writeString(dir.resolve("control.port"), daemon.port.toString())

            val run = start("--config", mt5.config(dir).toString(), "--identities", "brain")

            assertThat(run.first).isEqualTo(ExitCodes.USER_ERROR)
            assertThat(run.second).contains("identity brain is a strategy the daemon on this state directory runs")
        } finally {
            daemon.shutdown()
        }
    }

    @Test
    fun `identity clashes compare a portfolio child by its alias`() {
        refuseDeployedIdentities(listOf("scalper"), listOf("book/trend"))

        org.assertj.core.api.Assertions
            .assertThatThrownBy { refuseDeployedIdentities(listOf("trend"), listOf("book/trend")) }
            .hasMessageContaining("identity trend is a strategy")
    }

    private fun start(vararg extra: String): Pair<Int, String> {
        val buffer = java.io.ByteArrayOutputStream()
        val original = System.out
        System.setOut(java.io.PrintStream(buffer))
        val code =
            try {
                BotCommand(
                    Args(
                        arrayOf("bot", "session", "start", "--symbols", "EXNESS:XAUUSD", "--tf", "1h") +
                            extra + arrayOf("--state-dir", dir.toString(), "--json"),
                    ),
                ).run()
            } finally {
                System.setOut(original)
            }
        return code to buffer.toString()
    }
}
