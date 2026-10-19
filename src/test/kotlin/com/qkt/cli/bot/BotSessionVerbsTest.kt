package com.qkt.cli.bot

import com.qkt.cli.ExitCodes
import java.nio.file.Files
import java.nio.file.Path
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/** The verbs an agent manages a session with: close, cancel, submit, intents, orders, positions. */
class BotSessionVerbsTest {
    private val gold = "EXNESS:XAUUSD"

    @Test
    fun `close in a session closes through the engine and next reports the fills`(
        @TempDir tmp: Path,
    ) {
        val h = BotSessionHarness(tmp).start()
        h.bot("next", gold)
        h.bot("buy", "0.1", gold, "--as", "brain")
        val (_, afterBuy) = h.bot("next", gold)
        val (closeCode, closed) = h.bot("close", gold, "--as", "brain")
        h.bot("next", gold)
        val (_, intents) = h.bot("intents")
        h.finish()

        assertThat(afterBuy).contains("\"events\":[").contains("\"kind\":\"fill\"").contains("\"intentId\":\"int-1\"")
        assertThat(closeCode).isEqualTo(ExitCodes.SUCCESS)
        assertThat(closed).contains("\"queued\":true")
        assertThat(
            intents,
        ).contains("\"intentId\":\"int-1\"").contains("\"intentId\":\"int-2\"").doesNotContain("\"skipped\"")
        val trades = Files.readAllLines(h.out.resolve("trades.csv")).drop(1).filter { it.contains("brain") }
        assertThat(trades).hasSize(2)
    }

    @Test
    fun `submit sends any action text`(
        @TempDir tmp: Path,
    ) {
        val h = BotSessionHarness(tmp).start()
        h.bot("next", gold)
        val (code, reply) = h.bot("submit", "BUY x SIZING 0.1 EXIT AFTER 30m", "--symbol", gold, "--as", "brain")
        repeat(6) { h.bot("next", gold) }
        h.finish()

        assertThat(code).isEqualTo(ExitCodes.SUCCESS)
        assertThat(reply).contains("\"queued\":true")
        assertThat(Files.readAllLines(h.out.resolve("trades.csv")).drop(1).filter { it.contains("brain") }).hasSize(2)
    }

    @Test
    fun `cancel cancels the identity's working orders and orders lists them first`(
        @TempDir tmp: Path,
    ) {
        val h = BotSessionHarness(tmp).start()
        h.bot("next", gold)
        h.bot("buy", "0.1", gold, "--limit", "1700", "--as", "brain")
        h.bot("next", gold)
        val (_, working) = h.bot("orders")
        h.bot("cancel", gold, "--as", "brain")
        h.bot("next", gold)
        val (_, after) = h.bot("orders")
        val (_, intents) = h.bot("intents")
        h.finish()

        assertThat(working).contains("\"intentId\":\"int-1\"")
        assertThat(after.trim()).isEqualTo("[]")
        assertThat(intents).contains("\"state\":\"cancelled\"")
    }

    @Test
    fun `a risk rejection is visible in intents with the rule`(
        @TempDir tmp: Path,
    ) {
        val h = BotSessionHarness(tmp).start()
        h.bot("next", gold)
        h.bot("buy", "50", gold, "--as", "brain")
        h.bot("next", gold)
        val (_, intents) = h.bot("intents")
        h.finish()

        assertThat(intents).contains("\"state\":\"rejected\"")
    }

    @Test
    fun `positions keep symbol and qty and add each identity's legs`(
        @TempDir tmp: Path,
    ) {
        val h = BotSessionHarness(tmp).start()
        h.bot("next", gold)
        h.bot("buy", "0.1", gold, "--as", "brain")
        h.bot("next", gold)
        val (_, positions) = h.bot("positions")
        h.finish()

        assertThat(
            positions,
        ).contains("\"symbol\":\"$gold\"").contains("\"qty\":0.1").contains("\"identity\":\"brain\"")
    }

    @Test
    fun `modify and a partial close are refused in a session with what to do instead`(
        @TempDir tmp: Path,
    ) {
        val h = BotSessionHarness(tmp).start()
        h.bot("next", gold)
        val (modifyCode, modify) = h.bot("modify", gold, "--ticket", "1", "--sl", "1800")
        val (partialCode, partial) = h.bot("close", gold, "--partial", "0.05")
        h.finish()

        assertThat(modifyCode).isEqualTo(ExitCodes.USER_ERROR)
        assertThat(modify).contains("STEP TO BREAKEVEN")
        assertThat(partialCode).isEqualTo(ExitCodes.USER_ERROR)
        assertThat(partial).contains("qkt bot sell")
    }

    @Test
    fun `a session over two timeframes serves each series`(
        @TempDir tmp: Path,
    ) {
        val h = BotSessionHarness(tmp, tf = "15m,1h").start()
        val (_, hour) = h.bot("next", gold, "--tf", "1h")
        val (_, quarter) = h.bot("next", gold)
        val (code, unknown) = h.bot("next", gold, "--tf", "4h")
        h.finish()

        assertThat(hour).contains("\"timeMs\":1704153600000").contains("\"endMs\":1704157200000")
        assertThat(quarter).contains("\"timeMs\":1704153600000").contains("\"endMs\":1704154500000")
        assertThat(code).isEqualTo(ExitCodes.USER_ERROR)
        assertThat(unknown).contains("not a session series")
    }
}
