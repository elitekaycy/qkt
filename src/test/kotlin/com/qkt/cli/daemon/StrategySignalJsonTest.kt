package com.qkt.cli.daemon

import com.qkt.common.Side
import com.qkt.execution.OrderRequest
import com.qkt.execution.TimeInForce
import com.qkt.execution.Trade
import com.qkt.strategy.Signal
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** How a strategy's signals and last trade read in its event ring and `/status`. */
class StrategySignalJsonTest {
    private fun leg(
        side: Side,
        qty: String,
        symbol: String,
    ) = OrderRequest.Market(
        id = "leg-$symbol",
        symbol = symbol,
        side = side,
        quantity = BigDecimal(qty),
        timeInForce = TimeInForce.GTC,
        timestamp = 1L,
    )

    @Test
    fun `an option structure lists its legs and what it closes`() {
        val opening =
            signalToJson(Signal.SubmitGroup("st-1", "fly", listOf(leg(Side.BUY, "1", "C1"), leg(Side.SELL, "2", "C2"))))
        val closing =
            signalToJson(Signal.SubmitGroup("st-2", "fly", listOf(leg(Side.SELL, "1", "C1")), closes = "st-1"))

        assertThat(opening.toString())
            .isEqualTo("""{"kind":"structure","structureId":"st-1","alias":"fly","legs":"BUY 1 C1;SELL 2 C2"}""")
        assertThat(closing.toString()).contains("\"closes\":\"st-1\"", "\"legs\":\"SELL 1 C1\"")
    }

    @Test
    fun `a suppressed signal names its symbol and why`() {
        assertThat(signalToJson(Signal.Suppressed("EXNESS:XAUUSD", "portfolio gate")).toString())
            .isEqualTo("""{"kind":"suppressed","symbol":"EXNESS:XAUUSD","reason":"portfolio gate"}""")
    }

    @Test
    fun `the snapshot's last trade is the newest one`() {
        val snap =
            buildSnapshot(
                strategyName = "s",
                strategyVersion = 1,
                startMs = 0L,
                startedAt = "t",
                trades =
                    listOf(
                        Trade("o1", "EXNESS:XAUUSD", BigDecimal("2650"), BigDecimal("0.1"), Side.BUY, 1_000L),
                        Trade("o2", "EXNESS:XAUUSD", BigDecimal("2660"), BigDecimal("0.1"), Side.SELL, 2_000L),
                    ),
            )

        val last = snap.lastTrade!!
        assertThat(last.side).isEqualTo("SELL")
        assertThat(last.price).isEqualByComparingTo("2660")
        assertThat(last.timestamp).isEqualTo("1970-01-01T00:00:02Z")
        assertThat(last.realized).isEqualByComparingTo("0")
    }
}
