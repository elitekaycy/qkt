package com.qkt.cli.daemon

import com.qkt.cli.Args
import com.qkt.cli.DaemonCommand
import com.qkt.cli.ExitCodes
import com.sun.net.httpserver.HttpServer
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.net.InetSocketAddress
import java.nio.file.Files
import java.nio.file.Path
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/** `qkt daemon` refuses to start, before serving anything, on each boot step that fails. */
class DaemonCommandBootTest {
    @TempDir lateinit var tmp: Path

    private fun config(yaml: String): Path = tmp.resolve("qkt.config.yaml").also { Files.writeString(it, yaml) }

    private fun daemon(vararg argv: String): Pair<Int, String> {
        val err = ByteArrayOutputStream()
        val prev = System.err
        System.setErr(PrintStream(err))
        val code =
            try {
                DaemonCommand(Args(arrayOf("daemon", *argv))).run()
            } finally {
                System.setErr(prev)
            }
        return code to err.toString()
    }

    private fun start(cfg: Path) = daemon("--config", cfg.toString(), "--state-dir", tmp.resolve("sd").toString())

    @Test
    fun `an unknown subcommand is an argument error naming the choices`() {
        val (code, err) = daemon("restart")

        assertThat(code).isEqualTo(ExitCodes.ARG_ERROR)
        assertThat(err).contains("unknown daemon subcommand 'restart' (expected: start, stop, status)")
    }

    @Test
    fun `a control token that cannot be created stops the boot`() {
        Files.createDirectories(tmp.resolve("sd/control.token"))

        val (code, err) = start(config("state:\n  enabled: false\n"))

        assertThat(code).isEqualTo(ExitCodes.USER_ERROR)
        assertThat(err).contains("control token initialization failed")
        assertThat(StateDir.resolve(tmp.resolve("sd").toString()).readControlPort()).isNull()
    }

    @Test
    fun `an unreadable instruments file stops the boot`() {
        val data = Files.createDirectories(tmp.resolve("data"))
        Files.writeString(data.resolve("instruments.yaml"), "- not\n- a map\n")

        val (code, err) = start(config("data_root: $data\nstate:\n  enabled: false\n"))

        assertThat(code).isEqualTo(ExitCodes.USER_ERROR)
        assertThat(err).contains("instrument registry load failed")
    }

    @Test
    fun `a broker the connectors refuse stops the boot`() {
        val cfg =
            config(
                """
                state:
                  enabled: false
                brokers:
                  exness:
                    type: mt5
                    gateway_url: http://127.0.0.1:1
                    magic: 1
                    not_a_setting: 1
                """.trimIndent(),
            )

        val (code, _) = start(cfg)

        assertThat(code).isEqualTo(ExitCodes.USER_ERROR)
        assertThat(StateDir.resolve(tmp.resolve("sd").toString()).readControlPort()).isNull()
    }

    @Test
    fun `an account that answers as another login stops the boot`() {
        val gateway = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        gateway.createContext("/") { ex ->
            val body =
                """{"login":42,"server":"Demo","trade_mode":0,"balance":1,"equity":1,"currency":"USD",""" +
                    """"leverage":100,"margin_mode":2}"""
            ex.sendResponseHeaders(200, body.length.toLong())
            ex.responseBody.use { it.write(body.toByteArray()) }
        }
        gateway.start()
        try {
            val cfg =
                config(
                    """
                    state:
                      enabled: false
                    brokers:
                      exness:
                        type: mt5
                        gateway_url: http://127.0.0.1:${gateway.address.port}
                        magic: 1
                        expected_account_login: 99
                    """.trimIndent(),
                )

            val (code, _) = start(cfg)

            assertThat(code).isEqualTo(ExitCodes.USER_ERROR)
            assertThat(StateDir.resolve(tmp.resolve("sd").toString()).readControlPort()).isNull()
        } finally {
            gateway.stop(0)
        }
    }
}
