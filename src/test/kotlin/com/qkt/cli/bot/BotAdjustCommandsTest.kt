package com.qkt.cli.bot

import com.qkt.cli.ExitCodes
import java.nio.file.Files
import java.nio.file.Path
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/** `qkt bot modify` keeps the level it was not told to move; `qkt bot cancel --all` cancels only its own. */
class BotAdjustCommandsTest {
    private val mt5 = BotMt5Stub()

    @TempDir lateinit var dir: Path

    @AfterEach
    fun stop() = mt5.close()

    private fun bot(vararg argv: String) = mt5.bot(dir, *argv)

    private fun sent(prefix: String) = mt5.requests.filter { it.startsWith(prefix) }

    @Test
    fun `modify moves the stop and keeps the venue's take profit`() {
        mt5.responses["/get_positions"] = 200 to "[${BotMt5Stub.position(11, sl = "2600", tp = "2700")}]"
        mt5.responses["/modify_sl_tp"] = 200 to BotMt5Stub.DONE

        val run = bot("modify", "EXNESS:XAUUSD", "--ticket", "11", "--sl", "2610")

        assertThat(run.code).isEqualTo(ExitCodes.SUCCESS)
        assertThat(run.out.trim()).isEqualTo("MODIFIED ticket=11 sl=2610 tp=2700")
        assertThat(sent("POST /modify_sl_tp").single()).contains("\"sl\":2610", "\"tp\":2700")
        val journal = Files.list(dir.resolve("state/bot/manual")).use { it.toList() }.single()
        assertThat(Files.readString(journal)).contains("bot.modify", "2610")
    }

    @Test
    fun `modify of a position without levels sends only the new target`() {
        mt5.responses["/get_positions"] = 200 to "[${BotMt5Stub.position(11)}]"
        mt5.responses["/modify_sl_tp"] = 200 to BotMt5Stub.DONE

        val run = bot("modify", "EXNESS:XAUUSD", "--ticket", "11", "--tp", "2720", "--json")

        assertThat(run.out.trim()).isEqualTo("""{"ok":true,"retcode":10009,"error":null}""")
        assertThat(bot("modify", "EXNESS:XAUUSD", "--ticket", "11", "--tp", "2720").out.trim())
            .isEqualTo("MODIFIED ticket=11 sl=- tp=2720")
    }

    @Test
    fun `a refused modify prints the venue reason`() {
        mt5.responses["/get_positions"] = 200 to "[${BotMt5Stub.position(11)}]"
        mt5.responses["/modify_sl_tp"] = 200 to BotMt5Stub.REFUSED

        val run = bot("modify", "EXNESS:XAUUSD", "--ticket", "11", "--sl", "2600")

        assertThat(run.code).isEqualTo(ExitCodes.USER_ERROR)
        assertThat(run.err).contains("FAILED: ", "No money", "(retcode 10019)")
    }

    @Test
    fun `modify refuses bad levels unknown tickets and unreadable venues`() {
        assertThat(bot("modify", "EXNESS:XAUUSD", "--ticket", "11", "--json").out).contains("pass --sl and/or --tp")
        assertThat(
            bot("modify", "EXNESS:XAUUSD", "--ticket", "11", "--sl", "x", "--json").out,
        ).contains("invalid --sl 'x'")
        assertThat(
            bot("modify", "EXNESS:XAUUSD", "--ticket", "11", "--tp", "y", "--json").out,
        ).contains("invalid --tp 'y'")
        assertThat(bot("modify", "EXNESS:XAUUSD", "--ticket", "11", "--sl", "1", "--json").out)
            .contains("venue positions read failed")
        mt5.responses["/get_positions"] = 200 to "[]"
        assertThat(bot("modify", "EXNESS:XAUUSD", "--ticket", "11", "--sl", "1", "--json").out)
            .contains("no open position with ticket 11")
        assertThat(sent("POST /modify_sl_tp")).isEmpty()
    }

    @Test
    fun `cancel by order ticket cancels that order`() {
        mt5.responses["/orders/9"] = 200 to """{"ok":true}"""

        val run = bot("cancel", "EXNESS:XAUUSD", "--order", "9")

        assertThat(run.code).isEqualTo(ExitCodes.SUCCESS)
        assertThat(run.out.trim()).isEqualTo("CANCELLED ticket=9")
        assertThat(sent("DELETE /orders/9")).hasSize(1)
    }

    @Test
    fun `a refused cancel fails and says so`() {
        val text = bot("cancel", "EXNESS:XAUUSD", "--order", "9")
        val json = bot("cancel", "EXNESS:XAUUSD", "--order", "9", "--json")

        assertThat(text.code).isEqualTo(ExitCodes.USER_ERROR)
        assertThat(text.err.trim()).isEqualTo("CANCEL FAILED ticket=9")
        assertThat(json.out.trim()).isEqualTo("""[{"ok":false,"ticket":9}]""")
    }

    @Test
    fun `cancel all touches only the caller's own orders unless any is given`() {
        mt5.responses["/orders"] =
            200 to "[${BotMt5Stub.order(9, "bot-agent-1-0001")},${BotMt5Stub.order(10, "dsl-trend")}]"
        mt5.responses["/orders/9"] = 200 to """{"ok":true}"""
        mt5.responses["/orders/10"] = 200 to """{"ok":true}"""

        val mine = bot("cancel", "EXNESS:XAUUSD", "--all", "--as", "agent", "--json")
        assertThat(mine.out.trim()).isEqualTo("""[{"ok":true,"ticket":9}]""")

        bot("cancel", "EXNESS:XAUUSD", "--all", "--any")
        assertThat(sent("DELETE /orders/10")).hasSize(1)
    }

    @Test
    fun `cancel refuses without a target and names what others placed`() {
        assertThat(bot("cancel", "EXNESS:XAUUSD", "--json").out).contains("pass --order <ticket> or --all")
        assertThat(bot("cancel", "EXNESS:XAUUSD", "--all", "--json").out).contains("venue pending-orders read failed")
        mt5.responses["/orders"] = 200 to "[${BotMt5Stub.order(10, "dsl-trend")}]"
        assertThat(bot("cancel", "EXNESS:XAUUSD", "--all", "--json").out)
            .contains("no pending orders of 'manual' on EXNESS:XAUUSD (1 placed by others; pass --any)")
        mt5.responses["/orders"] = 200 to "[]"
        assertThat(bot("cancel", "EXNESS:XAUUSD", "--all", "--json").out)
            .contains("no pending orders of 'manual' on EXNESS:XAUUSD\"")
        assertThat(sent("DELETE")).isEmpty()
    }
}
