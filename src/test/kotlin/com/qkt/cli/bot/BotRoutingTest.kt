package com.qkt.cli.bot

import com.qkt.cli.Args
import com.qkt.cli.ExitCodes
import com.qkt.trade.session.BotSessionDescriptor
import com.qkt.trade.session.BotSessionFiles
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Path
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/** Where a verb goes: never to the venue by accident, never to a session that is gone. */
class BotRoutingTest {
    private fun bot(vararg tokens: String): Pair<Int, String> {
        val buffer = ByteArrayOutputStream()
        val original = System.out
        System.setOut(PrintStream(buffer))
        val code =
            try {
                BotCommand(Args(arrayOf("bot", *tokens))).run()
            } finally {
                System.setOut(original)
            }
        return code to buffer.toString()
    }

    @Test
    fun `a verb naming a session that is not running fails and sends nothing`(
        @TempDir tmp: Path,
    ) {
        val (code, out) = bot("buy", "0.1", "EXNESS:XAUUSD", "--run", "gone", "--state-dir", tmp.toString(), "--json")

        assertThat(code).isEqualTo(ExitCodes.USER_ERROR)
        assertThat(out).contains("session gone is not running").contains("nothing was sent")
    }

    @Test
    fun `a descriptor whose process is gone is not a running session`(
        @TempDir tmp: Path,
    ) {
        val stateRoot = tmp.resolve("state")
        BotSessionFiles.write(stateRoot, BotSessionDescriptor("dead", 1, "t", "backtest", Int.MAX_VALUE.toLong()))

        assertThat(BotSessionFiles.resolve(stateRoot, null) { null }).isNull()
        assertThat(BotSessionFiles.resolve(stateRoot, "dead") { null }).isNull()
    }

    @Test
    fun `close without --run reaches the venue even while a session runs`(
        @TempDir tmp: Path,
    ) {
        val h = BotSessionHarness(tmp).start()
        val buffer = ByteArrayOutputStream()
        val original = System.out
        System.setOut(PrintStream(buffer))
        try {
            BotCommand(
                Args(arrayOf("bot", "close", "EXNESS:XAUUSD", "--state-dir", h.stateDir.toString(), "--json")),
            ).run()
        } finally {
            System.setOut(original)
        }
        h.finish()

        assertThat(buffer.toString()).doesNotContain("\"queued\":true")
    }
}
