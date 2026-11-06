package com.qkt.cli.bot

import com.qkt.cli.ExitCodes
import java.nio.file.Path
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/** The read-only `qkt bot` verbs print venue truth, and a failed venue read is an error, never empty. */
class BotQueryCommandTest {
    private val mt5 = BotMt5Stub()

    @TempDir lateinit var dir: Path

    @AfterEach
    fun stop() = mt5.close()

    private fun bot(vararg argv: String) = mt5.bot(dir, *argv)

    @Test
    fun `account prints the venue account as json and as one line`() {
        mt5.responses["/account"] = 200 to BotMt5Stub.ACCOUNT

        val json = bot("account", "--json")
        val text = bot("account")

        assertThat(json.code).isEqualTo(ExitCodes.SUCCESS)
        assertThat(json.out)
            .contains("\"ok\":true", "\"equity\":10000", "\"freeMargin\":9879.5", "\"login\":42", "\"hedging\":true")
        assertThat(text.out.trim())
            .isEqualTo(
                "equity=10000 balance=9800 USD margin=120.5 free=9879.5 level=8298.7 leverage=1:500 (Exness-Demo #42)",
            )
    }

    @Test
    fun `account without margin figures prints dashes`() {
        mt5.responses["/account"] =
            200 to """{"login":1,"server":"S","balance":1,"equity":1,"currency":"USD","leverage":30}"""

        assertThat(bot("account").out).contains("margin=- free=- level=- leverage=1:30")
    }

    @Test
    fun `a failed account read is an error not an empty account`() {
        val run = bot("account", "--json")

        assertThat(run.code).isEqualTo(ExitCodes.USER_ERROR)
        assertThat(run.out).contains("\"ok\":false", "venue account read failed")
    }

    @Test
    fun `positions on a symbol keep only that symbol and print each field`() {
        val other = BotMt5Stub.position(2).replace("XAUUSDm", "EURUSDm")
        mt5.responses["/get_positions"] = 200 to "[${BotMt5Stub.position(1, "bot-x", sl = "2600")},$other]"

        val json = bot("positions", "EXNESS:XAUUSD", "--json")
        val text = bot("positions", "EXNESS:XAUUSD")
        val all = bot("positions", "--json")

        assertThat(json.out).contains("\"ticket\":1", "\"side\":\"BUY\"", "\"sl\":2600", "\"comment\":\"bot-x\"")
        assertThat(json.out).doesNotContain("EURUSDm")
        assertThat(text.out.trim()).isEqualTo("1 XAUUSDm BUY 0.5 @ 2650 sl=2600 tp=0 pnl=12.5")
        assertThat(all.out).contains("\"ticket\":1", "\"ticket\":2")
    }

    @Test
    fun `no positions and a failed positions read read differently`() {
        mt5.responses["/get_positions"] = 200 to "[]"
        assertThat(bot("positions").out.trim()).isEqualTo("no open positions")

        mt5.responses.remove("/get_positions")
        val failed = bot("positions")
        assertThat(failed.code).isEqualTo(ExitCodes.USER_ERROR)
        assertThat(failed.err).contains("venue positions read failed")
    }

    @Test
    fun `orders list resting orders on a symbol as json and text`() {
        mt5.responses["/orders"] = 200 to """{"orders":[${BotMt5Stub.order(9)}],"total":1}"""

        val json = bot("orders", "EXNESS:XAUUSD", "--json")
        val text = bot("orders")

        assertThat(json.out).contains("\"ticket\":9", "\"type\":\"BUY_LIMIT\"", "\"lots\":0.2", "\"price\":2600")
        assertThat(text.out.trim()).isEqualTo("9 XAUUSDm BUY_LIMIT 0.2 @ 2600 sl=2590 tp=2700")
    }

    @Test
    fun `no pending orders and a failed orders read read differently`() {
        mt5.responses["/orders"] = 200 to "[]"
        assertThat(bot("orders", "EXNESS:XAUUSD").out.trim()).isEqualTo("no pending orders")

        mt5.responses.remove("/orders")
        assertThat(bot("orders", "--json").out).contains("venue pending-orders read failed")
    }

    @Test
    fun `quote prints bid ask and spread`() {
        mt5.responses["/symbol_info_tick/XAUUSDm"] = 200 to """{"bid":2650.0,"ask":2650.5,"time":1}"""

        assertThat(bot("quote", "EXNESS:XAUUSD", "--json").out).contains("\"bid\":2650.0", "\"spread\":0.5")
        assertThat(bot("quote", "EXNESS:XAUUSD").out.trim()).isEqualTo("EXNESS:XAUUSD bid=2650.0 ask=2650.5 spread=0.5")
    }

    @Test
    fun `a missing quote names the symbol`() {
        assertThat(
            bot("quote", "EXNESS:XAUUSD", "--json").out,
        ).contains("venue did not return a quote for EXNESS:XAUUSD")
    }

    @Test
    fun `bars print the newest closed bars as json and text`() {
        val json = bot("bars", "EXNESS:XAUUSD", "--tf", "1h", "--count", "2", "--json")
        val text = bot("bars", "EXNESS:XAUUSD", "--tf", "1h", "--count", "2")

        assertThat(json.code).isEqualTo(ExitCodes.SUCCESS)
        assertThat(json.out).contains("\"o\":2650", "\"h\":2655", "\"l\":2645", "\"c\":2652", "\"v\":10")
        assertThat(text.out).contains(":00:00Z o=2650", " h=2655", " l=2645", " c=2652")
        assertThat(mt5.requests).anyMatch { it.startsWith("GET /fetch_data_range") }
    }

    @Test
    fun `history prints closed deals since a date`() {
        mt5.responses["/history_deals_get"] =
            200 to
            """[{"ticket":5,"position_id":1,"symbol":"XAUUSDm","type":1,"entry":1,"volume":0.5,"price":2675,""" +
            """"profit":12.5,"commission":-0.5,"swap":0,"fee":0,"time_msc":1700000000000,"comment":"tp"}]"""

        val json = bot("history", "--since", "30d", "--json")
        val text = bot("history", "--since", "2023-11-01T00:00:00Z")

        assertThat(json.out)
            .contains(
                "\"dealTicket\":5",
                "\"positionTicket\":1",
                "\"side\":\"SELL\"",
                "\"entry\":\"OUT\"",
                "\"profit\":12.5",
            )
        assertThat(text.out.trim()).isEqualTo("2023-11-14T22:13:20Z XAUUSDm SELL 0.5 @ 2675 pnl=12.5")
    }

    @Test
    fun `no deals names the start of the window and a failed read is an error`() {
        mt5.responses["/history_deals_get"] = 200 to "[]"
        assertThat(
            bot("history", "--since", "1700000000000").out.trim(),
        ).isEqualTo("no deals since 2023-11-14T22:13:20Z")

        mt5.responses.remove("/history_deals_get")
        assertThat(bot("history", "--json").out).contains("venue deal-history read failed")
    }
}
