package com.qkt.dsl.compile

import com.qkt.dsl.parse.Dsl
import com.qkt.dsl.parse.ParseResult
import com.qkt.marketdata.Candle
import com.qkt.marketdata.Tick
import com.qkt.strategy.Signal
import com.qkt.strategy.testStrategyContext
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * Edge cases for seeded-history replay ordering across streams: the property under test
 * is always the same — after `bindToHub`, cross-stream indicator state equals what a
 * continuous run over the same closes would hold, so the first live bar evaluates as
 * bar N+1, never as bar 1.
 */
class CrossStreamWarmupEdgeCasesTest {
    private fun compile(src: String) =
        AstCompiler().compile((Dsl.parse(src) as ParseResult.Success).value) as DslCompiledStrategy

    private fun candle(
        symbol: String,
        startMs: Long,
        durationMs: Long,
        close: String,
    ): Candle =
        Candle(
            symbol = symbol,
            open = BigDecimal(close),
            high = BigDecimal(close),
            low = BigDecimal(close),
            close = BigDecimal(close),
            volume = BigDecimal.ONE,
            startTime = startMs,
            endTime = startMs + durationMs,
        )

    private val minute = 60_000L

    /**
     * Drive both symbols through live ticks until [received] is non-empty; returns how many
     * live bars had to CLOSE first (a tick at bar i closes bar i-1). 1 = fired on the first
     * live close, i.e. the indicator was warm from the seed.
     */
    private fun barsUntilFire(
        hub: CandleHub,
        received: List<Signal>,
        fromBar: Int,
        maxBars: Int = 12,
        symbols: List<String> = listOf("EXNESS:XAGUSD", "EXNESS:XAUUSD"),
    ): Int {
        for (i in fromBar until fromBar + maxBars) {
            for (sym in symbols) hub.feed(Tick(sym, BigDecimal("100"), i * minute))
            if (received.isNotEmpty()) return i - fromBar
        }
        return -1
    }

    @Test
    fun `unequal warmup lengths across streams still leave the cross-stream indicator warm`() {
        val s =
            compile(
                """
                STRATEGY t VERSION 1
                SYMBOLS
                  s = EXNESS:XAUUSD EVERY 1m WARMUP 6 BARS,
                  o = EXNESS:XAGUSD EVERY 1m WARMUP 12 BARS
                RULES
                  WHEN percentile_rank(s.close / lag(o.close, 2), 4) >= 0 THEN FLATTEN
                """.trimIndent(),
            )
        val hub = CandleHub()
        val sKey = s.declaredStreams.getValue("s")
        val oKey = s.declaredStreams.getValue("o")
        hub.register(sKey, retention = 20, strategyId = "t")
        hub.register(oKey, retention = 20, strategyId = "t")
        // o has twelve seeded bars, s only the last six of the same clock.
        hub.seed(oKey, (0..11).map { candle("EXNESS:XAGUSD", it * minute, minute, "${50 + it}") })
        hub.seed(sKey, (6..11).map { candle("EXNESS:XAUUSD", it * minute, minute, "${100 + it}") })
        val received = mutableListOf<Signal>()
        s.bindToHub(hub, testStrategyContext()) { received += it }

        assertThat(barsUntilFire(hub, received, fromBar = 12)).isEqualTo(1)
    }

    @Test
    fun `three streams with the secondary ones declared after the primary are all warm`() {
        val s =
            compile(
                """
                STRATEGY t VERSION 1
                SYMBOLS
                  s = EXNESS:XAUUSD EVERY 1m WARMUP 10 BARS,
                  o = EXNESS:XAGUSD EVERY 1m WARMUP 10 BARS,
                  p = EXNESS:XPTUSD EVERY 1m WARMUP 10 BARS
                RULES
                  WHEN percentile_rank((s.close / lag(o.close, 2)) - (lag(p.close, 3) / s.close), 5) >= 0 THEN FLATTEN
                """.trimIndent(),
            )
        val hub = CandleHub()
        val keys = listOf("s", "o", "p").map { s.declaredStreams.getValue(it) }
        val syms = listOf("EXNESS:XAUUSD", "EXNESS:XAGUSD", "EXNESS:XPTUSD")
        keys.forEach { hub.register(it, retention = 20, strategyId = "t") }
        keys.zip(syms).forEach { (k, sym) ->
            hub.seed(k, (0..9).map { candle(sym, it * minute, minute, "${100 + it}") })
        }
        val received = mutableListOf<Signal>()
        s.bindToHub(hub, testStrategyContext()) { received += it }

        assertThat(barsUntilFire(hub, received, fromBar = 10, symbols = syms)).isEqualTo(1)
    }

    @Test
    fun `mixed timeframes interleave by close time so the slow stream's lag is warm when the fast stream evaluates`() {
        val s =
            compile(
                """
                STRATEGY t VERSION 1
                SYMBOLS
                  s = EXNESS:XAUUSD EVERY 1m WARMUP 15 BARS,
                  o = EXNESS:XAGUSD EVERY 5m WARMUP 4 BARS
                RULES
                  WHEN percentile_rank(s.close / lag(o.close, 2), 5) >= 0 THEN FLATTEN
                """.trimIndent(),
            )
        val hub = CandleHub()
        val sKey = s.declaredStreams.getValue("s")
        val oKey = s.declaredStreams.getValue("o")
        hub.register(sKey, retention = 30, strategyId = "t")
        hub.register(oKey, retention = 10, strategyId = "t")
        // 20 minutes of history: four 5m bars on o, fifteen 1m bars on s (minutes 5..19).
        hub.seed(oKey, (0..3).map { candle("EXNESS:XAGUSD", it * 5 * minute, 5 * minute, "${50 + it}") })
        hub.seed(sKey, (5..19).map { candle("EXNESS:XAUUSD", it * minute, minute, "${100 + it}") })
        val received = mutableListOf<Signal>()
        s.bindToHub(hub, testStrategyContext()) { received += it }

        // lag(o.close, 2) needs three 5m closes — available from minute 15 on, so the last
        // five 1m closes of the seed fed percentile_rank(5) and the first live bar fires.
        assertThat(barsUntilFire(hub, received, fromBar = 20)).isEqualTo(1)
    }

    @Test
    fun `seeded replay equals a continuous run bar for bar`() {
        // The parity property behind the fix: the strategy's indicator values after
        // seeding N bars and going live must equal the values a continuous run holds at
        // the same bars. Compare the first live fire on both paths.
        val src =
            """
            STRATEGY t VERSION 1
            SYMBOLS
              s = EXNESS:XAUUSD EVERY 1m WARMUP 8 BARS,
              o = EXNESS:XAGUSD EVERY 1m WARMUP 8 BARS
            RULES
              WHEN percentile_rank((s.close / lag(s.close, 2)) / (o.close / lag(o.close, 2)), 4) > 0.7 THEN FLATTEN
            """.trimIndent()

        fun closes(i: Int) = "${100 + (i * 7) % 11}" to "${50 + (i * 3) % 5}"

        // Continuous: every bar arrives live.
        val cont = compile(src)
        val hubC = CandleHub()
        val cS = cont.declaredStreams.getValue("s")
        val cO = cont.declaredStreams.getValue("o")
        hubC.register(cS, retention = 30, strategyId = "t")
        hubC.register(cO, retention = 30, strategyId = "t")
        val firesC = mutableListOf<Long>()
        cont.bindToHub(hubC, testStrategyContext()) { firesC += hubC.latest(cS)!!.endTime }
        for (i in 0 until 24) {
            val (a, b) = closes(i)
            hubC.feed(Tick("EXNESS:XAGUSD", BigDecimal(b), i * minute))
            hubC.feed(Tick("EXNESS:XAUUSD", BigDecimal(a), i * minute))
        }
        hubC.feed(Tick("EXNESS:XAGUSD", BigDecimal("1"), 24 * minute))
        hubC.feed(Tick("EXNESS:XAUUSD", BigDecimal("1"), 24 * minute))

        // Seeded: the first 8 bars are history, the rest arrive live.
        val seeded = compile(src)
        val hubS = CandleHub()
        val sS = seeded.declaredStreams.getValue("s")
        val sO = seeded.declaredStreams.getValue("o")
        hubS.register(sS, retention = 30, strategyId = "t")
        hubS.register(sO, retention = 30, strategyId = "t")
        hubS.seed(sS, (0 until 8).map { candle("EXNESS:XAUUSD", it * minute, minute, closes(it).first) })
        hubS.seed(sO, (0 until 8).map { candle("EXNESS:XAGUSD", it * minute, minute, closes(it).second) })
        val firesS = mutableListOf<Long>()
        seeded.bindToHub(hubS, testStrategyContext()) { firesS += hubS.latest(sS)!!.endTime }
        for (i in 8 until 24) {
            val (a, b) = closes(i)
            hubS.feed(Tick("EXNESS:XAGUSD", BigDecimal(b), i * minute))
            hubS.feed(Tick("EXNESS:XAUUSD", BigDecimal(a), i * minute))
        }
        hubS.feed(Tick("EXNESS:XAGUSD", BigDecimal("1"), 24 * minute))
        hubS.feed(Tick("EXNESS:XAUUSD", BigDecimal("1"), 24 * minute))

        val liveWindow = firesC.filter { it > 8 * minute }
        assertThat(liveWindow).isNotEmpty()
        assertThat(firesS).containsExactlyElementsOf(liveWindow)
    }
}
