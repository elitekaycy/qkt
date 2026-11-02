package com.qkt.risk.rules

import com.qkt.common.Money
import com.qkt.common.Side
import com.qkt.execution.OrderRequest
import com.qkt.execution.TimeInForce
import com.qkt.execution.Trade
import com.qkt.instrument.CorporateAction
import com.qkt.instrument.CorporateActionSource
import com.qkt.instrument.InstrumentRegistry
import com.qkt.positions.StrategyPositionTracker
import com.qkt.risk.Decision
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** NVDA splits 10 for 1 on Tuesday 2026-06-09: no new position from Friday 06-05 through the ex-date. */
class SplitWindowTest {
    private val ledger = StrategyPositionTracker()
    private val positions = ledger.account
    private var reads = 0
    private val split = CorporateAction.Split(LocalDate.parse("2026-06-09"), BigDecimal(10))
    private val dividend = CorporateAction.Dividend(LocalDate.parse("2026-06-08"), BigDecimal("0.01"), "USD")

    private val registry =
        object : InstrumentRegistry {
            override fun lookup(qktSymbol: String) = null

            override fun corporateActions() =
                CorporateActionSource {
                    reads++
                    if (it == "EXNESS:NVDA") listOf(dividend, split) else emptyList()
                }
        }
    private val rule = SplitWindow(registry)

    private fun order(
        at: String,
        side: Side = Side.BUY,
        symbol: String = "EXNESS:NVDA",
        qty: String = "0.01",
    ) = OrderRequest.Market(
        id = "ORD-0",
        symbol = symbol,
        side = side,
        quantity = Money.of(qty),
        timeInForce = TimeInForce.GTC,
        timestamp = Instant.parse(at).toEpochMilli(),
    )

    @Test
    fun `opening orders are refused from four days before the ex-date through it`() {
        assertThat(rule.evaluate(order("2026-06-04T15:00:00Z"), positions)).isEqualTo(Decision.Approve)
        assertThat(rule.evaluate(order("2026-06-05T00:00:00Z"), positions))
            .isEqualTo(
                Decision.Reject("EXNESS:NVDA splits 10 for 1 on 2026-06-09: no new position is held into a split"),
            )
        assertThat(rule.evaluate(order("2026-06-09T19:00:00Z", Side.SELL), positions))
            .isInstanceOf(Decision.Reject::class.java)
        assertThat(rule.evaluate(order("2026-06-10T10:00:00Z"), positions)).isEqualTo(Decision.Approve)
    }

    @Test
    fun `closing a held position passes inside the window, adding to it does not`() {
        ledger.apply("acct", Trade("ORD-X", "EXNESS:NVDA", Money.of("1000"), Money.of("0.05"), Side.BUY, 1000L))
        assertThat(rule.evaluate(order("2026-06-08T15:00:00Z", Side.SELL, qty = "0.05"), positions))
            .isEqualTo(Decision.Approve)
        assertThat(rule.evaluate(order("2026-06-08T15:00:00Z", Side.BUY), positions))
            .isInstanceOf(Decision.Reject::class.java)
    }

    @Test
    fun `a share with no split, or no corporate actions at all, is not judged`() {
        assertThat(rule.evaluate(order("2026-06-08T15:00:00Z", symbol = "EXNESS:AAPL"), positions))
            .isEqualTo(Decision.Approve)
        val bare =
            SplitWindow(
                object : InstrumentRegistry {
                    override fun lookup(qktSymbol: String) = null
                },
            )
        assertThat(bare.evaluate(order("2026-06-08T15:00:00Z"), positions)).isEqualTo(Decision.Approve)
    }

    @Test
    fun `a symbol's actions are read once a day, not on every order`() {
        repeat(5) { rule.evaluate(order("2026-06-01T1$it:00:00Z"), positions) }
        assertThat(reads).isEqualTo(1)
        rule.evaluate(order("2026-06-02T10:00:00Z"), positions)
        assertThat(reads).isEqualTo(2)
    }
}
