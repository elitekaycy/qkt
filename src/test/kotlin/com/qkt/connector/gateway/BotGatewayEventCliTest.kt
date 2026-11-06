package com.qkt.connector.gateway

import com.qkt.cli.Args
import com.qkt.cli.ExitCodes
import com.qkt.cli.bot.BotCommand
import com.qkt.connector.gateway.EventWire.code
import com.qkt.connector.gateway.EventWire.contract
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path
import kotlin.concurrent.thread
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/** `qkt bot` on event contracts end to end against a gateway: the catalog, one event's chain, slots, refusals. */
class BotGatewayEventCliTest {
    private val openMs = System.currentTimeMillis() - 60_000L
    private val fake = FakeGateway(emptyList())

    @Volatile private var quoting = true
    private val quoter =
        thread(isDaemon = true) {
            while (quoting) {
                EventWire.quotes().forEach(fake.quotes::send)
                Thread.sleep(50)
            }
        }

    init {
        EventWire.list(fake, EventWire.ladder(openMs))
    }

    @AfterEach
    fun stop() {
        quoting = false
        quoter.join()
        fake.shutdown()
    }

    private fun run(
        dir: Path,
        json: Boolean,
        vararg argv: String,
    ): Pair<Int, String> {
        val config = dir.resolve("qkt.config.yaml")
        Files.writeString(
            config,
            """
            |brokers:
            |  kalshi:
            |    type: gateway
            |    gateway_url: ${fake.url}
            |    api_key: k
            |    expected_adapter: fake
            |    expected_account_login: "7"
            |    expected_trade_mode: demo
            |account:
            |  currency: USD
            """.trimMargin(),
        )
        val out = ByteArrayOutputStream()
        val prev = System.out
        System.setOut(PrintStream(out))
        val flags =
            listOfNotNull("--config", config.toString(), "--state-dir", dir.toString(), "--json".takeIf { json })
        val code =
            try {
                BotCommand(Args(arrayOf("bot", *argv, *flags.toTypedArray()))).run()
            } finally {
                System.setOut(prev)
            }
        return code to out.toString()
    }

    @Test
    fun `markets lists the catalog through the listing filters`(
        @TempDir dir: Path,
    ) {
        val paused = contract(2, 1, trading = "disabled", openMs = openMs)
        EventWire.list(fake, EventWire.ladder(openMs).map { if (it.code == paused.code) paused else it })

        val (code, out) = run(dir, true, "markets", "KALSHI", "--category", "Financials", "--family", "KXS", "--open")
        val (_, text) = run(dir, false, "markets", "KALSHI", "--family", "KXS")
        val (_, none) = run(dir, false, "markets", "KALSHI", "--category", "Weather")

        assertThat(code).isEqualTo(ExitCodes.SUCCESS)
        assertThat(out).contains("\"symbol\":\"KALSHI:KXS_H1_T1\"").contains("\"strikeType\":\"greater\"")
            .contains("\"takerFeeRate\":0.07").doesNotContain("KXS_H2_T1")
        assertThat(text).contains("KALSHI:KXS_H2_T1 KXS-H2 \"above 100\" disabled")
        assertThat(none).contains("no event contracts match")
    }

    @Test
    fun `chain lists the front event's contracts with their quotes`(
        @TempDir dir: Path,
    ) {
        val (code, out) = run(dir, true, "chain", "KALSHI:KXS@front")
        val (_, text) = run(dir, false, "chain", "KALSHI:KXS@front")

        assertThat(code).isEqualTo(ExitCodes.SUCCESS)
        assertThat(out).startsWith("{\"event\":\"KXS-H1\"").contains("\"symbol\":\"KALSHI:KXS_H1_T2\"")
            .contains("\"bid\":0.69,\"ask\":0.71")
        assertThat(text).contains("KXS-H1 closes").contains("KALSHI:KXS_H1_T4 \"above 400\" bid=0.09 ask=0.11 full")
    }

    @Test
    fun `chain refuses a malformed spec and an event that is not open`(
        @TempDir dir: Path,
    ) {
        EventWire.list(fake, (1..4).map { contract(1, it, openMs = openMs) })

        assertThat(run(dir, true, "chain", "KXS@front").second).contains("chain takes VENUE:FAMILY@<front|next>")
        assertThat(run(dir, true, "chain", "KALSHI:KXS@next").second).contains("KALSHI:KXS has no next event open now")
    }

    @Test
    fun `quote and buy resolve a slot to the contract it follows now`(
        @TempDir dir: Path,
    ) {
        val filler =
            thread {
                while (fake.submits.isEmpty()) Thread.sleep(5)
                fake.act { fill(fake.submits.first().clientOrderId, "f1", "10", "0.71", FakeGateway.TIME) }
            }

        val (_, quote) = run(dir, true, "quote", "KALSHI:KXS@front.p70")
        val (_, plain) = run(dir, false, "quote", "KALSHI:KXS_H1_T3")
        val (code, bought) = run(dir, true, "buy", "10", "KALSHI:KXS@front.p70", "--as", "agent")
        filler.join()

        assertThat(quote).contains("\"symbol\":\"KALSHI:KXS@front.p70\",\"contract\":\"KALSHI:KXS_H1_T2\"")
            .contains("\"ask\":0.71")
        assertThat(plain).startsWith("KALSHI:KXS_H1_T3 bid=0.39")
        assertThat(code).isEqualTo(ExitCodes.SUCCESS)
        assertThat(bought).contains("\"symbol\":\"KALSHI:KXS_H1_T2\"")
        assertThat(fake.submits.single().symbol).isEqualTo(code(1, 2))
    }

    @Test
    fun `a paused contract is refused by name and nothing is sent`(
        @TempDir dir: Path,
    ) {
        fake.instruments[code(1, 3)] = contract(1, 3, trading = "disabled", openMs = openMs)

        val (code, out) = run(dir, true, "sell", "10", "KALSHI:KXS_H1_T3", "--limit", "0.39")

        assertThat(code).isEqualTo(ExitCodes.USER_ERROR)
        assertThat(out).contains("KALSHI:KXS_H1_T3 is paused by the venue")
        assertThat(fake.submits).isEmpty()
    }
}
