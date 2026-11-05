package com.qkt.dsl.compile

import com.qkt.common.FixedClock
import com.qkt.dsl.ast.StateAccessor
import com.qkt.dsl.ast.StateSource
import com.qkt.marketdata.Candle
import com.qkt.positions.Position
import com.qkt.positions.StrategyPositionView
import com.qkt.strategy.testStrategyContext
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** `holding_duration` measured from the open of the bar the position opened in (#1633). */
class HoldingBarClockTest {
    private val hour = 3_600_000L

    // 2026-10-29T04:00:00Z, a 4h boundary.
    private val boundary = 1_761_710_400_000L

    private fun holdingSeconds(
        streams: Map<String, HubKey>,
        openedAt: Long,
        evaluatedAt: Long,
    ): BigDecimal {
        val pos =
            object : StrategyPositionView {
                override fun positionFor(symbol: String) =
                    Position("EXNESS:XAUUSD", BigDecimal.ONE, BigDecimal("2000"), openedAt = openedAt)

                override fun allPositions() = emptyMap<String, Position>()
            }
        val candle =
            Candle("XAUUSD", BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ZERO, 0L, 1L)
        val ec =
            EvalContext(
                candle = candle,
                streams = streams,
                lets = emptyMap(),
                strategyContext = testStrategyContext(clock = FixedClock(time = evaluatedAt), positions = pos),
                evaluationTimeMs = evaluatedAt,
            )
        val expr = ExprCompiler().compile(StateAccessor(StateSource.POSITION_HOLDING_DURATION, "gold"))
        return (expr.evaluate(ec) as Value.Num).v
    }

    private val gold4h = mapOf("gold" to HubKey("EXNESS", "XAUUSD", "4h"))

    @Test
    fun `a live fill a second after the bar close has held 144h on the bar six days later`() {
        // The live snipe trade: 4h stream, fill 04:00:01, exit bar 04:00 six days later.
        val held = holdingSeconds(gold4h, openedAt = boundary + 1_000L, evaluatedAt = boundary + 144 * hour)

        assertThat(held).isEqualByComparingTo("518400")
    }

    @Test
    fun `a fill on the bar boundary reads the same as before`() {
        val held = holdingSeconds(gold4h, openedAt = boundary, evaluatedAt = boundary + 144 * hour)

        assertThat(held).isEqualByComparingTo("518400")
    }

    @Test
    fun `a pending fill inside a bar counts from that bar's open`() {
        val held = holdingSeconds(gold4h, openedAt = boundary + 3 * hour, evaluatedAt = boundary + 8 * hour)

        assertThat(held).isEqualByComparingTo((8 * 3600).toString())
    }

    @Test
    fun `the finest timeframe declared on the symbol sets the bar`() {
        // A 1h entry stream and a 4h alias on the same symbol: a fill at 05:00:30 anchors to 05:00,
        // not back to the 4h bar's 04:00 open, even when the time exit reads the 4h alias.
        val streams =
            mapOf(
                "gold" to HubKey("EXNESS", "XAUUSD", "4h"),
                "gold1h" to HubKey("EXNESS", "XAUUSD", "1h"),
            )
        val held = holdingSeconds(streams, openedAt = boundary + hour + 30_000L, evaluatedAt = boundary + 8 * hour)

        assertThat(held).isEqualByComparingTo((7 * 3600).toString())
    }

    @Test
    fun `streams on other symbols do not set the bar`() {
        val streams =
            mapOf(
                "gold" to HubKey("EXNESS", "XAUUSD", "4h"),
                "btc" to HubKey("EXNESS", "BTCUSD", "1m"),
            )
        val held = holdingSeconds(streams, openedAt = boundary + 30_000L, evaluatedAt = boundary + 4 * hour)

        assertThat(held).isEqualByComparingTo((4 * 3600).toString())
    }
}
