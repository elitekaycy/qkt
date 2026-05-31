package com.qkt.dsl.compile

import com.qkt.dsl.parse.Dsl
import com.qkt.dsl.parse.ParseResult
import com.qkt.marketdata.Tick
import com.qkt.strategy.Signal
import com.qkt.strategy.testStrategyContext
import java.math.BigDecimal
import java.time.LocalDate
import java.time.ZoneOffset
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * #77 — Phase 40 Task 8. End-to-end integration of `AstCompiler.bindSchedules`
 * with [ScheduleRunner]. Verifies that compiled strategies wire each parsed
 * `SCHEDULE` clause into the runner, that the action body fires at the
 * scheduled time, and that warmup-cold strategies skip schedule fires with a
 * clear log instead of crashing.
 */
class AstCompilerScheduleBindTest {
    private val mondayMidnightUtc: Long =
        LocalDate.of(2026, 6, 1).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
    private val hour = 3_600_000L

    private fun compile(src: String): DslCompiledStrategy =
        AstCompiler().compile((Dsl.parse(src) as ParseResult.Success).value) as DslCompiledStrategy

    private fun tick(
        symbol: String,
        ts: Long,
        price: String = "100",
    ): Tick =
        Tick(
            symbol = symbol,
            price = BigDecimal(price),
            timestamp = ts,
            volume = BigDecimal.ONE,
        )

    @Test
    fun `bindSchedules registers one runner entry per trigger`() {
        val s =
            compile(
                """
                STRATEGY t VERSION 1
                SYMBOLS
                  gold = EXNESS:XAUUSD EVERY 1m
                SCHEDULE
                  AT 09:00 UTC          THEN LOG "single"
                  AT 12:00, 14:00 UTC   THEN LOG "list"
                  EVERY HOUR AT :00     THEN LOG "hourly"
                RULES
                  WHEN gold.close > 0 THEN LOG "tick"
                """.trimIndent(),
            )
        val hub = CandleHub()
        val runner = ScheduleRunner()
        hub.register(s.declaredStreams.values.single(), retention = 5, strategyId = "test")
        s.bindToHub(hub, testStrategyContext()) { /* discard */ }
        s.bindSchedules(runner, testStrategyContext(), nowMs = mondayMidnightUtc) { /* discard */ }

        assertThat(runner.triggerCount()).isEqualTo(4) // 1 + 2 + 1
    }

    @Test
    fun `scheduled BUY fires at the scheduled time, end-to-end through hub feed`() {
        val s =
            compile(
                """
                STRATEGY sched VERSION 1
                SYMBOLS
                  gold = EXNESS:XAUUSD EVERY 1m
                SCHEDULE
                  AT 09:00 UTC THEN BUY gold SIZING 0.1
                RULES
                  WHEN gold.close > 0 THEN LOG "tick"
                """.trimIndent(),
            )
        val hub = CandleHub()
        val runner = ScheduleRunner()
        hub.register(s.declaredStreams.values.single(), retention = 5, strategyId = "test")

        val signals = mutableListOf<Signal>()
        s.bindToHub(hub, testStrategyContext()) { sig -> signals.add(sig) }
        s.bindSchedules(runner, testStrategyContext(), nowMs = mondayMidnightUtc) { sig ->
            signals.add(sig)
        }

        // Warm up: feed a closing bar before 09:00 so the scheduled fire has a
        // synthesized current-candle to read from.
        hub.feed(tick("EXNESS:XAUUSD", mondayMidnightUtc + 8 * hour, "1000"))
        hub.feed(tick("EXNESS:XAUUSD", mondayMidnightUtc + 8 * hour + 60_000L, "1001"))

        // Heartbeat at 09:00 UTC — schedule fires.
        runner.tick(mondayMidnightUtc + 9 * hour)

        val buys = signals.filterIsInstance<Signal.Buy>()
        assertThat(buys).hasSize(1)
        assertThat(buys[0].symbol).isEqualTo("EXNESS:XAUUSD")
    }

    @Test
    fun `schedule fire before any bar is closed is skipped with no signal`() {
        val s =
            compile(
                """
                STRATEGY sched VERSION 1
                SYMBOLS
                  gold = EXNESS:XAUUSD EVERY 1m
                SCHEDULE
                  AT 00:30 UTC THEN BUY gold SIZING 0.1
                RULES
                  WHEN gold.close > 0 THEN LOG "tick"
                """.trimIndent(),
            )
        val hub = CandleHub()
        val runner = ScheduleRunner()
        hub.register(s.declaredStreams.values.single(), retention = 5, strategyId = "test")
        val signals = mutableListOf<Signal>()
        s.bindToHub(hub, testStrategyContext()) { sig -> signals.add(sig) }
        s.bindSchedules(runner, testStrategyContext(), nowMs = mondayMidnightUtc) { sig ->
            signals.add(sig)
        }

        // Heartbeat at 00:30, hub has no closed bars yet — schedule fire is a no-op.
        runner.tick(mondayMidnightUtc + 30 * 60_000L)
        assertThat(signals).isEmpty()
    }
}
