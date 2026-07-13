package com.qkt.cli.bot

import com.qkt.cli.Args
import com.qkt.cli.ExitCodes
import com.qkt.common.Side
import com.qkt.trade.BotTif
import com.qkt.trade.ExitSpec
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class BotCommandTest {
    private fun tradeArgs(vararg argv: String) = Args(arrayOf("buy") + argv)

    @Test
    fun `parses lots symbol and exits from positionals and flags`() {
        val intent =
            parseBotIntent(
                tradeArgs("0.5", "EXNESS:XAUUSD", "--sl", "by:30", "--tp", "rr:2"),
                Side.BUY,
            )
        assertThat(intent.lots).isEqualByComparingTo("0.5")
        assertThat(intent.qktSymbol).isEqualTo("EXNESS:XAUUSD")
        assertThat(intent.sl).isEqualTo(ExitSpec.By(BigDecimal("30")))
        assertThat(intent.tp).isEqualTo(ExitSpec.Rr(BigDecimal("2")))
        assertThat(intent.tif).isEqualTo(BotTif.GTC)
    }

    @Test
    fun `parses sizing form without lots`() {
        val intent =
            parseBotIntent(
                tradeArgs("EXNESS:XAUUSD", "--sizing", "2 % OF EQUITY"),
                Side.SELL,
            )
        assertThat(intent.lots).isNull()
        assertThat(intent.sizingDsl).isEqualTo("2 % OF EQUITY")
        assertThat(intent.side).isEqualTo(Side.SELL)
    }

    @Test
    fun `parses pending entries tif and expiry`() {
        val limit =
            parseBotIntent(
                tradeArgs("0.2", "EXNESS:XAUUSD", "--limit", "2680", "--tif", "day"),
                Side.SELL,
            )
        assertThat(limit.limitPrice).isEqualByComparingTo("2680")
        assertThat(limit.tif).isEqualTo(BotTif.DAY)

        val gtd =
            parseBotIntent(
                tradeArgs("0.2", "EXNESS:XAUUSD", "--stop", "2700", "--expires", "2026-07-14T00:00:00Z"),
                Side.BUY,
            )
        assertThat(gtd.tif).isEqualTo(BotTif.GTD)
        assertThat(gtd.expiresAtMs)
            .isEqualTo(
                java.time.Instant
                    .parse("2026-07-14T00:00:00Z")
                    .toEpochMilli(),
            )

        val stopLimit =
            parseBotIntent(
                tradeArgs("0.2", "EXNESS:XAUUSD", "--stop-limit", "2700:2701"),
                Side.BUY,
            )
        assertThat(stopLimit.stopPrice).isEqualByComparingTo("2700")
        assertThat(stopLimit.stopLimitPrice).isEqualByComparingTo("2701")
    }

    @Test
    fun `rejects malformed symbol and tif`() {
        assertThatThrownBy { parseBotIntent(tradeArgs("0.5", "XAUUSD"), Side.BUY) }
            .hasMessageContaining("BROKER:SYMBOL")
        assertThatThrownBy {
            parseBotIntent(tradeArgs("0.5", "EXNESS:XAUUSD", "--tif", "week"), Side.BUY)
        }.hasMessageContaining("--tif")
    }

    @Test
    fun `dry run prints canonical dsl without touching the network`() {
        val out = ByteArrayOutputStream()
        val prev = System.out
        System.setOut(PrintStream(out))
        val code =
            try {
                BotCommand(
                    Args(
                        arrayOf(
                            "bot",
                            "buy",
                            "0.5",
                            "EXNESS:XAUUSD",
                            "--sl",
                            "by:30",
                            "--tp",
                            "by:60",
                            "--dry-run",
                            "--json",
                        ),
                    ),
                ).run()
            } finally {
                System.setOut(prev)
            }
        assertThat(code).isEqualTo(ExitCodes.SUCCESS)
        val printed = out.toString()
        assertThat(printed).contains("\"dryRun\":true")
        assertThat(printed).contains("BRACKET { STOP LOSS BY 30, TAKE PROFIT BY 60 }")
        assertThat(printed).contains("\"sha256\":")
    }

    @Test
    fun `unknown verb is an argument error`() {
        assertThat(BotCommand(Args(arrayOf("bot", "yolo"))).run()).isEqualTo(ExitCodes.ARG_ERROR)
    }

    @Test
    fun `since accepts days iso and epoch`() {
        val now = 1_000_000_000_000L
        assertThat(parseSince(null, now)).isEqualTo(now - 7L * 86_400_000L)
        assertThat(parseSince("30d", now)).isEqualTo(now - 30L * 86_400_000L)
        assertThat(parseSince("123456", now)).isEqualTo(123456L)
        assertThat(parseSince("2026-07-01T00:00:00Z", now))
            .isEqualTo(
                java.time.Instant
                    .parse("2026-07-01T00:00:00Z")
                    .toEpochMilli(),
            )
        assertThatThrownBy { parseSince("lastweek", now) }.hasMessageContaining("--since")
    }
}
