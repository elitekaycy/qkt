package com.qkt.cli.bot

import com.qkt.cli.Args
import com.qkt.cli.ExitCodes
import java.nio.file.Files
import java.nio.file.Path
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/** `qkt bot close` picks its targets from venue truth and never guesses among several positions. */
class BotCloseCommandTest {
    private val mt5 = BotMt5Stub()

    @TempDir lateinit var dir: Path

    @AfterEach
    fun stop() = mt5.close()

    private fun bot(vararg argv: String) = mt5.bot(dir, "close", "EXNESS:XAUUSD", *argv)

    private fun held(vararg positions: String) {
        mt5.responses["/get_positions"] = 200 to positions.joinToString(",", "[", "]")
    }

    private fun closes() = mt5.requests.filter { it.startsWith("POST /close_position") }

    @Test
    fun `the single open position closes without a flag and is journaled`() {
        held(BotMt5Stub.position(11))
        mt5.responses["/close_position"] = 200 to BotMt5Stub.DONE

        val run = bot("--json")

        assertThat(run.code).isEqualTo(ExitCodes.SUCCESS)
        assertThat(
            run.out.trim(),
        ).isEqualTo("""[{"ok":true,"ticket":11,"deal":77,"price":2651,"retcode":10009,"error":null}]""")
        assertThat(closes().single()).contains("\"ticket\":11")
        val journal = Files.list(dir.resolve("state/bot/manual")).use { it.toList() }.single()
        assertThat(Files.readString(journal)).contains("bot.close", "11", "2651")
    }

    @Test
    fun `a partial close by ticket sends the volume and prints the deal`() {
        held(BotMt5Stub.position(11), BotMt5Stub.position(12))
        mt5.responses["/close_position"] = 200 to BotMt5Stub.DONE

        val run = bot("--ticket", "12", "--partial", "0.2")

        assertThat(run.out.trim()).isEqualTo("CLOSED ticket=12 deal=77 price=2651")
        assertThat(closes().single()).contains("\"ticket\":12").contains("0.2")
    }

    @Test
    fun `a venue refusal prints the reason and fails`() {
        held(BotMt5Stub.position(11))
        mt5.responses["/close_position"] = 200 to BotMt5Stub.REFUSED

        val run = bot()

        assertThat(run.code).isEqualTo(ExitCodes.USER_ERROR)
        assertThat(run.err).contains("CLOSE FAILED ticket=11: ", "No money", "(retcode 10019)")
    }

    @Test
    fun `all closes only the caller's own positions unless any is given`() {
        held(BotMt5Stub.position(11, "bot-agent-1-0001"), BotMt5Stub.position(12, "dsl-trend"))
        mt5.responses["/close_position"] = 200 to BotMt5Stub.DONE

        bot("--all", "--as", "agent")
        assertThat(closes()).hasSize(1).allMatch { it.contains("\"ticket\":11") }

        bot("--all", "--any")
        assertThat(closes()).hasSize(3)
    }

    @Test
    fun `all with nothing of the caller's names what others hold`() {
        held(BotMt5Stub.position(12, "dsl-trend"))
        assertThat(
            bot("--all", "--json").out,
        ).contains("no open positions of 'manual' on EXNESS:XAUUSD (1 held by others; pass --any)")

        held()
        assertThat(bot("--all", "--json").out).contains("no open positions of 'manual' on EXNESS:XAUUSD\"")
    }

    @Test
    fun `nothing or several open positions refuse without closing`() {
        held()
        assertThat(bot("--json").out).contains("no open positions for EXNESS:XAUUSD")

        held(BotMt5Stub.position(11), BotMt5Stub.position(12))
        assertThat(
            bot("--json").out,
        ).contains("multiple positions open for EXNESS:XAUUSD (tickets 11, 12); pass --ticket or --all")
        assertThat(bot("--ticket", "99", "--json").out).contains("no open position with ticket 99")
        assertThat(
            bot("--all", "--any", "--partial", "0.1", "--json").out,
        ).contains("--partial needs a single target ticket")
        assertThat(closes()).isEmpty()
    }

    @Test
    fun `a malformed partial and an unreadable venue refuse`() {
        held(BotMt5Stub.position(11))
        assertThat(bot("--partial", "half", "--json").out).contains("invalid --partial 'half'")

        mt5.responses.remove("/get_positions")
        assertThat(bot("--json").out).contains("refusing to close on unknown state")
        assertThat(closes()).isEmpty()
    }

    @Test
    fun `an unknown query verb is a command failure`() {
        assertThat(BotQueryCommand(Args(arrayOf("nope")), "nope").run()).isEqualTo(ExitCodes.USER_ERROR)
    }
}
