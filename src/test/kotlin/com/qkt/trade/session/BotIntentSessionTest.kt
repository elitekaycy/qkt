package com.qkt.trade.session

import com.qkt.backtest.Backtest
import com.qkt.backtest.BacktestResult
import com.qkt.common.Side
import com.qkt.marketdata.Tick
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

/** Session intents run as DSL actions inside the engine: brackets, closes, timed exits, sizing. */
class BotIntentSessionTest {
    private val gold = "EXNESS:XAUUSD"

    private val goldSpec =
        object : com.qkt.instrument.InstrumentRegistry {
            override fun lookup(qktSymbol: String) =
                com.qkt.instrument.InstrumentMeta(
                    qktSymbol = qktSymbol,
                    contractSize = BigDecimal("100"),
                    volumeStep = BigDecimal("0.01"),
                    volumeMin = BigDecimal("0.01"),
                    volumeMax = null,
                    pointSize = BigDecimal("0.01"),
                    digits = 2,
                    tradeStopsLevelPoints = 0,
                )
        }

    /** One tick at +1 s and one at +30 s of each 1m bar, at [prices] (one per bar). */
    private fun ticks(prices: List<Int>): List<Tick> =
        prices.flatMapIndexed { bar, p ->
            val base = bar * 60_000L
            listOf(Tick(gold, BigDecimal(p), base + 1_000L), Tick(gold, BigDecimal(p), base + 30_000L))
        }

    private fun session(
        prices: List<Int>,
        identities: List<String> = listOf("brain"),
    ): BotRunSession {
        val streams = BotSessionStreams(listOf(gold), listOf("1m"))
        val history = BarHistory(capacity = 100)
        val recorder = BotSessionRecorder(history)
        val strategies = BotSessionIdentities.compile(streams, identities)
        val engine =
            Backtest(
                strategies = strategies.map { (id, s) -> id to s } + (BotSessionRecorder.ID to recorder),
                ticks = ticks(prices),
                candleWindow = streams.baseWindow,
                startingBalance = BigDecimal("10000"),
                instruments = goldSpec,
            ).toEngine()
        return BotRunSession("t", ReplayBotRunBackend(engine), strategies, streams, history, recorder)
    }

    private fun intent(action: String) =
        "STRATEGY bot VERSION 1\n\nSYMBOLS\n    x = $gold EVERY 1m\n\nRULES\n    WHEN true\n    THEN $action\n"

    private fun BotRunSession.drain(): BacktestResult {
        while (next(gold) != null) Unit
        return finish()!!
    }

    @Test
    fun `buy with bracket opens and the stop closes it`() {
        val s = session(listOf(2400, 2401, 2402, 2390, 2390, 2390))
        s.next(gold)
        s.submit("brain", intent("BUY x SIZING 1 BRACKET { STOP LOSS BY 5, TAKE PROFIT BY 50 }"))

        val trades = s.drain().trades

        assertThat(trades.map { it.trade.side }).containsExactly(Side.BUY, Side.SELL)
        assertThat(trades.all { it.strategyId == "brain" }).isTrue
        assertThat(trades.last().trade.price).isLessThan(trades.first().trade.price)
    }

    @Test
    fun `close flattens the identity only`() {
        val s = session(listOf(2400, 2401, 2402, 2403, 2404, 2405), identities = listOf("brain", "other"))
        s.next(gold)
        s.submit("brain", intent("BUY x SIZING 0.1"))
        s.submit("other", intent("BUY x SIZING 0.2"))
        s.next(gold)
        s.next(gold)
        s.submit("brain", intent("CLOSE x"))

        val result = s.drain()

        assertThat(
            result.trades.filter { it.strategyId == "brain" }.map { it.trade.side },
        ).containsExactly(Side.BUY, Side.SELL)
        assertThat(result.trades.filter { it.strategyId == "other" }.map { it.trade.side }).containsExactly(Side.BUY)
    }

    @Test
    fun `exit after closes on time`() {
        val s = session(listOf(2400, 2401, 2402, 2403, 2404, 2405, 2406, 2407))
        s.next(gold)
        s.submit("brain", intent("BUY x SIZING 1 EXIT AFTER 2m"))

        val trades = s.drain().trades

        assertThat(trades.map { it.trade.side }).containsExactly(Side.BUY, Side.SELL)
        assertThat(trades.last().trade.timestamp - trades.first().trade.timestamp).isBetween(120_000L, 180_000L)
    }

    @Test
    fun `pct of equity sizes in the engine`() {
        val s = session(listOf(2400, 2400, 2400, 2400))
        s.next(gold)
        s.submit("brain", intent("BUY x SIZING 50 PCT OF EQUITY"))

        val result = s.drain()
        assertThat(s.fates().single().state).withFailMessage("%s", s.fates()).isEqualTo("fired")
        assertThat(result.rejections).withFailMessage("%s", result.rejections).isEmpty()
        val buy = result.trades.first()

        // 50% of 10,000 = 5,000 over 2,400 x 100 per lot = 0.0208 lots, floored to the 0.01 step.
        assertThat(buy.trade.quantity).isEqualByComparingTo("0.02")
    }

    @Test
    fun `stop only bracket is refused at intent`() {
        val s = session(listOf(2400, 2401))
        s.next(gold)

        assertThatThrownBy { s.submit("brain", intent("BUY x SIZING 1 BRACKET { STOP LOSS BY 5 }")) }
            .hasMessageContaining("BRACKET requires both STOP LOSS and TAKE PROFIT")
    }

    @Test
    fun `two intents in one bar fire in order on the same tick`() {
        val s = session(listOf(2400, 2401, 2402))
        s.next(gold)
        s.submit("brain", intent("BUY x SIZING 0.1"))
        s.submit("brain", intent("BUY x SIZING 0.2"))

        val trades = s.drain().trades

        assertThat(
            trades.map {
                it.trade.quantity
                    .stripTrailingZeros()
                    .toPlainString()
            },
        ).containsExactly("0.1", "0.2")
        assertThat(trades[0].trade.timestamp).isEqualTo(trades[1].trade.timestamp)
        assertThat(s.fates().map { it.state }).containsOnly("fired")
    }

    @Test
    fun `an intent before any closed bar is skipped with a reason`() {
        val s = session(listOf(2400, 2401))
        s.submit("brain", intent("BUY x SIZING 1"))

        s.drain()

        assertThat(s.fates().single().state).isEqualTo("skipped")
        assertThat(s.fates().single().reason).contains("no closed bar")
    }

    @Test
    fun `undeclared symbol is refused`() {
        val s = session(listOf(2400, 2401))

        assertThatThrownBy {
            s.submit("brain", intent("BUY x SIZING 1").replace(gold, "EXNESS:EURUSD"))
        }.hasMessageContaining("EXNESS:EURUSD is not a session symbol")
    }
}
