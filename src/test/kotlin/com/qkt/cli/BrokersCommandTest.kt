package com.qkt.cli

import java.nio.file.Files
import java.nio.file.Path
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class BrokersCommandTest {
    @TempDir
    lateinit var tmp: Path

    private fun brokers(vararg argv: String) = captureCliRun { BrokersCommand(Args(arrayOf("brokers", *argv))).run() }

    private fun config(body: String): String =
        tmp
            .resolve("qkt.config.yaml")
            .also {
                Files.writeString(it, body)
            }.toString()

    private val twoBrokers =
        """
        brokers:
          exness:
            type: mt5
            gateway_url: http://gw-a:5001
            server_time_zone: UTC
          ic:
            type: mt5
            gateway_url: http://gw-b:5002
            symbol_suffix: .r
            magic: 4242
            server_time_zone: Europe/Athens
        """.trimIndent()

    @Test
    fun `list prints one table row per resolved mt5 profile`() {
        val run = brokers("list", "--config", config(twoBrokers))

        assertThat(run.code).withFailMessage(run.err).isEqualTo(ExitCodes.SUCCESS)
        val lines = run.out.trim().lines()
        assertThat(lines[0]).startsWith("NAME").contains("GATEWAY").endsWith("MAGIC")
        val ic = lines.single { it.startsWith("ic ") }
        assertThat(
            ic.split(Regex("\\s+")),
        ).containsExactly("ic", "mt5", "http://gw-b:5002", ".r", "Europe/Athens", "4242")
        assertThat(lines.single { it.startsWith("exness ") }.split(Regex("\\s+"))[3]).isEqualTo("-")
    }

    @Test
    fun `json prints every profile with its gateway suffix zone and magic`() {
        val run = brokers("--config", config(twoBrokers), "--json")

        assertThat(run.code).isEqualTo(ExitCodes.SUCCESS)
        assertThat(run.out.trim())
            .startsWith("[")
            .endsWith("]")
            .contains(
                """{"name":"ic","kind":"mt5","gatewayUrl":"http://gw-b:5002","symbolSuffix":".r",""" +
                    """"serverTimeZone":"Europe/Athens","magic":4242}""",
            )
    }

    @Test
    fun `a profile that cannot be resolved is a user error`() {
        val run =
            brokers(
                "--config",
                config("brokers:\n  custom:\n    type: mt5\n    server_time_zone: UTC\n    magic: 1\n"),
            )

        assertThat(run.code).isEqualTo(ExitCodes.USER_ERROR)
        assertThat(run.err).contains("qkt: brokers load failed:").contains("gateway_url")
    }

    @Test
    fun `an unknown subcommand is an argument error`() {
        val run = brokers("add")

        assertThat(run.code).isEqualTo(ExitCodes.ARG_ERROR)
        assertThat(run.err).contains("unknown brokers subcommand")
    }

    @Test
    fun `without a config anywhere list names the searched locations`() {
        withUserHome(tmp) {
            assumeTrue(Config.locate() == null, "a qkt.config.yaml outside the temp home is visible")
            val run = brokers("list")
            assertThat(run.code).isEqualTo(ExitCodes.USER_ERROR)
            assertThat(run.err).contains("no qkt.config.yaml found in any of").contains(tmp.toString())
        }
    }
}
