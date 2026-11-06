package com.qkt.cli.bot

import com.qkt.cli.Args
import com.qkt.cli.ExitCodes
import com.qkt.common.Side
import com.qkt.trade.BotTif
import java.nio.file.Path
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/** One-shot `qkt bot` verbs with no session: trade, eval and help, through the real entry point. */
class BotOneShotTest {
    private val mt5 = BotMt5Stub()

    @TempDir lateinit var dir: Path

    @AfterEach
    fun stop() = mt5.close()

    private fun bot(vararg argv: String) = mt5.bot(dir, *argv)

    private fun quoted() {
        mt5.responses["/symbol_info_tick/XAUUSDm"] = 200 to """{"bid":2650.0,"ask":2650.5,"time":1}"""
        mt5.responses["/symbol_info/XAUUSDm"] =
            200 to
            """{"ask":2650.5,"bid":2650.0,"digits":2,"point":0.01,"trade_stops_level":0,""" +
            """"volume_min":0.01,"volume_step":0.01,"trade_contract_size":100}"""
        mt5.responses["/account"] = 200 to BotMt5Stub.ACCOUNT
    }

    @Test
    fun `an accepted buy prints the ticket deal and its exits`() {
        quoted()
        mt5.responses["/order"] =
            200 to """{"result":{"retcode":10009,"order":123,"deal":456,"price":2650.5,"comment":"done"}}"""

        val run = bot("buy", "0.5", "EXNESS:XAUUSD", "--sl", "at:2600", "--tp", "at:2700")

        assertThat(run.code).isEqualTo(ExitCodes.SUCCESS)
        assertThat(run.out)
            .contains(
                "ACCEPTED EXNESS:XAUUSD BUY 0.5",
                "ticket=123",
                "deal=456",
                "price=2650.5",
                " sl=2600",
                " tp=2700",
            )
        assertThat(mt5.requests.single { it.startsWith("POST /order") }).contains("\"symbol\":\"XAUUSDm\"")
    }

    @Test
    fun `an accepted buy without exits prints no levels`() {
        quoted()
        mt5.responses["/order"] =
            200 to """{"result":{"retcode":10009,"order":124,"deal":457,"price":2650.5,"comment":"done"}}"""

        val run = bot("buy", "0.5", "EXNESS:XAUUSD")

        assertThat(run.out).contains("ticket=124").doesNotContain(" sl=").doesNotContain(" tp=")
    }

    @Test
    fun `a rejected sell prints the venue reason on stderr`() {
        quoted()
        mt5.responses["/order"] = 200 to BotMt5Stub.REFUSED

        val run = bot("sell", "0.5", "EXNESS:XAUUSD")

        assertThat(run.code).isEqualTo(ExitCodes.USER_ERROR)
        assertThat(run.err).contains("REJECTED: ", "No money", "(retcode 10019)")
    }

    @Test
    fun `a text dry run prints the canonical dsl and its hash`() {
        val run = bot("sell", "0.5", "EXNESS:XAUUSD", "--dry-run")

        assertThat(run.code).isEqualTo(ExitCodes.SUCCESS)
        assertThat(run.out).contains("SELL", "sha256: ", "(dry run — nothing submitted)")
        assertThat(mt5.requests).isEmpty()
    }

    @Test
    fun `an explicit gtc tif parses`() {
        val intent = parseBotIntent(Args(arrayOf("buy", "0.5", "EXNESS:XAUUSD", "--tif", "gtc")), Side.BUY)

        assertThat(intent.tif).isEqualTo(BotTif.GTC)
    }

    @Test
    fun `eval prints the indicator over venue bars`() {
        val json = bot("eval", "sma(1)", "EXNESS:XAUUSD", "--tf", "1h", "--count", "3", "--json")
        val text = bot("eval", "sma(1)", "EXNESS:XAUUSD", "--tf", "1h", "--count", "3")

        assertThat(json.code).isEqualTo(ExitCodes.SUCCESS)
        assertThat(json.out)
            .contains(
                "\"ok\":true",
                "\"expression\":\"sma(1)\"",
                "\"tf\":\"1h\"",
                "\"value\":2652",
                "\"lastClose\":2652",
            ).contains("\"error\":null")
        assertThat(text.code).isEqualTo(ExitCodes.SUCCESS)
        assertThat(text.out).contains("sma(1) on EXNESS:XAUUSD", "= 2652 (over ")
    }

    @Test
    fun `eval of an indicator that is not warm fails with how many bars it needs`() {
        val json = bot("eval", "sma(500)", "EXNESS:XAUUSD", "--tf", "1h", "--count", "3", "--json")
        val text = bot("eval", "sma(500)", "EXNESS:XAUUSD", "--tf", "1h", "--count", "3")

        assertThat(json.code).isEqualTo(ExitCodes.USER_ERROR)
        assertThat(json.out).contains("\"ok\":false", "\"value\":null", "indicator not ready: needs 500 bars, got ")
        assertThat(text.code).isEqualTo(ExitCodes.USER_ERROR)
        assertThat(text.err).contains("qkt bot: indicator not ready: needs 500 bars")
    }

    @Test
    fun `eval refuses two-series and non-price indicators`() {
        assertThatThrownBy { evalIndicator("correlation(20)", emptyList()) }
            .hasMessageContaining("two-series indicators like CORRELATION are not supported one-shot")
        assertThatThrownBy { evalIndicator("runlength_where()", emptyList()) }
            .hasMessageContaining("needs a BOOLEAN_SERIES feed")
    }

    @Test
    fun `help and no verb print the verb reference`() {
        val help = bot("help")

        assertThat(help.code).isEqualTo(ExitCodes.SUCCESS)
        assertThat(help.out).contains("qkt bot — trade through qkt", "bot eval \"ema(21)\"", "SESSION")
        assertThat(BotCommand(Args(arrayOf("bot"))).run()).isEqualTo(ExitCodes.SUCCESS)
    }

    @Test
    fun `next intents and submit need a running session`() {
        val run = bot("intents", "--json")

        assertThat(run.code).isEqualTo(ExitCodes.USER_ERROR)
        assertThat(run.out).contains("bot intents needs a running session (qkt bot session start)")
    }

    @Test
    fun `json helpers encode lists numbers and other values`() {
        assertThat(jsonValue(listOf(1, "a", null))).isEqualTo("[1,\"a\",null]")
        assertThat(jsonValue(2.5)).isEqualTo("\"2.5\"")
        assertThat(jsonString("a\"b\\c\nd")).isEqualTo("\"a\\\"b\\\\c\\nd\"")
    }

    @Test
    fun `a session client names the run and mode of its descriptor`() {
        val client = BotSessionClient(com.qkt.trade.session.BotSessionDescriptor("r1", 1, "tok", "live", 7L))

        assertThat(client.runId).isEqualTo("r1")
        assertThat(client.mode).isEqualTo("live")
    }
}
