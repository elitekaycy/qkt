package com.qkt.connector.gateway

import com.qkt.cli.Args
import com.qkt.cli.ExitCodes
import com.qkt.cli.bot.BotCommand
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path
import kotlin.concurrent.thread
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/** `qkt bot` one-shot verbs end to end against a gateway broker (#1390). */
class BotGatewayCliTest {
    private val perp = "SOL_USDC-PERPETUAL"
    private val fake =
        FakeGateway(listOf(perp)).apply {
            instruments[perp] = WireInstrument(perp, "perpetual", "USDC", "1", "0.001", "0.1", "0.1")
            quotes.greeting = WireQuote(perp, bid = "105.4", ask = "105.6", time = 7)
        }

    @AfterEach
    fun stop() = fake.shutdown()

    private fun run(
        dir: Path,
        vararg argv: String,
    ): Pair<Int, String> {
        val config = dir.resolve("qkt.config.yaml")
        Files.writeString(
            config,
            """
            |brokers:
            |  deribit:
            |    type: gateway
            |    gateway_url: ${fake.url}
            |    api_key: k
            |    expected_adapter: fake
            |    expected_account_login: "7"
            |    expected_trade_mode: demo
            |account:
            |  currency: USDC
            """.trimMargin(),
        )
        val out = ByteArrayOutputStream()
        val prev = System.out
        System.setOut(PrintStream(out))
        val code =
            try {
                BotCommand(
                    Args(arrayOf("bot", *argv, "--config", config.toString(), "--state-dir", dir.toString(), "--json")),
                ).run()
            } finally {
                System.setOut(prev)
            }
        return code to out.toString()
    }

    @Test
    fun `bot buy on a gateway broker prints the json contract`(
        @TempDir dir: Path,
    ) {
        val filler =
            thread {
                while (fake.submits.isEmpty()) Thread.sleep(5)
                fake.act { fill(fake.submits.first().clientOrderId, "f1", "2", "105.6", FakeGateway.TIME) }
            }

        val (code, out) = run(dir, "buy", "2", "DERIBIT:SOL_USDC_PERPETUAL", "--as", "agent")
        filler.join()

        assertThat(code).isEqualTo(ExitCodes.SUCCESS)
        assertThat(out)
            .contains("\"ok\":true")
            .contains("\"state\":\"filled\"")
            .contains("\"fillPrice\":105.6")
            .contains("\"symbol\":\"DERIBIT:SOL_USDC_PERPETUAL\"")
            .contains("\"ticket\":\"bot-agent-")
        assertThat(java.math.BigDecimal(fake.submits.single().quantity)).isEqualByComparingTo("2")
    }

    @Test
    fun `a protected one-shot entry on a gateway is refused and nothing is sent`(
        @TempDir dir: Path,
    ) {
        val (code, out) = run(dir, "buy", "2", "DERIBIT:SOL_USDC_PERPETUAL", "--sl", "by:5", "--tp", "by:10")

        assertThat(code).isEqualTo(ExitCodes.USER_ERROR)
        assertThat(out).contains("\"ok\":false").contains("a gateway venue holds no stop for a position")
        assertThat(fake.submits).isEmpty()
        assertThat(
            Files.readString(Files.list(dir.resolve("state/bot/manual")).findFirst().get()),
        ).contains("bot.rejected")
    }

    @Test
    fun `positions account and quote read the gateway`(
        @TempDir dir: Path,
    ) {
        assertThat(run(dir, "quote", "DERIBIT:SOL_USDC_PERPETUAL").second).contains("\"bid\":105.4")
        assertThat(run(dir, "account").second).contains("\"currency\":\"USDC\"").contains("\"login\":7")
        assertThat(run(dir, "positions").second.trim()).isEqualTo("[]")
    }
}
