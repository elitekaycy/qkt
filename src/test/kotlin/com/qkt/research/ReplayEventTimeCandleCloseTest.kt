package com.qkt.research

import com.qkt.backtest.Backtest
import com.qkt.candles.TimeWindow
import com.qkt.common.Money
import com.qkt.common.Side
import com.qkt.dsl.compile.AstCompiler
import com.qkt.dsl.parse.Dsl
import com.qkt.dsl.parse.ParseResult
import com.qkt.marketdata.Tick
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * #1134 — replay closes a quiet symbol's ended bar from event time, like the live heartbeat.
 *
 * Two synchronized 5s streams: a dense leader that quotes every second and a sparse
 * follower that quotes once per bar and then goes quiet past the window end. Live closes the
 * follower's bar from the heartbeat within about a second of the window end; replay used to
 * wait for the follower's own next tick, so the group decided seconds late at a different price.
 */
class ReplayEventTimeCandleCloseTest {
    private fun compile(src: String) = AstCompiler().compile((Dsl.parse(src) as ParseResult.Success).value)

    private val src =
        """
        STRATEGY lag VERSION 1
        SYMBOLS
          f = BACKTEST:XAUUSD EVERY 5s,
          x = BACKTEST:AUDUSD EVERY 5s
          SYNCHRONIZE f x
        RULES
          WHEN x.close > x.open AND POSITION.f = 0
          THEN BUY f SIZING 0.01
        """.trimIndent()

    private fun tick(
        symbol: String,
        t: Long,
        price: String,
    ) = Tick(symbol = symbol, price = Money.of(price), timestamp = t)

    /**
     * Bar [0, 5s): the follower prints 0.6400 at 0.1 s and 0.6402 at 2.0 s, then goes quiet
     * until 17.9 s. The leader prints every second at a rising price.
     */
    private fun tape(): List<Tick> =
        buildList {
            add(tick("BACKTEST:AUDUSD", 100L, "0.6400"))
            add(tick("BACKTEST:AUDUSD", 2_000L, "0.6402"))
            for (i in 0..20) add(tick("BACKTEST:XAUUSD", i * 1_000L, "4338.${i.toString().padStart(3, '0')}"))
            add(tick("BACKTEST:AUDUSD", 17_885L, "0.6405"))
        }.sortedBy { it.timestamp }

    @Test
    fun `a synchronized group decides on the first event past the window end, not the sparse symbol's next tick`() {
        val result =
            Backtest(
                strategies = listOf("lag" to compile(src)),
                ticks = tape(),
                candleWindow = TimeWindow.parse("5s"),
            ).run()

        val buy = result.trades.single { it.trade.side == Side.BUY }.trade
        // The follower's [0,5s) bar closes on the leader's 6 s tick — the first event strictly
        // past the window end — so the entry fills against the leader's 6 s quote, one second
        // after the bar ended, instead of 12.9 s later on the follower's next tick.
        assertThat(buy.timestamp).isEqualTo(6_000L)
        assertThat(buy.price).isEqualByComparingTo(Money.of("4338.006"))
    }

    @Test
    fun `ticks sharing a timestamp are all ingested before any of them closes another symbol's bar`() {
        // X quotes at the 5 s boundary before Y does. Y's boundary tick must still be the one
        // that closes Y's bar (and the quote a Y fill sees), as it was before #1134.
        val pair =
            """
            STRATEGY pair VERSION 1
            SYMBOLS
              x = BACKTEST:X EVERY 5s,
              y = BACKTEST:Y EVERY 5s
            RULES
              WHEN y.close > y.open AND POSITION.y = 0
              THEN BUY y SIZING 1
            """.trimIndent()
        val ticks =
            listOf(
                tick("BACKTEST:X", 0L, "100"),
                tick("BACKTEST:Y", 0L, "200"),
                tick("BACKTEST:Y", 2_000L, "200.5"),
                tick("BACKTEST:X", 5_000L, "101"),
                tick("BACKTEST:Y", 5_000L, "201"),
                tick("BACKTEST:X", 6_000L, "101"),
                tick("BACKTEST:Y", 6_000L, "201"),
            )
        val result =
            Backtest(
                strategies = listOf("pair" to compile(pair)),
                ticks = ticks,
                candleWindow = TimeWindow.parse("5s"),
            ).run()
        val buy = result.trades.single { it.trade.side == Side.BUY }.trade
        assertThat(buy.timestamp).isEqualTo(5_000L)
        assertThat(buy.price).isEqualByComparingTo(Money.of("201"))
    }

    @Test
    fun `a dense symbol's own boundary tick still closes its bar and fills against that tick`() {
        // Single-stream control: the close for a symbol that quotes past its own boundary is
        // unchanged — the crossing tick closes the bar and is the quote the fill sees.
        val single =
            """
            STRATEGY dense VERSION 1
            SYMBOLS
              f = BACKTEST:XAUUSD EVERY 5s
            RULES
              WHEN f.close > f.open AND POSITION.f = 0
              THEN BUY f SIZING 0.01
            """.trimIndent()
        val ticks = (0..10).map { i -> tick("BACKTEST:XAUUSD", i * 1_000L, "4338.${i.toString().padStart(3, '0')}") }
        val result =
            Backtest(
                strategies = listOf("dense" to compile(single)),
                ticks = ticks,
                candleWindow = TimeWindow.parse("5s"),
            ).run()
        val buy = result.trades.single { it.trade.side == Side.BUY }.trade
        assertThat(buy.timestamp).isEqualTo(5_000L)
        assertThat(buy.price).isEqualByComparingTo(Money.of("4338.005"))
    }
}
