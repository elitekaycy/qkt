package com.qkt.cli.bot

import com.qkt.cli.Args
import com.qkt.cli.ExitCodes
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Path
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/** Read verbs, dry runs and refusals routed through a running backtest session. */
class BotSessionRoutingTest {
    private val gold = "EXNESS:XAUUSD"

    @Test
    fun `quote account bars and session status answer from the session`(
        @TempDir tmp: Path,
    ) {
        val h = BotSessionHarness(tmp).start()
        val (noQuoteCode, noQuote) = h.bot("quote", gold)
        h.bot("next", gold)
        val (quoteCode, quote) = h.bot("quote", gold)
        val (_, account) = h.bot("account")
        val (_, bars) = h.bot("bars", gold, "--count", "3", "--tf", "15m")
        val (statusCode, status) = h.bot("session", "status")
        h.finish()

        assertThat(noQuoteCode).isEqualTo(ExitCodes.USER_ERROR)
        assertThat(noQuote).contains("\"ok\":false", "no quote yet for $gold")
        assertThat(quoteCode).isEqualTo(ExitCodes.SUCCESS)
        assertThat(quote).contains("\"ok\":true", "\"symbol\":\"$gold\"", "\"bid\":", "\"ask\":")
        assertThat(account).contains("\"run\":\"harness\"", "\"identities\":[\"brain\"]", "\"symbols\":[\"$gold\"]")
        assertThat(bars).startsWith("[")
        assertThat(statusCode).isEqualTo(ExitCodes.SUCCESS)
        assertThat(status).contains("\"api\":2", "\"finished\":false")
    }

    @Test
    fun `dry runs render the dsl and queue nothing`(
        @TempDir tmp: Path,
    ) {
        val h = BotSessionHarness(tmp).start()
        h.bot("next", gold)
        val (_, buy) = h.bot("sell", "0.1", gold, "--as", "brain", "--dry-run")
        val (_, closeAll) = h.bot("close", "--all", "--as", "brain", "--dry-run")
        val (_, cancelAll) = h.bot("cancel", "--all", "--as", "brain", "--dry-run")
        val (_, submit) = h.bot("submit", "BUY a SIZING 0.1", "--symbols", "a=$gold", "--dry-run")
        val (_, intents) = h.bot("intents")
        h.finish()

        assertThat(buy).contains("\"dryRun\":true", "SELL x SIZING 0.1", "\"as\":\"brain\"")
        assertThat(closeAll).contains("\"dryRun\":true", "CLOSE_ALL")
        assertThat(cancelAll).contains("\"dryRun\":true", "CANCEL_ALL")
        assertThat(submit).contains("\"dryRun\":true", "SIZING 0.1", "\"as\":\"manual\"")
        assertThat(intents.trim()).isEqualTo("[]")
    }

    @Test
    fun `close cancel and submit without a target are refused with guidance`(
        @TempDir tmp: Path,
    ) {
        val h = BotSessionHarness(tmp).start()
        val close = h.bot("close")
        val closeTicket = h.bot("close", gold, "--ticket", "4")
        val cancel = h.bot("cancel")
        val cancelOrder = h.bot("cancel", gold, "--order", "4")
        val submit = h.bot("submit", "CLOSE x")
        h.finish()

        assertThat(close.second).contains("pass BROKER:SYMBOL, or --all to close every position")
        assertThat(closeTicket.second).contains("in a session, close by symbol")
        assertThat(cancel.second).contains("pass BROKER:SYMBOL, or --all to cancel every working order")
        assertThat(cancelOrder.second).contains("in a session, cancel by symbol")
        assertThat(submit.second).contains("pass --symbol BROKER:SYMBOL (alias x)")
        assertThat(
            listOf(close, closeTicket, cancel, cancelOrder, submit),
        ).allMatch { it.first == ExitCodes.USER_ERROR }
    }

    @Test
    fun `session status and finish with no session running fail`(
        @TempDir tmp: Path,
    ) {
        val state = tmp.resolve("state").toString()

        assertThat(bot("session", "status", "--state-dir", state, "--json").second).contains("no bot session running")
        assertThat(bot("session", "finish", "--state-dir", state, "--json").second).contains("no bot session running")
    }

    @Test
    fun `an unknown session subcommand prints the usage`() {
        val (code, out) = bot("session", "pause", "--json")

        assertThat(code).isEqualTo(ExitCodes.USER_ERROR)
        assertThat(out).contains("usage: qkt bot session start|wait|status|finish|replay (got 'pause')")
    }

    @Test
    fun `a backtest start whose series cannot parse fails before serving`(
        @TempDir tmp: Path,
    ) {
        val (code, out) =
            bot(
                "session",
                "start",
                "--backtest",
                "--symbols",
                "EXNESS:XA\"U",
                "--tf",
                "15m",
                "--from",
                "2024-01-02",
                "--to",
                "2024-01-03",
                "--state-dir",
                tmp.resolve("state").toString(),
                "--json",
            )

        assertThat(code).isEqualTo(ExitCodes.USER_ERROR)
        assertThat(out).contains("\"ok\":false", "session streams failed to parse")
    }

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
}
