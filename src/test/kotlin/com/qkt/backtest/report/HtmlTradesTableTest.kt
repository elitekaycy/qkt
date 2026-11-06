package com.qkt.backtest.report

import com.qkt.backtest.TradeRecord
import com.qkt.common.Side
import com.qkt.execution.Trade
import com.qkt.positions.Position
import com.qkt.positions.StrategyPositionTracker.LegAction
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class HtmlTradesTableTest {
    private fun record(
        id: String,
        side: Side = Side.BUY,
        before: String? = null,
        after: String? = null,
        legAction: LegAction? = null,
    ) = TradeRecord(
        trade = Trade(id, "X", BigDecimal("10"), BigDecimal("1"), side, 0L),
        realized = BigDecimal.ZERO,
        strategyId = "s",
        strategyPositionBefore = before?.let { Position("X", BigDecimal(it), BigDecimal("10")) },
        strategyPositionAfter = after?.let { Position("X", BigDecimal(it), BigDecimal("10")) },
        legAction = legAction,
    )

    @Test
    fun `a long trade list shows head and tail with a pointer to the csv`() {
        val trades = (1..5).map { record("order-$it") }

        val html = HtmlTradesTable.render(trades, HtmlReportConfig(tradeTableHead = 1, tradeTableTail = 1))

        assertThat(Regex("<tr><td>").findAll(html).count()).isEqualTo(2)
        assertThat(html).contains("Showing first 1 and last 1 of 5 trades. Full list in trades.csv.")
    }

    @Test
    fun `a short trade list shows every trade without the pointer`() {
        val html = HtmlTradesTable.render(listOf(record("a")), HtmlReportConfig(tradeTableHead = 1, tradeTableTail = 1))

        assertThat(html).doesNotContain("Showing first")
    }

    @Test
    fun `position effects name reversals and unchanged positions`() {
        assertThat(
            TradeAuditSummaries.positionEffect(record("r", before = "-1", after = "2")),
        ).isEqualTo("REVERSE_TO_LONG")
        assertThat(TradeAuditSummaries.positionEffect(record("r", Side.SELL, "1", "-2"))).isEqualTo("REVERSE_TO_SHORT")
        assertThat(TradeAuditSummaries.positionEffect(record("u", before = "1", after = "1"))).isEqualTo("UNKNOWN")
        assertThat(TradeAuditSummaries.positionEffect(record("n"))).isEqualTo("UNKNOWN")
    }

    @Test
    fun `a leg-routed fill takes its effect from the leg, a netted one from the net position`() {
        assertThat(TradeAuditSummaries.positionEffect(record("o", Side.SELL, legAction = LegAction.OPENED)))
            .isEqualTo("OPEN_SHORT")
        assertThat(TradeAuditSummaries.positionEffect(record("c", Side.BUY, legAction = LegAction.CLOSED)))
            .isEqualTo("CLOSE_SHORT")
        assertThat(
            TradeAuditSummaries.positionEffect(record("t", before = "0", after = "1", legAction = LegAction.NETTED)),
        ).isEqualTo("OPEN_LONG")
    }
}
