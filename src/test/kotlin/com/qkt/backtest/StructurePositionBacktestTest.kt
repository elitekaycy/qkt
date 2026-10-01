package com.qkt.backtest

import com.qkt.common.Side
import java.math.BigDecimal
import java.nio.file.Path
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/**
 * Structure positions on the four real book snapshots of `btc-usdc-book-live4` (see its PROVENANCE
 * and [StructureBacktestTest]): one live structure per alias, its fields, and `CLOSE`.
 */
class StructurePositionBacktestTest {
    private val open =
        "OPEN ps = OPTIONS ON DERIBIT:BTC_USDC { SELL PUT DELTA 0.25 DTE 7 TO 30, BUY PUT DELTA 0.10 SAME EXPIRY }"

    private fun run(
        dir: Path,
        rules: String,
        flags: List<String> = emptyList(),
    ) = FuturesFixtureRun
        .run(
            dir,
            "btc-usdc-book-live4",
            "STRATEGY spread VERSION 1\nSYMBOLS\n    chain = OPTIONS:DERIBIT.BTC_USDC EVERY 1m,\n" +
                "    iv = CHAIN:DERIBIT.BTC_USDC.atm_iv.7d EVERY 1m\nRULES\n$rules\n",
            from = "2026-10-01",
            to = "2026-10-02",
            flags = flags,
            resources = "options",
        ).first

    @Test
    fun `a second OPEN of a live alias fires nothing`(
        @TempDir dir: Path,
    ) {
        val result =
            run(
                dir,
                "    WHEN iv.close > 0\n    THEN $open SIZING 0.1\n" +
                    "    WHEN iv.close > 0.01\n    THEN $open SIZING 0.2\n",
            )

        assertThat(result.trades).hasSize(2).allMatch { it.trade.quantity.compareTo(BigDecimal("0.1")) == 0 }
    }

    private fun quotesAt(atMs: Long): Map<String, Pair<BigDecimal?, BigDecimal?>> =
        StructureBacktestRows.rows.getValue(atMs).associate {
            "DERIBIT:${it.contract.replace('-', '_')}" to
                (it.bid to it.ask)
        }

    @Test
    fun `CLOSE closes the open structure as one group at the next snapshot, once its fields are defined`(
        @TempDir dir: Path,
    ) {
        val result =
            run(
                dir,
                "    WHEN iv.close > 0\n    THEN $open SIZING 0.1\n" +
                    "    WHEN POSITION.ps.pnl_pct > -1000 AND POSITION.ps.delta > -1\n    THEN CLOSE ps\n",
            )

        val trades = result.trades.map { it.trade }.sortedBy { it.timestamp }
        assertThat(trades).hasSize(4)
        val (opens, closes) = trades.partition { it.timestamp == trades.first().timestamp }
        assertThat(closes.map { it.timestamp }.distinct()).hasSize(1).allMatch { it > opens.first().timestamp }
        val quotes = quotesAt(closes.first().timestamp)
        for (close in closes) {
            val (bid, ask) = quotes.getValue(close.symbol)
            assertThat(close.price).isEqualByComparingTo(requireNotNull(if (close.side == Side.BUY) ask else bid))
        }
        val opposite = { side: Side -> if (side == Side.BUY) Side.SELL else Side.BUY }
        assertThat(opens.map { it.symbol to opposite(it.side) })
            .containsExactlyInAnyOrderElementsOf(closes.map { it.symbol to it.side })
        assertThat(result.rejections).isEmpty()
        // The report row, from the trades alone (contract size 1): credit = sells − buys at entry, P&L = all flows.
        val cash = { t: com.qkt.execution.Trade ->
            (
                if (t.side ==
                    Side.SELL
                ) {
                    t.price
                } else {
                    t.price.negate()
                }
            ).multiply(t.quantity)
        }
        val row = result.structures.single()
        assertThat(row.alias).isEqualTo("ps")
        assertThat(row.openedAt).isEqualTo(opens.first().timestamp)
        assertThat(row.closedAt).isEqualTo(closes.first().timestamp)
        assertThat(row.outcome).isEqualTo(com.qkt.events.StructureOutcome.CLOSED)
        assertThat(row.credit).isEqualByComparingTo(opens.fold(BigDecimal.ZERO) { sum, t -> sum.add(cash(t)) })
        assertThat(row.realized).isEqualByComparingTo(trades.fold(BigDecimal.ZERO) { sum, t -> sum.add(cash(t)) })
        val csv =
            com.qkt.backtest.report.DerivativeReportFiles
                .render(result)
                .toMap()
                .getValue("structures.csv")
        assertThat(
            csv.lines().first(),
        ).isEqualTo("openedAt,closedAt,strategy,structure,alias,outcome,legs,credit,realized")
        assertThat(csv.lines()[1]).startsWith("${row.openedAt},${row.closedAt},spread,").contains(",ps,CLOSED,")
    }

    @Test
    fun `FLATTEN closes a structure as a group and never closes its legs twice`(
        @TempDir dir: Path,
    ) {
        // On 500 of equity the long wing could not be sold alone: the short put left would need about 800.
        val rules =
            "    WHEN iv.close > 0\n    THEN $open SIZING 0.1\n" +
                "    WHEN POSITION.ps.credit > -100000\n    THEN FLATTEN\n"
        val result = run(dir, rules, listOf("--starting-balance", "500"))

        val trades = result.trades.map { it.trade }
        assertThat(trades).hasSize(4)
        assertThat(result.rejections).isEmpty()
        val signed = { t: com.qkt.execution.Trade -> if (t.side == Side.BUY) t.quantity else t.quantity.negate() }
        val net =
            trades.groupBy { it.symbol }.mapValues { (_, legs) ->
                legs.fold(BigDecimal.ZERO) { q, t -> q.add(signed(t)) }
            }
        assertThat(net.values).hasSize(2).allMatch { it.signum() == 0 }
    }
}
