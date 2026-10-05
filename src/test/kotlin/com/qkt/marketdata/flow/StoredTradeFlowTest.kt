package com.qkt.marketdata.flow

import com.qkt.common.Side
import java.math.BigDecimal
import java.nio.file.Path
import java.time.LocalDate
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/** A backtest's flow is each window's sums over the stored prints, by side; a window with none is zero. */
class StoredTradeFlowTest {
    @TempDir lateinit var root: Path
    private val perp = "DERIBIT:BTC_USDC_PERPETUAL"
    private val day = LocalDate.parse("2026-10-04")
    private val dayStart = 1_791_072_000_000L
    private val minute = 60_000L

    private fun print(
        offsetMs: Long,
        size: String,
        side: Side,
    ) = Print("p$offsetMs", dayStart + offsetMs, BigDecimal("85000"), BigDecimal(size), side)

    @Test
    fun `a window sums the sizes printed in it on each side, its end excluded`() {
        val store = TapeStore(root)
        store.write(
            perp,
            FlowKind.TRADES,
            day,
            listOf(
                print(0, "0.5", Side.BUY),
                print(30_000, "0.0011", Side.SELL),
                print(59_999, "0.25", Side.BUY),
                print(minute, "9", Side.SELL),
            ),
        )
        val flow = StoredTradeFlow(store)

        val first = flow.window(perp, FlowKind.TRADES, minute, dayStart)!!

        assertThat(first.buy).isEqualByComparingTo("0.75")
        assertThat(first.sell).isEqualByComparingTo("0.0011")
        assertThat(flow.window(perp, FlowKind.TRADES, minute, dayStart + minute)!!.sell).isEqualByComparingTo("9")
        val quiet = flow.window(perp, FlowKind.TRADES, minute, dayStart + 5 * minute)!!
        assertThat(quiet.buy.signum() + quiet.sell.signum()).isZero
    }

    @Test
    fun `a day not stored has no flow`() {
        assertThat(StoredTradeFlow(TapeStore(root)).window(perp, FlowKind.LIQUIDATIONS, minute, dayStart)).isNull()
    }
}
